package ru.smsbridge.app;

import java.util.concurrent.*;
import java.util.function.Consumer;

/** One serial task, checked by a separate supervisor. Never replaces a still-running task. */
final class RecoveringTask implements AutoCloseable {
    interface Step {void run() throws Exception;}
    private final ScheduledExecutorService worker;private final Step step;
    private final Consumer<Exception> report;private final Runnable restarted;private final long delayMillis;
    private ScheduledFuture<?> job;private boolean closed;
    RecoveringTask(ScheduledExecutorService worker,Step step,Consumer<Exception> report,Runnable restarted,long delayMillis) {
        this.worker=worker;this.step=step;this.report=report;this.restarted=restarted;this.delayMillis=delayMillis;
    }
    synchronized void ensure() {
        if(closed||job!=null&&!job.isDone())return;
        boolean recovery=job!=null;
        job=worker.scheduleWithFixedDelay(()->{
            try{step.run();}catch(Exception e){try{report.accept(e);}catch(Exception ignored){}}
        },0,delayMillis,TimeUnit.MILLISECONDS);
        if(recovery)try{restarted.run();}catch(Exception ignored){}
    }
    @Override public synchronized void close(){closed=true;if(job!=null)job.cancel(true);worker.shutdownNow();}
}
