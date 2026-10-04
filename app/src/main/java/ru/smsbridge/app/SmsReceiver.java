package ru.smsbridge.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import org.json.JSONObject;

public final class SmsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent) {
        if(!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction()))return;
        Store s=BridgeApp.store();if(!s.enabled())return;
        try {
            SmsMessage[] parts=Telephony.Sms.Intents.getMessagesFromIntent(intent);if(parts==null||parts.length==0)return;
            String sender=parts[0].getOriginatingAddress();StringBuilder body=new StringBuilder();
            for(SmsMessage p:parts)body.append(p.getMessageBody());
            int sub=intent.getIntExtra("subscription",intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,-1));
            int slot=intent.getIntExtra("slot",intent.getIntExtra("slot_id",-1));
            if(slot<0 && sub>=0 && c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)==PackageManager.PERMISSION_GRANTED) {
                SubscriptionManager sm=c.getSystemService(SubscriptionManager.class);SubscriptionInfo info=sm.getActiveSubscriptionInfo(sub);if(info!=null)slot=info.getSimSlotIndex();
            }
            String recipient=(slot>=0||sub>=0)?s.recipient(slot,sub):"Номер не определён: Android не передал SIM";
            long now=System.currentTimeMillis(),networkTime=parts[0].getTimestampMillis();
            String fingerprint=Rules.hash(sub+"|"+slot+"|"+sender+"|"+networkTime+"|"+body);
            s.enqueue(fingerprint,new JSONObject().put("sender",sender==null?"Неизвестно":sender).put("body",body.toString())
                .put("recipient",recipient).put("received",now).put("network_time",networkTime).put("sub_id",sub).put("slot",slot));
        } catch(Exception e) {s.put("error","Не удалось сохранить входящее SMS. Проверь разрешения и свободное место.");}
    }
}
