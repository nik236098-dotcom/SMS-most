package ru.smsbridge.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class Bot {
    private final Store s; private final LpaClient lpa; private final NativeSims sims; private final AdapterTasks tasks; private long currentChat;
    private long replyTo(){return currentChat>0?currentChat:s.chat();}
    private String draftKey(){return "draft:"+replyTo();}
    private String menuKey(){return "profile_menu:"+replyTo();}
    private String cardKey(){return "selected_adapter:"+replyTo();}
    private static final AtomicBoolean POLLING=new AtomicBoolean(false);
    /** Keeps the CPU awake while work runs outside the polling request's own wake lock. */
    interface Awake {AutoCloseable hold(long millis);}
    private Awake awake=millis->()->{};
    // QR images need neither the card nor the adapter queue; one image at a time.
    private static final java.util.concurrent.ExecutorService MEDIA=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"smsbridge-qr");t.setDaemon(true);return t;});
    private static final AtomicBoolean MEDIA_BUSY=new AtomicBoolean(false);
    private enum Route {NOW,MEDIA,CARD}
    Bot(Context c) {
        this(BridgeApp.store(),new LpaClient(c),NativeSims.on(c),AdapterTasks.shared());
        android.os.PowerManager power=c.getSystemService(android.os.PowerManager.class);
        awake=millis->{
            android.os.PowerManager.WakeLock lock=power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"smsbridge:work");
            lock.setReferenceCounted(false);lock.acquire(millis);return ()->{if(lock.isHeld())lock.release();};
        };
    }
    Bot(Store store,LpaClient adapter) {this(store,adapter,()->new JSONArray());}
    Bot(Store store,LpaClient adapter,NativeSims subscriptions) {this(store,adapter,subscriptions,null);}
    Bot(Store store,LpaClient adapter,NativeSims subscriptions,AdapterTasks taskRunner) {s=store;lpa=adapter;sims=NativeSims.bounded(subscriptions);tasks=taskRunner;}
    private String replyPrefix;private int replySequence;private boolean onCard;
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
        if(!s.running()||t.closed())return;
        long wait=s.telegramRemaining();if(wait>0){java.util.concurrent.TimeUnit.MILLISECONDS.sleep(Math.min(1000,wait));return;}
        if(!POLLING.compareAndSet(false,true)){java.util.concurrent.TimeUnit.MILLISECONDS.sleep(300);return;}
        try {
            String session=s.epoch();
            JSONArray updates=t.call("getUpdates",new JSONObject().put("offset",Long.parseLong(s.get("offset","0")))
                .put("timeout",timeout).put("limit",50).put("allowed_updates",new JSONArray().put("message").put("callback_query"))).getJSONArray("result");
            if(t.closed()||!s.running()||!java.util.Objects.equals(session,s.epoch()))return;
            s.put("bot_last_seen",""+System.currentTimeMillis());s.put("bot_error","");
            for(int i=0;i<updates.length() && s.running();i++) {
                if(t.closed()||!java.util.Objects.equals(session,s.epoch()))return;
                s.put("bot_loop_seen",""+System.currentTimeMillis());
                JSONObject u=updates.getJSONObject(i);long id=u.getLong("update_id");
                // Commit consumption before side effects. A crash must not replay a one-use download.
                s.put("offset",""+(id+1));
                if(!s.beginOperation(id))continue;
                boolean deferred=false;
                try {deferred=dispatch(t,u,id);} catch(Exception e) {s.put("error",Telegram.safe(e));
                    if(e instanceof Telegram.ApiError && ((Telegram.ApiError)e).code==429){s.telegramWait(Math.max(1,((Telegram.ApiError)e).retry));break;}
                    if(s.chat()>0)try {send(t,replyTo(),Telegram.safe(e),null);} catch(Exception ignored) {}}
                finally {if(!deferred)s.endOperation(id);}
            }
        } catch(Telegram.ApiError e) {if(e.code==429)s.telegramWait(Math.max(1,e.retry));throw e;}
        finally {POLLING.set(false);}
    }
    private boolean dispatch(Telegram t,JSONObject u,long operation) throws Exception {
        if(t.closed())return false;
        if(tasks==null){handle(t,u);return false;}
        JSONObject cb=u.optJSONObject("callback_query"),m=cb==null?u.optJSONObject("message"):cb.optJSONObject("message");
        JSONObject from=cb==null?(m==null?null:m.optJSONObject("from")):cb.optJSONObject("from");
        if(m==null||from==null||s.chat()==0){handle(t,u);return false;}
        JSONObject chat=m.getJSONObject("chat");long target=chat.optLong("id");
        if(!Rules.authorized(s.chats(),target,chat.optString("type"),from.optBoolean("is_bot"))||from.optLong("id")!=target)return false;
        currentChat=target;
        Bot handler=new Bot(s,lpa,sims,tasks);handler.currentChat=target;handler.awake=awake;
        String command=cb==null?m.optString("text","").trim():cb.optString("data");
        // Only work that really talks to the card waits for the adapter queue. Menus, saved lists,
        // number input and drafts answer at once, even during a long SIM operation.
        Route route=handler.route(cb,m,command);
        if(route==Route.NOW){handler.handle(t,u);return false;}
        String session=s.epoch();
        if(cb!=null)try{t.call("answerCallbackQuery",new JSONObject().put("callback_query_id",cb.getString("id")));}catch(Exception ignored){}
        if(route==Route.MEDIA)return media(t,u,handler,target,session,operation);
        handler.replyPrefix="bot-reply:"+session+":"+operation+":";handler.onCard=true;
        AutoCloseable[] hold=new AutoCloseable[1];
        AdapterTasks.Submission submitted=tasks.submitUser(()->{
            try{hold[0]=awake.hold(15*60000L);}catch(RuntimeException ignored){}
            s.put("esim_native_phase","");
            s.put("esim_task_started",""+System.currentTimeMillis());s.put("esim_task_state","running");
            s.put("esim_task_label",command.startsWith("confirm:")?"Подтверждённая операция с профилем":"Чтение адаптеров 9eSIM");
        },()->{
            try {if(!t.closed()&&s.running()&&session.equals(s.epoch())&&s.chats().contains(target))handler.handle(t,u);}
            catch(Exception e){
                s.put("error",Telegram.safe(e));
                if(e instanceof Telegram.ApiError&&((Telegram.ApiError)e).code==429)s.telegramWait(Math.max(1,((Telegram.ApiError)e).retry));
                if(s.running()&&session.equals(s.epoch())&&s.chats().contains(target))try{
                    String message="Операция 9eSIM не завершена с подтверждением: "+Telegram.safe(e)+"\nЕсли менял профиль, проверь /esim перед повтором.";
                    s.put("esim_last_result",message);s.enqueueNotice("adapter-error:"+session+":"+operation,target,message);
                }catch(Exception ignored){}
            }
        },()->{
            if(s.running()&&session.equals(s.epoch())&&s.chats().contains(target))try{
                s.enqueueNotice("adapter-slow:"+session+":"+operation,target,
                    "Операция с SIM пока не завершена. Результат ещё не подтверждён. Повторно удаление или установку не запускай. Бот продолжает отвечать: /start и /status.");
            }catch(Exception ignored){}
        },()->{try{s.put("esim_task_state","idle");}finally{try{s.endOperation(operation);}finally{if(hold[0]!=null)try{hold[0].close();}catch(Exception ignored){}}}});
        if(submitted==AdapterTasks.Submission.QUEUED)try{s.enqueueNotice("adapter-accepted:"+session+":"+operation,target,"Запрос принят: ожидает окончания фоновой проверки карты. Повторно нажимать кнопку не нужно.");}catch(Exception ignored){}
        if(submitted==AdapterTasks.Submission.BUSY)send(t,target,"Сейчас занят обработчик SIM: "+s.get("esim_task_label","операция с картой")+". Новый запрос не запущен. Этап и время: /status.",null);
        return submitted!=AdapterTasks.Submission.BUSY;
    }
    private Route route(JSONObject cb,JSONObject m,String command) throws Exception {
        if(cb!=null) {
            if(command.startsWith("confirm:")||command.startsWith("esim:refresh"))return Route.CARD;
            if(command.equals("adapter")||command.startsWith("adapter:")||command.startsWith("card:"))return listRoute();
            if(command.equals("add"))return snapshotEmpty()?Route.CARD:Route.NOW;
            if(command.startsWith("add:"))return snapCard(command.substring(4))==null?Route.CARD:Route.NOW;
            return Route.NOW;
        }
        if(command.equals("/esim")||command.equals("/9esim"))return listRoute();
        if(command.equals("/add"))return snapshotEmpty()?Route.CARD:Route.NOW;
        if(command.startsWith("/"))return Route.NOW;
        String stage=draft().optString("stage");
        if((stage.equals("qr")||stage.equals("confirm_add"))&&(m.optJSONArray("photo")!=null||m.optJSONObject("document")!=null))return Route.MEDIA;
        return Route.NOW;
    }
    /** During a running card operation a saved list is shown at once (marked) instead of "busy". */
    private Route listRoute() throws Exception {
        if(!needsRead())return Route.NOW;
        return tasks.userBusy()&&!snapshotEmpty()?Route.NOW:Route.CARD;
    }
    private boolean media(Telegram t,JSONObject u,Bot handler,long target,String session,long operation) throws Exception {
        if(!MEDIA_BUSY.compareAndSet(false,true)){send(t,target,"Предыдущее изображение ещё обрабатывается. Подожди несколько секунд.",null);return false;}
        try {MEDIA.execute(()->{
            AutoCloseable hold=null;
            try {
                try{hold=awake.hold(120000);}catch(RuntimeException ignored){}
                if(!t.closed()&&s.running()&&session.equals(s.epoch())&&s.chats().contains(target))handler.handle(t,u);
            } catch(Exception e) {
                try{s.put("error",Telegram.safe(e));
                    if(e instanceof Telegram.ApiError&&((Telegram.ApiError)e).code==429)s.telegramWait(Math.max(1,((Telegram.ApiError)e).retry));
                    else t.send(target,Telegram.safe(e),null);}catch(Exception ignored){}
            } finally {
                MEDIA_BUSY.set(false);try{s.endOperation(operation);}catch(Exception ignored){}
                if(hold!=null)try{hold.close();}catch(Exception ignored){}
            }
        });}catch(RuntimeException e){MEDIA_BUSY.set(false);throw e;}
        return true;
    }
    void reconcileAsync() {
        if(tasks==null)throw new IllegalStateException("Adapter worker missing");
        if(!s.running()||!s.get("esim_control","false").equals("true"))return;
        tasks.submitBackground(()->{s.put("esim_task_started",""+System.currentTimeMillis());s.put("esim_task_state","running");s.put("esim_task_label","Проверка активного профиля");},()->{
            try {reconcile();}catch(Exception e){s.clearActive();s.put("esim_refresh_error",Telegram.safe(e));}
        },()->s.put("esim_refresh_error","Адаптер долго не отвечает; Telegram продолжает работать"),()->s.put("esim_task_state","idle"));
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
                    send(t,id,"✅ Этот Telegram подключён как получатель SMS.\nНа Android больше ничего подтверждать не нужно.",null);
                    menu(t,null);
                } else send(t,id,"Код неверный, истёк или временно заблокирован после нескольких попыток. Посмотри код на главном экране SMS Мост и попробуй ещё раз через минуту.",null);
            } else {
                long now=System.currentTimeMillis();
                if(now-prompts.getOrDefault(id,0L)<10000)return;
                if(prompts.size()>=64)prompts.remove(prompts.keySet().iterator().next());prompts.put(id,now);
                send(t,id,"Бот работает ✅\nОтправь сюда 8 цифр с главного экрана приложения SMS Мост на Android.\n\nОтправить код должен тот, кто будет получать SMS. Владельцу Android не нужно подключать свой Telegram.",null);
            }
            return;
        }
        if(from==null || !Rules.authorized(s.chats(),chat.getLong("id"),chat.optString("type"),from.optBoolean("is_bot")) || from.optLong("id")!=chat.getLong("id"))return;
        currentChat=chat.getLong("id");
        if(cb!=null) {
            if(replyPrefix==null)try {t.call("answerCallbackQuery",new JSONObject().put("callback_query_id",cb.getString("id")));}catch(Exception ignored){}
            String data=cb.optString("data");
            if(data.equals("menu")){ menu(t,m);return; }
            if(data.equals("status")){ show(t,m,status(),keyboard(button("Назад","menu")));return; }
            if(data.equals("last")){ recent(t,m);return; }
            if(data.equals("queue")){queue(t,m,false);return;}
            if(data.equals("retry")){queue(t,m,true);return;}
            if(data.equals("calls")){recentCalls(t,m);return;}
            if(data.equals("test")){send(t,replyTo(),"✅ Бот отвечает. Телефон: "+s.get("device_status","")+"\nПересылка SMS: "+(s.enabled()?"включена":"выключена — проверь разрешение SMS на Android"),null);return;}
            if(data.equals("profiles")){ profiles(t,m);return; }
            if(data.equals("adapter")||data.startsWith("card:")){showProfiles(t,m,0,false);return;}
            if(data.startsWith("adapter:")){showProfiles(t,m,Integer.parseInt(data.substring(8)),false);return;}
            if(data.equals("esim:refresh")){showProfiles(t,m,0,true);return;}
            if(data.startsWith("esim:refresh:")){showProfiles(t,m,Integer.parseInt(data.substring(13)),true);return;}
            if(data.startsWith("physical:")){physicalNumber(t,m,Integer.parseInt(data.substring(9)));return;}
            if(data.equals("add")){beginAdd(t,m);return;}
            if(data.startsWith("add:")){beginAdd(t,m,data.substring(4));return;}
            if(data.equals("cancel")){s.put(draftKey(),"{}");menu(t,m);return;}
            if(data.startsWith("confirm:")){confirm(t,m,data.substring(8));return;}
            if(data.startsWith("select:")){select(t,m,data.substring(7));return;}
            if(data.startsWith("delete:")){beginDelete(t,m,data.substring(7));return;}
            if(data.equals("rename")){rename(t,m);return;}
            return;
        }
        String text=m.optString("text","").trim();
        if(text.equals("/start")||text.equals("/menu")){s.put(draftKey(),"{}");menu(t,null);return;}
        if(text.equals("/cancel")){s.put(draftKey(),"{}");menu(t,null);return;}
        if(text.equals("/status")){send(t,replyTo(),status(),null);return;}
        if(text.equals("/queue")){queue(t,null,false);return;}
        if(text.equals("/retry")){queue(t,null,true);return;}
        if(text.equals("/calls")){recentCalls(t,null);return;}
        if(text.equals("/profiles")){profiles(t,null);return;}
        if(text.equals("/esim")||text.equals("/9esim")){s.put(draftKey(),"{}");adapterProfiles(t,null);return;}
        if(text.equals("/add")){beginAdd(t,null);return;}
        JSONObject d=draft();String stage=d.optString("stage");
        if(stage.equals("phone")) {
            d.put("number",Rules.phone(text)).put("stage","qr");s.put(draftKey(),d.toString());
            send(t,replyTo(),"Номер сохранён: "+d.getString("number")+"\nТеперь пришли QR-код картинкой или строку LPA:1$… от оператора.\nЕсли нужен PIN оператора, его можно добавить командой /pin 1234 перед подтверждением.",keyboard(button("Отмена","cancel")));return;
        }
        if(stage.equals("physical_phone")) {
            int id=d.getInt("sub");requireSim(id);String n=Rules.phone(text);
            s.number("physical:"+id,n);s.put(draftKey(),"{}");
            send(t,replyTo(),"Номер SIM сохранён: "+n,null);profiles(t,null);return;
        }
        if(stage.equals("rename_phone")) {
            String n=Rules.phone(text);s.number(d.getString("key"),n);s.put(draftKey(),"{}");send(t,replyTo(),"Номер профиля сохранён: "+n,null);adapterProfiles(t,null);return;
        }
        if(stage.equals("qr") || stage.equals("confirm_add")) {
            if(text.startsWith("/pin ")) {
                String pin=text.substring(5).trim();if(!pin.matches("[A-Za-z0-9]{1,32}"))throw new UserError("Проверь PIN оператора");
                d.put("pin",pin);s.put(draftKey(),d.toString());send(t,replyTo(),"PIN добавлен.",null);return;
            }
            String code=text;
            JSONArray photos=m.optJSONArray("photo");JSONObject document=m.optJSONObject("document");
            if(photos!=null && photos.length()>0)code=QrImage.decode(t.file(photos.getJSONObject(photos.length()-1).getString("file_id")));
            else if(document!=null)code=QrImage.decode(t.file(document.getString("file_id")));
            code=code.trim();
            if(!code.startsWith("LPA:1$") || code.length()>2048 || code.split("\\$",-1).length<3)throw new UserError("Не удалось прочитать код eSIM. Пришли QR-код чёткой картинкой или строку активации.");
            d.put("code",code).put("stage","confirm_add");s.put(draftKey(),d.toString());
            send(t,replyTo(),"Добавление eSIM\nНомер: "+d.getString("number")+"\nQR-код получен.\nПосле загрузки сохраним номер за профилем и попробуем активировать его.",keyboard(button("Установить eSIM","confirm:"+d.getString("nonce")),button("Отмена","cancel")));return;
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
        chooseCard(t,m,true);
    }
    private void beginAdd(Telegram t,JSONObject m,String eid) throws Exception {
        // The saved scan is enough to ask for the number; the download itself re-reads the card by EID.
        requireAdapterControl();JSONObject card=snapCard(eid);if(card==null)card=lpa.card(eid);
        s.put(cardKey(),eid);s.put(menuKey(),"{}");
        JSONObject d=newDraft("phone").put("eid",card.getString("eid")).put("slot",card.getInt("slot"));s.put(draftKey(),d.toString());
        show(t,m,"Добавление eSIM · "+cardLabel(card)+"\nСначала введи номер этой eSIM с кодом страны, например +79991234567.\nНомер ты задаёшь сам; он будет подписывать SMS этого профиля.",keyboard(button("Отмена","cancel")));
    }
    private static String cardLabel(JSONObject card) throws Exception {
        String eid=card.getString("eid");return "слот "+(card.getInt("slot")+1)+" · карта …"+eid.substring(Math.max(0,eid.length()-6));
    }
    private void chooseCard(Telegram t,JSONObject m,boolean adding) throws Exception {
        requireAdapterControl();s.put(draftKey(),"{}");s.put(menuKey(),"{}");
        if(snapshotEmpty())readCards();
        java.util.List<JSONObject> cards=new java.util.ArrayList<>();for(JSONObject c:snapCards())if(!c.optBoolean("unavailable"))cards.add(c);
        if(cards.size()==1){beginAdd(t,m,cards.get(0).getString("eid"));return;}
        StringBuilder text=new StringBuilder("На какой адаптер добавить eSIM?\n");JSONArray rows=new JSONArray();
        for(JSONObject card:snapCards()) {
            if(card.optBoolean("unavailable")){text.append("\nСлот ").append(card.getInt("slot")+1).append(": карта временно недоступна\n");continue;}
            JSONObject info=card.optJSONObject("info");
            text.append("\n").append(cardLabel(card)).append(" · профилей: ").append(card.getJSONArray("profiles").length())
                .append(info==null?"":"\n"+EsimErrors.memory(info)).append("\n");
            rows.put(new JSONArray().put(button(cardLabel(card),"add:"+card.getString("eid"))));
        }
        if(cards.isEmpty())text.append("\nАдаптеры пока не обнаружены. Проверь карты на телефоне и нажми «Обновить».");
        rows.put(new JSONArray().put(button("Обновить","esim:refresh")));rows.put(new JSONArray().put(button("Главное меню","menu")));
        show(t,m,text.toString(),new JSONObject().put("inline_keyboard",rows));
    }
    private JSONObject requireSim(int id) throws Exception {
        JSONArray list=sims.list();for(int i=0;i<list.length();i++)if(list.getJSONObject(i).getInt("id")==id)return list.getJSONObject(i);
        throw new UserError("SIM-карта больше не активна. Обнови список SIM-карт.");
    }
    private void physicalNumber(Telegram t,JSONObject m,int id) throws Exception {
        JSONObject sim=requireSim(id);
        if(s.managed(sim.getInt("slot")))
            throw new UserError("Для управляемого адаптера номер задаётся отдельно каждому профилю в разделе 9eSIM.");
        s.put(draftKey(),newDraft("physical_phone").put("sub",id).toString());
        show(t,m,"Введи номер SIM в слоте "+(sim.getInt("slot")+1)+", начиная с + и кода страны. Например +79991234567.",keyboard(button("Отмена","cancel")));
    }
    private void profiles(Telegram t,JSONObject m) throws Exception {
        JSONArray list=sims.list(),rows=new JSONArray();StringBuilder text=new StringBuilder("Мои SIM-карты\n");
        if(list.length()==0)text.append("\nAndroid не видит активных SIM. Проверь, что карта вставлена и включена в настройках телефона.\n");
        for(int i=0;i<list.length();i++) {
            JSONObject sim=list.getJSONObject(i);int id=sim.getInt("id"),slot=sim.getInt("slot");
            boolean managed=s.managed(slot);
            text.append("\nСлот ").append(slot+1).append(" · ").append(sim.optString("name")).append("\n")
                .append(managed?"Номер активного профиля: "+s.recipient(slot,id):s.number("physical:"+id)).append("\n");
            if(!managed)rows.put(new JSONArray().put(button("Задать номер · слот "+(slot+1),"physical:"+id)));
        }
        text.append("\nПриём SMS работает без приложения 9eSIM. Номер задаёшь ты; Android не всегда сообщает его.");
        rows.put(new JSONArray().put(button("Обновить SIM-карты","profiles")));
        rows.put(new JSONArray().put(button("Профили 9eSIM","adapter")));
        rows.put(new JSONArray().put(button("Назад","menu")));
        show(t,m,text.toString(),new JSONObject().put("inline_keyboard",rows));
    }
    private void adapterProfiles(Telegram t,JSONObject m) throws Exception {showProfiles(t,m,0,false);}
    private static String shortName(String value,int max) {
        String text=value.replaceAll("[\\r\\n\\t]+"," ").trim();
        if(text.codePointCount(0,text.length())<=max)return text;
        return text.substring(0,text.offsetByCodePoints(0,max-1))+"…";
    }
    private String profileNumber(JSONObject card,JSONObject profile) throws Exception {
        return s.number(Rules.profileKey(card.getString("eid"),profile.getString("iccid")));
    }
    private static String profileName(JSONObject p) {
        String nickname=p.optString("nickname","").trim(),provider=p.optString("provider","").trim();
        return shortName(nickname.isEmpty()?(provider.isEmpty()?"Профиль eSIM":provider):nickname,48);
    }
    private static String iccidSuffix(JSONObject p) throws Exception {
        String iccid=p.getString("iccid");return iccid.substring(Math.max(0,iccid.length()-6));
    }
    private String profileLabel(JSONObject card,JSONObject p) throws Exception {
        String number=profileNumber(card,p);
        return (number.equals("Номер не задан")?profileName(p):number)+" · …"+iccidSuffix(p);
    }
    private void requireAdapterControl() throws Exception {
        if(!s.get("esim_control","false").equals("true"))throw new UserError("Управление адаптером выключено на телефоне");
    }
    private JSONObject findProfile(JSONObject card,String iccid) throws Exception {
        JSONArray ps=lpa.profiles(card);
        for(int i=0;i<ps.length();i++)if(ps.getJSONObject(i).getString("iccid").equals(iccid))return ps.getJSONObject(i);
        throw new UserError("Профиль больше не найден на адаптере. Обнови список: /esim");
    }
    // ---- Saved card scan: lists open instantly; card operations still re-verify on the chip. ----
    private JSONObject snap() throws Exception {JSONObject v=s.snapshot();return v==null||!v.has("cards")?new JSONObject().put("cards",new JSONObject()):v;}
    private java.util.List<JSONObject> snapCards() throws Exception {
        JSONObject cards=snap().getJSONObject("cards");java.util.List<JSONObject> out=new java.util.ArrayList<>();
        java.util.Iterator<String> keys=cards.keys();while(keys.hasNext())out.add(cards.getJSONObject(keys.next()));
        out.sort(java.util.Comparator.comparingInt(c->c.optInt("slot")));return out;
    }
    boolean snapshotEmpty() throws Exception {return snapCards().isEmpty();}
    /** No saved scan yet, or a card operation since then may have changed the card. */
    private boolean needsRead() throws Exception {
        java.util.List<JSONObject> cards=snapCards();if(cards.isEmpty())return true;
        for(JSONObject c:cards)if(c.optBoolean("dirty"))return true;return false;
    }
    private JSONObject snapCard(String eid) throws Exception {
        JSONObject c=snap().getJSONObject("cards").optJSONObject(eid);return c==null||c.optBoolean("unavailable")?null:c;
    }
    private JSONObject snapProfile(String eid,String iccid) throws Exception {
        JSONObject c=snapCard(eid);if(c==null)return null;JSONArray ps=c.getJSONArray("profiles");
        for(int i=0;i<ps.length();i++)if(ps.getJSONObject(i).optString("iccid").equals(iccid))return ps.getJSONObject(i);return null;
    }
    /** Reads every adapter once: card list, profiles, active slot bindings and free memory. Card thread only. */
    private void readCards() throws Exception {
        JSONArray cards=lpa.cards();s.rememberCards(cards);StringBuilder errors=new StringBuilder();
        for(int i=0;i<cards.length();i++) {
            JSONObject c=cards.getJSONObject(i);if(c.optBoolean("unavailable"))continue;
            try{JSONArray ps=lpa.profiles(c);s.snapshotCard(c,ps);lpa.refresh(s,c,ps);}
            catch(Exception e){errors.append("Слот ").append(c.getInt("slot")+1).append(": ").append(Telegram.safe(e)).append("\n");}
            try{JSONObject info=lpa.info(c);if(info!=null)s.snapshotInfo(c.getString("eid"),info);}catch(Exception ignored){}
        }
        s.snapshotCards(cards);s.put("esim_refresh_error",errors.toString().trim());
    }
    /** The slot now reports another Android subscription than when it was read: list may be outdated. */
    private boolean changedSince(int slot) {
        try{String seen=s.get("active_sub:"+slot,"");int now=s.androidSubscription(slot);return !seen.isEmpty()&&now>=0&&!seen.equals(""+now);}
        catch(Exception e){return false;}
    }
    private void showProfiles(Telegram t,JSONObject m,int requestedPage,boolean refresh) throws Exception {
        s.put(draftKey(),"{}");
        if(!s.get("esim_control","false").equals("true") || !lpa.installed()) {
            show(t,m,"Профили 9eSIM\nНа телефоне открой SMS Мост → Настройки → 9eSIM → Разрешить управление → Проверить адаптер.\nУправление встроено в объединённый APK SMS Моста.\nДля обычных SIM и пересылки SMS включать его не нужно.",keyboard(button("Обновить","adapter"),button("Мои SIM-карты","profiles"),button("Назад","menu")));return;
        }
        // The card is read only on the card thread (or in synchronous tests without a task runner).
        if((refresh||needsRead())&&(onCard||tasks==null))readCards();
        java.util.List<JSONObject> cards=snapCards();JSONArray entries=new JSONArray();
        for(JSONObject c:cards){if(c.optBoolean("unavailable"))continue;JSONArray ps=c.getJSONArray("profiles");
            for(int i=0;i<ps.length();i++)entries.put(new JSONObject().put("eid",c.getString("eid")).put("iccid",ps.getJSONObject(i).getString("iccid")));}
        int pageSize=8,pages=Math.max(1,(entries.length()+pageSize-1)/pageSize),page=Math.max(0,Math.min(requestedPage,pages-1));
        String nonce=UUID.randomUUID().toString().replace("-","");
        s.put(menuKey(),new JSONObject().put("entries",entries).put("nonce",nonce).put("epoch",s.epoch()).put("page",page).put("expires",System.currentTimeMillis()+30*60000L).toString());
        long oldest=Long.MAX_VALUE;for(JSONObject c:cards)oldest=Math.min(oldest,c.optLong("time",0));
        StringBuilder text=new StringBuilder("Профили 9eSIM · всего ").append(entries.length()).append("\n");
        if(!cards.isEmpty())text.append("Обновлено: ").append(SmsDiagnostics.time(""+oldest)).append("\n");
        if(tasks!=null&&!onCard&&tasks.userBusy())text.append("⏳ Сейчас идёт операция с картой: после неё нажми «Обновить».\n");
        String errors=s.get("esim_refresh_error","");if(refresh&&!errors.isEmpty())text.append(errors).append("\n");
        JSONArray rows=new JSONArray(),pair=new JSONArray();int index=0;
        for(JSONObject card:cards) {
            JSONArray ps=card.optJSONArray("profiles");int count=card.optBoolean("unavailable")||ps==null?0:ps.length();
            boolean onPage=index<(page+1)*pageSize&&index+count>page*pageSize;
            if(onPage||count==0&&page==pages-1) {
                text.append("\n").append(cardLabel(card).substring(0,1).toUpperCase(java.util.Locale.ROOT)).append(cardLabel(card).substring(1));
                if(card.optBoolean("unavailable"))text.append(" · временно недоступна");
                else if(changedSince(card.getInt("slot"))){text.append(" · SIM в слоте сменилась, нажми «Обновить»");RelayService.requestAdapterCheck();}
                text.append("\n");
                if(!card.optBoolean("unavailable")) {
                    for(int i=0;i<count;i++)if(ps.getJSONObject(i).optBoolean("enabled"))text.append("🟢 Активен: ").append(profileLabel(card,ps.getJSONObject(i))).append("\n");
                    text.append(EsimErrors.memory(card.optJSONObject("info"))).append("\n");
                    if(count==0)text.append("Профилей нет\n");
                }
            }
            for(int i=0;i<count;i++,index++) {
                if(index<page*pageSize||index>=(page+1)*pageSize)continue;
                JSONObject p=ps.getJSONObject(i);boolean active=p.optBoolean("enabled");
                text.append(active?"🟢 ":"⚪ ").append(index+1).append(". ").append(profileLabel(card,p)).append(" · ").append(shortName(p.optString("provider","оператор не указан"),32))
                    .append(active?" — активен":"").append("\n");
                pair.put(button((active?"🟢 ":"⚪ ")+(index+1)+". "+shortName(profileLabel(card,p),24),"select:"+nonce+":"+index));
                if(pair.length()==2){rows.put(pair);pair=new JSONArray();}
            }
        }
        if(pair.length()>0)rows.put(pair);
        if(cards.isEmpty())text.append("\nАдаптеры пока не обнаружены. Проверь карты на телефоне и нажми «Обновить».\n");
        else if(entries.length()==0)text.append("\nНа адаптерах пока нет профилей. Нажми «Добавить eSIM».\n");
        if(pages>1) {
            text.append("\nСтраница ").append(page+1).append(" из ").append(pages).append("\n");
            JSONArray navigation=new JSONArray();
            if(page>0)navigation.put(button("← Назад","adapter:"+(page-1)));
            if(page+1<pages)navigation.put(button("Далее →","adapter:"+(page+1)));
            rows.put(navigation);
        }
        text.append("\nНажми на профиль: переключить, задать номер или удалить. На каждом адаптере активен свой профиль.");
        rows.put(new JSONArray().put(button("➕ Добавить eSIM","add")).put(button("🔄 Обновить","esim:refresh:"+page)));
        rows.put(new JSONArray().put(button("Главное меню","menu")));
        show(t,m,text.toString(),new JSONObject().put("inline_keyboard",rows));
    }
    private void select(Telegram t,JSONObject m,String data) throws Exception {
        requireAdapterControl();
        String[] parts=data.split(":");JSONObject cache=new JSONObject(s.get(menuKey(),"{}"));
        if(parts.length!=2 || !cache.optString("nonce").equals(parts[0]) || !cache.optString("epoch").equals(s.epoch()) || cache.optLong("expires")<System.currentTimeMillis())throw new UserError("Обнови список профилей: /esim");
        int index=Integer.parseInt(parts[1]);JSONArray entries=cache.optJSONArray("entries");if(entries==null||index<0||index>=entries.length())return;
        JSONObject entry=entries.getJSONObject(index);String eid=entry.getString("eid");
        JSONObject card=snapCard(eid),p=snapProfile(eid,entry.getString("iccid"));
        if(card==null||p==null)throw new UserError("Профиль не найден в последнем списке. Обнови список: /esim");
        String key=Rules.profileKey(eid,p.getString("iccid"));
        JSONObject d=newDraft("confirm_enable").put("iccid",p.getString("iccid")).put("eid",eid).put("key",key);s.put(draftKey(),d.toString());
        JSONArray rows=new JSONArray();
        if(!p.optBoolean("enabled"))rows.put(new JSONArray().put(button("Переключить на этот профиль","confirm:"+d.getString("nonce"))));
        rows.put(new JSONArray().put(button("Задать / изменить номер","rename")));
        rows.put(new JSONArray().put(button("Удалить профиль","delete:"+d.getString("nonce"))));
        rows.put(new JSONArray().put(button("К списку профилей","adapter:"+cache.optInt("page",0))));
        show(t,m,cardLabel(card)+"\n"+profileName(p)+"\nНомер: "+s.number(key)+"\nОператор: "+shortName(p.optString("provider"),48)+"\nICCID: "+p.getString("iccid")
            +(p.optBoolean("enabled")?"\n\n🟢 Этот профиль уже активен на своём адаптере.":"\n\nПри переключении отключится только текущий профиль этого адаптера. Мобильная связь на нём временно прервётся; оставь телефон на Wi-Fi.")
            +(s.number(key).equals("Номер не задан")?"\nНомер можно задать вручную, чтобы он отображался в SMS.":""),new JSONObject().put("inline_keyboard",rows));
    }
    private void rename(Telegram t,JSONObject m) throws Exception {
        JSONObject d=draft();if(!d.has("key"))throw new UserError("Сначала выбери профиль");
        d.put("stage","rename_phone");s.put(draftKey(),d.toString());show(t,m,"Введи номер для этого профиля, начиная с + и кода страны.",keyboard(button("Отмена","cancel")));
    }
    private void beginDelete(Telegram t,JSONObject m,String nonce) throws Exception {
        requireAdapterControl();JSONObject selected=draft();
        if(!selected.optString("stage").equals("confirm_enable")||!selected.optString("nonce").equals(nonce))
            throw new UserError("Выбор профиля устарел. Открой его заново: /esim");
        // Confirmation re-reads the card; an outdated "inactive" state is refused there.
        JSONObject card=snapCard(selected.getString("eid")),p=snapProfile(selected.getString("eid"),selected.getString("iccid"));
        if(card==null||p==null)throw new UserError("Профиль не найден в последнем списке. Обнови /esim");boolean active=p.optBoolean("enabled");
        JSONObject d=newDraft("confirm_delete").put("eid",card.getString("eid")).put("iccid",p.getString("iccid"))
            .put("key",selected.getString("key")).put("allow_active",active);
        s.put(draftKey(),d.toString());
        show(t,m,"Удалить eSIM с адаптера?\n"+cardLabel(card)+"\n"+profileName(p)+"\nНомер: "+s.number(d.getString("key"))+"\nICCID: "+p.getString("iccid")
            +"\n\nПрофиль будет удалён с карты. Старый QR-код может не подойти для повторной установки — это зависит от оператора."
            +(active?"\n\nЭтот профиль активен: перед удалением он отключится, SMS и звонки на него перестанут приходить. Оставь телефон на Wi-Fi.":""),
            keyboard(button(active?"Отключить и удалить":"Подтвердить удаление","confirm:"+d.getString("nonce")),button("Отмена","cancel")));
    }
    private void confirmDelete(Telegram t,JSONObject m,JSONObject d,JSONObject card) throws Exception {
        JSONObject p=findProfile(card,d.getString("iccid"));
        if(p.optBoolean("enabled")&&!d.optBoolean("allow_active"))throw new UserError("Профиль стал активным. Открой его заново и подтверди удаление с отключением связи.");
        s.put(draftKey(),"{}");show(t,m,"Удаляем выбранный профиль… Дождись проверки адаптера.",null);
        int slot=card.getInt("slot");s.switching(slot,true);s.clearActive(slot);boolean verified=false;
        try {
            s.rememberDelete(new JSONObject(d.toString()).put("chat_id",replyTo()));
            JSONObject result=lpa.delete(d.getString("eid"),d.getString("iccid"),d.optBoolean("allow_active"));
            // The provider verifies absence on the same EID. Only then discard this number binding.
            if(result==null||!result.optBoolean("success"))throw new UserError("Удаление не подтверждено. Обнови /esim.");
            s.forgetNumber(d.getString("key"));s.put(menuKey(),"{}");verified=true;
            String message="✅ Профиль удалён с адаптера.\n"+cardLabel(card)+"\nICCID: "+d.getString("iccid")
                +(result.optBoolean("notification_warning")?"\nУведомление серверу оператора не подтверждено. На карте профиль уже отсутствует.":"")
                +"\nАктивный профиль будет проверен отдельно. Список: /esim";
            s.put("esim_last_result",message);
            // Persist the verified result before another adapter read or network request can fail.
            s.enqueueNotice("delete-result:"+s.epoch()+":"+d.getString("nonce"),replyTo(),message);
            s.finishDelete(d.getString("nonce"));
        } finally {if(!verified)s.put("error","eSIM: удаление не подтверждено; проверь /esim");}
    }
    private void confirm(Telegram t,JSONObject m,String nonce) throws Exception {
        JSONObject d=draft();if(!d.optString("nonce").equals(nonce))throw new UserError("Подтверждение устарело. Начни операцию заново.");
        if(!s.get("esim_control","false").equals("true"))throw new UserError("Управление адаптером выключено на телефоне");
        String stage=d.optString("stage");
        if(!stage.equals("confirm_add")&&!stage.equals("confirm_enable")&&!stage.equals("confirm_delete"))return;
        if(stage.equals("confirm_add")) {
            String[] activation=d.getString("code").split("\\$",-1);
            if(activation.length>4 && activation[4].equals("1") && d.optString("pin").isEmpty())
                throw new UserError("Оператор требует код подтверждения. Пришли /pin КОД, затем снова нажми «Установить eSIM».");
        }
        // Until the card confirms the result, the saved list of this card is not trusted.
        try{s.snapshotDirty(d.getString("eid"));}catch(Exception ignored){}
        show(t,m,"Проверяем выбранный профиль на адаптере… Статус операции доступен через /status.",null);
        JSONObject card=lpa.card(d.getString("eid"));if(!card.getString("eid").equals(d.optString("eid")))throw new UserError("Адаптер изменился. Начни операцию заново.");
        if(stage.equals("confirm_delete")){confirmDelete(t,m,d,card);return;}
        if(stage.equals("confirm_enable")) {
            JSONObject p=findProfile(card,d.getString("iccid"));
            if(p.optBoolean("enabled")) {
                s.put(draftKey(),"{}");lpa.refresh(s,card);
                show(t,m,"🟢 Этот профиль уже активен: "+profileLabel(card,p),keyboard(button("Профили 9eSIM","adapter")));return;
            }
        }
        show(t,m,stage.equals("confirm_add")?"Загружаем eSIM. Дождись результата; повторно QR-код не отправляй.":"Переключаем профиль… Дождись проверки адаптера.",null);
        // Invalidate before changing the chip. Repeated button taps cannot repeat the operation.
        int slot=card.getInt("slot");s.put(draftKey(),"{}");s.switching(slot,true);s.clearActive(slot);
        boolean refreshed=false;
        try {
            String iccid=d.optString("iccid"),number;
            if(stage.equals("confirm_add")) {
                // Persist intended number/code first. If the chip succeeds but the response is lost,
                // reconciliation is manual instead of falsely attributing it to another number.
                s.put("last_install",d.toString());
                JSONObject p;
                try {p=lpa.download(d.getString("eid"),d.getString("code"),d.optString("pin"));s.put("esim_last_error","");}
                catch(Exception e){s.put("esim_last_error",Telegram.safe(e));throw e;}
                iccid=p.getString("iccid");s.put("esim_last_result","Профиль загружен на карту; проверяем активацию. ICCID: "+iccid);
                String key=Rules.profileKey(p.getString("eid"),iccid);number=d.getString("number");s.number(key,number);
            } else number=s.number(d.getString("key"));
            s.put("esim_task_label","Переключение и проверка профиля на карте");
            JSONArray ps=lpa.enable(card,iccid);boolean active=false;
            for(int i=0;i<ps.length();i++) {JSONObject p=ps.getJSONObject(i);if(p.optString("iccid").equals(iccid)&&p.optBoolean("enabled"))active=true;}
            if(!active)throw new UserError("Профиль сохранён, но активация не подтверждена. Проверь список eSIM.");
            // The private provider already verified this EID and returned a fresh snapshot.
            // Reopening every card and rereading profiles here delayed the success message.
            lpa.refresh(s,card,ps);
            s.switching(slot,false);refreshed=true;
            if(stage.equals("confirm_add"))s.put("last_install","{}");
            String result=number.equals("Номер не задан")?"🟢 Активирован профиль с ICCID …"+iccid.substring(Math.max(0,iccid.length()-6))+".\nНомер пока не задан. Его можно указать в профиле.":"🟢 Активирован профиль "+number+".\nВ новых SMS будет указан этот номер.";
            result+="\n"+cardLabel(card);s.put("esim_last_result",result);
            try {send(t,replyTo(),result,keyboard(button("Профили 9eSIM","adapter")));}
            catch(Exception e){s.enqueueNotice("activation-result:"+s.epoch()+":"+d.getString("nonce"),replyTo(),result);if(e instanceof Telegram.ApiError&&((Telegram.ApiError)e).code==429)s.telegramWait(Math.max(1,((Telegram.ApiError)e).retry));}
        } finally {
            if(!refreshed) {s.clearActive(slot);s.put("error","eSIM: проверь состояние профилей после незавершённой операции");}
        }
    }
    void reconcile() throws Exception {
        if(!lpa.installed()||!s.get("esim_control","false").equals("true"))return;
        // Runs on the same thread as mutations, so it never clears 'switching' mid-download.
        lpa.refresh(s);recoverDeletion();
    }
    private void recoverDeletion() throws Exception {
        JSONArray pending=s.pendingDeletes();Exception failure=null;
        for(int index=0;index<pending.length();index++)try {
        JSONObject d=pending.getJSONObject(index);if(!d.optString("epoch").equals(s.epoch()))continue;
        JSONObject card=lpa.card(d.getString("eid"));if(!card.getString("eid").equals(d.getString("eid")))throw new UserError("Для проверки прерванного удаления нужен прежний адаптер");
        JSONArray ps=lpa.profiles(card);boolean found=false;
        for(int i=0;i<ps.length();i++)if(ps.getJSONObject(i).getString("iccid").equals(d.getString("iccid")))found=true;
        String message=found?"После прерванной операции профиль всё ещё на карте. Удаление не подтверждено; автоматически не повторялось. Проверь /esim.":
            "✅ Проверка после прерванной операции: профиль отсутствует на прежнем адаптере. ICCID: "+d.getString("iccid");
        if(!found)s.forgetNumber(d.getString("key"));
        s.put("esim_last_result",message);s.enqueueNotice("delete-result:"+s.epoch()+":"+d.getString("nonce"),d.getLong("chat_id"),message);
        s.finishDelete(d.getString("nonce"));
        }catch(Exception e){failure=e;}
        if(failure!=null)s.put("esim_refresh_error",Telegram.safe(failure));
    }
    private void send(Telegram t,long target,String text,JSONObject keyboard) throws Exception {
        if(replyPrefix==null){t.send(target,text,keyboard);return;}
        java.util.List<String> parts=Rules.chunks(text);
        for(int i=0;i<parts.size();i++) {
            JSONObject request=new JSONObject().put("chat_id",target).put("text",parts.get(i)).put("protect_content",false);
            if(i==parts.size()-1&&keyboard!=null)request.put("reply_markup",keyboard);
            s.enqueueReply(replyPrefix+(replySequence++),target,"sendMessage",request);
        }
    }
    private void show(Telegram t,JSONObject m,String text,JSONObject keyboard) throws Exception {
        if(m==null){send(t,replyTo(),text,keyboard);return;}
        JSONObject p=new JSONObject().put("chat_id",replyTo()).put("message_id",m.getLong("message_id")).put("text",text);
        p.put("reply_markup",keyboard==null?new JSONObject().put("inline_keyboard",new JSONArray()):keyboard);
        if(replyPrefix!=null){s.enqueueReply(replyPrefix+(replySequence++),replyTo(),"editMessageText",p);return;}
        try {t.call("editMessageText",p);}catch(Telegram.ApiError e){if(e.code!=400)throw e;}
    }
    private void menu(Telegram t,JSONObject m) throws Exception {show(t,m,"SMS Мост\n"+status(),keyboard(button("Проверить связь","test"),button("Статус телефона","status"),button("Последние SMS","last"),button("Очередь отправки","queue"),button("Входящие звонки","calls"),button("Мои SIM-карты","profiles"),button("Профили 9eSIM","adapter")));}
    private void queue(Telegram t,JSONObject m,boolean retry) throws Exception {
        long target=replyTo();if(retry)s.retry(target);
        StringBuilder text=new StringBuilder(retry?"Повторная отправка запрошена\n":"Очередь отправки\n");
        text.append("Ожидают отправки тебе: ").append(s.pendingFor(target)).append("\n");
        JSONArray rows=s.queued(target);long now=System.currentTimeMillis();
        for(int i=0;i<rows.length();i++) {
            JSONObject p=rows.getJSONObject(i);String error=p.optString("error");long next=p.optLong("next_try");
            text.append("\n#").append(p.optLong("id")).append(" · ").append(p.optString("recipient"))
                .append("\n").append((p.optString("kind").equals("notice")||p.optString("kind").equals("bot_reply"))?"Ответ бота / операция 9eSIM":p.optString("kind").equals("call")?"Входящий звонок":Rules.service(p.optString("sender")))
                .append("\n").append(error.isEmpty()?"Ожидает отправки":error)
                .append("\n").append(next>now?"Следующая попытка: "+SmsDiagnostics.time(""+next):"Готово к отправке").append("\n");
        }
        show(t,m,text.toString(),keyboard(button("Повторить отправку","retry"),button("Обновить","queue"),button("Назад","menu")));
    }
    private String status() {String error=s.get("esim_last_error","");return "Телефон на связи\n"+BotHealth.details(s)+"\n"+SmsDiagnostics.report(s)+"\n\n"+CallDiagnostics.report(s)+"\nОтправлено сегодня: "+s.today()+"\nВ очереди: "+s.pending()+"\n"+s.get("device_status","")+battery()+"\n"+androidSims()+adapterStatus(s)+(error.isEmpty()?"":"\n\nПоследняя ошибка загрузки eSIM:\n"+error);}
    private String battery() {
        return s.get("battery_unrestricted","true").equals("false")?"\n⚠️ Для SMS Моста включена оптимизация батареи: Android может остановить бота в фоне и не дать ему перезапуститься. На телефоне: Настройки → Батарея → «Без ограничений».":"";
    }
    /** Android's subscription per slot: a new subId after a switch means Android saw the new profile. */
    private String androidSims() {
        try {
            JSONArray list=sims.list();if(list.length()==0)return "SIM в Android: активных нет\n";
            StringBuilder text=new StringBuilder("SIM в Android:");
            for(int i=0;i<list.length();i++){JSONObject sim=list.getJSONObject(i);
                text.append("\nСлот ").append(sim.optInt("slot")+1).append(" · subId ").append(sim.optInt("id")).append(" · ").append(shortName(sim.optString("name"),32));}
            return text.append("\n").toString();
        } catch(Exception e){return "";}
    }
    static String adapterStatus(Store s) {
        String state=s.get("esim_task_state","idle"),result=s.get("esim_last_result","");
        String text="9eSIM: "+(state.equals("running")?s.get("esim_task_label","операция выполняется"):state.equals("interrupted")?"результат предыдущей операции неизвестен":"нет текущей операции");
        if(state.equals("running"))try{text+=" · "+Math.max(0,(System.currentTimeMillis()-Long.parseLong(s.get("esim_task_started","0")))/1000)+" сек.";}catch(NumberFormatException ignored){}
        String refresh=s.get("esim_refresh_error","");
        String phase=s.get("esim_native_phase","");
        String timing=s.get("esim_switch_timing","");
        return text+(state.equals("running")&&!phase.isEmpty()?"\n"+phase:"")+(timing.isEmpty()?"":"\nПоследнее переключение: "+timing)+(refresh.isEmpty()?"":"\n"+refresh)+(result.isEmpty()?"":"\nПоследний результат:\n"+result);
    }
    private void recent(Telegram t,JSONObject m) throws Exception {
        StringBuilder b=new StringBuilder("Последние SMS\n");JSONArray rows=s.recent(replyTo(),"sms");
        for(int i=0;i<rows.length();i++){JSONObject p=rows.getJSONObject(i);b.append("\n#").append(p.getLong("id")).append(" · ").append(p.optString("recipient")).append("\n")
            .append(Rules.service(p.optString("sender"))).append(" · ").append(p.optString("state").equals("sent")?"Доставлено":"В очереди").append("\n");}
        show(t,m,b.toString(),keyboard(button("Очередь и причины задержки","queue"),button("Повторить отправку","retry"),button("Назад","menu")));
    }
    private void recentCalls(Telegram t,JSONObject m) throws Exception {
        StringBuilder text=new StringBuilder("Входящие звонки\n\n").append(CallDiagnostics.report(s));JSONArray rows=s.recent(replyTo(),"call");
        if(rows.length()==0)text.append("\n\nСохранённых звонков пока нет.");
        for(int i=0;i<rows.length();i++) {
            JSONObject p=rows.getJSONObject(i);text.append("\n\n📞 ").append(p.optString("sender"))
                .append("\nНа номер: ").append(p.optString("recipient")).append("\n").append(SmsDiagnostics.time(p.optString("received","0")))
                .append(" · ").append(p.optString("state").equals("sent")?"Доставлено":"В очереди");
        }
        show(t,m,text.toString(),keyboard(button("Обновить","calls"),button("Назад","menu")));
    }
    static String header(JSONObject p) {
        String slot=p.optInt("slot",-1)>=0?"Слот SIM: "+(p.optInt("slot")+1)+"\n":"";
        if(p.optString("kind").equals("call"))return "📞 Входящий звонок #"+p.optLong("id")+"\n\n📲 На номер: "+p.optString("recipient","Номер не определён")+"\n"+slot+"Абонент: "+p.optString("sender")+"\n\n";
        return "📩 Новое SMS #"+p.optLong("id")+"\n\n📲 На номер: "+p.optString("recipient","Номер не определён")+"\n"+slot+"🏷 Сервис: "+Rules.service(p.optString("sender"))+
            "\nОтправитель: "+p.optString("sender")+"\n\n";
    }
    static String format(JSONObject p) {
        return header(p)+p.optString("body")+"\n\n🕒 Получено: "+new SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale.forLanguageTag("ru")).format(new Date(p.optLong("received")));
    }
}
