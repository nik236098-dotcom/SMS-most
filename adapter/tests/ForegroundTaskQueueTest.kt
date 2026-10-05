package im.angry.openeuicc.service

import im.angry.openeuicc.service.EuiccChannelManagerService.ForegroundTaskState
import im.angry.openeuicc.service.EuiccChannelManagerService.ForegroundTaskSubscriberFlow
import im.angry.openeuicc.service.EuiccChannelManagerService.Companion.waitDone
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundTaskQueueTest {
    private class Fixture(val test: TestScope, val owner: CoroutineScope = test) {
        val queue = ForegroundTaskQueue(owner, StandardTestDispatcher(test.testScheduler), StandardTestDispatcher(test.testScheduler), 30_000)
        var ran = 0
        var cleaned = 0
        var requested = 0
        fun launch(
            start: (Long) -> Unit = { queue.onStarted(it) },
            prepare: suspend () -> Unit = {},
            work: suspend () -> Unit = {},
            cleanup: () -> Unit = {},
            progress: suspend () -> Unit = {},
            failure: (Throwable) -> Unit = {}
        ) = queue.launch({ requested++;start(it) }, prepare, { ran++;work() }, { cleaned++;cleanup() }, progress, failure)
        suspend fun nextSucceeds() {
            assertEquals(ForegroundTaskState.Idle, queue.state.value)
            val next = launch();test.runCurrent();assertNull(next.waitDone())
            assertEquals(ForegroundTaskState.Idle, queue.state.value)
        }
    }
    @Test fun successfulTaskCanBeObservedAfterItFinishes() = runTest {
        val f = Fixture(this);val task = f.launch();runCurrent()
        assertNull(task.waitDone());assertEquals(1,f.ran);assertEquals(1,f.cleaned);f.nextSucceeds()
    }
    @Test fun rejectedStartProducesFailureAndAllowsNextOperation() = runTest {
        val f = Fixture(this);val cause = IllegalStateException("foreground start rejected")
        val task = f.launch(start = { throw cause });runCurrent()
        val failure = task.waitDone();assertTrue(failure is ForegroundTaskStartException);assertSame(cause,failure!!.cause)
        assertEquals(0,f.ran);assertEquals(1,f.cleaned);f.nextSucceeds()
    }
    @Test fun missingStartCallbackTimesOutAndDoesNotLeakWaitingSubscriber() = runTest {
        val f = Fixture(this);val task = f.launch(start = {})
        advanceTimeBy(30_000);runCurrent()
        val failure = task.waitDone();assertTrue(failure is ForegroundTaskStartException)
        assertTrue(failure!!.cause is TimeoutCancellationException);assertEquals(0,f.ran);f.nextSucceeds()
    }
    @Test fun lateCallbackFromFailedOperationCannotStartNextOne() = runTest {
        val f = Fixture(this);var oldId = -1L
        val first = f.launch(start = { oldId = it });advanceTimeBy(30_000);runCurrent();assertNotNull(first.waitDone())
        var nextId = -1L;val second = f.launch(start = { nextId = it })
        f.queue.onStarted(oldId);runCurrent();assertEquals(0,f.ran)
        f.queue.onStarted(nextId);runCurrent();assertNull(second.waitDone());assertEquals(1,f.ran)
    }
    @Test fun notificationOrWakeLockSetupFailureCompletesBeforeAnyCardWrite() = runTest {
        val f = Fixture(this);val cause = SecurityException("notification denied")
        val task = f.launch(prepare = { throw cause });runCurrent()
        assertSame(cause,task.waitDone()!!.cause);assertEquals(0,f.ran);assertEquals(1,f.cleaned);f.nextSucceeds()
    }
    @Test fun networkFailureRunsCleanupAndNextTaskCanSucceed() = runTest {
        val f = Fixture(this);val cause = IOException("connection interrupted")
        val task = f.launch(work = { throw cause });runCurrent()
        assertSame(cause,task.waitDone());assertEquals(1,f.cleaned);f.nextSucceeds()
    }
    @Test fun cleanupFailureCannotLoseTerminalResult() = runTest {
        val f = Fixture(this);val cause = IllegalStateException("release failed")
        val task = f.launch(cleanup = { throw cause });runCurrent()
        assertSame(cause,task.waitDone());f.nextSucceeds()
    }
    @Test fun originalFailureSurvivesCleanupFailure() = runTest {
        val f = Fixture(this);val cause = IOException("network interrupted")
        val task = f.launch(work = { throw cause },cleanup = { throw IllegalStateException("release failed") });runCurrent()
        assertSame(cause,task.waitDone());f.nextSucceeds()
    }
    @Test fun progressNotificationFailureCannotKillCompletionStream() = runTest {
        val f = Fixture(this);var updates = 0
        val task = f.launch(work = {
            f.queue.state.value = ForegroundTaskState.InProgress(50);delay(1)
        }, progress = { updates++;throw SecurityException("notification failed") })
        advanceUntilIdle();assertTrue(updates>0);assertNull(task.waitDone());f.nextSucceeds()
    }
    @Test fun failureNotificationFailureStillReleasesQueue() = runTest {
        val f = Fixture(this);val cause = IOException("failed")
        val task = f.launch(work = { throw cause },failure = { throw IllegalStateException("notify failed") });runCurrent()
        assertSame(cause,task.waitDone());f.nextSucceeds()
    }
    @Test fun cancellationWhileWaitingToStartCompletesTheTask() = runTest {
        val owner = CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler));val f = Fixture(this,owner)
        val task = f.launch(start = {});owner.cancel();runCurrent()
        assertNotNull(task.waitDone());assertEquals(0,f.ran);assertEquals(ForegroundTaskState.Idle,f.queue.state.value)
    }
    @Test fun cancellationDoesNotMarkIdleWhileNativeWorkIsStillRunning() = runTest {
        val owner = CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler));val f = Fixture(this,owner)
        val release = CompletableDeferred<Unit>();val task = f.launch(work = { release.await() });runCurrent()
        owner.cancel();runCurrent();assertTrue(f.queue.state.value is ForegroundTaskState.InProgress);assertEquals(0,f.cleaned)
        release.complete(Unit);runCurrent();assertNotNull(task.waitDone());assertEquals(1,f.cleaned)
        assertEquals(ForegroundTaskState.Idle,f.queue.state.value)
    }
    @Test fun secondTaskCannotWriteWhileFirstTaskIsRunning() = runTest {
        val f = Fixture(this);val release = CompletableDeferred<Unit>();val first = f.launch(work = { release.await() });runCurrent()
        val second = f.launch();assertNotNull(second.waitDone());assertEquals(1,f.ran);assertEquals(1,f.requested)
        release.complete(Unit);runCurrent();assertNull(first.waitDone());f.nextSucceeds()
    }
    @Test fun recoveredSubscriberKeepsItsOwnOutcomeAcrossLaterTasks() = runTest {
        val f = Fixture(this);val cause = IOException("first failed");val first = f.launch(work = { throw cause });runCurrent()
        f.nextSucceeds();assertSame(cause,f.queue.recover(first.taskId)!!.waitDone());assertSame(cause,first.waitDone())
    }
    @Test fun taskIdsAreUniqueEvenWithinOneClockTickAndCacheIsBounded() = runTest {
        val f = Fixture(this);val ids = mutableListOf<Long>()
        repeat(7) { val task = f.launch();runCurrent();assertNull(task.waitDone());ids.add(task.taskId) }
        assertEquals(7,ids.distinct().size);assertNull(f.queue.recover(ids[0]));assertNull(f.queue.recover(ids[1]))
        ids.takeLast(5).forEach { assertNull(f.queue.recover(it)!!.waitDone()) }
    }
    @Test fun failedOperationEmitsExactlyOneTerminalResultWithoutRetryingWork() = runTest {
        val f = Fixture(this);val task = f.launch(work = { throw IOException("interrupted") });runCurrent()
        val events = task.toList();assertEquals(1,events.count { it is ForegroundTaskState.Done });assertEquals(1,f.ran)
        assertTrue(events.last() is ForegroundTaskState.Done)
    }
}
