package ru.smsbridge.app;

import android.content.Context;
import android.content.Intent;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual receiver and call state machine; Android and persistent storage are test doubles. */
public class CallReceiverTest {
    Store store;Context context;Map<String,String> values;List<JSONObject> delivered;
    int starts;boolean rejectStart,failStorage;
    @Before public void setup() throws Exception {
        store=mock(Store.class);context=mock(Context.class);values=new HashMap<>();delivered=new ArrayList<>();
        when(store.running()).thenReturn(true);when(store.callsEnabled()).thenReturn(true);
        when(store.phonePermission()).thenReturn(true);when(store.callLogPermission()).thenReturn(true);when(store.epoch()).thenReturn("session");
        when(store.get(anyString(),anyString())).thenAnswer(i->values.getOrDefault(i.getArgument(0),i.getArgument(1)));
        doAnswer(i->{values.put(i.getArgument(0),i.getArgument(1));return null;}).when(store).put(anyString(),anyString());
        when(store.recipient(anyInt(),anyInt())).thenReturn("+79990000001");
        doAnswer(i->{
            if(failStorage)throw new IllegalStateException("private failure text");
            String key=i.getArgument(0);JSONObject next=i.getArgument(1),payload=i.getArgument(2);
            if(payload!=null){delivered.add(new JSONObject(payload.toString()));next.put("notified",true).put("notify",false);
                values.put("call_last_saved","1");values.put("call_result","Входящий звонок сохранён в очередь Telegram");}
            values.put(key,next.toString());return null;
        }).when(store).saveCall(anyString(),any(),nullable(JSONObject.class));
    }
    private void receive(String state,String number,boolean numberExtra,int sub,int slot) {
        Intent intent=mock(Intent.class);when(intent.getAction()).thenReturn(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        when(intent.getStringExtra(TelephonyManager.EXTRA_STATE)).thenReturn(state);
        when(intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)).thenReturn(number);
        when(intent.hasExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)).thenReturn(numberExtra);
        when(intent.getIntExtra(anyString(),anyInt())).thenAnswer(i->i.getArgument(1));
        when(intent.getIntExtra(eq(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX),anyInt())).thenReturn(sub);
        when(intent.getIntExtra(eq(SubscriptionManager.EXTRA_SLOT_INDEX),anyInt())).thenReturn(slot);
        deliver(intent);
    }
    private void deliver(Intent intent) {
        try(MockedStatic<BridgeApp> app=mockStatic(BridgeApp.class);MockedStatic<RelayService> service=mockStatic(RelayService.class)) {
            app.when(BridgeApp::store).thenReturn(store);
            service.when(()->RelayService.start(context)).thenAnswer(i->{starts++;if(rejectStart)throw new IllegalStateException();return null;});
            new CallReceiver().onReceive(context,intent);
        }
    }
    private void event(String state,String number,boolean numberExtra) {receive(state,number,numberExtra,7,0);}
    @Test public void incomingCallerIdIsQueuedImmediatelyAndRestartsService() throws Exception {
        event("RINGING","+79991234567",true);assertEquals(1,delivered.size());JSONObject p=delivered.get(0);
        assertEquals("call",p.getString("kind"));assertEquals("+79991234567",p.getString("sender"));
        assertEquals("+79990000001",p.getString("recipient"));assertEquals(7,p.getInt("sub_id"));assertEquals(1,starts);
        assertTrue(p.getLong("received")>0);
    }
    @Test public void blankBroadcastBeforeNumberProducesOneCompleteNotification() {
        event("RINGING",null,false);assertEquals(0,delivered.size());event("RINGING","+79991234567",true);
        event("IDLE",null,false);event("IDLE",null,true);assertEquals(1,delivered.size());
        assertEquals("+79991234567",delivered.get(0).optString("sender"));assertEquals(1,starts);
    }
    @Test public void numberBroadcastBeforeBlankDoesNotDuplicateOrLoseNumber() {
        event("RINGING","+79991234567",true);event("RINGING",null,false);event("RINGING","+79991234567",true);
        event("OFFHOOK",null,false);event("IDLE",null,false);assertEquals(1,delivered.size());
    }
    @Test public void nextCallFromSameNumberIsANewEvent() {
        event("RINGING","+79991234567",true);event("IDLE",null,false);event("RINGING","+79991234567",true);
        assertEquals(2,delivered.size());assertEquals(2,starts);
    }
    @Test public void outgoingCallCannotCreateAnIncomingNotification() {
        event("OFFHOOK","+79991234567",true);event("IDLE",null,false);assertTrue(delivered.isEmpty());assertEquals(0,starts);
    }
    @Test public void hiddenNumberIsReportedOnce() {
        event("RINGING",null,false);event("RINGING","",true);event("IDLE",null,false);
        assertEquals(1,delivered.size());assertTrue(delivered.get(0).optString("sender").contains("скрыт"));
    }
    @Test public void missingCallLogPermissionStillReportsCallWithActionableReasonAtEnd() {
        when(store.callLogPermission()).thenReturn(false);event("RINGING",null,false);event("IDLE",null,false);
        assertEquals(1,delivered.size());assertTrue(delivered.get(0).optString("sender").contains("Журнал вызовов"));
    }
    @Test public void recipientSnapshotIsNotReplacedAfterProfileSwitch() throws Exception {
        event("RINGING",null,false);when(store.recipient(anyInt(),anyInt())).thenReturn("+79990000002");
        event("RINGING","+79991234567",true);assertEquals("+79990000001",delivered.get(0).getString("recipient"));
    }
    @Test public void subscriptionStatesAreIndependent() {
        receive("RINGING","+79991234567",true,7,0);receive("RINGING","+79991234567",true,8,1);
        receive("IDLE",null,false,7,0);receive("RINGING",null,false,8,1);assertEquals(2,delivered.size());
        assertEquals(0,delivered.get(0).optInt("slot"));assertEquals(1,delivered.get(1).optInt("slot"));
    }
    private SubscriptionInfo sim(int id,int slot) {
        SubscriptionInfo info=mock(SubscriptionInfo.class);when(info.getSubscriptionId()).thenReturn(id);when(info.getSimSlotIndex()).thenReturn(slot);return info;
    }
    @Test public void singleActiveSimCanFillMissingPhoneStateExtras() {
        SubscriptionManager manager=mock(SubscriptionManager.class);when(context.getSystemService(SubscriptionManager.class)).thenReturn(manager);
        when(manager.getActiveSubscriptionInfoList()).thenReturn(Collections.singletonList(sim(7,0)));
        receive("RINGING","+79991234567",true,-1,-1);assertEquals(7,delivered.get(0).optInt("sub_id"));
    }
    @Test public void multipleSimsWithoutExtrasDoNotGuessDestinationNumber() throws Exception {
        SubscriptionManager manager=mock(SubscriptionManager.class);when(context.getSystemService(SubscriptionManager.class)).thenReturn(manager);
        when(manager.getActiveSubscriptionInfoList()).thenReturn(Arrays.asList(sim(7,0),sim(8,1)));
        receive("RINGING","+79991234567",true,-1,-1);assertTrue(delivered.get(0).getString("recipient").contains("не определён"));
        verify(store,never()).recipient(anyInt(),anyInt());
    }
    @Test public void simLookupFailureDoesNotDropIncomingCall() {
        SubscriptionManager manager=mock(SubscriptionManager.class);when(context.getSystemService(SubscriptionManager.class)).thenReturn(manager);
        when(manager.getActiveSubscriptionInfo(7)).thenThrow(new SecurityException("private"));
        receive("RINGING","+79991234567",true,7,-1);assertEquals(1,delivered.size());assertTrue(values.get("call_sim_warning").contains("SecurityException"));
    }
    @Test public void recipientLookupFailureDoesNotDropIncomingCall() throws Exception {
        when(store.recipient(anyInt(),anyInt())).thenThrow(new IllegalStateException());event("RINGING","+79991234567",true);
        assertEquals(1,delivered.size());assertTrue(delivered.get(0).getString("recipient").contains("не определён"));
    }
    @Test public void stoppedDisabledOrPhonePermissionRevokedPreventsCapture() {
        when(store.running()).thenReturn(false);event("RINGING","+79991234567",true);
        when(store.running()).thenReturn(true);when(store.callsEnabled()).thenReturn(false);event("RINGING","+79991234567",true);
        when(store.callsEnabled()).thenReturn(true);when(store.phonePermission()).thenReturn(false);event("RINGING","+79991234567",true);
        assertTrue(delivered.isEmpty());assertEquals(0,starts);
    }
    @Test public void serviceStartDenialDoesNotUndoSavedCall() {
        rejectStart=true;event("RINGING","+79991234567",true);assertEquals(1,delivered.size());
        assertEquals(1,starts);assertEquals("",values.get("call_receive_error"));
    }
    @Test public void persistenceFailureHasSeparateDiagnosticsAndCanRetryNextBroadcast() {
        failStorage=true;event("RINGING","+79991234567",true);assertTrue(delivered.isEmpty());assertEquals(0,starts);
        String error=CallDiagnostics.report(store);assertTrue(error.contains("IllegalStateException"));assertFalse(error.contains("private failure text"));
        failStorage=false;event("RINGING","+79991234567",true);assertEquals(1,delivered.size());
    }
    @Test public void receiverRecreationKeepsDeduplication() {
        event("RINGING","+79991234567",true);event("RINGING","+79991234567",true);assertEquals(1,delivered.size());
    }
    @Test public void nullAndUnrelatedIntentsDoNotTouchStorage() {
        deliver(null);Intent intent=mock(Intent.class);when(intent.getAction()).thenReturn("unrelated");deliver(intent);verifyNoInteractions(store);
    }
}
