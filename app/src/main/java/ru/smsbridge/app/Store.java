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
    Store(Context c) { super(c, "bridge.db", null, 2); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE settings (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
        db.execSQL("CREATE TABLE outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, fingerprint TEXT UNIQUE, payload TEXT NOT NULL, created INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'pending', part INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0, next_try INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '', delivered INTEGER NOT NULL DEFAULT 0, route TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE numbers (profile TEXT PRIMARY KEY, number TEXT NOT NULL)");
        db.execSQL("CREATE TABLE active (slot INTEGER PRIMARY KEY, profile TEXT NOT NULL, observed INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE operations (id INTEGER PRIMARY KEY, state TEXT NOT NULL, updated INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if(oldVersion==1 && newVersion==2) {db.execSQL("ALTER TABLE outbox ADD COLUMN route TEXT NOT NULL DEFAULT ''");
            String previousEpoch="",previousChat="0";
            try(Cursor c=db.rawQuery("SELECT k,v FROM settings WHERE k IN ('epoch','chat')",null)){
                while(c.moveToNext())try{if(c.getString(0).equals("epoch"))previousEpoch=Crypto.open(c.getString(1));else previousChat=Crypto.open(c.getString(1));}catch(Exception e){throw new IllegalStateException("Не удалось обновить очередь",e);}
            }
            ContentValues cv=new ContentValues();cv.put("route",previousChat.equals("0")?"unclaimed":Rules.hash(previousEpoch+"|"+previousChat));db.update("outbox",cv,null,null);return;}
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
    synchronized boolean enabled() { return get("enabled", "false").equals("true"); }
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
            if (running() || enabled()) throw new IllegalStateException("Сначала останови бота");
            if (pending() > 0) throw new IllegalStateException("Сначала отправь или удали очередь прежнего бота");
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
    synchronized String number(String profile) throws Exception {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT number FROM numbers WHERE profile=?", new String[]{profile})) {
            return c.moveToFirst() ? Crypto.open(c.getString(0)) : "Номер не задан";
        }
    }
    synchronized void clearActive() { getWritableDatabase().delete("active", null, null); }
    synchronized void active(int slot, String profile) {
        ContentValues cv = new ContentValues(); cv.put("slot", slot); cv.put("profile", profile); cv.put("observed", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("active", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }
    synchronized String recipient(int slot, int subId) throws Exception {
        if (get("switching", "false").equals("true")) return "Номер не определён: переключение eSIM";
        try (Cursor c = getReadableDatabase().rawQuery("SELECT profile,observed FROM active WHERE slot=?", new String[]{""+slot})) {
            if (c.moveToFirst()) {
                // A stale adapter observation must never become a confidently labelled SMS.
                if (System.currentTimeMillis() - c.getLong(1) > 25000) return "Номер не определён: профиль не проверен";
                return number(c.getString(0));
            }
        }
        // Only an explicitly configured physical-SIM mapping may be used as fallback.
        if (get("adapter_slot", "-1").equals(""+slot)) return "Номер не определён: нет связи с адаптером";
        return number("physical:"+subId);
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
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,payload,part,attempts,next_try FROM outbox r WHERE state='pending' AND next_try<=? AND id=(SELECT MIN(id) FROM outbox q WHERE q.state='pending' AND q.route=r.route) ORDER BY id LIMIT 1", new String[]{""+System.currentTimeMillis()})) {
            if (!c.moveToFirst() || c.getLong(4) > System.currentTimeMillis()) return null;
            return new JSONObject(Crypto.open(c.getString(1))).put("id", c.getLong(0)).put("part", c.getInt(2)).put("attempts", c.getInt(3));
        }
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
    synchronized int today() {
        java.util.Calendar cal=java.util.Calendar.getInstance(); cal.set(java.util.Calendar.HOUR_OF_DAY,0); cal.set(java.util.Calendar.MINUTE,0); cal.set(java.util.Calendar.SECOND,0); cal.set(java.util.Calendar.MILLISECOND,0);
        try (Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM outbox WHERE state='sent' AND delivered>=?",new String[]{""+cal.getTimeInMillis()})) { c.moveToFirst();return c.getInt(0); }
    }
    synchronized JSONArray recent() throws Exception {
        JSONArray out=new JSONArray();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,payload,state,error FROM outbox ORDER BY id DESC LIMIT 20",null)) {
            while(c.moveToNext())out.put(new JSONObject(Crypto.open(c.getString(1))).put("id",c.getLong(0)).put("state",c.getString(2)).put("error",c.getString(3)));
        }return out;
    }
    synchronized JSONArray recent(long target) throws Exception {
        JSONArray out = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,payload,state,error FROM outbox WHERE route=? ORDER BY id DESC LIMIT 10", new String[]{route(target)})) {
            while(c.moveToNext()) out.put(new JSONObject(Crypto.open(c.getString(1))).put("id",c.getLong(0)).put("state",c.getString(2)).put("error",c.getString(3)));
        } return out;
    }
    synchronized void retry() { getWritableDatabase().execSQL("UPDATE outbox SET next_try=0 WHERE state='pending'"); }
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
