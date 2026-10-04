package ru.smsbridge.app;

import android.content.Context;
import android.os.PowerManager;
import org.json.JSONObject;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class Outbox {
    private static final AtomicBoolean DRAINING=new AtomicBoolean(false);
    static void drain(Context c) {
        Store s=BridgeApp.store();if(!s.running()||s.chat()==0||!DRAINING.compareAndSet(false,true))return;
        PowerManager.WakeLock lock=c.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"smsbridge:delivery");
        try {
            lock.acquire(90000);Telegram t=new Telegram(s.get("token",""));String epoch=s.epoch();
            for(int count=0;count<5 && s.running();count++) {
                JSONObject p=s.next();if(p==null)return;long id=p.getLong("id");
                long destination=p.optLong("chat_id");if(destination<=0)destination=s.chat();
                if(!s.chats().contains(destination)){s.failed(id,0,86400,"Получатель изменился: доставка остановлена");continue;}
                if(!p.optString("epoch").equals(s.epoch())){s.failed(id,0,86400,"Чат изменился: очередь нельзя перенаправить другому получателю");return;}
                List<JSONObject> chunks=SmsText.messages(p,destination);
                try {
                    for(int i=p.getInt("part");i<chunks.size();i++) {
                        if(!s.running() || !epoch.equals(s.epoch()) || !s.pending(id))return;
                        t.call("sendMessage",chunks.get(i));s.progress(id,i+1);
                    }s.delivered(id);
                } catch(Exception e) {
                    boolean blocked=e instanceof Telegram.ApiError && (((Telegram.ApiError)e).code==403 || ((Telegram.ApiError)e).code==400);
                    s.failed(id,p.optInt("attempts"),blocked?86400:e instanceof Telegram.ApiError?((Telegram.ApiError)e).retry:0,Telegram.safe(e));
                    if(!blocked)return;
                }
            }
        } catch(Exception e){s.put("error",Telegram.safe(e));}
        finally {if(lock.isHeld())lock.release();DRAINING.set(false);}
    }
}
