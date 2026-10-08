package ru.smsbridge.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

final class Store extends SQLiteOpenHelper {
    private final Context context;
    Store(Context c) { super(c, "bridge.db", null, 3); context=c.getApplicationContext(); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE settings (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
        db.execSQL("CREATE TABLE outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, fingerprint TEXT UNIQUE, payload TEXT NOT NULL, created INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'pending', part INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0, next_try INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '', delivered INTEGER NOT NULL DEFAULT 0, route TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE numbers (profile TEXT PRIMARY KEY, number TEXT NOT NULL)");
        db.execSQL("CREATE TABLE active (slot INTEGER PRIMARY KEY, profile TEXT NOT NULL, observed INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE operations (id INTEGER PRIMARY KEY, state TEXT NOT NULL, updated INTEGER NOT NULL)");
        createDeliveryWait(db);
    }
    private static void createDeliveryWait(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE delivery_wait (route TEXT PRIMARY KEY, until_time INTEGER NOT NULL, reason TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if(oldVersion==1) {db.execSQL("ALTER TABLE outbox ADD COLUMN route TEXT NOT NULL DEFAULT ''");
            String previousEpoch="",previousChat="0";
            try(Cursor c=db.rawQuery("SELECT k,v FROM settings WHERE k IN ('epoch','chat')",null)){
                while(c.moveToNext())try{if(c.getString(0).equals("epoch"))previousEpoch=Crypto.open(c.getString(1));else previousChat=Crypto.open(c.getString(1));}catch(Exception e){throw new IllegalStateException("Не удалось обновить очередь",e);}
            }
            ContentValues cv=new ContentValues();cv.put("route",previousChat.equals("0")?"unclaimed":Rules.hash(previousEpoch+"|"+previousChat));db.update("outbox",cv,null,null);oldVersion=2;}
        if(oldVersion==2 && newVersion==3) {
            createDeliveryWait(db);
            // Preserve any known Telegram flood deadline while releasing old day-long local waits.
            // HAVING without GROUP BY is rejected by SQLite < 3.39 (Android 9–13).
            // Keep the aggregate row even for an empty queue; zero means no cooldown.
            db.execSQL("INSERT INTO delivery_wait(route,until_time,reason) SELECT '*',COALESCE(MAX(next_try),0),'telegram' FROM outbox WHERE state='pending' AND error='Telegram ограничил частоту отправки'");
            db.execSQL("UPDATE outbox SET next_try=0 WHERE state='pending'");
            return;
        }
        throw new IllegalStateException("Unsupported database version");
    }
    synchronized String get(String k, String fallback) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT v FROM settings WHERE k=?", new String[]{k})) {
            return c.moveToFirst() ? Crypto.open(c.getString(0)) : fallback;
        } catch (Exception e) { throw new IllegalStateException("Не удалось прочитать защищённые настройки. Откройте приложение.", e); }
    }
    synchronized void put(String k, String v) {
        try { ContentValues cv = new ContentValues(); cv.put("k", k); cv.put("v", Crypto.seal(v));
            getWritableDatabase().insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) { throw new IllegalStateException("Не удалось сохранить настройки", e); }
    }
    boolean smsPermission() { return context.checkSelfPermission(android.Manifest.permission.RECEIVE_SMS)==android.content.pm.PackageManager.PERMISSION_GRANTED; }
    boolean phonePermission() { return context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)==android.content.pm.PackageManager.PERMISSION_GRANTED; }
    boolean callLogPermission() { return context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)==android.content.pm.PackageManager.PERMISSION_GRANTED; }
    boolean callsEnabled() { return get("calls_enabled","true").equals("true"); }
    synchronized void resetCalls() {getWritableDatabase().delete("settings","k LIKE 'call_state:%'",null);}
    synchronized void saveCall(String key,JSONObject next,JSONObject payload) throws Exception {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            if(payload!=null) {
                enqueue("call|"+next.getString("event"),payload);
                next.put("notified",true).put("notify",false);
                put("call_last_saved",""+System.currentTimeMillis());put("call_result","Входящий звонок сохранён в очередь Telegram");
            }
            put(key,next.toString());db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }
    // Permission can change in Android settings while the bot keeps running.
    // The legacy "enabled" preference is not a live permission check.
    synchronized boolean enabled() { return running() && smsPermission(); }
    synchronized boolean running() { return get("bot_enabled", get("enabled", "false")).equals("true") && !get("token", "").isEmpty(); }
    synchronized long chat() { return Long.parseLong(get("chat", "0")); }
    synchronized java.util.List<Long> chats() {
        return Rules.telegramIds(get("chat_ids",chat()>0?""+chat():""));
    }
    private String route(long chat) {return chat==0?"unclaimed":Rules.hash(epoch()+"|"+chat);}
    synchronized String epoch() { return get("epoch", ""); }
    synchronized void configureBot(String token, String username, java.util.List<Long> recipients) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
        java.util.List<Long> checked=Rules.telegramIds(Rules.joinIds(recipients));
        long recipient=checked.isEmpty()?0:checked.get(0);
        if (!get("token", "").equals(token) || !chats().equals(checked)) {
            if(AdapterTasks.shared().busy())throw new IllegalStateException("Дождись завершения операции 9eSIM перед изменением получателей или бота");
            if (running() || enabled()) throw new IllegalStateException("Сначала останови бота");
            if (pending() > 0) throw new IllegalStateException("Сначала отправь или удали очередь прежнего бота");
            if(!get("token", "").equals(token))db.delete("delivery_wait",null,null);
            put("chat", ""+recipient);put("chat_ids",Rules.joinIds(checked)); put("chat_name", Rules.joinIds(checked)); put("offset", "0");
            put("epoch", UUID.randomUUID().toString()); put("draft", "{}");
            put("setup_code", ""); getWritableDatabase().delete("operations", null, null);
            getWritableDatabase().delete("outbox", "state='sent'", null);
        }
        put("token", token); put("bot_username", username); put("error", "");
        if (chat() == 0) setupCode();
        db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }
    synchronized String setupCode() {
        if (chat() != 0) return "";
        if (Long.parseLong(get("setup_expires", "0")) <= System.currentTimeMillis() || get("setup_code", "").isEmpty()) {
            put("setup_code", SetupCode.create()); put("setup_expires", ""+(System.currentTimeMillis()+30*60000L));
            put("setup_created", ""+(System.currentTimeMillis()/1000*1000));
            put("setup_failures", "0"); put("setup_locked_until", "0");
        }
        return get("setup_code", "");
    }
    synchronized boolean claim(long chat, String name, String code, long received) {
        long now=System.currentTimeMillis();
        if (chat<=0 || chat()!=0 || !running() || received<Long.parseLong(get("setup_created", "0"))
            || now<Long.parseLong(get("setup_locked_until", "0"))) return false;
        if (!SetupCode.matches(get("setup_code", ""), code, Long.parseLong(get("setup_expires", "0")), now)) {
            int attempts=Integer.parseInt(get("setup_failures", "0"))+1; put("setup_failures", ""+attempts);
            if (attempts>=10) {put("setup_locked_until", ""+(now+60000L));put("setup_failures", "0");}
            return false;
        }
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {put("chat", ""+chat);put("chat_ids",""+chat);put("chat_name", name);put("setup_code", "");put("setup_expires", "0");
            ContentValues cv=new ContentValues();cv.put("route",route(chat));db.update("outbox",cv,"route='unclaimed'",null);
            put("setup_failures", "0");put("error", "");db.setTransactionSuccessful();return true;
        } finally {db.endTransaction();}
    }
    synchronized void bind(String token, long chat, String name) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            if (enabled()) throw new IllegalStateException("Сначала остановите пересылку");
            if (pending() > 0) throw new IllegalStateException("Сначала отправьте или удалите очередь для прежнего чата");
            put("token", token); put("chat", Long.toString(chat));put("chat_ids",""+chat); put("chat_name", name);
            put("epoch", UUID.randomUUID().toString()); put("offset", "0"); put("draft", "{}");
            db.delete("operations", null, null);
            put("pair_nonce", ""); put("error", "");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    synchronized void number(String profile, String number) throws Exception {
        ContentValues cv = new ContentValues(); cv.put("profile", profile); cv.put("number", Crypto.seal(Rules.phone(number)));
        getWritableDatabase().insertWithOnConflict("numbers", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }
    synchronized void forgetNumber(String profile) {getWritableDatabase().delete("numbers","profile=?",new String[]{profile});}
    synchronized String number(String profile) throws Exception {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT number FROM numbers WHERE profile=?", new String[]{profile})) {
            return c.moveToFirst() ? Crypto.open(c.getString(0)) : "Номер не задан";
        }
    }
    synchronized void clearActive() { getWritableDatabase().delete("active", null, null); }
    synchronized void clearActive(int slot) {getWritableDatabase().delete("active","slot=?",new String[]{""+slot});}
    synchronized boolean managed(int slot) {
        if(!get("esim_control","false").equals("true")||slot<0)return false;
        String cards=get("adapter_cards","");
        if(cards.isEmpty())return get("adapter_slot","-1").equals(""+slot);
        try{return new JSONObject(cards).has(""+slot);}catch(Exception e){return true;}
    }
    synchronized void rememberCards(JSONArray cards) throws Exception {
        JSONObject before=new JSONObject(get("adapter_cards","{}")),after=new JSONObject();
        for(int i=0;i<cards.length();i++) {
            JSONObject card=cards.getJSONObject(i);String slot=""+card.getInt("slot");
            String eid=card.optBoolean("unavailable")?before.optString(slot,"unknown"):card.getString("eid");
            if(after.has(slot))throw new UserError("В одном слоте обнаружено несколько карт; проверь адаптеры");
            after.put(slot,eid);if(!eid.equals(before.optString(slot)))clearActive(card.getInt("slot"));
            if(card.optBoolean("unavailable"))clearActive(card.getInt("slot"));
        }
        java.util.Iterator<String> keys=before.keys();while(keys.hasNext()){String slot=keys.next();if(!after.has(slot))clearActive(Integer.parseInt(slot));}
        put("adapter_cards",after.toString());
    }
    synchronized void switching(int slot,boolean value) {put("switching:"+slot,""+value);}
    synchronized boolean switching(int slot) {return get("switching:"+slot,get("adapter_slot","-1").equals(""+slot)?get("switching","false"):"false").equals("true");}
    synchronized JSONArray pendingDeletes() throws Exception {
        JSONArray list=new JSONArray(get("pending_deletes","[]"));JSONObject legacy=new JSONObject(get("last_delete","{}"));
        if(legacy.has("iccid")){boolean seen=false;for(int i=0;i<list.length();i++)if(list.getJSONObject(i).optString("nonce").equals(legacy.optString("nonce")))seen=true;
            if(!seen)list.put(legacy);put("pending_deletes",list.toString());put("last_delete","{}");}
        return list;
    }
    synchronized void rememberDelete(JSONObject intent) throws Exception {
        JSONArray list=pendingDeletes();for(int i=0;i<list.length();i++)if(list.getJSONObject(i).getString("nonce").equals(intent.getString("nonce")))return;
        list.put(intent);put("pending_deletes",list.toString());
    }
    synchronized void finishDelete(String nonce) throws Exception {
        JSONArray list=pendingDeletes(),rest=new JSONArray();for(int i=0;i<list.length();i++)if(!list.getJSONObject(i).getString("nonce").equals(nonce))rest.put(list.getJSONObject(i));put("pending_deletes",rest.toString());
    }
    synchronized void active(int slot, String profile) {
        ContentValues cv = new ContentValues(); cv.put("slot", slot); cv.put("profile", profile); cv.put("observed", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("active", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }
    synchronized String recipient(int slot, int subId) throws Exception {
        if (!managed(slot)) return number("physical:"+subId);
        if (switching(slot)) return "Номер не определён: переключение eSIM";
        try (Cursor c = getReadableDatabase().rawQuery("SELECT profile,observed FROM active WHERE slot=?", new String[]{""+slot})) {
            if (c.moveToFirst()) {
                // A stale adapter observation must never become a confidently labelled SMS.
                if (System.currentTimeMillis() - c.getLong(1) > 25000) return "Номер не определён: профиль не проверен";
                String eid=new JSONObject(get("adapter_cards","{}")).optString(""+slot,"");
                if(!eid.isEmpty()&&!c.getString(0).startsWith(eid+":"))return "Номер не определён: адаптер изменился";
                return number(c.getString(0));
            }
        }
        // Only an explicitly configured physical-SIM mapping may be used as fallback.
        return "Номер не определён: нет связи с адаптером";
    }
    synchronized long enqueue(String fingerprint, JSONObject p) throws Exception {
        p.put("epoch", epoch());
        java.util.List<Long> targets=chats();if(targets.isEmpty())targets=java.util.Collections.singletonList(0L);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();long first=-1;
        try {for(long target:targets) {
            JSONObject copy=new JSONObject(p.toString()).put("chat_id",target);
            ContentValues cv=new ContentValues();cv.put("fingerprint",fingerprint+"|"+target);cv.put("payload",Crypto.seal(copy.toString()));
            cv.put("created",System.currentTimeMillis());cv.put("route",route(target));
            long id=db.insertWithOnConflict("outbox",null,cv,SQLiteDatabase.CONFLICT_IGNORE);if(first<0)first=id;
        } db.setTransactionSuccessful();return first;}finally{db.endTransaction();}
    }
    synchronized JSONObject next() throws Exception {
        long now=System.currentTimeMillis();
        for(int checked=0;checked<10;checked++) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,payload,part,attempts,next_try FROM outbox r WHERE state='pending' AND next_try<=? AND NOT EXISTS (SELECT 1 FROM delivery_wait w WHERE (w.route=r.route OR w.route='*') AND w.until_time>?) ORDER BY id LIMIT 1", new String[]{""+now,""+now})) {
            if (!c.moveToFirst() || c.getLong(4) > System.currentTimeMillis()) return null;
            try{return new JSONObject(Crypto.open(c.getString(1))).put("id", c.getLong(0)).put("part", c.getInt(2)).put("attempts", c.getInt(3));}
            catch(Exception e){failed(c.getLong(0),c.getInt(3),0,"Не удалось прочитать сохранённое сообщение. Оно сохранено; остальные отправляются.");}
        }
        }return null;
    }
    synchronized void enqueueNotice(String fingerprint,long target,String text) throws Exception {
        if(!chats().contains(target))return;
        JSONObject p=new JSONObject().put("kind","notice").put("body",text).put("epoch",epoch()).put("chat_id",target).put("received",System.currentTimeMillis());
        ContentValues cv=new ContentValues();cv.put("fingerprint",fingerprint+"|"+target);cv.put("payload",Crypto.seal(p.toString()));
        cv.put("created",System.currentTimeMillis());cv.put("route",route(target));
        getWritableDatabase().insertWithOnConflict("outbox",null,cv,SQLiteDatabase.CONFLICT_IGNORE);
    }
    synchronized void progress(long id, int nextPart) {
        ContentValues cv = new ContentValues(); cv.put("part", nextPart); cv.put("attempts", 0); cv.put("next_try", 0);
        getWritableDatabase().update("outbox", cv, "id=?", new String[]{""+id});
    }
    synchronized boolean pending(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM outbox WHERE id=? AND state='pending'", new String[]{""+id})) {
            return c.moveToFirst();
        }
    }
    synchronized void delivered(long id) {
        ContentValues cv = new ContentValues(); cv.put("state", "sent"); cv.put("delivered", System.currentTimeMillis()); cv.put("error", "");
        getWritableDatabase().update("outbox", cv, "id=?", new String[]{""+id});
        put("last_delivery", Long.toString(System.currentTimeMillis())); put("error", "");
        getWritableDatabase().delete("outbox", "state='sent' AND delivered<?", new String[]{""+(System.currentTimeMillis()-7*86400000L)});
    }
    synchronized void failed(long id, int attempts, long seconds, String error) {
        ContentValues cv = new ContentValues(); cv.put("attempts", attempts+1); cv.put("next_try", System.currentTimeMillis()+Rules.retryMillis(attempts, seconds)); cv.put("error", error);
        getWritableDatabase().update("outbox", cv, "id=?", new String[]{""+id}); put("error", error);
    }
    synchronized int pending() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM outbox WHERE state='pending'", null)) { c.moveToFirst(); return c.getInt(0); }
    }
    synchronized int pendingFor(long target) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM outbox WHERE state='pending' AND route=?",new String[]{route(target)})) {c.moveToFirst();return c.getInt(0);}
    }
    synchronized JSONArray queued(long target) throws Exception {
        JSONArray out=new JSONArray();long wait=deliveryWait(target);
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,payload,error,next_try FROM outbox WHERE state='pending' AND route=? ORDER BY id LIMIT 10",new String[]{route(target)})) {
            while(c.moveToNext())out.put(historyPayload(c.getString(1)).put("id",c.getLong(0)).put("error",c.getString(2)).put("next_try",Math.max(wait,c.getLong(3))));
        }return out;
    }
    synchronized long deliveryWait(long target) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT MAX(until_time) FROM delivery_wait WHERE route=? OR route='*'",new String[]{route(target)})) {return c.moveToFirst()?c.getLong(0):0;}
    }
    private void defer(String key,long millis,String reason) {
        long until=System.currentTimeMillis()+Math.min(millis,Long.MAX_VALUE/2);
        try(Cursor c=getReadableDatabase().rawQuery("SELECT until_time FROM delivery_wait WHERE route=?",new String[]{key})) {if(c.moveToFirst()&&c.getLong(0)>=until)return;}
        ContentValues cv=new ContentValues();cv.put("route",key);cv.put("until_time",until);cv.put("reason",reason);
        getWritableDatabase().insertWithOnConflict("delivery_wait",null,cv,SQLiteDatabase.CONFLICT_REPLACE);
    }
    synchronized void pace(long target) {defer(route(target),1000,"pace");}
    synchronized void recipientUnavailable(long target) {defer(route(target),15000,"recipient");}
    synchronized void telegramWait(long seconds) {defer("*",Rules.retryMillis(0,Math.max(1,seconds)),"telegram");}
    synchronized long telegramRemaining() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT until_time FROM delivery_wait WHERE route='*'",null)) {return c.moveToFirst()?Math.max(0,c.getLong(0)-System.currentTimeMillis()):0;}
    }
    synchronized int today() {
        java.util.Calendar cal=java.util.Calendar.getInstance(); cal.set(java.util.Calendar.HOUR_OF_DAY,0); cal.set(java.util.Calendar.MINUTE,0); cal.set(java.util.Calendar.SECOND,0); cal.set(java.util.Calendar.MILLISECOND,0);
        try (Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM outbox WHERE state='sent' AND delivered>=?",new String[]{""+cal.getTimeInMillis()})) { c.moveToFirst();return c.getInt(0); }
    }
    synchronized JSONArray recent() throws Exception {
        JSONArray out=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,payload,state,error FROM outbox ORDER BY id DESC LIMIT 20",null)) {
            while(c.moveToNext())out.put(historyPayload(c.getString(1)).put("id",c.getLong(0)).put("state",c.getString(2)).put("error",c.getString(3)));
        }return out;
    }
    synchronized JSONArray recent(long target) throws Exception {return recent(target,null);}
    synchronized JSONArray recent(long target,String kind) throws Exception {
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,payload,state,error FROM outbox WHERE route=? ORDER BY id DESC", new String[]{route(target)})) {
            while(out.length()<10 && c.moveToNext()) {
                JSONObject p=historyPayload(c.getString(1));
                if(kind==null||p.optString("kind","sms").equals(kind))out.put(p.put("id",c.getLong(0)).put("state",c.getString(2)).put("error",c.getString(3)));
            }
        } return out;
    }
    private JSONObject historyPayload(String encrypted) throws Exception {
        try{return new JSONObject(Crypto.open(encrypted));}
        catch(Exception e){return new JSONObject().put("kind","sms").put("recipient","Запись пока не читается")
            .put("sender","Ошибка чтения").put("body","Не удалось прочитать сохранённое сообщение. Запись сохранена в очереди.");}
    }
    synchronized void retry() { getWritableDatabase().execSQL("UPDATE outbox SET next_try=0 WHERE state='pending'"); }
    synchronized void retry(long target) {
        ContentValues cv=new ContentValues();cv.put("next_try",0);
        getWritableDatabase().update("outbox",cv,"state='pending' AND route=?",new String[]{route(target)});
        getWritableDatabase().delete("delivery_wait","route=? AND reason='recipient'",new String[]{route(target)});
        // A manual retry must never shorten Telegram's explicit retry_after or send pacing.
    }
    synchronized void purgeQueue() { getWritableDatabase().delete("outbox", "state='pending'", null); }
    synchronized boolean beginOperation(long id) {
        ContentValues cv = new ContentValues(); cv.put("id", id); cv.put("state", "started"); cv.put("updated",System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict("operations", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }
    synchronized void endOperation(long id) {
        ContentValues cv = new ContentValues(); cv.put("state","done"); cv.put("updated",System.currentTimeMillis());
        getWritableDatabase().update("operations",cv,"id=?",new String[]{""+id});
        getWritableDatabase().delete("operations","state='done' AND updated<?",new String[]{""+(System.currentTimeMillis()-7*86400000L)});
    }
}
