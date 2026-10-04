package ru.smsbridge.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real receiver + real permission gate; only Android and persistence are test doubles. */
public class SmsReceiverTest {
    Store s;Context context;Intent intent;Map<String,String> settings;
    @Before public void setup() throws Exception {
        s=mock(Store.class);context=mock(Context.class);intent=mock(Intent.class);settings=new HashMap<>();
        settings.put("token","configured-test-token");settings.put("bot_enabled","true");settings.put("enabled","false");
        when(s.get(anyString(),anyString())).thenAnswer(i->settings.getOrDefault(i.getArgument(0),i.getArgument(1)));
        doAnswer(i->{settings.put(i.getArgument(0),i.getArgument(1));return null;}).when(s).put(anyString(),anyString());
        when(s.running()).thenCallRealMethod();when(s.enabled()).thenCallRealMethod();when(s.smsPermission()).thenReturn(true);
        when(intent.getAction()).thenReturn(Telephony.Sms.Intents.SMS_RECEIVED_ACTION);
        when(intent.getIntExtra(anyString(),anyInt())).thenAnswer(i->i.getArgument(1));
        when(s.enqueue(anyString(),any(JSONObject.class))).thenReturn(1L);
        when(s.recipient(anyInt(),anyInt())).thenReturn("+79990000000");
    }
    private SmsMessage part(String body) {
        SmsMessage p=mock(SmsMessage.class);when(p.getMessageBody()).thenReturn(body);
        when(p.getOriginatingAddress()).thenReturn("Service");when(p.getTimestampMillis()).thenReturn(1720000000000L);return p;
    }
    private void receive(SmsMessage... parts) {
        try(MockedStatic<BridgeApp> app=mockStatic(BridgeApp.class);MockedStatic<Telephony.Sms.Intents> sms=mockStatic(Telephony.Sms.Intents.class)) {
            app.when(BridgeApp::store).thenReturn(s);sms.when(()->Telephony.Sms.Intents.getMessagesFromIntent(intent)).thenReturn(parts);
            new SmsReceiver().onReceive(context,intent);
        }
    }
    private JSONObject saved() throws Exception {
        ArgumentCaptor<JSONObject> p=ArgumentCaptor.forClass(JSONObject.class);verify(s).enqueue(anyString(),p.capture());return p.getValue();
    }
    @Test public void permissionGrantedAfterBotStartCapturesDespiteOldDisabledFlag() throws Exception {
        assertEquals("false",settings.get("enabled"));assertTrue(s.enabled());
        receive(part("Код 012345"));assertEquals("Код 012345",saved().getString("body"));
        assertTrue(settings.containsKey("sms_last_saved"));assertTrue(settings.get("sms_result").contains("сохранено"));
    }
    @Test public void livePermissionChangesAreObservedWithoutRestartingBot() {
        when(s.smsPermission()).thenReturn(false);assertFalse(s.enabled());
        when(s.smsPermission()).thenReturn(true);assertTrue(s.enabled());
        when(s.smsPermission()).thenReturn(false);assertFalse(s.enabled());assertTrue(s.running());
    }
    @Test public void explicitStopStillPreventsCaptureEvenWithPermission() throws Exception {
        settings.put("bot_enabled","false");settings.put("enabled","true");assertFalse(s.enabled());
        receive(part("Test"));verify(s,never()).enqueue(anyString(),any());
        assertTrue(settings.get("sms_result").contains("остановлен"));
    }
    @Test public void revokedPermissionDoesNotTrustOldEnabledFlag() throws Exception {
        settings.put("enabled","true");when(s.smsPermission()).thenReturn(false);assertFalse(s.enabled());
        receive(part("Test"));verify(s,never()).enqueue(anyString(),any());
        assertTrue(settings.get("sms_result").contains("нет разрешения"));
    }
    @Test public void telephonySecurityExceptionDoesNotDropSms() throws Exception {
        when(intent.getIntExtra(eq("subscription"),anyInt())).thenReturn(7);
        when(context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)).thenReturn(PackageManager.PERMISSION_GRANTED);
        SubscriptionManager manager=mock(SubscriptionManager.class);when(context.getSystemService(SubscriptionManager.class)).thenReturn(manager);
        when(manager.getActiveSubscriptionInfo(7)).thenThrow(new SecurityException("private data must not appear in diagnostics"));
        receive(part("hello"));assertEquals("hello",saved().getString("body"));
        assertTrue(settings.get("sms_sim_warning").contains("SecurityException"));
        assertFalse(settings.get("sms_sim_warning").contains("private data"));
    }
    @Test public void recipientLookupFailureSavesUnknownNumberInsteadOfDroppingSms() throws Exception {
        when(intent.getIntExtra(eq("slot"),anyInt())).thenReturn(0);
        when(s.recipient(anyInt(),anyInt())).thenThrow(new IllegalStateException());
        receive(part("hello"));assertTrue(saved().getString("recipient").contains("Номер не определён"));
        assertTrue(settings.get("sms_receive_error").isEmpty());
    }
    @Test public void multipartSmsIsSavedOnceInOrder() throws Exception {
        receive(part("Код "),part("001234"));assertEquals("Код 001234",saved().getString("body"));
    }
    @Test public void emptyBroadcastHasVisibleReason() throws Exception {
        receive();verify(s,never()).enqueue(anyString(),any());
        assertTrue(settings.containsKey("sms_last_broadcast"));assertFalse(settings.containsKey("sms_last_saved"));
        assertTrue(SmsDiagnostics.report(s).contains("без частей SMS"));
    }
    @Test public void malformedPartDoesNotSavePartialSms() throws Exception {
        receive(part("half"),null);verify(s,never()).enqueue(anyString(),any());
        assertTrue(settings.get("sms_result").contains("Не удалось разобрать"));
    }
    @Test public void storageFailureIsVisibleSeparatelyFromTelegram() throws Exception {
        when(s.enqueue(anyString(),any())).thenThrow(new IllegalStateException("sensitive message"));
        receive(part("secret"));assertFalse(settings.containsKey("sms_last_saved"));
        s.put("error","");String report=SmsDiagnostics.report(s);
        assertTrue(report.contains("IllegalStateException"));assertFalse(report.contains("sensitive message"));assertFalse(report.contains("secret"));
    }
    @Test public void repeatIsDistinguishedFromNewlySavedSms() throws Exception {
        when(s.enqueue(anyString(),any())).thenReturn(-1L);receive(part("repeat"));
        assertTrue(settings.get("sms_result").contains("Повтор"));assertFalse(settings.containsKey("sms_last_saved"));
    }
    @Test public void unrelatedBroadcastIsIgnored() throws Exception {
        when(intent.getAction()).thenReturn("unrelated");receive(part("Test"));verifyNoInteractions(s);
    }
}
