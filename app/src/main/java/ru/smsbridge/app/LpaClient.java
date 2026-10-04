package ru.smsbridge.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/** Connects only to the protected companion built by tools/build_companion.py. */
final class LpaClient {
    private final Context context;
    LpaClient(Context c) { context=c.getApplicationContext(); }
    boolean installed() { return context.getPackageManager().resolveContentProvider("ru.smsbridge.lpa",0)!=null; }
    JSONArray query(String action,Map<String,String> args) throws Exception {
        if(!installed()) throw new UserError("Компонент управления 9eSIM ещё не установлен. Пересылка SMS работает отдельно.");
        android.content.pm.ProviderInfo provider=context.getPackageManager().resolveContentProvider("ru.smsbridge.lpa",0);
        String pin=BuildConfig.LPA_SIGNER_SHA256;
        if(pin.isEmpty() || !context.getPackageManager().hasSigningCertificate(provider.packageName,hex(pin),android.content.pm.PackageManager.CERT_INPUT_SHA256))
            throw new UserError("Подпись компонента 9eSIM не подтверждена. Нужна согласованная сборка двух APK.");
        Uri.Builder u=new Uri.Builder().scheme("content").authority("ru.smsbridge.lpa").appendPath(action);
        for(Map.Entry<String,String> e:args.entrySet()) u.appendQueryParameter(e.getKey(),e.getValue());
        u.appendQueryParameter("json","true");
        try(Cursor c=context.getContentResolver().query(u.build(),null,null,null,null)) {
            if(c==null||!c.moveToFirst()) throw new UserError("Адаптер не ответил");
            int error=c.getColumnIndex("error");if(error>=0 && c.getString(error)!=null) throw new UserError("Адаптер отклонил операцию. Откройте компонент 9eSIM на телефоне.");
            int rows=c.getColumnIndex("rows"); if(rows<0) throw new UserError("Несовместимая версия компонента 9eSIM");
            JSONArray data=new JSONArray(c.getString(rows));
            for(int i=0;i<data.length();i++) if(data.getJSONObject(i).has("error")) throw new UserError("Адаптер не выполнил операцию. Проверь список профилей перед повтором.");
            return data;
        } catch(SecurityException e) { throw new UserError("Компоненты приложения собраны с разными ключами. Нужна согласованная сборка."); }
    }
    private static byte[] hex(String text) {byte[] out=new byte[text.length()/2];for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(text.substring(i*2,i*2+2),16);return out;}
    JSONArray cards() throws Exception { return query("cards",new HashMap<>()); }
    Map<String,String> args(int slot,int port) {Map<String,String> a=new HashMap<>();a.put("slot",""+slot);a.put("port",""+port);return a;}
    JSONArray profiles(int slot,int port) throws Exception {return query("profiles",args(slot,port));}
    JSONObject card() throws Exception {
        JSONArray a=cards();if(a.length()!=1) throw new UserError(a.length()==0?"9eSIM не обнаружен":"Найдено несколько адаптеров. Эта версия рассчитана на один 9eSIM.");return a.getJSONObject(0);
    }
    void refresh(Store s) throws Exception {
        JSONObject card=card();int slot=card.getInt("slot"),port=card.optInt("port",0);
        JSONArray ps=profiles(slot,port);s.put("adapter_slot",""+slot);
        String key=null;for(int i=0;i<ps.length();i++) {JSONObject p=ps.getJSONObject(i);if(p.optBoolean("enabled")) {
            if(key!=null) throw new UserError("Несколько активных профилей: требуется отдельная настройка");key=Rules.profileKey(card.getString("eid"),p.getString("iccid"));
        }}
        s.clearActive();if(key!=null)s.active(slot,key);
    }
    JSONObject download(String code,String confirmation) throws Exception {
        // Certificate checking must remain enabled in the underlying LPA.
        Map<String,String> settings=new HashMap<>();settings.put("name","ignoreTlsCertificate");settings.put("enabled","false");query("setPreference",settings);
        JSONObject card=card();Map<String,String> a=args(card.getInt("slot"),card.optInt("port",0));
        a.put("activationCode",code);if(!confirmation.isEmpty())a.put("confirmationCode",confirmation);
        JSONArray result=query("downloadProfile",a);
        if(result.length()!=1 || !result.getJSONObject(0).has("iccid")) throw new UserError("Загрузка не подтверждена. Проверь профили; повторно использовать QR-код автоматически не будем.");
        JSONObject p=result.getJSONObject(0);p.put("eid",card.getString("eid"));return p;
    }
    void enable(String iccid) throws Exception {
        JSONObject card=card();Map<String,String>a=args(card.getInt("slot"),card.optInt("port",0));a.put("iccid",iccid);a.put("refresh","true");
        JSONArray result=query("enableProfile",a);
        if(result.length()!=1 || !result.getJSONObject(0).optBoolean("success")) throw new UserError("Переключение не подтверждено");
    }
}
