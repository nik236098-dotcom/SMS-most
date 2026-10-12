package ru.smsbridge.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Batch install: number + QR pairs are collected first, then installed one by one. */
public class BatchInstallTest {
    static final long CHAT=456L;
    static final String EID="89000000000000000000000000000001";
    Store store;Telegram api;LpaClient adapter;Bot bot;
    Map<String,String> settings,numbers;JSONArray profiles;long update;int downloads;
    @Before public void setup() throws Exception {
        store=mock(Store.class);api=mock(Telegram.class);adapter=mock(LpaClient.class);
        settings=new HashMap<>();numbers=new HashMap<>();profiles=new JSONArray();
        settings.put("esim_control","true");
        when(store.running()).thenReturn(true);when(store.epoch()).thenReturn("session");
        when(store.chat()).thenReturn(CHAT);when(store.chats()).thenReturn(java.util.Collections.singletonList(CHAT));
        when(store.beginOperation(anyLong())).thenReturn(true);
        when(store.get(anyString(),anyString())).thenAnswer(i->settings.getOrDefault(i.getArgument(0),i.getArgument(1)));
        doAnswer(i->{settings.put(i.getArgument(0),i.getArgument(1));return null;}).when(store).put(anyString(),anyString());
        when(store.number(anyString())).thenAnswer(i->numbers.getOrDefault(i.getArgument(0),"Номер не задан"));
        doAnswer(i->{numbers.put(i.getArgument(0),i.getArgument(1));return null;}).when(store).number(anyString(),anyString());
        when(adapter.installed()).thenReturn(true);
        when(adapter.card(anyString())).thenAnswer(i->new JSONObject().put("slot",0).put("port",0).put("eid",EID));
        when(adapter.cards()).thenAnswer(i->new JSONArray().put(adapter.card(EID)));
        when(adapter.profiles(any(JSONObject.class))).thenAnswer(i->new JSONArray(profiles.toString()));
        when(adapter.download(eq(EID),anyString(),anyString())).thenAnswer(i->{
            String iccid="89000000000000"+String.format(java.util.Locale.ROOT,"%05d",++downloads);
            profiles.put(new JSONObject().put("iccid",iccid).put("enabled",false));return new JSONObject().put("iccid",iccid).put("eid",EID);
        });
        TestStoreRouting.attach(store);bot=new Bot(store,adapter);
    }
    private void send(JSONObject message,String callback) throws Exception {
        JSONObject from=new JSONObject().put("id",CHAT).put("is_bot",false);
        message.put("message_id",22).put("from",from).put("chat",new JSONObject().put("id",CHAT).put("type","private"));
        JSONObject item=new JSONObject().put("update_id",++update);
        if(callback==null)item.put("message",message);
        else item.put("callback_query",new JSONObject().put("id","cb"+update).put("data",callback).put("from",from).put("message",message));
        when(api.call(eq("getUpdates"),any())).thenReturn(new JSONObject().put("result",new JSONArray().put(item)));
        bot.poll(api,0);
    }
    private void text(String text) throws Exception {send(new JSONObject().put("text",text),null);}
    private void tap(String data) throws Exception {send(new JSONObject().put("text",""),data);}
    private JSONObject draft() throws Exception {return new JSONObject(settings.get("draft:"+CHAT));}
    private String confirm() throws Exception {return "confirm:"+draft().getString("nonce");}
    private String key(int n){return Rules.profileKey(EID,"89000000000000"+String.format(java.util.Locale.ROOT,"%05d",n));}
    private void collectTwo() throws Exception {
        text("/batch");text("+7 999 000-00-01");text("LPA:1$one.example.test$FIRST");text("8 (999) 000-00-02");text("LPA:1$two.example.test$SECOND");
    }
    @Test public void collectsPairsThenInstallsAllInOrderWithoutSwitching() throws Exception {
        collectTwo();
        verify(adapter,never()).download(anyString(),anyString(),anyString());
        assertEquals(2,draft().getJSONArray("items").length());
        verify(api).send(eq(CHAT),contains("eSIM №2 добавлена: +79990000002"),argThat(k->k.toString().contains("Завершить и установить (2)")));
        tap("batch:done");assertEquals("confirm_batch",draft().getString("stage"));
        verify(adapter,never()).download(anyString(),anyString(),anyString());
        tap(confirm());
        InOrder order=inOrder(adapter);
        order.verify(adapter).download(EID,"LPA:1$one.example.test$FIRST","");order.verify(adapter).download(EID,"LPA:1$two.example.test$SECOND","");
        verify(adapter,never()).enable(any(JSONObject.class),anyString());
        assertEquals("+79990000001",numbers.get(key(1)));assertEquals("+79990000002",numbers.get(key(2)));
        verify(api).send(eq(CHAT),contains("Массовая установка завершена: 2 из 2"),notNull());
        verify(store).enqueueNotice(contains(":0"),eq(CHAT),startsWith("✅ 1/2 +79990000001"));
        assertEquals("{}",settings.get("last_install"));
    }
    @Test public void failedItemIsReportedAndOthersContinueButNothingIsReplayed() throws Exception {
        when(adapter.download(EID,"LPA:1$one.example.test$FIRST","")).thenThrow(new UserError("Код уже использован"));
        collectTwo();tap("batch:done");String confirm=confirm();tap(confirm);tap(confirm);
        verify(adapter,times(1)).download(EID,"LPA:1$one.example.test$FIRST","");verify(adapter,times(1)).download(EID,"LPA:1$two.example.test$SECOND","");
        verify(api).send(eq(CHAT),argThat(t->t.contains("1 из 2")&&t.contains("⚠️ 1/2 +79990000001: Код уже использован")&&t.contains("повторно не использовались")),notNull());
        assertTrue(numbers.containsValue("+79990000002"));assertFalse(numbers.containsValue("+79990000001"));
    }
    @Test public void duplicateQrIsRejected() throws Exception {
        text("/batch");text("+79990000001");text("LPA:1$one.example.test$FIRST");text("+79990000002");text("LPA:1$one.example.test$FIRST");
        verify(api).send(eq(CHAT),contains("уже добавлен"),isNull());assertEquals(1,draft().getJSONArray("items").length());
    }
    @Test public void photoWithNumberInCaptionAddsOneItem() throws Exception {
        byte[] image={1};when(api.file("qr")).thenReturn(image);text("/batch");
        try(MockedStatic<QrImage> images=mockStatic(QrImage.class)) {
            images.when(()->QrImage.decode(image)).thenReturn("LPA:1$photo.example.test$CODE");
            send(new JSONObject().put("caption","+7 999 000 00 05").put("photo",new JSONArray().put(new JSONObject().put("file_id","qr"))),null);
        }
        JSONObject item=draft().getJSONArray("items").getJSONObject(0);
        assertEquals("+79990000005",item.getString("number"));assertEquals("LPA:1$photo.example.test$CODE",item.getString("code"));
    }
    @Test public void requiredOperatorPinBlocksInstallUntilProvided() throws Exception {
        text("/batch");text("+79990000001");text("LPA:1$pin.example.test$CODE$$1");
        verify(api).send(eq(CHAT),contains("пришли /pin"),notNull());
        tap("batch:done");tap(confirm());verify(adapter,never()).download(anyString(),anyString(),anyString());
        verify(api).send(eq(CHAT),contains("требует код подтверждения для: +79990000001"),isNull());
        tap("batch:more");text("/pin 1234");tap("batch:done");tap(confirm());
        verify(adapter).download(EID,"LPA:1$pin.example.test$CODE$$1","1234");
    }
    @Test public void undoRemovesLastAndCancelInstallsNothing() throws Exception {
        collectTwo();tap("batch:undo");assertEquals(1,draft().getJSONArray("items").length());
        tap("batch:done");String confirm=confirm();tap("cancel");tap(confirm);
        verify(adapter,never()).download(anyString(),anyString(),anyString());
    }
    @Test public void waitingForQrRejectsAnotherNumber() throws Exception {
        text("/batch");text("+79990000001");text("+79990000002");
        verify(api).send(eq(CHAT),contains("Жду QR-код для номера +79990000001"),isNull());
        assertEquals("batch_qr",draft().getString("stage"));
    }
}
