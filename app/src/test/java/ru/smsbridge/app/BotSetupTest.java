package ru.smsbridge.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Exercises the real Bot handler with Telegram and storage replaced by test doubles. */
public class BotSetupTest {
    Store s;Telegram api;Bot bot;Map<String,String> values;long owner;
    @Before public void setup() throws Exception {
        s=mock(Store.class);api=mock(Telegram.class);values=new HashMap<>();owner=0;
        when(s.epoch()).thenReturn("test-session");when(s.running()).thenReturn(true);when(s.enabled()).thenReturn(false);
        when(s.chat()).thenAnswer(i->owner);
        when(s.chats()).thenAnswer(i->owner==0?java.util.Collections.emptyList():java.util.Collections.singletonList(owner));
        when(s.get(anyString(),anyString())).thenAnswer(i->values.getOrDefault(i.getArgument(0),i.getArgument(1)));
        doAnswer(i->{values.put(i.getArgument(0),i.getArgument(1));return null;}).when(s).put(anyString(),anyString());
        when(s.beginOperation(anyLong())).thenReturn(true);
        bot=new Bot(s,mock(LpaClient.class));
    }
    private JSONObject message(String text,long id,String type) throws Exception {
        return new JSONObject().put("update_id",101).put("message",new JSONObject().put("message_id",22)
            .put("text",text).put("date",System.currentTimeMillis()/1000)
            .put("chat",new JSONObject().put("id",id).put("type",type).put("first_name","Recipient"))
            .put("from",new JSONObject().put("id",id).put("is_bot",false)));
    }
    private void updates(JSONObject update) throws Exception {
        when(api.call(eq("getUpdates"),any())).thenReturn(new JSONObject().put("result",new JSONArray().put(update)));
    }
    @Test public void startAnswersBeforeRecipientAndBeforeSmsPermission() throws Exception {
        updates(message("/start",456,"private"));bot.poll(api,0);
        verify(api).send(eq(456L),contains("Бот работает"),isNull());
        verify(s,never()).claim(anyLong(),anyString(),anyString(),anyLong());
        assertTrue(values.containsKey("bot_last_seen"));
    }
    @Test public void correctCodeConnectsWithoutAnotherAndroidAction() throws Exception {
        when(s.claim(eq(456L),anyString(),eq("01234567"),anyLong())).thenAnswer(i->{owner=456;return true;});
        updates(message("01234567",456,"private"));bot.poll(api,0);
        verify(s).claim(eq(456L),eq("Recipient"),eq("01234567"),anyLong());
        verify(api).send(eq(456L),contains("больше ничего подтверждать"),isNull());
        verify(api).send(eq(456L),contains("SMS Мост"),notNull());
    }
    @Test public void wrongCodeCannotAccessMenuOrSms() throws Exception {
        updates(message("99999999",456,"private"));bot.poll(api,0);
        assertEquals(0,owner);verify(api).send(eq(456L),contains("Код неверный"),isNull());
        verify(s,never()).recent(anyLong());
    }
    @Test public void configuredOwnerAnswersEvenWhenSmsDisabled() throws Exception {
        owner=456;updates(message("/start",456,"private"));bot.poll(api,0);
        verify(api).send(eq(456L),contains("SMS Мост"),notNull());
    }
    @Test public void statusReportsCaptureFailureInsteadOfOnlyTelegramConnection() throws Exception {
        owner=456;when(s.smsPermission()).thenReturn(true);
        values.put("sms_result","Ошибка обработки входящего SMS");
        values.put("sms_receive_error","Не удалось сохранить SMS (IllegalStateException)");
        updates(message("/status",456,"private"));bot.poll(api,0);
        verify(api).send(eq(456L),argThat(text->text.contains("Разрешение SMS: есть")
            && text.contains("Сигнал о новом SMS: пока не было") && text.contains("IllegalStateException")),isNull());
    }
    @Test public void otherAccountCannotAccessConfiguredBot() throws Exception {
        owner=789;updates(message("/start",456,"private"));bot.poll(api,0);
        verify(api,never()).send(anyLong(),anyString(),any());verify(s,never()).recent(anyLong());
    }
    @Test public void secondAllowedAccountGetsItsOwnReply() throws Exception {
        owner=789;when(s.chats()).thenReturn(java.util.Arrays.asList(789L,456L));
        updates(message("/start",456,"private"));bot.poll(api,0);
        verify(api).send(eq(456L),contains("SMS Мост"),notNull());
        verify(api,never()).send(eq(789L),anyString(),any());
    }
    @Test public void forgedSenderCannotUseAllowedChat() throws Exception {
        owner=456;JSONObject update=message("/start",456,"private");
        update.getJSONObject("message").getJSONObject("from").put("id",999);
        updates(update);bot.poll(api,0);verify(api,never()).send(anyLong(),anyString(),any());
    }
    @Test public void groupCannotClaimPhone() throws Exception {
        updates(message("01234567",456,"group"));bot.poll(api,0);
        verify(s,never()).claim(anyLong(),anyString(),anyString(),anyLong());
        verify(api,never()).send(anyLong(),anyString(),any());
    }
    @Test public void stoppedBotDoesNotPoll() throws Exception {
        when(s.running()).thenReturn(false);bot.poll(api,0);verifyNoInteractions(api);
    }
    @Test public void networkErrorExplainsTelegramEndpoint() {
        assertTrue(Telegram.safe(new java.net.SocketTimeoutException()).contains("api.telegram.org"));
        assertTrue(Telegram.safe(new java.net.UnknownHostException()).contains("DNS"));
    }
    @Test public void simListWorksWithoutAdapterComponent() throws Exception {
        owner=456;LpaClient adapter=mock(LpaClient.class);
        when(s.number("physical:7")).thenReturn("+79991234567");
        bot=new Bot(s,adapter,()->new JSONArray().put(new JSONObject().put("id",7).put("slot",0).put("name","Carrier")));
        updates(message("/profiles",456,"private"));bot.poll(api,0);
        verify(api).send(eq(456L),contains("+79991234567"),notNull());verifyNoInteractions(adapter);
    }
    @Test public void optionalAdapterDoesNotBlockSimMenu() throws Exception {
        owner=456;LpaClient adapter=mock(LpaClient.class);bot=new Bot(s,adapter);
        JSONObject update=callback("adapter");updates(update);bot.poll(api,0);
        verify(api).call(eq("editMessageText"),argThat(p->p.optString("text").contains("Для обычных SIM")));
        verify(adapter,never()).card();
    }
    private JSONObject callback(String data) throws Exception {
        JSONObject original=message("",456,"private");JSONObject m=original.getJSONObject("message");
        return new JSONObject().put("update_id",102).put("callback_query",new JSONObject().put("id","cb")
            .put("data",data).put("message",m).put("from",m.getJSONObject("from")));
    }
    @Test public void physicalSimNumberCanBeSetFromTelegramWithoutAdapter() throws Exception {
        owner=456;LpaClient adapter=mock(LpaClient.class);
        NativeSims sims=()->new JSONArray().put(new JSONObject().put("id",7).put("slot",0).put("name","Carrier"));
        bot=new Bot(s,adapter,sims);updates(callback("physical:7"));bot.poll(api,0);
        updates(message("+79991234567",456,"private"));bot.poll(api,0);
        verify(s).number("physical:7","+79991234567");verifyNoInteractions(adapter);
    }
    @Test public void removedSimCannotReceiveStaleNumberAssignment() throws Exception {
        owner=456;NativeSims sims=mock(NativeSims.class);
        when(sims.list()).thenReturn(new JSONArray().put(new JSONObject().put("id",7).put("slot",0)),new JSONArray());
        bot=new Bot(s,mock(LpaClient.class),sims);updates(callback("physical:7"));bot.poll(api,0);
        updates(message("+79991234567",456,"private"));bot.poll(api,0);
        verify(s,never()).number(anyString(),anyString());
        verify(api).send(eq(456L),contains("больше не активна"),isNull());
    }
    private LpaClient beginEsim(String code) throws Exception {
        owner=456;values.put("esim_control","true");LpaClient adapter=mock(LpaClient.class);
        when(adapter.installed()).thenReturn(true);
        when(adapter.card()).thenReturn(new JSONObject().put("slot",0).put("port",0).put("eid","89000000000000000000000000000001"));
        when(adapter.download(anyString(),anyString())).thenReturn(new JSONObject().put("eid","89000000000000000000000000000001").put("iccid","8900000000000000001"));
        when(adapter.profiles(0,0)).thenReturn(new JSONArray().put(new JSONObject().put("iccid","8900000000000000001").put("enabled",true)));
        bot=new Bot(s,adapter);updates(message("/add",456,"private"));bot.poll(api,0);
        updates(message("+79991234567",456,"private"));bot.poll(api,0);
        updates(message(code,456,"private"));bot.poll(api,0);return adapter;
    }
    private void confirmEsim() throws Exception {
        JSONObject draft=new JSONObject(values.get("draft:456"));updates(callback("confirm:"+draft.getString("nonce")));bot.poll(api,0);
    }
    @Test public void esimInstallBindsEnteredNumberBeforeActivationAndVerifiesActiveProfile() throws Exception {
        LpaClient adapter=beginEsim("LPA:1$smdp.example$MATCH");confirmEsim();
        org.mockito.InOrder order=inOrder(adapter,s);
        order.verify(adapter).download("LPA:1$smdp.example$MATCH","");
        order.verify(s).number(Rules.profileKey("89000000000000000000000000000001","8900000000000000001"),"+79991234567");
        order.verify(adapter).enable("8900000000000000001");order.verify(adapter).refresh(s);
        verify(api).send(eq(456L),contains("Активирован профиль +79991234567"),notNull());
        assertEquals("{}",values.get("last_install"));assertEquals("false",values.get("switching"));
    }
    @Test public void esimChangedCardCannotConsumeActivationCode() throws Exception {
        LpaClient adapter=beginEsim("LPA:1$smdp.example$MATCH");
        when(adapter.card()).thenReturn(new JSONObject().put("eid","different-card"));confirmEsim();
        verify(adapter,never()).download(anyString(),anyString());verify(api).send(eq(456L),contains("Адаптер изменился"),isNull());
    }
    @Test public void failedEsimDownloadDoesNotActivateOrClaimSuccess() throws Exception {
        LpaClient adapter=beginEsim("LPA:1$smdp.example$MATCH");when(adapter.download(anyString(),anyString())).thenThrow(new UserError("Оператор отклонил загрузку"));
        confirmEsim();verify(adapter,never()).enable(anyString());verify(s,never()).number(anyString(),anyString());
        verify(api,never()).send(anyLong(),contains("Активирован профиль"),any());
        assertFalse(values.get("last_install").equals("{}"));
    }
    @Test public void esimInstalledButInactiveDoesNotClaimSuccess() throws Exception {
        LpaClient adapter=beginEsim("LPA:1$smdp.example$MATCH");
        when(adapter.profiles(0,0)).thenReturn(new JSONArray().put(new JSONObject().put("iccid","8900000000000000001").put("enabled",false)));
        confirmEsim();verify(api,never()).send(anyLong(),contains("Активирован профиль"),any());
        verify(api).send(eq(456L),contains("активация не подтверждена"),isNull());
    }
    @Test public void repeatInstallButtonDoesNotRedownloadOneTimeCode() throws Exception {
        LpaClient adapter=beginEsim("LPA:1$smdp.example$MATCH");String nonce=new JSONObject(values.get("draft:456")).getString("nonce");
        confirmEsim();updates(callback("confirm:"+nonce));bot.poll(api,0);
        verify(adapter,times(1)).download(anyString(),anyString());
    }
    @Test public void requiredConfirmationCodeCanBeAddedBeforeConsumingDraft() throws Exception {
        LpaClient adapter=beginEsim("LPA:1$smdp.example$MATCH$$1");confirmEsim();
        verify(adapter,never()).download(anyString(),anyString());assertEquals("confirm_add",new JSONObject(values.get("draft:456")).getString("stage"));
        updates(message("/pin 9876",456,"private"));bot.poll(api,0);confirmEsim();
        verify(adapter).download("LPA:1$smdp.example$MATCH$$1","9876");
    }

    @Test public void callsCommandShowsCallerAndRecipientWithoutReadingSimAdapter() throws Exception {
        owner=456;when(s.recent(456L,"call")).thenReturn(new JSONArray().put(new JSONObject()
            .put("sender","+79991234567").put("recipient","+79997654321").put("received",1780000000000L).put("state","sent")));
        updates(message("/calls",456,"private"));bot.poll(api,0);
        verify(api).send(eq(456L),argThat(text->text.contains("Входящие звонки")&&text.contains("+79991234567")&&text.contains("+79997654321")),notNull());
    }
    @Test public void emptyCallsHistoryExplainsPermissions() throws Exception {
        owner=456;when(s.recent(456L,"call")).thenReturn(new JSONArray());updates(message("/calls",456,"private"));bot.poll(api,0);
        verify(api).send(eq(456L),argThat(text->text.contains("Сохранённых звонков пока нет")&&text.contains("Журнал вызовов")),notNull());
    }

}
