package ru.smsbridge.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Drives the real bot through profile navigation and switching with a simulated card. */
public class EsimProfilesTest {
    static final long CHAT=456L;
    static final String EID="89000000000000000000000000000001";
    Store store;Telegram api;LpaClient adapter;Bot bot;
    Map<String,String> settings,numbers;JSONArray profiles;long update;
    @Before public void setup() throws Exception {
        store=mock(Store.class);api=mock(Telegram.class);adapter=mock(LpaClient.class);
        settings=new HashMap<>();numbers=new HashMap<>();profiles=new JSONArray();
        settings.put("esim_control","true");
        when(store.running()).thenReturn(true);when(store.epoch()).thenReturn("session");
        when(store.chat()).thenReturn(CHAT);when(store.chats()).thenReturn(java.util.Arrays.asList(CHAT,789L));
        when(store.beginOperation(anyLong())).thenReturn(true);
        when(store.get(anyString(),anyString())).thenAnswer(i->settings.getOrDefault(i.getArgument(0),i.getArgument(1)));
        doAnswer(i->{settings.put(i.getArgument(0),i.getArgument(1));return null;}).when(store).put(anyString(),anyString());
        when(store.number(anyString())).thenAnswer(i->numbers.getOrDefault(i.getArgument(0),"Номер не задан"));
        doAnswer(i->{numbers.put(i.getArgument(0),i.getArgument(1));return null;}).when(store).number(anyString(),anyString());
        when(adapter.installed()).thenReturn(true);
        when(adapter.card()).thenAnswer(i->new JSONObject().put("slot",0).put("port",0).put("eid",EID));
        when(adapter.profiles(0,0)).thenAnswer(i->new JSONArray(profiles.toString()));
        doAnswer(i->{String iccid=i.getArgument(0);for(int j=0;j<profiles.length();j++) {
            JSONObject p=profiles.getJSONObject(j);p.put("enabled",p.getString("iccid").equals(iccid));
        }return null;}).when(adapter).enable(anyString());
        bot=new Bot(store,adapter);
        add(1,true);add(2,false);
    }
    private String iccid(int n){return "890000000000000"+String.format(java.util.Locale.ROOT,"%04d",n);}
    private void add(int n,boolean active) throws Exception {
        profiles.put(new JSONObject().put("iccid",iccid(n)).put("enabled",active).put("provider","Operator "+n).put("nickname","Line "+n));
    }
    private void send(String text,String callback,long chat) throws Exception {
        JSONObject from=new JSONObject().put("id",chat).put("is_bot",false);
        JSONObject message=new JSONObject().put("message_id",22).put("text",text).put("from",from)
            .put("chat",new JSONObject().put("id",chat).put("type","private"));
        JSONObject item=new JSONObject().put("update_id",++update);
        if(callback==null)item.put("message",message);
        else item.put("callback_query",new JSONObject().put("id","cb"+update).put("data",callback).put("from",from).put("message",message));
        when(api.call(eq("getUpdates"),any())).thenReturn(new JSONObject().put("result",new JSONArray().put(item)));
        bot.poll(api,0);
    }
    private void command(String text) throws Exception {send(text,null,CHAT);}
    private void tap(String data) throws Exception {send("",data,CHAT);}
    private String choice(int index,long chat) throws Exception {
        return "select:"+new JSONObject(settings.get("profile_menu:"+chat)).getString("nonce")+":"+index;
    }
    private String confirmation() throws Exception {
        return "confirm:"+new JSONObject(settings.get("draft:"+CHAT)).getString("nonce");
    }
    private JSONObject lastEdit() throws Exception {
        ArgumentCaptor<JSONObject> c=ArgumentCaptor.forClass(JSONObject.class);
        verify(api,atLeastOnce()).call(eq("editMessageText"),c.capture());
        return c.getValue();
    }
    @Test public void mainMenuExposesProfilesWithoutReadingCard() throws Exception {
        command("/menu");verify(api).send(eq(CHAT),anyString(),argThat(k->k.toString().contains("Профили 9eSIM")));
        verifyNoInteractions(adapter);
    }
    @Test public void listShowsInstalledProfilesActiveStateNumbersAndIccid() throws Exception {
        numbers.put(Rules.profileKey(EID,iccid(1)),"+79991234567");command("/esim");
        verify(api).send(eq(CHAT),argThat(t->t.contains("Всего: 2")&&t.contains("🟢 Активен: +79991234567")
            &&t.contains("Line 2 — выключен")&&t.contains("Оператор: Operator 2")&&t.contains("ICCID: …000002")),
            argThat(k->k.toString().contains("Обновить список")&&k.toString().contains("select:")));
    }
    @Test public void aliasAndMainButtonOpenProfiles() throws Exception {
        command("/9esim");tap("adapter");assertTrue(lastEdit().getString("text").contains("Всего: 2"));
    }
    @Test public void largeListHasBoundedPagesAndFreshRefresh() throws Exception {
        for(int i=3;i<=19;i++)add(i,false);command("/esim");
        verify(api).send(eq(CHAT),argThat(t->t.length()<4096&&t.contains("Страница 1 из 3")&&!t.contains("Line 9 —")),
            argThat(k->k.toString().contains("adapter:1")));
        String stale=choice(0,CHAT);tap("adapter:2");
        JSONObject output=lastEdit();assertTrue(output.getString("text").contains("Line 19"));
        assertTrue(output.getString("text").contains("🟢 Активен:"));
        assertFalse(output.getString("text").contains("Line 8 —"));
        JSONArray rows=output.getJSONObject("reply_markup").getJSONArray("inline_keyboard");
        for(int i=0;i<rows.length();i++)for(int j=0;j<rows.getJSONArray(i).length();j++)
            assertTrue(rows.getJSONArray(i).getJSONObject(j).getString("callback_data").getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=64);
        tap(stale);verify(api).send(eq(CHAT),contains("Обнови список профилей"),isNull());
        verify(adapter,never()).enable(anyString());
        profiles=new JSONArray();tap("adapter:2");assertTrue(lastEdit().getString("text").contains("пока нет профилей"));
    }
    @Test public void switchUsesExactIccidAfterListOrderChanges() throws Exception {
        numbers.put(Rules.profileKey(EID,iccid(2)),"+79992222222");command("/esim");String selected=choice(1,CHAT);
        profiles=new JSONArray().put(profiles.getJSONObject(1)).put(profiles.getJSONObject(0));tap(selected);
        assertTrue(lastEdit().getString("text").contains(iccid(2)));tap(confirmation());
        verify(adapter).enable(iccid(2));verify(adapter).refresh(store);
        verify(api).send(eq(CHAT),contains("Активирован профиль +79992222222"),notNull());
        assertEquals("false",settings.get("switching"));
    }
    @Test public void unnamedProfileCanSwitchWithoutInventingNumber() throws Exception {
        command("/esim");tap(choice(1,CHAT));tap(confirmation());
        verify(adapter).enable(iccid(2));verify(api).send(eq(CHAT),contains("Номер пока не задан"),notNull());
        verify(store,never()).number(anyString(),anyString());
    }
    @Test public void activeProfileDoesNotOfferRedundantSwitch() throws Exception {
        command("/esim");tap(choice(0,CHAT));JSONObject output=lastEdit();
        assertTrue(output.getString("text").contains("уже активен"));
        assertFalse(output.getJSONObject("reply_markup").toString().contains("confirm:"));
        verify(adapter,never()).enable(anyString());
    }
    @Test public void profileActivatedElsewhereBeforeConfirmIsNotSwitchedAgain() throws Exception {
        command("/esim");tap(choice(1,CHAT));profiles.getJSONObject(1).put("enabled",true);
        profiles.getJSONObject(0).put("enabled",false);tap(confirmation());
        verify(adapter,never()).enable(anyString());assertTrue(lastEdit().getString("text").contains("уже активен"));
    }
    @Test public void repeatedConfirmationSwitchesOnlyOnce() throws Exception {
        command("/esim");tap(choice(1,CHAT));String confirm=confirmation();tap(confirm);tap(confirm);
        verify(adapter,times(1)).enable(iccid(2));
    }
    @Test public void removedProfileCannotBeSwitchedFromStaleCard() throws Exception {
        command("/esim");tap(choice(1,CHAT));profiles=new JSONArray().put(profiles.getJSONObject(0));tap(confirmation());
        verify(adapter,never()).enable(anyString());verify(api).send(eq(CHAT),contains("Профиль больше не найден"),isNull());
    }
    @Test public void changedAdapterCannotUseOldSelection() throws Exception {
        command("/esim");when(adapter.card()).thenReturn(new JSONObject().put("eid","different"));tap(choice(1,CHAT));
        verify(adapter,never()).enable(anyString());verify(api).send(eq(CHAT),contains("Адаптер изменился"),isNull());
    }
    @Test public void expiredListAndRevokedControlCannotSwitch() throws Exception {
        command("/esim");String selection=choice(1,CHAT);
        JSONObject cache=new JSONObject(settings.get("profile_menu:"+CHAT)).put("expires",1);
        settings.put("profile_menu:"+CHAT,cache.toString());tap(selection);
        verify(api).send(eq(CHAT),contains("Обнови список профилей"),isNull());
        command("/esim");tap(choice(1,CHAT));settings.put("esim_control","false");tap(confirmation());
        verify(adapter,never()).enable(anyString());verify(api).send(eq(CHAT),contains("Управление адаптером выключено"),isNull());
    }
    @Test public void unconfirmedSwitchNeverClaimsSuccess() throws Exception {
        doNothing().when(adapter).enable(anyString());command("/esim");tap(choice(1,CHAT));tap(confirmation());
        verify(api,never()).send(anyLong(),contains("Активирован профиль"),any());
        verify(api).send(eq(CHAT),contains("активация не подтверждена"),isNull());
        assertEquals("true",settings.get("switching"));
    }
    @Test public void adapterFailureNeverClaimsSuccess() throws Exception {
        doThrow(new UserError("Переключение не подтверждено")).when(adapter).enable(anyString());
        command("/esim");tap(choice(1,CHAT));tap(confirmation());
        verify(api,never()).send(anyLong(),contains("Активирован профиль"),any());
        verify(api).send(eq(CHAT),contains("Переключение не подтверждено"),isNull());
    }
    @Test public void manualNumberStaysAttachedToEidAndIccidAndReturnsToProfiles() throws Exception {
        command("/esim");tap(choice(1,CHAT));tap("rename");command("+79993333333");
        verify(store).number(Rules.profileKey(EID,iccid(2)),"+79993333333");
        verify(api).send(eq(CHAT),argThat(t->t.startsWith("Профили 9eSIM")&&t.contains("+79993333333")),notNull());
    }
    @Test public void menusForMultipleAllowedUsersDoNotOverwriteEachOther() throws Exception {
        command("/esim");String selection=choice(1,CHAT);send("/esim",null,789L);tap(selection);
        assertTrue(lastEdit().getString("text").contains(iccid(2)));
        assertEquals(CHAT,lastEdit().getLong("chat_id"));
        assertNotEquals(settings.get("profile_menu:"+CHAT),settings.get("profile_menu:789"));
    }
    @Test public void unknownUserCannotReadOrSwitchProfiles() throws Exception {
        send("/esim",null,999L);send("","adapter",999L);verifyNoInteractions(adapter);
        verify(api,never()).send(anyLong(),anyString(),any());
    }
}
