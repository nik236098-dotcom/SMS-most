package ru.smsbridge.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class Bot {
    private final Store s; private final LpaClient lpa; private long currentChat;
    private long replyTo(){return currentChat>0?currentChat:s.chat();}
    private String draftKey(){return "draft:"+replyTo();}
    private String menuKey(){return "profile_menu:"+replyTo();}
    private static final AtomicBoolean POLLING=new AtomicBoolean(false);
    Bot(Context c) {s=BridgeApp.store();lpa=new LpaClient(c);}
    Bot(Store store,LpaClient adapter) {s=store;lpa=adapter;}
    private final java.util.Map<Long,Long> prompts=new java.util.LinkedHashMap<>();
    static JSONObject button(String text,String data) throws Exception {return new JSONObject().put("text",text).put("callback_data",data);}
    static JSONObject keyboard(JSONObject... buttons) throws Exception {
        JSONArray rows=new JSONArray();for(JSONObject b:buttons) rows.put(new JSONArray().put(b));
        return new JSONObject().put("inline_keyboard",rows);
    }
    void poll(int timeout) throws Exception {
        poll(new Telegram(s.get("token","")),timeout);
    }
    void poll(Telegram t,int timeout) throws Exception {
        if(!s.running())return;
        if(!POLLING.compareAndSet(false,true)){java.util.concurrent.TimeUnit.MILLISECONDS.sleep(300);return;}
        String session=s.epoch();
        try {
            JSONArray updates=t.call("getUpdates",new JSONObject().put("offset",Long.parseLong(s.get("offset","0")))
                .put("timeout",timeout).put("limit",50).put("allowed_updates",new JSONArray().put("message").put("callback_query"))).getJSONArray("result");
            if(!java.util.Objects.equals(session,s.epoch()))return;
            s.put("bot_last_seen",""+System.currentTimeMillis());s.put("bot_error","");
            for(int i=0;i<updates.length() && s.running();i++) {
                if(!java.util.Objects.equals(session,s.epoch()))return;
                JSONObject u=updates.getJSONObject(i);long id=u.getLong("update_id");
                // Commit consumption before side effects. A crash must not replay a one-use download.
                s.put("offset",""+(id+1));
                if(!s.beginOperation(id))continue;
                try {handle(t,u);} catch(Exception e) {s.put("error",Telegram.safe(e));
                    if(s.chat()>0)try {t.send(replyTo(),Telegram.safe(e),null);} catch(Exception ignored) {}}
                finally {s.endOperation(id);}
            }
        } finally {POLLING.set(false);}
    }
    private void handle(Telegram t,JSONObject u) throws Exception {
        JSONObject cb=u.optJSONObject("callback_query");JSONObject m=cb==null?u.optJSONObject("message"):cb.optJSONObject("message");
        if(m==null)return;
        JSONObject chat=m.getJSONObject("chat"),from=cb==null?m.optJSONObject("from"):cb.optJSONObject("from");
        if(s.chat()==0) {
            if(cb!=null || from==null || from.optBoolean("is_bot") || !chat.optString("type").equals("private")
                || chat.optLong("id")<=0 || from.optLong("id")!=chat.optLong("id"))return;
            long id=chat.getLong("id");String input=m.optString("text","").trim();
            if(input.matches("[0-9]{8}")) {
                currentChat=id;
                if(s.claim(id,chat.optString("first_name","Получатель SMS"),input,m.optLong("date")*1000L)) {
                    t.send(id,"✅ Этот Telegram подключён как получатель SMS.\nНа Android больше ничего подтверждать не нужно.",null);
                    menu(t,null);
                } else t.send(id,"Код неверный, истёк или временно заблокирован после нескольких попыток. Посмотри код на главном экране SMS Мост и попробуй ещё раз через минуту.",null);
            } else {
                long now=System.currentTimeMillis();
                if(now-prompts.getOrDefault(id,0L)<10000)return;
                if(prompts.size()>=64)prompts.remove(prompts.keySet().iterator().next());prompts.put(id,now);
                t.send(id,"Бот работает ✅\nОтправь сюда 8 цифр с главного экрана приложения SMS Мост на Android.\n\nОтправить код должен тот, кто будет получать SMS. Владельцу Android не нужно подключать свой Telegram.",null);
            }
            return;
        }
        if(from==null || !Rules.authorized(s.chats(),chat.getLong("id"),chat.optString("type"),from.optBoolean("is_bot")) || from.optLong("id")!=chat.getLong("id"))return;
        currentChat=chat.getLong("id");
        if(cb!=null) {
            try {t.call("answerCallbackQuery",new JSONObject().put("callback_query_id",cb.getString("id")));}catch(Exception ignored){}
            String data=cb.optString("data");
            if(data.equals("menu")){ menu(t,m);return; }
            if(data.equals("status")){ show(t,m,status(),keyboard(button("Назад","menu")));return; }
            if(data.equals("last")){ recent(t,m);return; }
            if(data.equals("test")){t.send(replyTo(),"✅ Бот отвечает. Телефон: "+s.get("device_status","")+"\nПересылка SMS: "+(s.enabled()?"включена":"выключена — проверь разрешение SMS на Android"),null);return;}
            if(data.equals("profiles")){ profiles(t,m);return; }
            if(data.equals("add")){beginAdd(t,m);return;}
            if(data.equals("cancel")){s.put(draftKey(),"{}");menu(t,m);return;}
            if(data.startsWith("confirm:")){confirm(t,m,data.substring(8));return;}
            if(data.startsWith("select:")){select(t,m,data.substring(7));return;}
            if(data.equals("rename")){rename(t,m);return;}
            return;
        }
        String text=m.optString("text","").trim();
        if(text.equals("/start")||text.equals("/menu")){s.put(draftKey(),"{}");menu(t,null);return;}
        if(text.equals("/cancel")){s.put(draftKey(),"{}");menu(t,null);return;}
        if(text.equals("/status")){t.send(replyTo(),status(),null);return;}
        if(text.equals("/profiles")){profiles(t,null);return;}
        if(text.equals("/add")){beginAdd(t,null);return;}
        JSONObject d=draft();String stage=d.optString("stage");
        if(stage.equals("phone")) {
            d.put("number",Rules.phone(text)).put("stage","qr");s.put(draftKey(),d.toString());
            t.send(replyTo(),"Номер сохранён: "+d.getString("number")+"\nТеперь пришли QR-код картинкой или строку LPA:1$… от оператора.\nЕсли нужен PIN оператора, его можно добавить командой /pin 1234 перед подтверждением.",keyboard(button("Отмена","cancel")));return;
        }
        if(stage.equals("rename_phone")) {
            String n=Rules.phone(text);s.number(d.getString("key"),n);s.put(draftKey(),"{}");t.send(replyTo(),"Номер профиля сохранён: "+n,null);profiles(t,null);return;
        }
        if(stage.equals("qr") || stage.equals("confirm_add")) {
            if(text.startsWith("/pin ")) {
                String pin=text.substring(5).trim();if(!pin.matches("[A-Za-z0-9]{1,32}"))throw new UserError("Проверь PIN оператора");
                d.put("pin",pin);s.put(draftKey(),d.toString());t.send(replyTo(),"PIN добавлен.",null);return;
            }
            String code=text;
            JSONArray photos=m.optJSONArray("photo");JSONObject document=m.optJSONObject("document");
            if(photos!=null && photos.length()>0)code=decode(t.file(photos.getJSONObject(photos.length()-1).getString("file_id")));
            else if(document!=null && document.optString("mime_type").startsWith("image/"))code=decode(t.file(document.getString("file_id")));
            if(!code.startsWith("LPA:1$") || code.length()>2048 || code.split("\\$",-1).length<3)throw new UserError("Не удалось прочитать код eSIM. Пришли QR-код чёткой картинкой или строку активации.");
            d.put("code",code).put("stage","confirm_add");s.put(draftKey(),d.toString());
            t.send(replyTo(),"Добавление eSIM\nНомер: "+d.getString("number")+"\nQR-код получен.\nПосле загрузки сохраним номер за профилем и попробуем активировать его.",keyboard(button("Установить eSIM","confirm:"+d.getString("nonce")),button("Отмена","cancel")));return;
        }
        menu(t,null);
    }
    private JSONObject draft() throws Exception {
        JSONObject d=new JSONObject(s.get(draftKey(),"{}"));
        if(d.optLong("expires")<System.currentTimeMillis() || !d.optString("epoch").equals(s.epoch())){s.put(draftKey(),"{}");return new JSONObject();}return d;
    }
    private JSONObject newDraft(String stage) throws Exception {return new JSONObject().put("stage",stage).put("epoch",s.epoch()).put("nonce",UUID.randomUUID().toString().replace("-",""))
        .put("expires",System.currentTimeMillis()+15*60000L);}
    private void beginAdd(Telegram t,JSONObject m) throws Exception {
        if(!s.get("esim_control","false").equals("true"))throw new UserError("Сначала включите управление 9eSIM в настройках приложения на телефоне");
        JSONObject card=lpa.card();JSONObject d=newDraft("phone").put("eid",card.getString("eid"));s.put(draftKey(),d.toString());
        show(t,m,"Добавление eSIM\nСначала введи номер этой eSIM с кодом страны, например +79991234567.\nНомер ты задаёшь сам; он будет подписывать SMS этого профиля.",keyboard(button("Отмена","cancel")));
    }
    private void profiles(Telegram t,JSONObject m) throws Exception {
        JSONObject card=lpa.card();JSONArray ps=lpa.profiles(card.getInt("slot"),card.optInt("port",0));
        String nonce=UUID.randomUUID().toString().replace("-","");
        JSONObject cache=new JSONObject().put("eid",card.getString("eid")).put("profiles",ps).put("nonce",nonce).put("expires",System.currentTimeMillis()+10*60000L);
        s.put(menuKey(),cache.toString());
        StringBuilder text=new StringBuilder("Номера и eSIM\n");JSONArray rows=new JSONArray();
        for(int i=0;i<ps.length();i++) {
            JSONObject p=ps.getJSONObject(i);String number=s.number(Rules.profileKey(card.getString("eid"),p.getString("iccid")));
            text.append("\n").append(p.optBoolean("enabled")?"🟢 ":"⚪ ").append(i+1).append(". ").append(number).append(" · ").append(p.optString("provider","Профиль")).append("\n");
            rows.put(new JSONArray().put(button((p.optBoolean("enabled")?"Настроить ":"Выбрать ")+number,"select:"+nonce+":"+i)));
        }
        text.append("\nSMS принимаются на активный профиль.");
        rows.put(new JSONArray().put(button("Добавить eSIM по QR-коду","add")));rows.put(new JSONArray().put(button("Назад","menu")));
        show(t,m,text.toString(),new JSONObject().put("inline_keyboard",rows));
    }
    private void select(Telegram t,JSONObject m,String data) throws Exception {
        String[] parts=data.split(":");JSONObject cache=new JSONObject(s.get(menuKey(),"{}"));
        if(parts.length!=2 || !cache.optString("nonce").equals(parts[0]) || cache.optLong("expires")<System.currentTimeMillis())throw new UserError("Обнови список профилей");
        int index=Integer.parseInt(parts[1]);JSONArray ps=cache.getJSONArray("profiles");if(index<0||index>=ps.length())return;
        JSONObject p=ps.getJSONObject(index);String key=Rules.profileKey(cache.getString("eid"),p.getString("iccid"));
        JSONObject d=newDraft("confirm_enable").put("iccid",p.getString("iccid")).put("eid",cache.getString("eid")).put("key",key);s.put(draftKey(),d.toString());
        show(t,m,"Профиль: "+s.number(key)+"\n"+p.optString("provider")+"\nВключить этот профиль или изменить его номер?",keyboard(button("Включить","confirm:"+d.getString("nonce")),button("Задать номер","rename"),button("Назад","profiles")));
    }
    private void rename(Telegram t,JSONObject m) throws Exception {
        JSONObject d=draft();if(!d.has("key"))throw new UserError("Сначала выбери профиль");
        d.put("stage","rename_phone");s.put(draftKey(),d.toString());show(t,m,"Введи номер для этого профиля, начиная с + и кода страны.",keyboard(button("Отмена","cancel")));
    }
    private void confirm(Telegram t,JSONObject m,String nonce) throws Exception {
        JSONObject d=draft();if(!d.optString("nonce").equals(nonce))throw new UserError("Подтверждение устарело. Начни операцию заново.");
        if(!s.get("esim_control","false").equals("true"))throw new UserError("Управление адаптером выключено на телефоне");
        JSONObject card=lpa.card();if(!card.getString("eid").equals(d.optString("eid")))throw new UserError("Адаптер изменился. Начни операцию заново.");
        String stage=d.optString("stage");if(!stage.equals("confirm_add")&&!stage.equals("confirm_enable"))return;
        if(stage.equals("confirm_enable") && s.number(d.getString("key")).equals("Номер не задан"))throw new UserError("Сначала задай номер этого профиля");
        // Invalidate before changing the chip. Repeated button taps cannot repeat the operation.
        s.put(draftKey(),"{}");s.put("switching","true");s.clearActive();
        show(t,m,stage.equals("confirm_add")?"Загружаем eSIM. Дождись результата; повторно QR-код не отправляй.":"Переключаем профиль…",null);
        boolean refreshed=false;
        try {
            String iccid=d.optString("iccid"),number;
            if(stage.equals("confirm_add")) {
                // Persist intended number/code first. If the chip succeeds but the response is lost,
                // reconciliation is manual instead of falsely attributing it to another number.
                s.put("last_install",d.toString());
                JSONObject p=lpa.download(d.getString("code"),d.optString("pin"));iccid=p.getString("iccid");
                String key=Rules.profileKey(p.getString("eid"),iccid);number=d.getString("number");s.number(key,number);
            } else number=s.number(d.getString("key"));
            lpa.enable(iccid);
            lpa.refresh(s);JSONArray ps=lpa.profiles(card.getInt("slot"),card.optInt("port",0));boolean active=false;
            for(int i=0;i<ps.length();i++) {JSONObject p=ps.getJSONObject(i);if(p.optString("iccid").equals(iccid)&&p.optBoolean("enabled"))active=true;}
            if(!active)throw new UserError("Профиль сохранён, но активация не подтверждена. Проверь список eSIM.");
            s.put("switching","false");refreshed=true;s.put("last_install","{}");
            t.send(replyTo(),"🟢 Активирован профиль "+number+".\nВ новых SMS будет указан этот номер.",keyboard(button("Номера и eSIM","profiles")));
        } finally {
            if(!refreshed) {s.clearActive();s.put("error","eSIM: проверь состояние профилей после незавершённой операции");}
        }
    }
    void reconcile() throws Exception {
        if(!lpa.installed()||!s.get("esim_control","false").equals("true"))return;
        // Runs on the same thread as mutations, so it never clears 'switching' mid-download.
        lpa.refresh(s);s.put("switching","false");
    }
    private void show(Telegram t,JSONObject m,String text,JSONObject keyboard) throws Exception {
        if(m==null){t.send(replyTo(),text,keyboard);return;}
        JSONObject p=new JSONObject().put("chat_id",replyTo()).put("message_id",m.getLong("message_id")).put("text",text);
        p.put("reply_markup",keyboard==null?new JSONObject().put("inline_keyboard",new JSONArray()):keyboard);
        try {t.call("editMessageText",p);}catch(Telegram.ApiError e){if(e.code!=400)throw e;}
    }
    private void menu(Telegram t,JSONObject m) throws Exception {show(t,m,"SMS Мост\n"+status(),keyboard(button("Проверить связь","test"),button("Статус телефона","status"),button("Последние SMS","last"),button("Номера и eSIM","profiles")));}
    private String status() {return "Телефон на связи\nПересылка: "+(s.enabled()?"включена":"выключена")+"\nОтправлено сегодня: "+s.today()+"\nВ очереди: "+s.pending()+"\n"+s.get("device_status","");}
    private void recent(Telegram t,JSONObject m) throws Exception {
        StringBuilder b=new StringBuilder("Последние SMS\n");JSONArray rows=s.recent(replyTo());
        for(int i=0;i<rows.length();i++){JSONObject p=rows.getJSONObject(i);b.append("\n#").append(p.getLong("id")).append(" · ").append(p.optString("recipient")).append("\n")
            .append(Rules.service(p.optString("sender"))).append(" · ").append(p.optString("state").equals("sent")?"Доставлено":"В очереди").append("\n");}
        show(t,m,b.toString(),keyboard(button("Назад","menu")));
    }
    static String format(JSONObject p) {
        return "📩 Новое SMS #"+p.optLong("id")+"\n\n📲 На номер: "+p.optString("recipient","Номер не определён")+"\n🏷 Сервис: "+Rules.service(p.optString("sender"))+
            "\nОтправитель: "+p.optString("sender")+"\n\n"+p.optString("body")+"\n\n🕒 Получено: "+new SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale.forLanguageTag("ru")).format(new Date(p.optLong("received")));
    }
    private String decode(byte[] raw) throws Exception {
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(raw,0,raw.length,options);
        if(options.outWidth<=0||options.outHeight<=0)throw new UserError("Это не изображение QR-кода");
        options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>2048)options.inSampleSize*=2;
        options.inJustDecodeBounds=false;Bitmap bm=BitmapFactory.decodeByteArray(raw,0,raw.length,options);
        if(bm==null)throw new UserError("Не удалось открыть изображение");
        try {int w=bm.getWidth(),h=bm.getHeight();int[] pixels=new int[w*h];bm.getPixels(pixels,0,w,0,0,w,h);
            java.util.Map<com.google.zxing.DecodeHintType,Object> hints=new java.util.EnumMap<>(com.google.zxing.DecodeHintType.class);hints.put(com.google.zxing.DecodeHintType.TRY_HARDER,true);
            return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w,h,pixels))),hints).getText();
        } catch(Exception e){throw new UserError("Не удалось распознать QR-код. Пришли чёткое изображение без лишнего фона.");}
        finally {bm.recycle();}
    }
}
