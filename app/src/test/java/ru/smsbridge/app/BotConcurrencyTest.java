package ru.smsbridge.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** The adapter really blocks on another thread; getUpdates and ordinary commands must still run. */
public class BotConcurrencyTest {
    static final String EID="89000000000000000000000000000001",ICCID="8900000000000000001";
    Store s;Telegram api;LpaClient lpa;Bot bot;AdapterTasks tasks;
    Map<String,String> values;ExecutorService worker;List<Runnable> alarms;long update;
    CountDownLatch entered,release;
    @Before public void setup() throws Exception {
        s=mock(Store.class);api=mock(Telegram.class);lpa=mock(LpaClient.class);values=new ConcurrentHashMap<>();
        worker=Executors.newSingleThreadExecutor();alarms=new ArrayList<>();entered=new CountDownLatch(1);release=new CountDownLatch(1);
        ScheduledExecutorService timer=mock(ScheduledExecutorService.class);
        doAnswer(i->{alarms.add(i.getArgument(0));return mock(ScheduledFuture.class);}).when(timer).schedule(any(Runnable.class),anyLong(),any(TimeUnit.class));
        tasks=new AdapterTasks(worker,timer,45000);bot=new Bot(s,lpa,()->new JSONArray(),tasks);
        when(s.get(anyString(),anyString())).thenAnswer(i->values.getOrDefault(i.getArgument(0),i.getArgument(1)));
        doAnswer(i->{values.put(i.getArgument(0),i.getArgument(1));return null;}).when(s).put(anyString(),anyString());
        TestStoreRouting.attach(s);
        when(s.running()).thenReturn(true);when(s.epoch()).thenReturn("session");when(s.chat()).thenReturn(456L);
        when(s.chats()).thenReturn(java.util.Arrays.asList(456L,789L));when(s.beginOperation(anyLong())).thenReturn(true);
        when(s.number(anyString())).thenReturn("+79991234567");values.put("esim_control","true");
        when(lpa.installed()).thenReturn(true);when(lpa.card(anyString())).thenReturn(new JSONObject().put("eid",EID).put("slot",0).put("port",0));
        when(lpa.profiles(any(JSONObject.class))).thenReturn(new JSONArray().put(new JSONObject().put("iccid",ICCID).put("enabled",false)));
    }
    @After public void finish() throws Exception {release.countDown();worker.shutdown();assertTrue(worker.awaitTermination(3,TimeUnit.SECONDS));}
    void block() throws Exception {entered.countDown();assertTrue("test release",release.await(3,TimeUnit.SECONDS));}
    void awaitBlocked() throws Exception {assertTrue("native call started",entered.await(2,TimeUnit.SECONDS));}
    void complete() throws Exception {release.countDown();worker.submit(()->{}).get(2,TimeUnit.SECONDS);assertFalse(tasks.busy());}
    void draft(String stage) throws Exception {
        values.put("draft:456",new JSONObject().put("stage",stage).put("epoch","session").put("nonce","nonce")
            .put("expires",System.currentTimeMillis()+60000).put("eid",EID).put("iccid",ICCID).put("key",Rules.profileKey(EID,ICCID))
            .put("number","+79991234567").put("code","LPA:1$example.test$one-use").toString());
    }
    void send(String text,String callback,long chat) throws Exception {
        JSONObject from=new JSONObject().put("id",chat).put("is_bot",false);
        JSONObject m=new JSONObject().put("text",text).put("message_id",22).put("chat",new JSONObject().put("id",chat).put("type","private")).put("from",from);
        JSONObject u=new JSONObject().put("update_id",++update);
        if(callback==null)u.put("message",m);else u.put("callback_query",new JSONObject().put("id","cb"+update).put("message",m).put("from",from).put("data",callback));
        when(api.call(eq("getUpdates"),any())).thenReturn(new JSONObject().put("result",new JSONArray().put(u)));bot.poll(api,0);
    }
    void deleting() throws Exception {
        draft("confirm_delete");when(lpa.delete(EID,ICCID,false)).thenAnswer(i->{block();return new JSONObject().put("success",true);});
        send("","confirm:nonce",456);awaitBlocked();
    }
    @Test public void blockedDeletionDoesNotBlockStartStatusOrAnotherRecipient() throws Exception {
        deleting();verify(s,never()).endOperation(1);
        send("/start",null,456);send("/status",null,789);
        verify(api).send(eq(456L),contains("SMS Мост"),notNull());verify(api).send(eq(789L),contains("Подтверждённая операция"),isNull());
        complete();verify(s).endOperation(1);verify(s).enqueueNotice(anyString(),eq(456L),contains("Профиль удалён"));
        verify(s,never()).enqueueNotice(anyString(),eq(789L),contains("Профиль удалён"));
    }
    @Test public void repeatDeleteWhileBusyIsRejectedAndNeverReplayed() throws Exception {
        deleting();send("","confirm:nonce",456);verify(api).send(eq(456L),contains("Новый запрос не запущен"),isNull());
        complete();verify(lpa,times(1)).delete(EID,ICCID,false);
    }
    @Test public void smsDeliveryContinuesWhileNativeDeletionWaits() throws Exception {
        deleting();android.content.Context c=mock(android.content.Context.class);android.os.PowerManager power=mock(android.os.PowerManager.class);
        android.os.PowerManager.WakeLock lock=mock(android.os.PowerManager.WakeLock.class);when(c.getSystemService(android.os.PowerManager.class)).thenReturn(power);
        when(power.newWakeLock(anyInt(),anyString())).thenReturn(lock);when(s.pending(77)).thenReturn(true);
        when(s.next()).thenReturn(new JSONObject().put("id",77).put("part",0).put("chat_id",456).put("epoch","session").put("sender","Carrier").put("body","SMS still arrives").put("received",1),null);
        Outbox.drain(c,s,api);verify(api).call(eq("sendMessage"),argThat(p->p.optString("text").contains("SMS still arrives")));verify(s).delivered(77);assertTrue(tasks.busy());complete();
    }
    @Test public void slowOperationWarnsWithoutClaimingFailureOrReleasingGate() throws Exception {
        deleting();alarms.get(0).run();assertTrue(tasks.busy());
        verify(s).enqueueNotice(anyString(),eq(456L),contains("Результат ещё не подтверждён"));verify(s,never()).forgetNumber(anyString());
        complete();verify(s).enqueueNotice(anyString(),eq(456L),contains("Профиль удалён"));
    }
    @Test public void blockedBackgroundProfileReadDoesNotBlockCommands() throws Exception {
        doAnswer(i->{block();return null;}).when(lpa).refresh(s);bot.reconcileAsync();awaitBlocked();
        send("/start",null,456);verify(api).send(eq(456L),contains("SMS Мост"),notNull());
        send("/esim",null,456);verify(api).send(eq(456L),contains("Новый запрос не запущен"),isNull());complete();
    }
    @Test public void blockedDownloadDoesNotBlockCommandsOrRepeatQr() throws Exception {
        draft("confirm_add");when(lpa.download(eq(EID),anyString(),anyString())).thenAnswer(i->{block();return new JSONObject().put("eid",EID).put("iccid",ICCID);});
        when(lpa.profiles(any(JSONObject.class))).thenReturn(new JSONArray().put(new JSONObject().put("iccid",ICCID).put("enabled",true)));
        send("","confirm:nonce",456);awaitBlocked();send("/start",null,456);send("","confirm:nonce",456);
        verify(api).send(eq(456L),contains("SMS Мост"),notNull());complete();verify(lpa,times(1)).download(eq(EID),anyString(),anyString());
    }
    @Test public void blockedSwitchDoesNotBlockCommands() throws Exception {
        draft("confirm_enable");doAnswer(i->{block();when(lpa.profiles(any(JSONObject.class))).thenReturn(new JSONArray().put(new JSONObject().put("iccid",ICCID).put("enabled",true)));return null;}).when(lpa).enable(EID,ICCID);
        send("","confirm:nonce",456);awaitBlocked();send("/status",null,456);verify(api).send(eq(456L),contains("Телефон на связи"),isNull());complete();
    }
    @Test public void nativeFailureIsQueuedAndDoesNotDiscardBindingOrKeepGateLocked() throws Exception {
        draft("confirm_delete");when(lpa.delete(EID,ICCID,false)).thenThrow(new UserError("Нет ответа карты"));
        send("","confirm:nonce",456);worker.submit(()->{}).get(2,TimeUnit.SECONDS);assertFalse(tasks.busy());
        verify(s).enqueueNotice(anyString(),eq(456L),contains("Нет ответа карты"));verify(s,never()).forgetNumber(anyString());
    }
    @Test public void verifiedActivationResultSurvivesTelegramNetworkFailure() throws Exception {
        draft("confirm_enable");doAnswer(i->{when(lpa.profiles(any(JSONObject.class))).thenReturn(new JSONArray().put(new JSONObject().put("iccid",ICCID).put("enabled",true)));return null;}).when(lpa).enable(EID,ICCID);
        doThrow(new java.net.SocketTimeoutException()).when(api).send(eq(456L),contains("Активирован профиль"),any());
        send("","confirm:nonce",456);worker.submit(()->{}).get(2,TimeUnit.SECONDS);
        verify(s).enqueueNotice(startsWith("activation-result:"),eq(456L),contains("Активирован профиль"));
        assertTrue(values.get("esim_last_result").contains("Активирован профиль"));assertEquals("false",values.get("switching:0"));
    }
    @Test public void unauthorizedUpdateCannotOccupyAdapterWorker() throws Exception {
        send("/esim",null,999);assertFalse(tasks.busy());assertTrue(alarms.isEmpty());verifyNoInteractions(lpa);
    }
    @Test public void restartReportsUnknownOutcomeWithoutReplayingMutation() {
        values.put("esim_task_state","running");values.put("last_install","saved intent");
        BridgeApp.recoverAdapterState(s);assertEquals("interrupted",values.get("esim_task_state"));
        assertTrue(values.get("esim_refresh_error").contains("автоматически не повторяется"));assertEquals("saved intent",values.get("last_install"));verifyNoInteractions(lpa);
    }
    void interruptedDelete() throws Exception {
        draft("confirm_delete");values.put("last_delete",new JSONObject(values.get("draft:456")).put("chat_id",456).toString());
    }
    @Test public void interruptedDeleteIsVerifiedReadOnlyAndResultRestoredWhenProfileAbsent() throws Exception {
        interruptedDelete();when(lpa.profiles(any(JSONObject.class))).thenReturn(new JSONArray());bot.reconcile();
        verify(lpa,never()).delete(anyString(),anyString(),anyBoolean());verify(s).forgetNumber(Rules.profileKey(EID,ICCID));
        verify(s).enqueueNotice(startsWith("delete-result:"),eq(456L),contains("отсутствует на прежнем адаптере"));assertEquals("{}",values.get("last_delete"));
    }
    @Test public void interruptedDeleteDoesNotRetryOrRemoveBindingWhenProfileRemains() throws Exception {
        interruptedDelete();bot.reconcile();verify(lpa,never()).delete(anyString(),anyString(),anyBoolean());verify(s,never()).forgetNumber(anyString());
        verify(s).enqueueNotice(anyString(),eq(456L),contains("профиль всё ещё на карте"));
    }
    @Test public void wrongCardOrFailedProfileReadNeverProvesDeletion() throws Exception {
        interruptedDelete();when(lpa.card(anyString())).thenReturn(new JSONObject().put("eid","different"));bot.reconcile();
        when(lpa.card(anyString())).thenReturn(new JSONObject().put("eid",EID).put("slot",0));when(lpa.profiles(any(JSONObject.class))).thenThrow(new UserError("unreadable"));
        bot.reconcile();verify(s,never()).forgetNumber(anyString());verify(s,never()).enqueueNotice(anyString(),anyLong(),anyString());assertEquals(1,s.pendingDeletes().length());
    }
}
