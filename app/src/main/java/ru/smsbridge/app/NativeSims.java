package ru.smsbridge.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/** Active subscriptions come from Android, independently of an adapter companion. */
interface NativeSims {
    JSONArray list() throws Exception;
    static NativeSims on(Context c) {
        return () -> {
            if(c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED)
                throw new UserError("На Android открой «Мои SIM-карты» и разреши доступ к состоянию телефона.");
            SubscriptionManager manager=c.getSystemService(SubscriptionManager.class);
            if(manager==null)throw new UserError("Android не предоставил список SIM-карт");
            List<SubscriptionInfo> subscriptions=manager.getActiveSubscriptionInfoList();
            JSONArray result=new JSONArray();
            if(subscriptions!=null)for(SubscriptionInfo info:subscriptions)
                result.put(new JSONObject().put("id",info.getSubscriptionId()).put("slot",info.getSimSlotIndex())
                    .put("name",String.valueOf(info.getDisplayName())).put("embedded",info.isEmbedded()));
            return result;
        };
    }
}
