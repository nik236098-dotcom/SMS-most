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
    private TextView status,stats,connection,error;
    private Button relayToggle;
    private final Runnable ticker=new Runnable(){public void run(){if(alive){if(homeVisible)refresh();handler.postDelayed(this,3000);}}};
    @Override public void onCreate(Bundle b){super.onCreate(b);s=BridgeApp.store();home();handler.post(ticker);}
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
        page("SMS Мост","SMS автоматически в Telegram");homeVisible=true;
        LinearLayout state=card();status=label(state,"",21,true);
        relayToggle=button(state,s.enabled()?"Остановить пересылку":"Включить пересылку",true,()->{if(s.enabled())stop();else consent();});
        LinearLayout telegram=card();label(telegram,"Telegram",17,true);connection=label(telegram,"",15,false);
        stats=label(card(),"",22,true);error=label(root,"",14,false);error.setTextColor(Color.rgb(255,196,105));
        button(root,"Отправить тест",true,()->{if(!s.enabled()){toast("Сначала включи пересылку");return;}background(()->{
            s.enqueue(UUID.randomUUID().toString(),new JSONObject().put("recipient","Тест приложения").put("sender","SMS Мост").put("body","Пересылка в Telegram настроена. Это тест, не реальное SMS.").put("received",System.currentTimeMillis()));Outbox.drain(this);return "Тест добавлен в очередь";
        },this::toast);});
        button(root,"Последние SMS и очередь",false,this::history);
        button(root,"Номера и настройки",false,this::settings);
        label(root,"При работе отображается постоянное уведомление. Можно закрыть приложение.",13,false);refresh();
    }
    private void refresh(){status.setText(s.enabled()?"🟢 Пересылка включена":"Пересылка выключена");connection.setText(s.chat()>0?s.get("chat_name","Личный чат"):"Не подключён");
        relayToggle.setText(s.enabled()?"Остановить пересылку":"Включить пересылку");
        stats.setText("Отправлено сегодня: "+s.today()+"\nВ очереди: "+s.pending());error.setText(s.get("error",""));}
    private void consent() {
        if(s.chat()==0){pair();return;}
        new AlertDialog.Builder(this).setTitle("Включить пересылку?")
            .setMessage("Все новые входящие SMS с этого телефона будут автоматически отправляться в Telegram-чат «"+s.get("chat_name","")+"».\n\nРазрешения выдаёт владелец телефона. Остановить пересылку можно здесь или через постоянное уведомление.")
            .setNegativeButton("Отмена",null).setPositiveButton("Включить",(d,w)->permissions()).show();
    }
    private void permissions() {
        java.util.ArrayList<String> ps=new java.util.ArrayList<>();ps.add(Manifest.permission.RECEIVE_SMS);ps.add(Manifest.permission.READ_PHONE_STATE);
        if(Build.VERSION.SDK_INT>=33)ps.add(Manifest.permission.POST_NOTIFICATIONS);
        java.util.ArrayList<String> missing=new java.util.ArrayList<>();for(String p:ps)if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)missing.add(p);
        if(missing.isEmpty())start();else requestPermissions(missing.toArray(new String[0]),41);
    }
    @Override public void onRequestPermissionsResult(int code,String[] ps,int[] grants){super.onRequestPermissionsResult(code,ps,grants);if(code==41) {
        for(int g:grants)if(g!=PackageManager.PERMISSION_GRANTED){toast("Для работы нужны разрешения на SMS, SIM и уведомления");return;}start();}}
    private void start(){try{s.put("enabled","true");RelayService.start(this);home();}catch(Exception e){s.put("enabled","false");toast("Android не разрешил запуск. Попробуй ещё раз и проверь настройки батареи.");}}
    private void stop(){s.put("enabled","false");stopService(new Intent(this,RelayService.class));home();}
    private void history() {
        page("Доставка SMS","Номер закрепляется в момент получения сообщения");
        try {JSONArray rows=s.recent();if(rows.length()==0)label(root,"Сообщений пока нет",17,false);
            for(int i=0;i<rows.length();i++){JSONObject p=rows.getJSONObject(i);LinearLayout c=card();label(c,p.optString("recipient"),19,true);
                label(c,"Сервис: "+Rules.service(p.optString("sender")),16,true);label(c,"Отправитель: "+p.optString("sender"),14,false);
                label(c,p.optString("body"),15,false);label(c,p.optString("state").equals("sent")?"✓ Доставлено":"В очереди · "+p.optString("error"),14,false);
            }}catch(Exception e){toast("Не удалось прочитать историю");}
        button(root,"Повторить отправку",true,()->{s.retry();toast("Повторная отправка запланирована");});button(root,"Назад",false,this::home);
    }
    private void settings() {
        page("Настройки","Телефон остаётся дома на Wi-Fi и зарядке");
        button(root,"Подключить / изменить Telegram",true,()->{if(s.enabled()){toast("Сначала останови пересылку");return;}if(s.pending()>0){toast("Сначала отправь или удали очередь старого чата");return;}pair();});
        CheckBox control=new CheckBox(this);control.setText("Разрешить управление моим адаптером 9eSIM из привязанного Telegram-чата");control.setTextColor(Color.WHITE);control.setChecked(s.get("esim_control","false").equals("true"));root.addView(control);
        control.setOnCheckedChangeListener((b,on)->{s.put("esim_control",""+on);if(!on)s.clearActive();});
        label(root,new LpaClient(this).installed()?"Компонент 9eSIM обнаружен":"Компонент 9eSIM не установлен. Установка профилей пока недоступна.",14,false);
        button(root,"Проверить адаптер",false,()->background(()->{LpaClient l=new LpaClient(this);JSONObject c=l.card();l.refresh(s);return "Адаптер обнаружен, слот "+(c.getInt("slot")+1);},this::toast));
        button(root,"Задать номер физической SIM",false,this::physical);
        button(root,"Настройки батареи",false,()->{try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(Exception e){appSettings();}});
        button(root,"Разрешения приложения",false,this::appSettings);
        label(root,"На Xiaomi: разреши автозапуск и выбери режим батареи «Без ограничений». После перезагрузки разблокируй телефон один раз.",14,false);
        button(root,"Удалить неотправленную очередь",false,()->new AlertDialog.Builder(this).setTitle("Удалить очередь?").setMessage("Неотправленные SMS будут удалены без отправки.").setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,w)->{s.purgeQueue();toast("Очередь удалена");}).show());
        button(root,"Назад",false,this::home);
    }
    private void physical() {
        if(checkSelfPermission(Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE},42);toast("После выдачи разрешения открой эту страницу ещё раз");return;}
        page("Номер физической SIM","Для eSIM номер вводится при добавлении профиля в боте");
        try {List<SubscriptionInfo> list=getSystemService(SubscriptionManager.class).getActiveSubscriptionInfoList();
            if(list==null||list.isEmpty())label(root,"Активных SIM пока нет",16,false);
            else for(SubscriptionInfo info:list) {
                if(s.get("adapter_slot","-1").equals(""+info.getSimSlotIndex()))continue;
                label(root,"Слот "+(info.getSimSlotIndex()+1)+" · "+info.getDisplayName(),17,true);
                EditText e=input("+79991234567",false);String current=s.number("physical:"+info.getSubscriptionId());if(!current.equals("Номер не задан"))e.setText(current);
                button(root,"Сохранить номер слота "+(info.getSimSlotIndex()+1),true,()->{try{s.number("physical:"+info.getSubscriptionId(),e.getText().toString());toast("Номер сохранён");}catch(Exception ex){toast(Telegram.safe(ex));}});
            }}catch(Exception e){toast("Не удалось прочитать SIM");}button(root,"Назад",false,this::settings);
    }
    private void pair() {
        page("Подключение Telegram","Создай отдельного бота через @BotFather и вставь его токен. Токен вводится только здесь.");
        EditText token=input("Токен бота",true);
        button(root,"Создать ссылку для привязки",true,()->{
            String value=token.getText().toString().trim();background(()->{
                Telegram t=new Telegram(value);JSONObject wh=t.call("getWebhookInfo",new JSONObject()).getJSONObject("result");
                if(!wh.optString("url").isEmpty())throw new UserError("У этого бота уже есть webhook. Создай отдельного бота для SMS.");
                String username=t.call("getMe",new JSONObject()).getJSONObject("result").getString("username");
                String nonce=UUID.randomUUID().toString().replace("-","");s.put("pair_token",value);s.put("pair_nonce",nonce);s.put("pair_expires",""+(System.currentTimeMillis()+15*60000));s.put("pair_offset","0");s.put("pair_link","https://t.me/"+username+"?start="+nonce);return "Готово";
            },r->pairLink());
        });button(root,"Назад",false,this::home);
    }
    private void pairLink() {
        page("Привязка твоего чата","Открой ссылку в своём Telegram и нажми «Начать». Ссылку можно отправить с Android на твой iPhone.");
        button(root,"Скопировать ссылку",false,()->{getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Привязка SMS Мост",s.get("pair_link","")));toast("Ссылка скопирована");});
        button(root,"Открыть Telegram",false,()->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(s.get("pair_link",""))));}catch(Exception e){toast("Скопируй ссылку и открой её на своём телефоне");}});
        button(root,"Проверить подключение",true,()->background(()->{
            if(System.currentTimeMillis()>Long.parseLong(s.get("pair_expires","0")))throw new UserError("Ссылка истекла. Создай новую.");
            Telegram t=new Telegram(s.get("pair_token",""));JSONArray updates=t.call("getUpdates",new JSONObject().put("timeout",0).put("limit",100).put("offset",Long.parseLong(s.get("pair_offset","0")))) .getJSONArray("result");
            for(int i=0;i<updates.length();i++){JSONObject u=updates.getJSONObject(i);s.put("pair_offset",""+(u.getLong("update_id")+1));JSONObject m=u.optJSONObject("message");if(m==null)continue;
                JSONObject chat=m.getJSONObject("chat"),from=m.optJSONObject("from");
                if(m.optString("text").equals("/start "+s.get("pair_nonce",""))&&chat.optString("type").equals("private")&&from!=null&&!from.optBoolean("is_bot")&&from.optLong("id")==chat.getLong("id")) {
                    long id=chat.getLong("id");String name=chat.optString("first_name","Личный чат");s.bind(s.get("pair_token",""),id,name);s.put("pair_token","");return "Привязан чат: "+name;
                }}throw new UserError("Нажми «Начать» по ссылке на своём Telegram, затем проверь ещё раз.");
        },r->{toast(r);home();}));button(root,"Назад",false,this::pair);
    }
    private void appSettings(){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}
    private interface Task {String run() throws Exception;}
    private interface Done {void accept(String value);}
    private void background(Task task,Done done){worker.submit(()->{try{String value=task.run();handler.post(()->{if(alive)done.accept(value);});}catch(Exception e){String message=Telegram.safe(e);handler.post(()->{if(alive)toast(message);});}});}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    @Override public void onBackPressed(){home();}
    @Override protected void onDestroy(){alive=false;handler.removeCallbacks(ticker);worker.shutdownNow();super.onDestroy();}
}
