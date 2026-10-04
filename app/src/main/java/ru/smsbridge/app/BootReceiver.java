package ru.smsbridge.app;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i) {
        if(!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())&&!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction()))return;
        if(BridgeApp.store().running()) {
            BridgeApp.store().put("service_last_boot",""+System.currentTimeMillis());
            // start() schedules retry before requesting the foreground service and records failures.
            try{RelayService.start(c);}catch(Exception ignored){}
        }
    }
}
