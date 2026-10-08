package im.angry.openeuicc.service

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class IdleServiceSessionTest {
    @Test fun consecutiveReadsReuseServiceAndCloseOnceAfterIdle() = runTest {
        var opens=0;var closes=0
        val session=IdleServiceSession(this,100) { IdleServiceSession.Handle(++opens,{true},{closes++}) }
        assertEquals(1,session.use { it });advanceTimeBy(80)
        assertEquals(1,session.use { it });advanceTimeBy(80);runCurrent();assertEquals(0,closes)
        advanceUntilIdle();assertEquals(1,closes);assertEquals(1,opens)
        assertEquals(2,session.use { it });advanceUntilIdle();assertEquals(2,closes)
    }
    @Test fun slowNativeOperationIsNotClosedAtIdleDeadline() = runTest {
        var closes=0
        val session=IdleServiceSession(this,100) { IdleServiceSession.Handle(1,{true},{closes++}) }
        session.use { delay(600_000);assertEquals(0,closes) }
        assertEquals(0,closes);advanceUntilIdle();assertEquals(1,closes)
    }
    @Test fun failedReadDoesNotReopenServiceForNextRead() = runTest {
        var opens=0;var closes=0
        val session=IdleServiceSession(this,100) { IdleServiceSession.Handle(++opens,{true},{closes++}) }
        try { session.use<Unit> { throw IllegalStateException("read failed") };fail() } catch (_: IllegalStateException) {}
        assertEquals(1,session.use { it });advanceUntilIdle();assertEquals(1,opens);assertEquals(1,closes)
    }
    @Test fun disconnectedServiceIsReplacedBeforeNextOperation() = runTest {
        var opens=0;var closes=0;var valid=true
        val session=IdleServiceSession(this,100) { IdleServiceSession.Handle(++opens,{valid},{closes++}) }
        session.use { };valid=false
        assertEquals(2,session.use { valid=true;it });assertEquals(1,closes)
        advanceUntilIdle();assertEquals(2,closes)
    }
    @Test fun failedConnectionCanBeRetried() = runTest {
        var attempts=0
        val session=IdleServiceSession(this,100) {
            attempts++;if(attempts==1)throw IllegalStateException("bind failed")
            IdleServiceSession.Handle(1,{true},{})
        }
        try { session.use { };fail() } catch (_: IllegalStateException) {}
        assertEquals(1,session.use { it });advanceUntilIdle();assertEquals(2,attempts)
    }
    @Test fun adjacentOperationsNeverOverlap() = runTest {
        var active=0;var maximum=0;var opens=0
        val session=IdleServiceSession(this,100) { IdleServiceSession.Handle(++opens,{true},{}) }
        val a=async { session.use { active++;maximum=maxOf(maximum,active);delay(200);active-- } }
        val b=async { session.use { active++;maximum=maxOf(maximum,active);delay(200);active-- } }
        a.await();b.await();assertEquals(1,maximum);assertEquals(1,opens);advanceUntilIdle()
    }
}
