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
}
