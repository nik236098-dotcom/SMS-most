package ru.smsbridge.app;
import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RetryJob extends JobService {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();private Future<?> task;
    @Override public boolean onStartJob(JobParameters p) {
        if(!BridgeApp.store().enabled())return false;
        task=executor.submit(()->{Outbox.drain(this);jobFinished(p,false);});return true;
    }
    @Override public boolean onStopJob(JobParameters p){if(task!=null)task.cancel(true);return BridgeApp.store().enabled();}
    @Override public void onDestroy(){executor.shutdownNow();super.onDestroy();}
}
