package ru.smsbridge.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Network;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class RelayService extends Service {
    private ScheduledExecutorService supervisor;private RecoveringTask sender,poller,adapter;
    private volatile boolean stopped;private Telegram pollingApi;private Bot worker;
    private volatile long nextPoll;private int pollFailures;
    private ConnectivityManager.NetworkCallback networkCallback;private volatile boolean networkReady;
    static void schedule(Context c) {
        JobScheduler jobs=c.getSystemService(JobScheduler.class);
        // Do not reset the retry interval on every SMS or foreground-service restart.
        if(jobs.getPendingJob(7701)!=null)return;
        if(jobs.schedule(new JobInfo.Builder(7701,new ComponentName(c,RetryJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60000L).setPersisted(true).build())!=JobScheduler.RESULT_SUCCESS)
            throw new IllegalStateException("Android не принял задачу повторной отправки");
    }
    static void start(Context c) {
        Store s=BridgeApp.store();if(!s.running())return;
        s.put("service_last_request",""+System.currentTimeMillis());
        try {schedule(c);s.put("service_schedule_error","");}
        catch(Exception e){s.put("service_schedule_error","Не удалось назначить повторную отправку ("+e.getClass().getSimpleName()+")");}
        try {c.startForegroundService(new Intent(c,RelayService.class));}
        catch(RuntimeException e){s.put("service_start_error","Запуск сервиса отклонён ("+e.getClass().getSimpleName()+"). Проверь автозапуск и батарею.");throw e;}
    }
    @Override public void onCreate() {
        super.onCreate();Store s=BridgeApp.store();
        if(!s.running()){stopSelf();return;}
        try {
            getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("relay","SMS и входящие звонки",NotificationManager.IMPORTANCE_LOW));
            Notification n=notification();
            if(Build.VERSION.SDK_INT>=34)startForeground(7702,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(7702,n);
            s.put("service_start_error","");
        } catch(RuntimeException e) {
            s.put("service_start_error","Не удалось запустить постоянный сервис ("+e.getClass().getSimpleName()+")");stopSelf();return;
        }
        worker=new Bot(this);pollingApi=new Telegram(s.get("token",""));
        s.put("bot_last_seen","0");s.put("bot_loop_seen","0");
        sender=new RecoveringTask(Executors.newSingleThreadScheduledExecutor(),this::deliver,
            e->s.put("error",Telegram.safe(e)),()->recovered("Отправка сообщений восстановлена"),1000);
        poller=new RecoveringTask(Executors.newSingleThreadScheduledExecutor(),this::pollOnce,
            e->s.put("bot_error",Telegram.safe(e)),()->recovered("Приём команд восстановлен"),300);
        adapter=new RecoveringTask(Executors.newSingleThreadScheduledExecutor(),worker::reconcileAsync,
            e->s.put("esim_refresh_error",Telegram.safe(e)),()->recovered("Проверка адаптеров восстановлена"),10000);
        supervisor=Executors.newSingleThreadScheduledExecutor();
        supervisor.scheduleWithFixedDelay(()->{try{ensureTasks();}catch(Exception ignored){}},0,3,TimeUnit.SECONDS);
        try {
            networkCallback=new ConnectivityManager.NetworkCallback() {
                @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities) {
                    boolean ready=capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    if(ready&&!networkReady)try{s.retry();reconnect();}catch(Exception e){s.put("bot_error",Telegram.safe(e));}networkReady=ready;
                }
                @Override public void onLost(Network network){networkReady=false;}
            };
            getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(networkCallback);
        }catch(RuntimeException e){networkCallback=null;}
    }
    private void recovered(String message){BridgeApp.store().put("connection_recovery",message+" · "+SmsDiagnostics.time(""+System.currentTimeMillis()));}
    private void ensureTasks() {
        if(stopped)return;if(sender!=null)sender.ensure();if(poller!=null)poller.ensure();if(adapter!=null)adapter.ensure();
    }
    private void reconnect(){if(stopped)return;if(pollingApi!=null)pollingApi.reconnectPolling();nextPoll=0;ensureTasks();}
    private void pollOnce() throws Exception {
        Store s=BridgeApp.store();if(stopped||!s.running())return;
        s.put("bot_loop_seen",""+System.currentTimeMillis());
        if(System.nanoTime()<nextPoll)return;
        android.os.PowerManager.WakeLock lock=null;
        try {
            lock=getSystemService(android.os.PowerManager.class).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"smsbridge:commands");lock.acquire(120000);
            worker.poll(pollingApi,10);pollFailures=0;
        }catch(Exception e){
            nextPoll=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Rules.retryMillis(pollFailures++,0));
            s.put("bot_error",Telegram.safe(e));
        }finally{if(lock!=null)try{if(lock.isHeld())lock.release();}catch(RuntimeException ignored){}}
    }
    void deliver() {
        Store s=BridgeApp.store();if(!s.running()){stopSelf();return;}
        try {s.put("service_last_work",""+System.currentTimeMillis());Outbox.drain(this);}
        catch(Exception e){s.put("error",Telegram.safe(e));}
        // Optional status/notification refresh must not prevent delivery.
        try {deviceStatus();getSystemService(NotificationManager.class).notify(7702,notification());}
        catch(RuntimeException ignored){}
    }
    private Notification notification() {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,RelayService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"relay").setSmallIcon(ru.smsbridge.app.R.drawable.ic_bridge).setContentTitle("SMS Мост · "+BotHealth.summary(BridgeApp.store()))
            .setContentText(BridgeApp.store().chat()==0?"Ожидает код от получателя в Telegram":"SMS и звонки в Telegram · в очереди: "+BridgeApp.store().pending()).setOngoing(true).setContentIntent(open)
            .addAction(new Notification.Action.Builder(null,"Остановить",stop).build()).build();
    }
    private void deviceStatus() {
        ConnectivityManager cm=getSystemService(ConnectivityManager.class);NetworkCapabilities nc=cm.getNetworkCapabilities(cm.getActiveNetwork());
        boolean online=nc!=null&&nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        Intent b=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        boolean plugged=b!=null&&b.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;
        int level=b==null?-1:b.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
        BridgeApp.store().put("device_status",Build.MODEL+" · "+(online?(nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":"мобильный интернет"):"нет интернета")+" · "+(plugged?"на зарядке":"батарея "+level+"%"));
    }
    @Override public int onStartCommand(Intent i,int flags,int id) {
        if(i!=null&&"STOP".equals(i.getAction())){BridgeApp.store().put("enabled","false");BridgeApp.store().put("bot_enabled","false");BridgeApp.store().resetCalls();stopSelf();return START_NOT_STICKY;}
        if(!BridgeApp.store().running()){stopSelf();return START_NOT_STICKY;}
        if(i!=null&&"RECONNECT".equals(i.getAction()))reconnect();else ensureTasks();return START_STICKY;
    }
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onDestroy(){stopped=true;if(pollingApi!=null)pollingApi.close();if(networkCallback!=null)try{getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);}catch(RuntimeException ignored){}if(supervisor!=null)supervisor.shutdownNow();if(sender!=null)sender.close();if(poller!=null)poller.close();if(adapter!=null)adapter.close();super.onDestroy();}
}
