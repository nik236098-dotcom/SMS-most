package ru.smsbridge.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BG=Color.rgb(13,23,36),CARD=Color.rgb(23,37,53),BLUE=Color.rgb(22,133,255),MUTED=Color.rgb(165,188,213);
    private Store s;private LinearLayout root;private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());private boolean alive=true,homeVisible;
    private TextView status,stats,connection,error,smsDiagnostics;
    private Button relayToggle; private TextView setupLabel; private EditText tokenInput,idInput; private boolean launching;
    private final Runnable ticker=new Runnable(){public void run(){if(alive){if(homeVisible)refresh();handler.postDelayed(this,3000);}}};
    @Override public void onCreate(Bundle b){super.onCreate(b);s=BridgeApp.store();if(s.running())try{RelayService.start(this);}catch(Exception e){s.put("bot_error",Telegram.safe(e));}home();handler.post(ticker);
        if(s.running()&&s.callsEnabled()&&!s.get("calls_permission_prompted","false").equals("true"))handler.post(()->{if(alive)callPermissions();});}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable shape(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(18));return d;}
    private void page(String title,String subtitle) {
        homeVisible=false;ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(28),dp(20),dp(28));
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(20),dp(20)+insets.getSystemWindowInsetTop(),dp(20),dp(20)+insets.getSystemWindowInsetBottom());return insets;});
        scroll.addView(root);setContentView(scroll);root.requestApplyInsets();label(root,title,29,true);if(!subtitle.isEmpty())label(root,subtitle,14,false);
    }
    private TextView label(LinearLayout parent,String text,int size,boolean bold) {
        TextView v=new TextView(this);v.setText(text);v.setTextColor(bold?Color.WHITE:MUTED);v.setTextSize(size);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);parent.addView(v,lp);return v;
    }
    private LinearLayout card() {
        LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setPadding(dp(18),dp(18),dp(18),dp(10));v.setBackground(shape(CARD));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(12);lp.bottomMargin=dp(4);root.addView(v,lp);return v;
    }
    private Button button(LinearLayout parent,String title,boolean primary,Runnable action) {
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextSize(16);b.setTextColor(Color.WHITE);b.setBackground(shape(primary?BLUE:CARD));b.setMinimumHeight(dp(54));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(12);lp.bottomMargin=dp(4);parent.addView(b,lp);b.setOnClickListener(v->action.run());return b;
    }
    private EditText input(String hint,boolean secret) {
        EditText e=new EditText(this);e.setTextColor(Color.WHITE);e.setHintTextColor(MUTED);e.setHint(hint);e.setTextSize(16);e.setSingleLine(true);
        e.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT);e.setPadding(dp(14),dp(12),dp(14),dp(12));e.setBackground(shape(CARD));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(12);root.addView(e,lp);return e;
    }
    private void home() {
        page("SMS Мост","Бот работает с этого телефона · сервер не нужен");homeVisible=true;
        tokenInput=input("Вставь токен из @BotFather",true);tokenInput.setText(s.get("token",""));
        idInput=input("Telegram ID: 123456789, 987654321",false);idInput.setSingleLine(false);idInput.setMaxLines(3);
        if(s.chat()>0)idInput.setText(Rules.joinIds(s.chats()));
        label(root,"Укажи ID получателей через запятую, до 10 аккаунтов. Бот отвечает только им и отправляет SMS каждому. Каждый получатель должен написать боту /start. Telegram на Android открывать не нужно. Если ID неизвестен, оставь поле пустым — подключение одного получателя по коду.",14,false);
        relayToggle=button(root,s.running()?"Остановить бота":"Запустить бота",true,()->{if(s.running())stop();else launch();});
        LinearLayout state=card();status=label(state,"",20,true);connection=label(state,"",15,false);setupLabel=label(state,"",18,true);
        button(state,"Скопировать данные подключения",false,()->{
            String text="Бот: @"+s.get("bot_username","")+"\nКод: "+(s.chat()==0?s.setupCode():"Получатель уже подключён");
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("SMS Мост",text));toast("Скопировано — передай получателю SMS");
        });
        stats=label(card(),"",20,true);smsDiagnostics=label(card(),"",14,false);error=label(root,"",14,false);error.setTextColor(Color.rgb(255,196,105));
        button(root,"Отправить тест в Telegram",true,()->{if(s.chat()==0){toast("Сначала отправь код боту со своего Telegram");return;}if(!s.running()){toast("Сначала запусти бота");return;}background(()->{
            Telegram t=new Telegram(s.get("token",""));int sent=0;String failure="";
            for(long target:s.chats())try{t.send(target,"✅ Телефон подключён. Это тест SMS Мост.\nПересылка SMS: "+(s.enabled()?"включена":"нет разрешения на SMS — открой настройки Android"),null);sent++;}catch(Exception e){failure=Telegram.safe(e);}
            if(!failure.isEmpty())s.put("error",failure+". Каждый получатель должен нажать «Начать» в боте.");
            return "Тест отправлен: "+sent+" из "+s.chats().size();
        },this::toast);});
        button(root,"Мои SIM-карты",false,this::physical);
        button(root,"Входящие звонки",false,this::callSettings);
        button(root,"Настройки",false,this::settings);
        button(root,"SMS, звонки и очередь",false,this::history);
        label(root,"При работе отображается постоянное уведомление. После настройки приложение можно закрыть.",13,false);refresh();
    }
    private void refresh(){
        boolean running=s.running();status.setText(running?"Бот запущен":"Бот остановлен");
        relayToggle.setText(launching?"Проверяем Telegram…":running?"Остановить бота":"Запустить бота");relayToggle.setEnabled(!launching);
        String username=s.get("bot_username","");
        connection.setText((username.isEmpty()?"Токен ещё не проверен":"Бот: @"+username)+"\n"+(s.chat()>0?"Получатель: "+s.get("chat_name",""):"Получатель пока не подключён"));
        setupLabel.setText(running&&s.chat()==0?"Код подключения: "+s.setupCode()+"\nОтправь эти 8 цифр боту со своего Telegram. Код действует 30 минут.":"");
        setupLabel.setVisibility(running&&s.chat()==0?View.VISIBLE:View.GONE);
        long seen=Long.parseLong(s.get("bot_last_seen","0"));String api=seen==0?"Ожидается первый ответ Telegram":System.currentTimeMillis()-seen<60000?"Telegram отвечает ✓":"Давно нет ответа Telegram";
        stats.setText(api+"\nSMS: "+(s.enabled()?(s.chat()>0?"пересылка включена":"ожидают подключения получателя"):"нужно разрешение SMS или запуск бота")+"\nДоставок сегодня: "+s.today()+" · очередь: "+s.pending());
        smsDiagnostics.setText(SmsDiagnostics.report(s)+"\n\n"+CallDiagnostics.report(s));
        error.setText(s.get("bot_error","")+ (s.get("bot_error","").isEmpty()?"":"\n")+s.get("error",""));
    }
    private void launch() {
        if(launching)return;String token=tokenInput.getText().toString().trim();launching=true;refresh();
        String idText=idInput.getText().toString().trim();final java.util.List<Long> recipients;
        try{recipients=Rules.telegramIds(idText);}catch(Exception e){launching=false;s.put("bot_error",Telegram.safe(e));refresh();return;}
        worker.submit(()->{
            try {
                Telegram t=new Telegram(token);JSONObject wh=t.call("getWebhookInfo",new JSONObject()).getJSONObject("result");
                if(!wh.optString("url").isEmpty())throw new UserError("Этот бот подключён к другому серверу. Используй отдельного бота из @BotFather.");
                String username=t.call("getMe",new JSONObject()).getJSONObject("result").getString("username");
                s.configureBot(token,username,recipients);s.put("bot_error","");s.put("bot_last_seen","0");
                handler.post(()->{launching=false;if(alive)permissions();});
            } catch(Exception e) {s.put("bot_error",Telegram.safe(e));handler.post(()->{launching=false;if(alive){refresh();toast(Telegram.safe(e));}});}
        });
    }
    private void permissions() {
        java.util.ArrayList<String> ps=new java.util.ArrayList<>();ps.add(Manifest.permission.RECEIVE_SMS);ps.add(Manifest.permission.READ_PHONE_STATE);
        if(s.callsEnabled()){ps.add(Manifest.permission.READ_CALL_LOG);s.put("calls_permission_prompted","true");}
        if(Build.VERSION.SDK_INT>=33)ps.add(Manifest.permission.POST_NOTIFICATIONS);
        java.util.ArrayList<String> missing=new java.util.ArrayList<>();for(String p:ps)if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)missing.add(p);
        if(missing.isEmpty())start();else requestPermissions(missing.toArray(new String[0]),41);
    }
    @Override public void onRequestPermissionsResult(int code,String[] ps,int[] grants){super.onRequestPermissionsResult(code,ps,grants);if(code==41)start();else if(code==43){callSettings();if(!s.callLogPermission())toast("Для номера звонящего разреши «Журнал вызовов» в настройках приложения");}else if(code==42){if(checkSelfPermission(Manifest.permission.READ_PHONE_STATE)==PackageManager.PERMISSION_GRANTED)physical();else toast("Без разрешения «Телефон» Android не предоставит список SIM");}}
    private void start(){try{
        s.put("bot_enabled","true");s.put("enabled",""+(checkSelfPermission(Manifest.permission.RECEIVE_SMS)==PackageManager.PERMISSION_GRANTED));
        RelayService.start(this);home();
    }catch(Exception e){s.put("enabled","false");s.put("bot_enabled","false");s.put("bot_error","Android не разрешил запуск: проверь уведомления и настройки батареи.");home();}}
    private void stop(){s.put("enabled","false");s.put("bot_enabled","false");s.resetCalls();stopService(new Intent(this,RelayService.class));home();}
    private void history() {
        page("SMS и входящие звонки","Номер SIM закрепляется в момент получения события");
        label(card(),SmsDiagnostics.report(s),14,false);
        label(card(),CallDiagnostics.report(s),14,false);
        try {JSONArray rows=s.recent();if(rows.length()==0)label(root,"Сообщений пока нет",17,false);
            for(int i=0;i<rows.length();i++){JSONObject p=rows.getJSONObject(i);LinearLayout c=card();label(c,p.optString("recipient"),19,true);
                label(c,"Доставка в Telegram ID: "+p.optLong("chat_id",s.chat()),14,false);
                if(p.optString("kind").equals("call")){label(c,"📞 Входящий звонок",16,true);label(c,"Абонент: "+p.optString("sender"),16,false);}
                else {label(c,"Сервис: "+Rules.service(p.optString("sender")),16,true);label(c,"Отправитель: "+p.optString("sender"),14,false);label(c,p.optString("body"),15,false);}
                label(c,SmsDiagnostics.time(p.optString("received","0")),14,false);label(c,p.optString("state").equals("sent")?"✓ Доставлено":"В очереди · "+p.optString("error"),14,false);
            }}catch(Exception e){toast("Не удалось прочитать историю");}
        button(root,"Повторить отправку",true,()->{s.retry();toast("Повторная отправка запланирована");});button(root,"Назад",false,this::home);
    }
    private void settings() {
        page("Настройки","Телефон остаётся дома на Wi-Fi и зарядке");
        button(root,"Токен и подключение Telegram",true,this::home);
        button(root,"Разрешения SMS и запуск",false,()->{if(s.get("token","").isEmpty()){home();toast("Сначала введи токен бота");}else permissions();});
        button(root,"Входящие звонки",false,this::callSettings);
        button(root,"Мои SIM-карты",false,this::physical);
        button(root,"9eSIM · дополнительно",false,this::adapterSettings);
        button(root,"Настройки батареи",false,()->{try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(Exception e){appSettings();}});
        button(root,"Разрешения приложения",false,this::appSettings);
        label(root,"На Xiaomi: разреши автозапуск и выбери режим батареи «Без ограничений». После перезагрузки разблокируй телефон один раз.",14,false);
        button(root,"Удалить неотправленную очередь",false,()->new AlertDialog.Builder(this).setTitle("Удалить очередь?").setMessage("Неотправленные SMS и уведомления о звонках будут удалены без отправки.").setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,w)->{s.purgeQueue();toast("Очередь удалена");}).show());
        button(root,"Назад",false,this::home);
    }
    private void callPermissions() {
        s.put("calls_permission_prompted","true");
        java.util.ArrayList<String> missing=new java.util.ArrayList<>();
        if(!s.phonePermission())missing.add(Manifest.permission.READ_PHONE_STATE);
        if(!s.callLogPermission())missing.add(Manifest.permission.READ_CALL_LOG);
        if(missing.isEmpty()){callSettings();return;}
        requestPermissions(missing.toArray(new String[0]),43);
    }
    private void callSettings() {
        page("Входящие звонки","Номер звонящего и время — в твой Telegram");
        CheckBox control=new CheckBox(this);control.setText("Уведомлять о входящих звонках");control.setTextColor(Color.WHITE);control.setChecked(s.callsEnabled());root.addView(control);
        control.setOnCheckedChangeListener((b,on)->{s.put("calls_enabled",""+on);s.resetCalls();callSettings();});
        label(card(),CallDiagnostics.report(s),14,false);
        label(root,"Разрешение «Телефон» даёт события звонков, «Журнал вызовов» — номер звонящего. Пересылаются новые входящие сотовые звонки. Старые записи журнала и звук разговора приложение не читает.",14,false);
        button(root,"Разрешить телефон и журнал вызовов",true,this::callPermissions);
        button(root,"Открыть разрешения приложения",false,this::appSettings);
        label(root,"После выдачи разрешений сделай новый входящий звонок. В боте: /calls — список, /status — диагностика. При скрытом номере появится соответствующая пометка.",14,false);
        button(root,"Обновить статус",false,this::callSettings);
        button(root,"SMS, звонки и очередь",false,this::history);
        button(root,"Назад",false,this::home);
    }
    private void adapterSettings() {
        page("9eSIM · дополнительно","Обычные SIM и пересылка SMS работают без этого компонента");
        CheckBox control=new CheckBox(this);control.setText("Разрешить управление моим адаптером 9eSIM из привязанного Telegram-чата");control.setTextColor(Color.WHITE);control.setChecked(s.get("esim_control","false").equals("true"));root.addView(control);
        control.setOnCheckedChangeListener((b,on)->{s.put("esim_control",""+on);if(!on)s.clearActive();});
        label(root,new LpaClient(this).installed()?"Управление 9eSIM встроено в приложение":"Встроенное управление недоступно в этой сборке",14,false);
        button(root,"Открыть управление 9eSIM",true,()->{try{startActivity(new Intent().setClassName(getPackageName(),"im.angry.openeuicc.ui.UnprivilegedMainActivity"));}catch(Exception e){toast("Управление картой доступно в объединённой сборке");}});
        label(root,"Добавление через бота: /add → введи номер → отправь QR-код или строку LPA → подтверди установку. Номер сохранится за профилем.",14,false);
        button(root,"Проверить адаптер",false,()->background(()->{LpaClient l=new LpaClient(this);JSONObject c=l.card();l.refresh(s);return "Адаптер обнаружен, слот "+(c.getInt("slot")+1);},this::toast));
        button(root,"Назад",false,this::settings);
    }
    private void physical() {
        if(checkSelfPermission(Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE},42);return;}
        page("Мои SIM-карты","SIM определяются Android. Для пересылки SMS компонент 9eSIM не нужен.");
        try {List<SubscriptionInfo> list=getSystemService(SubscriptionManager.class).getActiveSubscriptionInfoList();
            if(list==null||list.isEmpty())label(root,"Активных SIM пока нет",16,false);
            else for(SubscriptionInfo info:list) {
                if(s.get("esim_control","false").equals("true") && s.get("adapter_slot","-1").equals(""+info.getSimSlotIndex())) {
                    label(root,"Слот "+(info.getSimSlotIndex()+1)+" · управляемый адаптер 9eSIM. Номер задаётся каждому профилю в разделе 9eSIM.",16,false);continue;
                }
                label(root,"Слот "+(info.getSimSlotIndex()+1)+" · "+info.getDisplayName(),17,true);
                EditText e=input("+79991234567",false);String current=s.number("physical:"+info.getSubscriptionId());if(!current.equals("Номер не задан"))e.setText(current);
                button(root,"Сохранить номер слота "+(info.getSimSlotIndex()+1),true,()->{try{s.number("physical:"+info.getSubscriptionId(),e.getText().toString());toast("Номер сохранён");}catch(Exception ex){toast(Telegram.safe(ex));}});
            }}catch(Exception e){label(root,"Не удалось прочитать SIM: проверь разрешение «Телефон» в настройках приложения.",16,false);}
        button(root,"Обновить SIM-карты",false,this::physical);button(root,"Назад",false,this::home);
    }
    private void appSettings(){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}
    private interface Task {String run() throws Exception;}
    private interface Done {void accept(String value);}
    private void background(Task task,Done done){worker.submit(()->{try{String value=task.run();handler.post(()->{if(alive)done.accept(value);});}catch(Exception e){String message=Telegram.safe(e);handler.post(()->{if(alive){s.put("error",message);toast(message);if(homeVisible)refresh();}});}});}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    @Override public void onBackPressed(){home();}
    @Override protected void onDestroy(){alive=false;handler.removeCallbacks(ticker);worker.shutdownNow();super.onDestroy();}
}
