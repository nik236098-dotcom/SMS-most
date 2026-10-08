// SPDX-License-Identifier: GPL-3.0-or-later
package im.angry.openeuicc.service

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reuse APDU service binding across adjacent reads; never close it during an operation. */
class IdleServiceSession<T>(
    private val scope: CoroutineScope,
    private val idleMillis: Long = 30_000,
    private val connect: suspend () -> Handle<T>
) {
    class Handle<T>(val value: T, val valid: () -> Boolean, val close: () -> Unit)
    private val mutex = Mutex()
    private var handle: Handle<T>? = null
    private var expiry: Job? = null
    suspend fun <R> use(work: suspend (T) -> R): R = mutex.withLock {
        expiry?.cancel()
        if(handle?.valid?.invoke() == false) {
            val old = handle;handle = null;old?.close?.invoke()
        }
        val current = handle ?: connect().also { handle = it }
        try { work(current.value) }
        finally {
            expiry = scope.launch {
                delay(idleMillis)
                mutex.withLock {
                    if(handle === current) {
                        handle = null
                        current.close()
                    }
                }
            }
        }
    }
}
