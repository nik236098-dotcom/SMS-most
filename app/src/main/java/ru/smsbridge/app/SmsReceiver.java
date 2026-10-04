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
        if(intent==null || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction()))return;
        Store s=BridgeApp.store();
        try {
            s.put("sms_last_broadcast",""+System.currentTimeMillis());
            s.put("sms_receive_error","");s.put("sms_sim_warning","");
            if(!s.running()){s.put("sms_result","SMS пропущено: бот был остановлен");return;}
            if(!s.smsPermission()){s.put("sms_result","SMS пропущено: нет разрешения Android");return;}
            SmsMessage[] parts=Telephony.Sms.Intents.getMessagesFromIntent(intent);
            if(parts==null||parts.length==0){s.put("sms_result","Android передал событие без частей SMS");return;}
            for(SmsMessage p:parts)if(p==null || p.getMessageBody()==null){s.put("sms_result","Не удалось разобрать текст SMS");return;}
            String sender=parts[0].getOriginatingAddress();StringBuilder body=new StringBuilder();
            for(SmsMessage p:parts)body.append(p.getMessageBody());
            int sub=intent.getIntExtra("subscription",intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,-1));
            int slot=intent.getIntExtra("slot",intent.getIntExtra("slot_id",-1));
            // SIM metadata is optional. A telephony/adapter failure must not discard SMS.
            try {
                if(slot<0 && sub>=0 && c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)==PackageManager.PERMISSION_GRANTED) {
                    SubscriptionManager sm=c.getSystemService(SubscriptionManager.class);
                    SubscriptionInfo info=sm==null?null:sm.getActiveSubscriptionInfo(sub);if(info!=null)slot=info.getSimSlotIndex();
                }
            } catch(Exception e) {s.put("sms_sim_warning","Не удалось уточнить слот SIM ("+e.getClass().getSimpleName()+")");}
            String recipient="Номер не определён: Android не передал SIM";
            if(slot>=0||sub>=0)try {recipient=s.recipient(slot,sub);}
            catch(Exception e) {
                recipient="Номер не определён: ошибка определения SIM";
                s.put("sms_sim_warning","SMS сохранено без номера SIM ("+e.getClass().getSimpleName()+")");
            }
            long now=System.currentTimeMillis(),networkTime=parts[0].getTimestampMillis();
            String fingerprint=Rules.hash(sub+"|"+slot+"|"+sender+"|"+networkTime+"|"+body);
            long id=s.enqueue(fingerprint,new JSONObject().put("sender",sender==null?"Неизвестно":sender).put("body",body.toString())
                .put("recipient",recipient).put("received",now).put("network_time",networkTime).put("sub_id",sub).put("slot",slot));
            if(id>=0)s.put("sms_last_saved",""+now);
            s.put("sms_result",id>=0?"SMS сохранено в очередь Telegram":"Повтор уже сохранённого SMS");
            // Receiving a system broadcast does not imply that the delivery service is alive.
            // A rejected foreground start must not undo the saved message.
            try {RelayService.start(c);}catch(RuntimeException ignored) { /* start records the reason; periodic retry remains scheduled */ }
        } catch(Exception e) {
            // Keep capture errors separate: successful Telegram delivery must not erase them.
            s.put("sms_receive_error","Не удалось сохранить SMS ("+e.getClass().getSimpleName()+"). Проверь свободное место.");
            s.put("sms_result","Ошибка обработки входящего SMS");
        }
    }
}
