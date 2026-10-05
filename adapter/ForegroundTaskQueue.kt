// SPDX-License-Identifier: GPL-3.0-or-later
package im.angry.openeuicc.service

import im.angry.openeuicc.service.EuiccChannelManagerService.ForegroundTaskState
import im.angry.openeuicc.service.EuiccChannelManagerService.ForegroundTaskSubscriberFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class ForegroundTaskStartException(cause: Throwable) : Exception("Adapter operation could not start", cause)

/** A task owns its completion stream. Notifications cannot prevent delivery of its final result. */
internal class ForegroundTaskQueue(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val startTimeoutMillis: Long = 30_000L
) {
    val state = MutableStateFlow<ForegroundTaskState>(ForegroundTaskState.Idle)
    private val ids = AtomicLong(System.currentTimeMillis())
    private val subscribers = ConcurrentHashMap<Long, SharedFlow<ForegroundTaskState>>()
    private data class Starting(val id: Long, val signal: CompletableDeferred<Unit>)
    @Volatile private var starting: Starting? = null

    fun onStarted(taskId: Long) {
        starting?.takeIf { it.id == taskId }?.signal?.complete(Unit)
    }

    private fun stream(id: Long, events: SharedFlow<ForegroundTaskState>) =
        ForegroundTaskSubscriberFlow(id, events.transformWhile {
            emit(it)
            it !is ForegroundTaskState.Done
        })

    fun recover(id: Long): ForegroundTaskSubscriberFlow? = subscribers[id]?.let { stream(id, it) }

    fun launch(
        requestStart: (Long) -> Unit,
        prepare: suspend () -> Unit,
        work: suspend () -> Unit,
        cleanup: () -> Unit,
        progress: suspend () -> Unit,
        failure: (Throwable) -> Unit
    ): ForegroundTaskSubscriberFlow {
        val id = ids.incrementAndGet()
        if (!state.compareAndSet(ForegroundTaskState.Idle, ForegroundTaskState.InProgress(0))) {
            return ForegroundTaskSubscriberFlow(id, flow {
                emit(ForegroundTaskState.Done(IllegalStateException("There are tasks currently running")))
            })
        }
        val events = MutableSharedFlow<ForegroundTaskState>(replay = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        events.tryEmit(ForegroundTaskState.InProgress(0))
        subscribers[id] = events.asSharedFlow()
        subscribers.keys.sorted().dropLast(5).forEach { subscribers.remove(it) }
        val start = Starting(id, CompletableDeferred())
        starting = start
        val observer = scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            state.collect { current ->
                if (current is ForegroundTaskState.InProgress && current.progress > 0) {
                    events.tryEmit(current)
                    // Updating a notification is best effort; it must not kill the completion path.
                    try { progress() } catch (_: Exception) { }
                }
            }
        }
        val runner = scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            var error: Throwable? = null
            try {
                try {
                    withTimeout(startTimeoutMillis) { start.signal.await() }
                    prepare()
                } catch (t: Throwable) {
                    throw ForegroundTaskStartException(t)
                }
                // Never release the queue while JNI may still be writing a profile to the card.
                withContext(workerDispatcher + NonCancellable) { work() }
            } catch (t: Throwable) {
                error = t
            } finally {
                // Cleanup cannot suppress the terminal result, including cancellation/start failures.
                observer.cancel()
                try { cleanup() } catch (t: Throwable) { if (error == null) error = t }
                if (starting === start) starting = null
                if (error != null) try { failure(error!!) } catch (_: Throwable) { }
                events.tryEmit(ForegroundTaskState.Done(error))
                state.value = ForegroundTaskState.Idle
            }
        }
        // The startup waiter is already registered even when called from a Binder/IO thread.
        if (!runner.isCompleted) {
            try { requestStart(id) } catch (t: Throwable) { start.signal.completeExceptionally(t) }
        }
        return stream(id, events)
    }
}
