// SPDX-License-Identifier: GPL-3.0-or-later
package ru.smsbridge.adapter

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import im.angry.openeuicc.OpenEuiccApplication
import im.angry.openeuicc.core.EuiccChannel
import im.angry.openeuicc.core.EuiccChannelManager
import im.angry.openeuicc.service.EuiccChannelManagerService
import im.angry.openeuicc.service.EuiccChannelManagerService.Companion.waitDone
import im.angry.openeuicc.service.IdleServiceSession
import im.angry.openeuicc.util.LPAString
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import net.typeblog.lpac_jni.LocalProfileInfo
import org.json.JSONArray
import org.json.JSONObject
import ru.smsbridge.app.EsimErrors
import ru.smsbridge.app.AdapterProgress

/** Bound service owns APDU channels. This non-exported provider accepts only this app UID. */
class GatewayProvider : ContentProvider() {
    companion object {
        private val SE = EuiccChannel.SecureElementId.DEFAULT
    }
    override fun onCreate() = true
    private val session by lazy { IdleServiceSession(CoroutineScope(SupervisorJob() + Dispatchers.IO),30_000L,::connectService) }
    private suspend fun connectService(): IdleServiceSession.Handle<EuiccChannelManagerService> {
        val ready = CompletableDeferred<EuiccChannelManagerService>()
        val valid = java.util.concurrent.atomic.AtomicBoolean(true)
        val connection = object: ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { ready.complete((binder as EuiccChannelManagerService.LocalBinder).service) }
            override fun onServiceDisconnected(name: ComponentName) { valid.set(false);ready.cancel() }
            override fun onNullBinding(name: ComponentName) { valid.set(false);ready.cancel() }
            override fun onBindingDied(name: ComponentName) { valid.set(false);ready.cancel() }
        }
        check(context!!.bindService(Intent(context,EuiccChannelManagerService::class.java),connection,Context.BIND_AUTO_CREATE))
        try {
            val service = withTimeout(20_000L) { ready.await() }
            return IdleServiceSession.Handle(service,{valid.get()},{context!!.unbindService(connection)})
        } catch(e: Throwable) { context!!.unbindService(connection);throw e }
    }
    private fun authenticate() {
        if(Binder.getCallingUid() != android.os.Process.myUid())throw SecurityException("Internal provider only")
    }
    private fun error(code: String) = JSONArray().put(JSONObject().put("error", code))
    private fun profile(p: LocalProfileInfo) = JSONObject().put("iccid",p.iccid)
        .put("enabled",p.state == LocalProfileInfo.State.Enabled).put("provider",p.providerName).put("nickname",p.nickName)
    @Synchronized override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        authenticate()
        val identity = Binder.clearCallingIdentity()
        AdapterProgress.start("Подключение к адаптеру",uri.lastPathSegment == "enableProfile")
        try {
            val rows = runBlocking(Dispatchers.IO) {
                if(context!!.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
                    return@runBlocking error("phone_permission_required")
                if(uri.getQueryParameter("callbackUrl") != null) throw SecurityException("Callbacks disabled")
                val action = uri.lastPathSegment
                if(action !in setOf("cards","profiles","cardInfo","downloadProfile","enableProfile","deleteProfile","setPreference"))
                    throw SecurityException("Unsupported operation")
                if(action == "setPreference") {
                    require(uri.getQueryParameter("name") == "ignoreTlsCertificate" && uri.getQueryParameter("enabled") == "false")
                }
                val app = context!!.applicationContext as OpenEuiccApplication
                // Enforce TLS and stop activation codes from appearing in verbose HTTP logs.
                app.appContainer.preferenceRepository.ignoreTLSCertificateFlow.updatePreference(false)
                app.appContainer.preferenceRepository.verboseLoggingFlow.updatePreference(false)
                if(action == "setPreference") return@runBlocking JSONArray().put(JSONObject().put("success",true))
                val wake = (context!!.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"smsbridge:adapter")
                try {
                    wake.acquire(600000L)
                    session.use { service ->
                        withTimeout(10000L) { service.waitForForegroundTask() }
                        AdapterProgress.phase("Чтение карты")
                        handle(service, uri, action!!)
                    }
                } finally {
                    if(wake.isHeld)wake.release()
                }
            }
            return MatrixCursor(arrayOf("rows")).apply { addRow(arrayOf(rows.toString())) }
        } catch(e: SecurityException) { throw e }
          catch(e: Exception) {
            val code = when(e) {
                is im.angry.openeuicc.service.ForegroundTaskStartException -> "adapter_start_failed"
                is TimeoutCancellationException -> "adapter_busy_or_reconnecting"
                is net.typeblog.lpac_jni.LocalProfileAssistant.ProfileDownloadException -> "profile_download_failed"
                is EuiccChannelManager.EuiccChannelNotFoundException -> "card_access_denied"
                else -> "adapter_operation_failed"
            }
            val rows = if(e is net.typeblog.lpac_jni.LocalProfileAssistant.ProfileDownloadException)
                JSONArray().put(EsimErrors.download(e.lpaErrorReason,e.lastHttpResponse?.rcode ?: 0,e.lastHttpResponse?.data,e.lastHttpException,e.lastApduResponse))
                else error(code)
            return MatrixCursor(arrayOf("rows")).apply { addRow(arrayOf(rows.toString())) }
        } finally { AdapterProgress.finish();Binder.restoreCallingIdentity(identity) }
    }
    private suspend fun handle(service: EuiccChannelManagerService, uri: Uri, action: String): JSONArray {
        val manager = service.euiccChannelManager
        if(action == "cards") {
            val result = JSONArray()
            for((slot,port) in manager.flowInternalEuiccPorts().toList()) {
                // Each removable adapter is identified independently by its own EID.
                try {
                    if(manager.flowEuiccSecureElements(slot,port).toList().size != 1)continue
                    val eid = manager.withEuiccChannel(slot,port,SE) { it.lpa.eID }
                    result.put(JSONObject().put("slot",slot).put("port",port).put("eid",eid))
                } catch(e: Exception) {
                    if(e is CancellationException)throw e
                    result.put(JSONObject().put("slot",slot).put("port",port).put("unavailable",true))
                }
            }
            return result
        }
        val slot = requireNotNull(uri.getQueryParameter("slot")).toInt()
        val port = uri.getQueryParameter("port")?.toInt() ?: 0
        require(slot in 0..7 && port in 0..7)
        val expectedEid = requireNotNull(uri.getQueryParameter("expectedEid"))
        if(action == "profiles")return JSONArray(manager.withEuiccChannel(slot,port,SE) { c -> check(c.lpa.eID == expectedEid);c.lpa.profiles.map { profile(it) } })
        if(action == "cardInfo") {
            val info = manager.withEuiccChannel(slot,port,SE) { check(it.lpa.eID == expectedEid);it.lpa.euiccInfo2 }
            return JSONArray().put(JSONObject().apply { if(info != null && info.freeNvram >= 0)put("free_nvram_bytes",info.freeNvram) })
        }
        if(action == "downloadProfile") {
            val ac = LPAString.parse(requireNotNull(uri.getQueryParameter("activationCode")))
            val pin = uri.getQueryParameter("confirmationCode")
            require(!ac.confirmationCodeRequired || !pin.isNullOrBlank())
            val before = manager.withEuiccChannel(slot,port,SE) { c -> check(c.lpa.eID == expectedEid);c.lpa.profiles.map { it.iccid }.toSet() }
            // Reuse the upstream task queue so local UI and Telegram cannot mutate the card concurrently.
            val failure = service.launchProfileDownloadTask(slot,port,SE,ac.address,ac.matchingId,pin,null,expectedEid).waitDone()
            if(failure != null) {
                if(failure !is net.typeblog.lpac_jni.LocalProfileAssistant.ProfileDownloadException)throw failure
                val details = EsimErrors.download(failure.lpaErrorReason,failure.lastHttpResponse?.rcode ?: 0,
                    failure.lastHttpResponse?.data,failure.lastHttpException,failure.lastApduResponse)
                runCatching { manager.withEuiccChannel(slot,port,SE) { it.lpa.euiccInfo2?.freeNvram } }
                    .getOrNull()?.takeIf { it >= 0 }?.let { details.put("free_nvram_bytes",it) }
                return JSONArray().put(details)
            }
            val added = manager.withEuiccChannel(slot,port,SE) { c -> check(c.lpa.eID == expectedEid);c.lpa.profiles.filter { it.iccid !in before } }
            check(added.size == 1)
            return JSONArray().put(profile(added.single()))
        }
        if(action == "enableProfile") {
            val iccid = requireNotNull(uri.getQueryParameter("iccid"))
            require(iccid.matches(Regex("[0-9]{10,24}")))
            service.launchProfileSwitchTask(slot,port,SE,iccid,true,20000L,expectedEid,AdapterProgress::phase)
                .waitDone()?.let { throw it }
            AdapterProgress.phase("Проверка активного профиля")
            val profiles = manager.withEuiccChannel(slot,port,SE) { c -> check(c.lpa.eID == expectedEid);c.lpa.profiles }
            val active = profiles.count { it.state == LocalProfileInfo.State.Enabled } == 1 &&
                profiles.any { it.iccid == iccid && it.state == LocalProfileInfo.State.Enabled }
            return JSONArray().put(JSONObject().put("success",active).put("eid",expectedEid).put("slot",slot).put("port",port)
                .put("profiles",JSONArray(profiles.map { profile(it) })))
        }
        if(action == "deleteProfile") {
            val eid = requireNotNull(uri.getQueryParameter("expectedEid"))
            val iccid = requireNotNull(uri.getQueryParameter("iccid"))
            require(iccid.matches(Regex("[0-9]{10,24}")))
            val found = manager.withEuiccChannel(slot,port,SE) { channel ->
                check(channel.lpa.eID == eid)
                channel.lpa.profiles.find { it.iccid == iccid }
            } ?: return error("profile_not_found")
            if(found.state == LocalProfileInfo.State.Enabled) {
                if(uri.getQueryParameter("allowActive") != "true")return error("profile_became_active")
                service.launchProfileSwitchTask(slot,port,SE,iccid,false,20000L,eid).waitDone()?.let { throw it }
            }
            val failure = service.launchProfileDeleteTask(slot,port,SE,iccid,eid).waitDone()
            val remains = manager.withEuiccChannel(slot,port,SE) { channel ->
                check(channel.lpa.eID == eid)
                channel.lpa.profiles.any { it.iccid == iccid }
            }
            if(remains)return error("profile_delete_failed")
            return JSONArray().put(JSONObject().put("success",true).put("notification_warning",failure != null))
        }
        return error("unsupported_operation")
    }
    override fun getType(uri: Uri) = "application/json"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
