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

/** Real bot flows: two cards deliberately share an ICCID and operator name. */
public class DualAdapterTest {
    static final String A="89000000000000000000000000000001",B="89000000000000000000000000000002",ICCID="8900000000000000001";
    Store s;LpaClient lpa;Telegram api;Bot bot;JSONArray cards;long update;
    Map<String,String> values=new HashMap<>(),numbers=new HashMap<>();
    Map<String,JSONArray> profiles=new HashMap<>();
    @Before public void setup() throws Exception {
        s=mock(Store.class);lpa=mock(LpaClient.class);api=mock(Telegram.class);bot=new Bot(s,lpa);
        when(s.get(anyString(),anyString())).thenAnswer(i->values.getOrDefault(i.getArgument(0),i.getArgument(1)));
        doAnswer(i->{values.put(i.getArgument(0),i.getArgument(1));return null;}).when(s).put(anyString(),anyString());
        TestStoreRouting.attach(s);doCallRealMethod().when(s).rememberCards(any(JSONArray.class));
        when(s.running()).thenReturn(true);when(s.epoch()).thenReturn("session");when(s.chat()).thenReturn(456L);
        when(s.chats()).thenReturn(java.util.Arrays.asList(456L,789L));when(s.beginOperation(anyLong())).thenReturn(true);
        when(s.number(anyString())).thenAnswer(i->numbers.getOrDefault(i.getArgument(0),"Номер не задан"));
        doAnswer(i->{numbers.put(i.getArgument(0),i.getArgument(1));return null;}).when(s).number(anyString(),anyString());
        doAnswer(i->{numbers.remove(i.getArgument(0));return null;}).when(s).forgetNumber(anyString());
        values.put("esim_control","true");cards=new JSONArray().put(card(A,0)).put(card(B,1));
        when(lpa.installed()).thenReturn(true);when(lpa.cards()).thenAnswer(i->cards);
        when(lpa.card(anyString())).thenCallRealMethod();
        when(lpa.profiles(any(JSONObject.class))).thenAnswer(i->new JSONArray(profiles.get(((JSONObject)i.getArgument(0)).getString("eid")).toString()));
        for(String eid:new String[]{A,B})profiles.put(eid,new JSONArray().put(new JSONObject().put("iccid",ICCID).put("enabled",false).put("provider","Билайн").put("nickname","Билайн")));
        numbers.put(Rules.profileKey(A,ICCID),"+79991111111");numbers.put(Rules.profileKey(B,ICCID),"+79992222222");
        doAnswer(i->{profiles.get(i.getArgument(0)).getJSONObject(0).put("enabled",true);return null;}).when(lpa).enable(anyString(),anyString());
    }
    static JSONObject card(String eid,int slot) throws Exception {return new JSONObject().put("eid",eid).put("slot",slot).put("port",0);}
    void send(String text,String callback,long chat) throws Exception {
        JSONObject from=new JSONObject().put("id",chat).put("is_bot",false);
        JSONObject m=new JSONObject().put("text",text).put("message_id",22).put("chat",new JSONObject().put("id",chat).put("type","private")).put("from",from);
        JSONObject u=new JSONObject().put("update_id",++update);
        if(callback==null)u.put("message",m);else u.put("callback_query",new JSONObject().put("id","cb"+update).put("message",m).put("from",from).put("data",callback));
        when(api.call(eq("getUpdates"),any())).thenReturn(new JSONObject().put("result",new JSONArray().put(u)));bot.poll(api,0);
    }
    void command(String text) throws Exception {send(text,null,456);}
    void tap(String data) throws Exception {send("",data,456);}
    String select(long chat) throws Exception {return "select:"+new JSONObject(values.get("profile_menu:"+chat)).getString("nonce")+":0";}
    String confirm() throws Exception {return "confirm:"+new JSONObject(values.get("draft:456")).getString("nonce");}
    void pickSecond() throws Exception {command("/esim");tap("card:"+B);tap(select(456));}
    @Test public void chooserDoesNotSilentlyPickFirstCard() throws Exception {
        command("/esim");verify(lpa,never()).profiles(any(JSONObject.class));
        verify(api).send(eq(456L),argThat(t->t.contains("слот 1")&&t.contains("слот 2")),argThat(k->k.toString().contains("card:"+A)&&k.toString().contains("card:"+B)));
        tap("card:"+B);verify(lpa).profiles(argThat(c->c.optString("eid").equals(B)));
        verify(api).call(eq("editMessageText"),argThat(p->p.optString("text").contains("+79992222222")&&!p.optString("text").contains("+79991111111")));
    }
    @Test public void switchWithIdenticalIccidsTargetsOnlySelectedCard() throws Exception {
        pickSecond();clearInvocations(s);tap(confirm());
        verify(lpa).enable(B,ICCID);verify(lpa,never()).enable(eq(A),anyString());
        verify(s).clearActive(1);verify(s,never()).clearActive(0);verify(s,never()).clearActive();
        assertFalse(s.switching(1));assertFalse(s.switching(0));
        verify(api).send(eq(456L),argThat(t->t.contains("+79992222222")&&t.contains("слот 2")),notNull());
    }
    @Test public void reorderedAndMovedAdaptersKeepTheirIdentity() throws Exception {
        pickSecond();cards=new JSONArray().put(card(B,0)).put(card(A,1));tap(confirm());
        verify(lpa).enable(B,ICCID);verify(lpa).refresh(eq(s),argThat(c->c.optString("eid").equals(B)&&c.optInt("slot")==0));
    }
    @Test public void unpluggedSelectionNeverFallsBackToOtherCard() throws Exception {
        pickSecond();cards=new JSONArray().put(card(A,0));tap(confirm());
        verify(lpa,never()).enable(anyString(),anyString());verify(api).send(eq(456L),contains("недоступен"),isNull());
    }
    @Test public void downloadRequiresDestinationAndKeepsManualNumberOnThatCard() throws Exception {
        command("/add");assertFalse(new JSONObject(values.get("draft:456")).has("stage"));
        tap("add:"+B);command("+79993333333");command("LPA:1$example.test$second-card");
        when(lpa.download(B,"LPA:1$example.test$second-card","")).thenReturn(new JSONObject().put("eid",B).put("iccid",ICCID));tap(confirm());
        verify(lpa).download(B,"LPA:1$example.test$second-card","");verify(lpa,never()).download(eq(A),anyString(),anyString());
        assertEquals("+79993333333",numbers.get(Rules.profileKey(B,ICCID)));assertEquals("+79991111111",numbers.get(Rules.profileKey(A,ICCID)));
    }
    @Test public void deletionOnlyRemovesSelectedCardsNumber() throws Exception {
        pickSecond();String nonce=new JSONObject(values.get("draft:456")).getString("nonce");tap("delete:"+nonce);
        when(lpa.delete(B,ICCID,false)).thenReturn(new JSONObject().put("success",true));tap(confirm());
        verify(lpa).delete(B,ICCID,false);verify(lpa,never()).delete(eq(A),anyString(),anyBoolean());
        assertFalse(numbers.containsKey(Rules.profileKey(B,ICCID)));assertEquals("+79991111111",numbers.get(Rules.profileKey(A,ICCID)));
        assertEquals(0,s.pendingDeletes().length());
    }
    @Test public void oldProfileButtonCannotSelectProfileOnNewCard() throws Exception {
        command("/esim");tap("card:"+A);String old=select(456);tap("card:"+B);tap(old);
        verify(api).send(eq(456L),contains("Обнови список профилей"),isNull());verify(lpa,never()).enable(anyString(),anyString());
    }
    @Test public void authorizedUsersKeepIndependentSelections() throws Exception {
        command("/esim");tap("card:"+B);send("","card:"+A,789);
        tap(select(456));tap(confirm());verify(lpa).enable(B,ICCID);
        assertEquals(B,values.get("selected_adapter:456"));assertEquals(A,values.get("selected_adapter:789"));
    }
    @Test public void unreadableFirstCardDoesNotClearSecondCardsActiveProfile() throws Exception {
        profiles.get(B).getJSONObject(0).put("enabled",true);
        when(lpa.profiles(argThat(c->c!=null&&c.optString("eid").equals(A)))).thenThrow(new UserError("Нет ответа первой карты"));
        doCallRealMethod().when(lpa).refresh(s);doCallRealMethod().when(lpa).refresh(eq(s),any(JSONObject.class));
        lpa.refresh(s);verify(s).active(1,Rules.profileKey(B,ICCID));verify(s,never()).clearActive();assertTrue(values.get("esim_refresh_error").contains("Слот 1"));
    }
    @Test public void unavailablePendingDeletionDoesNotHideRecoveryOnSecondCard() throws Exception {
        for(String eid:new String[]{A,B})s.rememberDelete(new JSONObject().put("eid",eid).put("iccid",ICCID).put("key",Rules.profileKey(eid,ICCID)).put("nonce",eid).put("epoch","session").put("chat_id",456));
        cards=new JSONArray().put(card(B,1));profiles.put(B,new JSONArray());bot.reconcile();
        assertEquals(1,s.pendingDeletes().length());assertEquals(A,s.pendingDeletes().getJSONObject(0).getString("eid"));
        verify(s).forgetNumber(Rules.profileKey(B,ICCID));verify(s,never()).forgetNumber(Rules.profileKey(A,ICCID));
        verify(lpa,never()).delete(anyString(),anyString(),anyBoolean());
    }
}
