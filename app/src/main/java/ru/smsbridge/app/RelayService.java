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
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class RelayService extends Service {
    private ScheduledExecutorService send,bot; private volatile boolean stopped;
    static void schedule(Context c) {
        c.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(7701,new ComponentName(c,RetryJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60000L).setPersisted(true).build());
    }
    static void start(Context c) {schedule(c);c.startForegroundService(new Intent(c,RelayService.class));}
    @Override public void onCreate() {
        super.onCreate();Store s=BridgeApp.store();
        if(!s.running()){stopSelf();return;}
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("relay","Пересылка SMS",NotificationManager.IMPORTANCE_LOW));
        Notification n=notification();
        if(Build.VERSION.SDK_INT>=34)startForeground(7702,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(7702,n);
        send=Executors.newSingleThreadScheduledExecutor();bot=Executors.newSingleThreadScheduledExecutor();
        send.scheduleWithFixedDelay(()->{try{if(!s.running()){stopSelf();return;}deviceStatus();Outbox.drain(this);getSystemService(NotificationManager.class).notify(7702,notification());}catch(Exception e){s.put("error",Telegram.safe(e));}},0,5,TimeUnit.SECONDS);
        bot.execute(()->{Bot worker=new Bot(this);while(!stopped && s.running()) {
            try {worker.reconcile();}catch(Exception e){if(!s.get("adapter_slot","-1").equals("-1"))s.clearActive();}
            try {worker.poll(10);}catch(Exception e){s.put("bot_error",Telegram.safe(e));try{TimeUnit.SECONDS.sleep(e instanceof Telegram.ApiError?Math.min(60,Math.max(10,((Telegram.ApiError)e).retry)):10);}catch(InterruptedException stop){Thread.currentThread().interrupt();return;}}
        }});
    }
    private Notification notification() {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,RelayService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"relay").setSmallIcon(ru.smsbridge.app.R.drawable.ic_bridge).setContentTitle("SMS Мост · бот запущен")
            .setContentText(BridgeApp.store().chat()==0?"Ожидает код от получателя в Telegram":"SMS в Telegram · в очереди: "+BridgeApp.store().pending()).setOngoing(true).setContentIntent(open)
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
        if(i!=null&&"STOP".equals(i.getAction())){BridgeApp.store().put("enabled","false");BridgeApp.store().put("bot_enabled","false");stopSelf();return START_NOT_STICKY;}
        if(!BridgeApp.store().running()){stopSelf();return START_NOT_STICKY;}return START_STICKY;
    }
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onDestroy(){stopped=true;if(send!=null)send.shutdownNow();if(bot!=null)bot.shutdownNow();super.onDestroy();}
}
