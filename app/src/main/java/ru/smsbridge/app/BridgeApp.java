package ru.smsbridge.app;
import android.app.Application;

public final class BridgeApp extends Application {
    private static Store data;
    @Override public void onCreate() { super.onCreate(); data = new Store(this); }
    static Store store() { return data; }
}
