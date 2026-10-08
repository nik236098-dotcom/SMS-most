// SPDX-License-Identifier: GPL-3.0-or-later
package net.typeblog.lpac_jni.impl

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.typeblog.lpac_jni.HttpInterface
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Keeps upstream certificate selection; network deadlines also cover DNS and body trickling. */
class HttpInterfaceImpl(
    private val verboseLoggingFlow: Flow<Boolean>,
    private val ignoreTLSCertificateFlow: Flow<Boolean>
) : HttpInterface {
    private lateinit var trustManagers: Array<TrustManager>
    override fun transmit(url: String, tx: ByteArray, headers: Array<String>): HttpInterface.HttpResponse {
        // SMS Most requires verification even if an old installation retained the unsafe preference.
        check(!runBlocking { ignoreTLSCertificateFlow.first() }) { "TLS certificate verification required" }
        val trust = trustManagers.filterIsInstance<X509TrustManager>().single()
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
        val response = LpaHttpTransport.transmit(url,tx,headers,tls.socketFactory,trust)
        return HttpInterface.HttpResponse(response.code,response.body)
    }
    override fun usePublicKeyIds(pkids: Array<String>) {
        trustManagers = TrustManagerFactory.getInstance("PKIX").apply { init(keyIdToKeystore(pkids)) }.trustManagers
    }
}
