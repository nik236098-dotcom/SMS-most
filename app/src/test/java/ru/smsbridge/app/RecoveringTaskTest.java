package ru.smsbridge.app;

import org.junit.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RecoveringTaskTest {
    ScheduledExecutorService executor;RecoveringTask task;CountDownLatch release;
    @Before public void setup(){executor=Executors.newSingleThreadScheduledExecutor();release=new CountDownLatch(1);}
    @After public void finish() throws Exception {release.countDown();if(task!=null)task.close();else executor.shutdownNow();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));}
    @Test public void ordinaryFailureAndBrokenErrorLoggerDoNotEndLoop() throws Exception {
        AtomicInteger count=new AtomicInteger();CountDownLatch second=new CountDownLatch(1);
        task=new RecoveringTask(executor,()->{if(count.incrementAndGet()==1)throw new IllegalStateException();second.countDown();},e->{throw new IllegalStateException("storage unavailable");},()->{},5);
        task.ensure();assertTrue(second.await(2,TimeUnit.SECONDS));assertTrue(count.get()>=2);
    }
    @Test public void supervisorRearmsUnexpectedlyTerminatedTask() throws Exception {
        AtomicInteger count=new AtomicInteger(),restarts=new AtomicInteger();CountDownLatch first=new CountDownLatch(1),second=new CountDownLatch(1);
        task=new RecoveringTask(executor,()->{if(count.incrementAndGet()==1){first.countDown();throw new AssertionError("worker terminated");}second.countDown();},e->{},restarts::incrementAndGet,5);
        task.ensure();assertTrue(first.await(2,TimeUnit.SECONDS));executor.submit(()->{}).get(2,TimeUnit.SECONDS);
        task.ensure();assertTrue(second.await(2,TimeUnit.SECONDS));assertEquals(1,restarts.get());
    }
    @Test public void repeatedRecoveryCannotOverlapStillRunningWork() throws Exception {
        AtomicInteger count=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1);
        task=new RecoveringTask(executor,()->{count.incrementAndGet();entered.countDown();release.await();},e->{},()->{},5);
        task.ensure();assertTrue(entered.await(2,TimeUnit.SECONDS));for(int i=0;i<100;i++)task.ensure();assertEquals(1,count.get());
    }
    @Test public void explicitStopCannotBeUndoneBySupervisor() {
        AtomicInteger count=new AtomicInteger();task=new RecoveringTask(executor,count::incrementAndGet,e->{},()->{},5);
        task.close();task.ensure();assertEquals(0,count.get());
    }
    @Test public void rejectedSchedulingCanBeRetriedWithoutStuckMarker() {
        ScheduledExecutorService fake=org.mockito.Mockito.mock(ScheduledExecutorService.class);
        org.mockito.Mockito.when(fake.scheduleWithFixedDelay(org.mockito.ArgumentMatchers.any(Runnable.class),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS)))
            .thenThrow(new RejectedExecutionException()).thenReturn(org.mockito.Mockito.mock(ScheduledFuture.class));
        RecoveringTask retry=new RecoveringTask(fake,()->{},e->{},()->{},10);
        assertThrows(RejectedExecutionException.class,retry::ensure);retry.ensure();retry.close();
    }
}
