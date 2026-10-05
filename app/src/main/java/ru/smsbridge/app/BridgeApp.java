package ru.smsbridge.app;
import android.app.Application;

public final class BridgeApp extends Application {
    private static Store data;
    @Override public void onCreate() { super.onCreate(); data = new Store(this);recoverAdapterState(data); }
    static void recoverAdapterState(Store s) {
        if(!s.get("esim_task_state","idle").equals("running"))return;
        s.put("esim_task_state","interrupted");
        s.put("esim_refresh_error","Приложение перезапустилось во время операции 9eSIM. Её результат не подтверждён. Проверь список профилей: /esim. Операция автоматически не повторяется.");
    }
    static Store store() { return data; }
}
