package ru.smsbridge.app;
import org.json.JSONObject;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Share production routing/journal logic with test doubles that replace only persistence. */
final class TestStoreRouting {
    static void attach(Store s) throws Exception {
        doCallRealMethod().when(s).switching(anyInt(),anyBoolean());
        when(s.switching(anyInt())).thenCallRealMethod();when(s.managed(anyInt())).thenCallRealMethod();
        when(s.pendingDeletes()).thenCallRealMethod();
        doCallRealMethod().when(s).rememberDelete(any(JSONObject.class));doCallRealMethod().when(s).finishDelete(anyString());
    }
}
