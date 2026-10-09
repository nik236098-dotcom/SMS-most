package ru.smsbridge.app;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class AdapterTasksTest {
    @Test public void busyTaskRejectsSecondWorkAndDoesNotCancelNativeOperationOnWarning() {
        List<Runnable> work=new ArrayList<>(),alarms=new ArrayList<>();
        ScheduledExecutorService timer=mock(ScheduledExecutorService.class);ScheduledFuture<?> alarm=mock(ScheduledFuture.class);
        doAnswer(i->{alarms.add(i.getArgument(0));return alarm;}).when(timer).schedule(any(Runnable.class),anyLong(),any(TimeUnit.class));
        AdapterTasks tasks=new AdapterTasks(work::add,timer,45000);Runnable slow=mock(Runnable.class),done=mock(Runnable.class),nativeWork=mock(Runnable.class);
        assertTrue(tasks.submit(()->{},nativeWork,slow,done));assertTrue(tasks.busy());
        assertFalse(tasks.submit(()->fail("second accepted"),()->fail("second work"),()->{},()->{}));
        alarms.get(0).run();verify(slow).run();assertTrue(tasks.busy());verifyNoInteractions(nativeWork);
        work.get(0).run();verify(nativeWork).run();verify(done).run();assertFalse(tasks.busy());verify(alarm).cancel(false);
        alarms.get(0).run();verify(slow,times(1)).run();
    }
    @Test public void rejectedExecutorReleasesGateAndCancelsWarning() {
        ScheduledExecutorService timer=mock(ScheduledExecutorService.class);ScheduledFuture<?> alarm=mock(ScheduledFuture.class);
        doReturn(alarm).when(timer).schedule(any(Runnable.class),anyLong(),any(TimeUnit.class));
        AdapterTasks tasks=new AdapterTasks(r->{throw new RejectedExecutionException();},timer,45000);Runnable done=mock(Runnable.class);
        assertThrows(RejectedExecutionException.class,()->tasks.submit(()->{},()->{},()->{},done));
        assertFalse(tasks.busy());verify(done).run();verify(alarm).cancel(false);
    }
    @Test public void failingWorkReleasesGateEvenWhenCleanupFails() {
        List<Runnable> work=new ArrayList<>();ScheduledExecutorService timer=mock(ScheduledExecutorService.class);
        doReturn(mock(ScheduledFuture.class)).when(timer).schedule(any(Runnable.class),anyLong(),any(TimeUnit.class));
        AdapterTasks tasks=new AdapterTasks(work::add,timer,45000);
        tasks.submit(()->{},()->{throw new IllegalStateException();},()->{},()->{throw new IllegalArgumentException();});
        assertThrows(IllegalArgumentException.class,()->work.get(0).run());assertFalse(tasks.busy());
    }
    @Test public void userWaitsBehindBackgroundAndRunsBeforeAnotherScan() {
        List<Runnable> work=new ArrayList<>();List<String> events=new ArrayList<>();ScheduledExecutorService timer=mock(ScheduledExecutorService.class);
        doReturn(mock(ScheduledFuture.class)).when(timer).schedule(any(Runnable.class),anyLong(),any(TimeUnit.class));
        AdapterTasks tasks=new AdapterTasks(work::add,timer,45000);
        assertTrue(tasks.submitBackground(()->events.add("scan-start"),()->events.add("scan"),()->{},()->events.add("scan-done")));
        assertEquals(AdapterTasks.Submission.QUEUED,tasks.submitUser(()->events.add("user-start"),()->events.add("user"),()->{},()->events.add("user-done")));
        assertEquals(java.util.Arrays.asList("scan-start"),events);
        assertEquals(AdapterTasks.Submission.BUSY,tasks.submitUser(()->fail(),()->fail(),()->{},()->{}));
        work.get(0).run();assertTrue(tasks.busy());
        assertFalse(tasks.submitBackground(()->fail(),()->fail(),()->{},()->{}));
        work.get(1).run();assertFalse(tasks.busy());
        assertEquals(java.util.Arrays.asList("scan-start","scan","scan-done","user-start","user","user-done"),events);
    }
    @Test public void realUserMutationNeverQueuesAnotherMutationBehindIt() {
        List<Runnable> work=new ArrayList<>();ScheduledExecutorService timer=mock(ScheduledExecutorService.class);
        doReturn(mock(ScheduledFuture.class)).when(timer).schedule(any(Runnable.class),anyLong(),any(TimeUnit.class));
        AdapterTasks tasks=new AdapterTasks(work::add,timer,45000);
        assertEquals(AdapterTasks.Submission.STARTED,tasks.submitUser(()->{},()->{},()->{},()->{}));
        assertEquals(AdapterTasks.Submission.BUSY,tasks.submitUser(()->fail(),()->fail(),()->{},()->{}));
        work.get(0).run();assertEquals(1,work.size());assertFalse(tasks.busy());
    }
    @Test public void backgroundFailureStillRunsAcceptedUserRequestOnce() {
        List<Runnable> work=new ArrayList<>();ScheduledExecutorService timer=mock(ScheduledExecutorService.class);
        doReturn(mock(ScheduledFuture.class)).when(timer).schedule(any(Runnable.class),anyLong(),any(TimeUnit.class));
        AdapterTasks tasks=new AdapterTasks(work::add,timer,45000);Runnable user=mock(Runnable.class);
        tasks.submitBackground(()->{},()->{throw new IllegalStateException();},()->{},()->{throw new IllegalArgumentException();});
        tasks.submitUser(()->{},user,()->{},()->{});
        assertThrows(IllegalArgumentException.class,()->work.get(0).run());work.get(1).run();verify(user).run();assertFalse(tasks.busy());
    }
    @Test public void cancellationFailureStillReleasesBusyFlag() {
        List<Runnable> work=new ArrayList<>();ScheduledExecutorService timer=mock(ScheduledExecutorService.class);ScheduledFuture<?> alarm=mock(ScheduledFuture.class);
        doReturn(alarm).when(timer).schedule(any(Runnable.class),anyLong(),any(TimeUnit.class));doThrow(new IllegalStateException()).when(alarm).cancel(false);
        AdapterTasks tasks=new AdapterTasks(work::add,timer,45000);Runnable done=mock(Runnable.class);
        tasks.submit(()->{},()->{},()->{},done);assertThrows(IllegalStateException.class,()->work.get(0).run());verify(done).run();assertFalse(tasks.busy());
    }
    @Test public void directExecutorDoesNotRunCleanupTwiceAfterWorkThrows() {
        ScheduledExecutorService timer=mock(ScheduledExecutorService.class);doReturn(mock(ScheduledFuture.class)).when(timer).schedule(any(Runnable.class),anyLong(),any(TimeUnit.class));
        AdapterTasks tasks=new AdapterTasks(Runnable::run,timer,45000);Runnable done=mock(Runnable.class);
        assertThrows(IllegalStateException.class,()->tasks.submit(()->{},()->{throw new IllegalStateException();},()->{},done));verify(done).run();assertFalse(tasks.busy());
    }
}
