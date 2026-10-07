package ru.smsbridge.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/** Uses the private in-app provider; external providers require an explicitly pinned certificate. */
final class LpaClient {
    private final Context context;
    LpaClient(Context c) { context=c.getApplicationContext(); }
    boolean installed() { return context.getPackageManager().resolveContentProvider("ru.smsbridge.lpa",0)!=null; }
    JSONArray query(String action,Map<String,String> args) throws Exception {
        if(!installed()) throw new UserError("Компонент управления 9eSIM ещё не установлен. Пересылка SMS работает отдельно.");
        android.content.pm.ProviderInfo provider=context.getPackageManager().resolveContentProvider("ru.smsbridge.lpa",0);
        String pin=BuildConfig.LPA_SIGNER_SHA256;
        if(!provider.packageName.equals(context.getPackageName()) && (pin.isEmpty() || !context.getPackageManager().hasSigningCertificate(provider.packageName,hex(pin),android.content.pm.PackageManager.CERT_INPUT_SHA256)))
            throw new UserError("Подпись компонента 9eSIM не подтверждена. Нужна согласованная сборка двух APK.");
        Uri.Builder u=new Uri.Builder().scheme("content").authority("ru.smsbridge.lpa").appendPath(action);
        for(Map.Entry<String,String> e:args.entrySet()) u.appendQueryParameter(e.getKey(),e.getValue());
        u.appendQueryParameter("json","true");
        try(Cursor c=context.getContentResolver().query(u.build(),null,null,null,null)) {
            if(c==null||!c.moveToFirst()) throw new UserError("Адаптер не ответил");
            int error=c.getColumnIndex("error");if(error>=0 && c.getString(error)!=null) throw new UserError("Адаптер отклонил операцию. Откройте компонент 9eSIM на телефоне.");
            int rows=c.getColumnIndex("rows"); if(rows<0) throw new UserError("Несовместимая версия компонента 9eSIM");
            JSONArray data=new JSONArray(c.getString(rows));
            for(int i=0;i<data.length();i++) if(data.getJSONObject(i).has("error")) {
                JSONObject details=data.getJSONObject(i);String code=details.optString("error");
                throw new UserError(code.equals("phone_permission_required")?"Разреши доступ к телефону в SMS Мост, затем открой встроенное управление 9eSIM":
                    code.equals("card_access_denied")?"Нет доступа к карте: открой встроенное управление 9eSIM и проверь слот адаптера":
                    code.equals("profile_download_failed")?EsimErrors.describe(details):
                    code.equals("profile_became_active")?"Профиль стал активным. Открой его заново и подтверди удаление с отключением связи":
                    code.equals("profile_not_found")?"Профиль больше не найден. Обнови /esim":
                    code.equals("profile_delete_failed")?"Удаление не подтверждено: профиль остался на карте. Обнови /esim и проверь состояние":
                    code.equals("adapter_busy_or_reconnecting")?"Адаптер занят или переподключается. Подожди и обнови список профилей":
                    code.equals("adapter_start_failed")?"Не удалось запустить фоновую операцию 9eSIM. Попытка завершена с ошибкой. Проверь автозапуск и ограничения батареи SMS Моста на телефоне":
                    "Адаптер не выполнил операцию. Открой встроенное управление 9eSIM и проверь профили перед повтором.");
            }
            return data;
        } catch(SecurityException e) { throw new UserError("Доступ к адаптеру отклонён Android. Проверь разрешение «Телефон» и совместимость карты во встроенном управлении."); }
    }
    private static byte[] hex(String text) {byte[] out=new byte[text.length()/2];for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(text.substring(i*2,i*2+2),16);return out;}
    JSONArray cards() throws Exception { return query("cards",new HashMap<>()); }
    Map<String,String> args(int slot,int port) {Map<String,String> a=new HashMap<>();a.put("slot",""+slot);a.put("port",""+port);return a;}
    Map<String,String> args(JSONObject card) throws Exception {Map<String,String> a=args(card.getInt("slot"),card.optInt("port",0));a.put("expectedEid",card.getString("eid"));return a;}
    JSONArray profiles(JSONObject card) throws Exception {return query("profiles",args(card));}
    JSONObject info(JSONObject card) throws Exception {
        JSONArray rows=query("cardInfo",args(card));
        return rows.length()==1?rows.getJSONObject(0):new JSONObject();
    }
    JSONObject delete(String eid,String iccid,boolean allowActive) throws Exception {
        JSONObject card=card(eid);if(!card.getString("eid").equals(eid))throw new UserError("Адаптер изменился. Начни операцию заново.");
        Map<String,String>a=args(card.getInt("slot"),card.optInt("port",0));
        a.put("expectedEid",eid);a.put("iccid",iccid);a.put("allowActive",Boolean.toString(allowActive));
        JSONArray rows=query("deleteProfile",a);
        if(rows.length()!=1||!rows.getJSONObject(0).optBoolean("success"))throw new UserError("Удаление не подтверждено. Обнови /esim и проверь профиль.");
        return rows.getJSONObject(0);
    }
    JSONObject card(String eid) throws Exception {
        JSONArray a=cards();JSONObject found=null;
        for(int i=0;i<a.length();i++){JSONObject c=a.getJSONObject(i);if(!c.optBoolean("unavailable")&&eid.equals(c.optString("eid"))){if(found!=null)throw new UserError("Неоднозначный идентификатор адаптера. Проверь карты на телефоне");found=c;}}
        if(found==null)throw new UserError("Выбранный адаптер изменился или недоступен. Открой /esim и выбери карту заново.");return found;
    }
    void refresh(Store s) throws Exception {
        JSONArray list=cards();s.rememberCards(list);StringBuilder errors=new StringBuilder();
        for(int i=0;i<list.length();i++) {
            JSONObject c=list.getJSONObject(i);int slot=c.getInt("slot");
            try {if(c.optBoolean("unavailable"))throw new UserError("Карта недоступна");refresh(s,c);}
            catch(Exception e){s.clearActive(slot);errors.append("Слот ").append(slot+1).append(": ").append(Telegram.safe(e)).append("\n");}
        }
        s.put("esim_refresh_error",errors.toString().trim());
    }
    void refresh(Store s,JSONObject card) throws Exception {
        int slot=card.getInt("slot");JSONArray ps=profiles(card);
        String key=null;for(int i=0;i<ps.length();i++) {JSONObject p=ps.getJSONObject(i);if(p.optBoolean("enabled")) {
            if(key!=null) throw new UserError("Несколько активных профилей: требуется отдельная настройка");key=Rules.profileKey(card.getString("eid"),p.getString("iccid"));
        }}
        s.clearActive(slot);if(key!=null)s.active(slot,key);s.switching(slot,false);
    }
    JSONObject download(String eid,String code,String confirmation) throws Exception {
        // Certificate checking must remain enabled in the underlying LPA.
        Map<String,String> settings=new HashMap<>();settings.put("name","ignoreTlsCertificate");settings.put("enabled","false");query("setPreference",settings);
        JSONObject card=card(eid);Map<String,String> a=args(card);
        a.put("activationCode",code);if(!confirmation.isEmpty())a.put("confirmationCode",confirmation);
        JSONArray result=query("downloadProfile",a);
        if(result.length()!=1 || !result.getJSONObject(0).has("iccid")) throw new UserError("Загрузка не подтверждена. Проверь профили; повторно использовать QR-код автоматически не будем.");
        JSONObject p=result.getJSONObject(0);p.put("eid",card.getString("eid"));return p;
    }
    void enable(String eid,String iccid) throws Exception {
        JSONObject card=card(eid);Map<String,String>a=args(card);a.put("iccid",iccid);a.put("refresh","true");
        JSONArray result=query("enableProfile",a);
        if(result.length()!=1 || !result.getJSONObject(0).optBoolean("success")) throw new UserError("Переключение не подтверждено");
    }
}
