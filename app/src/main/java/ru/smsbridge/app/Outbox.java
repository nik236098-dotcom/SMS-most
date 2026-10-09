package ru.smsbridge.app;

import android.content.Context;
import android.os.PowerManager;
import org.json.JSONObject;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class Outbox {
    private static final AtomicBoolean DRAINING=new AtomicBoolean(false);
    static void drain(Context c) {
        Store s=BridgeApp.store();if(!s.running()||s.chat()==0)return;
        try {drain(c,s,new Telegram(s.get("token","")));}catch(Exception e){s.put("error",Telegram.safe(e));}
    }
    static void drain(Context c,Store s,Telegram t) {
        if(!s.running()||s.chat()==0||!DRAINING.compareAndSet(false,true))return;
        PowerManager.WakeLock lock=null;
        try {
            lock=c.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"smsbridge:delivery");
            lock.acquire(90000);String epoch=s.epoch();
            for(int count=0;count<10 && s.running();count++) {
                JSONObject p=s.next();if(p==null)return;long id=p.getLong("id");
                long destination=p.optLong("chat_id");if(destination<=0)destination=s.chat();
                if(!s.chats().contains(destination)){s.failed(id,0,15,"Получатель изменился: доставка остановлена");continue;}
                if(!p.optString("epoch").equals(s.epoch())){s.failed(id,0,15,"Чат изменился: очередь нельзя перенаправить другому получателю");continue;}
                try {
                    if(p.optString("kind").equals("bot_reply")) {
                        if(!s.running()||!epoch.equals(s.epoch())||!s.pending(id))return;
                        BotReply.deliver(t,p,destination);s.delivered(id);s.pace(destination);continue;
                    }
                    List<JSONObject> chunks=SmsText.messages(p,destination);int i=p.getInt("part");
                    if(i<chunks.size()) {
                        if(!s.running() || !epoch.equals(s.epoch()) || !s.pending(id))return;
                        JSONObject part=chunks.get(i);
                        try {t.call("sendMessage",part);}
                        catch(Telegram.ApiError e) {
                            if(!e.formatting || !part.has("entities"))throw e;
                            // A rejected formatting request has not delivered the text. Retry the same
                            // part once without entities; never replay already acknowledged parts.
                            JSONObject plain=new JSONObject(part.toString());plain.remove("entities");
                            t.call("sendMessage",plain);
                        }
                        s.progress(id,++i);s.pace(destination);
                    }
                    if(i>=chunks.size())s.delivered(id);
                } catch(Exception e) {
                    Telegram.ApiError api=e instanceof Telegram.ApiError?(Telegram.ApiError)e:null;
                    if(api!=null && api.code==429) {
                        s.telegramWait(Math.max(1,api.retry));
                        s.failed(id,p.optInt("attempts"),Math.max(1,api.retry),Telegram.safe(e));return;
                    }
                    s.failed(id,p.optInt("attempts"),0,Telegram.safe(e));
                    if(api!=null && api.code==403)s.recipientUnavailable(destination);
                    if(api==null || (api.code!=400 && api.code!=403))return;
                    // Only this message (400) or recipient (403) waits. Other ready messages proceed.
                }
            }
        } catch(Exception e){s.put("error",Telegram.safe(e));}
        finally {
            try {if(lock!=null && lock.isHeld())lock.release();}catch(RuntimeException ignored){}
            finally {DRAINING.set(false);}
        }
    }
}
