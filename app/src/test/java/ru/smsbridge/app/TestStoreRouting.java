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
        // Saved card scans use the same get/put doubles as the rest of the settings.
        when(s.snapshot()).thenCallRealMethod();doCallRealMethod().when(s).snapshotCard(any(JSONObject.class),any(org.json.JSONArray.class));
        doCallRealMethod().when(s).snapshotCards(any(org.json.JSONArray.class));doCallRealMethod().when(s).snapshotInfo(anyString(),any(JSONObject.class));doCallRealMethod().when(s).snapshotDirty(anyString());
        when(s.androidSubscription(anyInt())).thenReturn(-1);when(s.recipient(anyInt(),anyInt(),anyInt())).thenCallRealMethod();
    }
}
