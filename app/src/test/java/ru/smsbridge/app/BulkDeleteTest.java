package ru.smsbridge.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Bulk deletion: only inactive profiles, explicit confirmation, journaled one by one. */
public class BulkDeleteTest {
    static final long CHAT=456L;
    static final String EID="89000000000000000000000000000001";
    Store store;Telegram api;LpaClient adapter;Bot bot;Map<String,String> settings,numbers;JSONArray profiles;long update;
    String iccid(int n){return "890000000000000"+String.format(java.util.Locale.ROOT,"%04d",n);}
    String key(int n){return Rules.profileKey(EID,iccid(n));}
    @Before public void setup() throws Exception {
        store=mock(Store.class);api=mock(Telegram.class);adapter=mock(LpaClient.class);settings=new HashMap<>();numbers=new HashMap<>();profiles=new JSONArray();
        settings.put("esim_control","true");
        when(store.running()).thenReturn(true);when(store.epoch()).thenReturn("session");
        when(store.chat()).thenReturn(CHAT);when(store.chats()).thenReturn(java.util.Collections.singletonList(CHAT));when(store.beginOperation(anyLong())).thenReturn(true);
        when(store.get(anyString(),anyString())).thenAnswer(i->settings.getOrDefault(i.getArgument(0),i.getArgument(1)));
        doAnswer(i->{settings.put(i.getArgument(0),i.getArgument(1));return null;}).when(store).put(anyString(),anyString());
        when(store.number(anyString())).thenAnswer(i->numbers.getOrDefault(i.getArgument(0),"Номер не задан"));
        doAnswer(i->{numbers.remove(i.getArgument(0));return null;}).when(store).forgetNumber(anyString());
        when(adapter.installed()).thenReturn(true);
        when(adapter.card(anyString())).thenAnswer(i->new JSONObject().put("slot",0).put("port",0).put("eid",EID));
        when(adapter.cards()).thenAnswer(i->new JSONArray().put(adapter.card(EID)));
        when(adapter.profiles(any(JSONObject.class))).thenAnswer(i->new JSONArray(profiles.toString()));
        when(adapter.delete(eq(EID),anyString(),anyBoolean())).thenAnswer(i->{
            String id=i.getArgument(1);JSONArray rest=new JSONArray();
            for(int j=0;j<profiles.length();j++)if(!profiles.getJSONObject(j).getString("iccid").equals(id))rest.put(profiles.getJSONObject(j));
            profiles=rest;return new JSONObject().put("success",true);
        });
        TestStoreRouting.attach(store);bot=new Bot(store,adapter);
        for(int n=1;n<=3;n++){profiles.put(new JSONObject().put("iccid",iccid(n)).put("enabled",n==1).put("provider","Op "+n));numbers.put(key(n),"+7999000000"+n);}
    }
    void send(String text,String callback) throws Exception {
        JSONObject from=new JSONObject().put("id",CHAT).put("is_bot",false);
        JSONObject m=new JSONObject().put("message_id",22).put("text",text).put("from",from).put("chat",new JSONObject().put("id",CHAT).put("type","private"));
        JSONObject u=new JSONObject().put("update_id",++update);
        if(callback==null)u.put("message",m);else u.put("callback_query",new JSONObject().put("id","cb"+update).put("data",callback).put("from",from).put("message",m));
        when(api.call(eq("getUpdates"),any())).thenReturn(new JSONObject().put("result",new JSONArray().put(u)));bot.poll(api,0);
    }
    void tap(String data) throws Exception {send("",data);}
    JSONObject draft() throws Exception {return new JSONObject(settings.get("draft:"+CHAT));}
    String toggle(int i) throws Exception {return "bd:"+draft().getString("nonce")+":"+i;}
    String confirm() throws Exception {return "confirm:"+draft().getString("nonce");}
    @Test public void onlyInactiveProfilesAreOfferedAndSelectedOnesAreDeletedInOrder() throws Exception {
        send("/bulkdelete",null);
        verify(api).send(eq(CHAT),argThat(t->t.contains("+79990000002")&&t.contains("+79990000003")&&!t.contains("+79990000001")&&t.contains("Активные профили")),notNull());
        assertEquals(2,draft().getJSONArray("entries").length());
        tap("bd:all");tap("bd:go");verify(adapter,never()).delete(anyString(),anyString(),anyBoolean());
        tap(confirm());
        InOrder order=inOrder(adapter);order.verify(adapter).delete(EID,iccid(2),false);order.verify(adapter).delete(EID,iccid(3),false);
        verify(adapter,never()).delete(EID,iccid(1),false);verify(adapter,never()).delete(anyString(),anyString(),eq(true));
        assertFalse(numbers.containsKey(key(2)));assertFalse(numbers.containsKey(key(3)));assertEquals("+79990000001",numbers.get(key(1)));
        verify(api).send(eq(CHAT),contains("Массовое удаление завершено: 2 из 2"),notNull());
        assertEquals(0,store.pendingDeletes().length());
    }
    @Test public void toggleSelectsOnlyChosenProfile() throws Exception {
        send("/bulkdelete",null);tap(toggle(1));tap("bd:go");tap(confirm());
        verify(adapter).delete(EID,iccid(3),false);verify(adapter,never()).delete(EID,iccid(2),false);
    }
    @Test public void profileThatBecameActiveIsSkipped() throws Exception {
        send("/bulkdelete",null);tap("bd:all");tap("bd:go");profiles.getJSONObject(1).put("enabled",true);tap(confirm());
        verify(adapter,never()).delete(EID,iccid(2),false);verify(adapter).delete(EID,iccid(3),false);
        verify(api).send(eq(CHAT),argThat(t->t.contains("1 из 2")&&t.contains("стал активным")),notNull());assertEquals("+79990000002",numbers.get(key(2)));
    }
    @Test public void failedDeletionKeepsNumberAndJournalAndOthersContinue() throws Exception {
        doReturn(new JSONObject().put("success",false)).when(adapter).delete(EID,iccid(2),false);
        send("/bulkdelete",null);tap("bd:all");tap("bd:go");String confirm=confirm();tap(confirm);tap(confirm);
        verify(adapter,times(1)).delete(EID,iccid(3),false);assertEquals("+79990000002",numbers.get(key(2)));
        assertEquals(1,store.pendingDeletes().length());assertEquals(iccid(2),store.pendingDeletes().getJSONObject(0).getString("iccid"));
    }
    @Test public void cancelInvalidatesConfirmation() throws Exception {
        send("/bulkdelete",null);tap("bd:all");tap("bd:go");String confirm=confirm();tap("cancel");tap(confirm);
        verify(adapter,never()).delete(anyString(),anyString(),anyBoolean());
    }
    @Test public void onlyActiveProfileIsExplained() throws Exception {
        profiles=new JSONArray().put(profiles.getJSONObject(0));send("/bulkdelete",null);
        verify(api).send(eq(CHAT),contains("Неактивных профилей нет"),isNull());
    }
}
