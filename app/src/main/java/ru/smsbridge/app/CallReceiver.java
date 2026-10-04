package ru.smsbridge.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import org.json.JSONObject;
import java.util.List;

/** Captures new incoming cellular calls and queues their caller ID for Telegram. */
public final class CallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        if(intent==null||!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction()))return;
        Store store=BridgeApp.store();boolean queued=false;
        try {
            String state=intent.getStringExtra(TelephonyManager.EXTRA_STATE);
            if(!"RINGING".equals(state)&&!"OFFHOOK".equals(state)&&!"IDLE".equals(state))return;
            store.put("call_last_broadcast",""+System.currentTimeMillis());
            store.put("call_receive_error","");store.put("call_sim_warning","");
            if(!store.running()||!store.callsEnabled()) {store.put("call_result","Уведомления о звонках остановлены");return;}
            if(!store.phonePermission()){store.put("call_result","Нет разрешения «Телефон»");return;}
            int sub=intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,intent.getIntExtra("subscription",-1));
            int slot=intent.getIntExtra(SubscriptionManager.EXTRA_SLOT_INDEX,intent.getIntExtra("slot",intent.getIntExtra("slot_id",-1)));
            // Never fall back to the default SIM on a multi-SIM phone: it may be the wrong line.
            try {
                SubscriptionManager manager=context.getSystemService(SubscriptionManager.class);
                if(manager!=null) {
                    if(sub>=0&&slot<0) {SubscriptionInfo info=manager.getActiveSubscriptionInfo(sub);if(info!=null)slot=info.getSimSlotIndex();}
                    else if(sub<0) {
                        List<SubscriptionInfo> active=manager.getActiveSubscriptionInfoList();
                        if(active!=null)for(SubscriptionInfo info:active)
                            if((slot>=0&&slot==info.getSimSlotIndex())||(slot<0&&active.size()==1)) {sub=info.getSubscriptionId();slot=info.getSimSlotIndex();break;}
                    }
                }
            } catch(Exception e) {store.put("call_sim_warning","Не удалось уточнить SIM звонка ("+e.getClass().getSimpleName()+")");}
            String key="call_state:"+(sub>=0?"sub:"+sub:slot>=0?"slot:"+slot:"unknown");
            synchronized(store) {
                JSONObject old=new JSONObject(store.get(key,"{}"));
                // Do not reuse a previous bot's unfinished call after recipients have changed.
                if(!old.optString("epoch").equals(store.epoch()))old=new JSONObject();
                boolean numberPresent=intent.hasExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                JSONObject next=CallEvents.transition(old,state,intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER),numberPresent,System.currentTimeMillis());
                next.put("epoch",store.epoch());
                if(!next.has("recipient")&&next.optBoolean("active")) {
                    String recipient="Номер SIM не определён: Android не передал SIM";
                    if(sub>=0||slot>=0)try{recipient=store.recipient(slot,sub);}
                    catch(Exception e){store.put("call_sim_warning","Звонок сохранён без номера SIM ("+e.getClass().getSimpleName()+")");}
                    next.put("recipient",recipient).put("sub",sub).put("slot",slot);
                }
                JSONObject payload=null;
                if(next.optBoolean("notify")) {
                    String number=next.optString("number");
                    String caller=number.isEmpty()?(store.callLogPermission()?"Номер скрыт или не передан оператором":"Номер недоступен: разреши «Журнал вызовов»"):number;
                    payload=new JSONObject().put("kind","call").put("sender",caller).put("body","")
                        .put("recipient",next.optString("recipient","Номер SIM не определён"))
                        .put("received",next.getLong("received")).put("sub_id",next.optInt("sub",-1)).put("slot",next.optInt("slot",-1));
                }
                store.saveCall(key,next,payload);queued=payload!=null;
                if(!queued&&"RINGING".equals(state)&&!next.optBoolean("notified"))
                    store.put("call_result","Входящий звонок: ожидается номер абонента");
            }
            if(queued)try{RelayService.start(context);}catch(RuntimeException ignored){ /* Persisted queue survives a rejected foreground start. */ }
        } catch(Exception e) {
            store.put("call_receive_error","Не удалось сохранить звонок ("+e.getClass().getSimpleName()+"). Проверь свободное место.");
            store.put("call_result","Ошибка обработки звонка");
        }
    }
}
