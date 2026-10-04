package ru.smsbridge.app;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i) {
        if(!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())&&!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction()))return;
        if(BridgeApp.store().running())try{RelayService.start(c);}catch(Exception e){RelayService.schedule(c);BridgeApp.store().put("error","Автозапуск ограничен Android. Откройте приложение.");}
    }
}
