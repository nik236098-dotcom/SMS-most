package ru.smsbridge.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class LpaClientTest {
    private LpaClient client() throws Exception {
        LpaClient c=mock(LpaClient.class);when(c.card()).thenReturn(new JSONObject().put("eid","confirmed").put("slot",1).put("port",0));
        when(c.args(anyInt(),anyInt())).thenCallRealMethod();when(c.delete(anyString(),anyString(),anyBoolean())).thenCallRealMethod();return c;
    }
    @Test public void deletePassesExactCardProfileAndConsent() throws Exception {
        LpaClient c=client();when(c.query(eq("deleteProfile"),anyMap())).thenReturn(new JSONArray().put(new JSONObject().put("success",true)));
        assertTrue(c.delete("confirmed","8900000000000000001",true).getBoolean("success"));
        verify(c).query(eq("deleteProfile"),argThat(a->a.get("expectedEid").equals("confirmed")&&a.get("iccid").equals("8900000000000000001")&&a.get("allowActive").equals("true")&&a.get("slot").equals("1")));
    }
    @Test public void changedCardCannotReachMutation() throws Exception {
        LpaClient c=client();assertThrows(UserError.class,()->c.delete("old","8900000000000000001",false));verify(c,never()).query(anyString(),anyMap());
    }
    @Test public void absentSuccessIsNotDeletion() throws Exception {
        LpaClient c=client();when(c.query(eq("deleteProfile"),anyMap())).thenReturn(new JSONArray().put(new JSONObject().put("success",false)));
        assertThrows(UserError.class,()->c.delete("confirmed","8900000000000000001",false));
    }
    @Test public void failedOperatorNotificationDoesNotUndoVerifiedDeletion() throws Exception {
        LpaClient c=client();when(c.query(eq("deleteProfile"),anyMap())).thenReturn(new JSONArray().put(new JSONObject().put("success",true).put("notification_warning",true)));
        assertTrue(c.delete("confirmed","8900000000000000001",false).getBoolean("notification_warning"));
    }
}
