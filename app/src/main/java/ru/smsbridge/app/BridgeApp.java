package ru.smsbridge.app;
import android.app.Application;

public final class BridgeApp extends Application {
    private static Store data;
    @Override public void onCreate() {
        super.onCreate(); data = new Store(this);
        // Unreadable settings (e.g. Android lost the encryption key) must not crash every start;
        // MainActivity offers an explicit reset instead.
        try{recoverAdapterState(data);}catch(RuntimeException e){settingsUnreadable();}
    }
    private static volatile boolean unreadable;
    /** True when saved settings cannot be decrypted any more. */
    static boolean settingsUnreadable() {
        if(unreadable)return true;Store s=data;if(s==null)return false;
        try{s.get("bot_enabled","false");return false;}catch(IllegalStateException e){unreadable=true;return true;}
    }
    /** A keystore failure can be temporary (e.g. right after boot): allow an explicit re-check. */
    static boolean recheckSettings(){unreadable=false;return settingsUnreadable();}
    static void recoverAdapterState(Store s) {
        if(!s.get("esim_task_state","idle").equals("running"))return;
        s.put("esim_task_state","interrupted");
        s.put("esim_refresh_error","Приложение перезапустилось во время операции 9eSIM. Её результат не подтверждён. Проверь список профилей: /esim. Операция автоматически не повторяется.");
    }
    static Store store() { return data; }
}
