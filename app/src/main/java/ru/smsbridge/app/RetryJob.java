package ru.smsbridge.app;
import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RetryJob extends JobService {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();private Future<?> task;
    @Override public boolean onStartJob(JobParameters p) {
        try{if(!BridgeApp.store().running())return false;}catch(RuntimeException e){return false;}
        task=executor.submit(()->{try{
            BridgeApp.store().put("service_last_retry",""+System.currentTimeMillis());
            try{RelayService.start(this);}catch(RuntimeException ignored){}
            Outbox.drain(this);
            // Android 12+ may refuse to restart the service from a background job (battery
            // optimisation on). Then at least answer queued commands every 15 minutes.
            if(!RelayService.active())try{new Bot(this).poll(0);}catch(Exception e){BridgeApp.store().put("bot_error",Telegram.safe(e));}
        }catch(RuntimeException ignored){}finally{jobFinished(p,false);}});return true;
    }
    @Override public boolean onStopJob(JobParameters p){if(task!=null)task.cancel(true);return BridgeApp.store().running();}
    @Override public void onDestroy(){executor.shutdownNow();super.onDestroy();}
}
