package ru.smsbridge.app;

import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class BridgeAppTest {
    private static void store(Store s) throws Exception {
        java.lang.reflect.Field f=BridgeApp.class.getDeclaredField("data");f.setAccessible(true);f.set(null,s);
        java.lang.reflect.Field u=BridgeApp.class.getDeclaredField("unreadable");u.setAccessible(true);u.set(null,false);
    }
    @After public void clear() throws Exception {store(null);}
    @Test public void undecryptableSettingsAreReportedInsteadOfCrashing() throws Exception {
        Store s=mock(Store.class);when(s.get(anyString(),anyString())).thenThrow(new IllegalStateException("Не удалось прочитать защищённые настройки"));
        store(s);assertTrue(BridgeApp.settingsUnreadable());
        // A temporary keystore failure can be re-checked explicitly.
        reset(s);when(s.get(anyString(),anyString())).thenReturn("false");assertTrue(BridgeApp.settingsUnreadable());assertFalse(BridgeApp.recheckSettings());
    }
    @Test public void readableSettingsAreNotFlagged() throws Exception {
        Store s=mock(Store.class);when(s.get(anyString(),anyString())).thenReturn("true");store(s);assertFalse(BridgeApp.settingsUnreadable());
    }
}
