package ru.smsbridge.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class LpaClientTest {
    private LpaClient client() throws Exception {
        LpaClient c=mock(LpaClient.class);when(c.cards()).thenReturn(new JSONArray().put(new JSONObject().put("eid","confirmed").put("slot",1).put("port",0)));
        when(c.card(anyString())).thenCallRealMethod();
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
    @Test public void downloadCarriesExpectedEidAndCurrentSlotToProvider() throws Exception {
        LpaClient c=client();when(c.args(any(JSONObject.class))).thenCallRealMethod();when(c.download(anyString(),anyString(),anyString())).thenCallRealMethod();
        when(c.query(eq("downloadProfile"),anyMap())).thenReturn(new JSONArray().put(new JSONObject().put("iccid","8900000000000000001")));
        c.download("confirmed","LPA:1$example.test$token","");
        verify(c).query(eq("downloadProfile"),argThat(a->a.get("expectedEid").equals("confirmed")&&a.get("slot").equals("1")));
    }
    private JSONObject confirmed() throws Exception {return new JSONObject().put("success",true).put("eid","confirmed").put("slot",1).put("port",0)
        .put("profiles",new JSONArray().put(new JSONObject().put("iccid","8900000000000000001").put("enabled",true)));}
    @Test public void switchCarriesExpectedEidAndDoesNotDefaultToSlotZero() throws Exception {
        LpaClient c=client();when(c.args(any(JSONObject.class))).thenCallRealMethod();when(c.enable(any(JSONObject.class),anyString())).thenCallRealMethod();
        when(c.query(eq("enableProfile"),anyMap())).thenReturn(new JSONArray().put(confirmed()));
        c.enable(c.card("confirmed"),"8900000000000000001");
        verify(c).query(eq("enableProfile"),argThat(a->a.get("expectedEid").equals("confirmed")&&a.get("slot").equals("1")));
    }
    @Test public void confirmedSwitchUsesReturnedSnapshotWithoutScanningCardsAgain() throws Exception {
        LpaClient c=client();when(c.args(any(JSONObject.class))).thenCallRealMethod();when(c.enable(any(JSONObject.class),anyString())).thenCallRealMethod();
        when(c.query(eq("enableProfile"),anyMap())).thenReturn(new JSONArray().put(confirmed()));
        JSONObject card=c.card("confirmed");clearInvocations(c);
        JSONArray ps=c.enable(card,"8900000000000000001");assertTrue(ps.getJSONObject(0).getBoolean("enabled"));
        verify(c,never()).cards();verify(c,never()).profiles(any(JSONObject.class));
    }
    @Test public void mismatchedOrUnverifiedSnapshotCannotConfirmActivation() throws Exception {
        LpaClient c=client();when(c.args(any(JSONObject.class))).thenCallRealMethod();when(c.enable(any(JSONObject.class),anyString())).thenCallRealMethod();
        JSONObject card=c.card("confirmed");
        for(JSONObject invalid:new JSONObject[]{confirmed().put("eid","other"),confirmed().put("slot",0),confirmed().put("port",1),confirmed().put("success",false),
            confirmed().put("profiles",new JSONArray()),confirmed().put("profiles",new JSONArray().put(new JSONObject().put("iccid","other").put("enabled",true))),
            confirmed().put("profiles",new JSONArray().put(new JSONObject().put("iccid","8900000000000000001").put("enabled",true)).put(new JSONObject().put("iccid","other").put("enabled",true)))}){
            when(c.query(eq("enableProfile"),anyMap())).thenReturn(new JSONArray().put(invalid));
            assertThrows(UserError.class,()->c.enable(card,"8900000000000000001"));
        }
    }
}
