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
    private JSONObject selectedCard(){return argThat(c->c!=null&&EID.equals(c.optString("eid")));}
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
        when(adapter.card(anyString())).thenAnswer(i->new JSONObject().put("slot",0).put("port",0).put("eid",EID));
        when(adapter.profiles(any(JSONObject.class))).thenAnswer(i->new JSONArray(profiles.toString()));
        doAnswer(i->{String iccid=i.getArgument(1);for(int j=0;j<profiles.length();j++) {
            JSONObject p=profiles.getJSONObject(j);p.put("enabled",p.getString("iccid").equals(iccid));
        }return new JSONArray(profiles.toString());}).when(adapter).enable(selectedCard(),anyString());
        when(adapter.cards()).thenAnswer(i->new JSONArray().put(adapter.card(EID)));
        doAnswer(i->{store.switching(0,false);return null;}).when(adapter).refresh(store);
        TestStoreRouting.attach(store);
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
        verify(adapter,never()).enable(selectedCard(),anyString());
        profiles=new JSONArray();tap("adapter:2");assertTrue(lastEdit().getString("text").contains("пока нет профилей"));
    }
    @Test public void switchUsesExactIccidAfterListOrderChanges() throws Exception {
        numbers.put(Rules.profileKey(EID,iccid(2)),"+79992222222");command("/esim");String selected=choice(1,CHAT);
        profiles=new JSONArray().put(profiles.getJSONObject(1)).put(profiles.getJSONObject(0));tap(selected);
        assertTrue(lastEdit().getString("text").contains(iccid(2)));tap(confirmation());
        verify(adapter).enable(selectedCard(),eq(iccid(2)));verify(adapter).refresh(eq(store),any(JSONObject.class),any(JSONArray.class));
        verify(api).send(eq(CHAT),contains("Активирован профиль +79992222222"),notNull());
        assertEquals("false",settings.get("switching:0"));
    }
    @Test public void unnamedProfileCanSwitchWithoutInventingNumber() throws Exception {
        command("/esim");tap(choice(1,CHAT));tap(confirmation());
        verify(adapter).enable(selectedCard(),eq(iccid(2)));verify(api).send(eq(CHAT),contains("Номер пока не задан"),notNull());
        verify(store,never()).number(anyString(),anyString());
    }
    @Test public void verifiedSwitchDoesNotWaitForAnotherScanOrProfileRead() throws Exception {
        command("/esim");tap(choice(1,CHAT));clearInvocations(adapter);
        doAnswer(i->{
            JSONArray confirmed=new JSONArray().put(new JSONObject().put("iccid",iccid(2)).put("enabled",true));
            when(adapter.card(anyString())).thenThrow(new UserError("Unnecessary scan blocked"));
            when(adapter.profiles(any(JSONObject.class))).thenThrow(new UserError("Unnecessary reread blocked"));
            return confirmed;
        }).when(adapter).enable(selectedCard(),eq(iccid(2)));
        tap(confirmation());verify(api).send(eq(CHAT),contains("Активирован профиль"),notNull());
        verify(adapter,times(1)).card(EID);verify(adapter,times(1)).profiles(any(JSONObject.class));
        verify(adapter).refresh(eq(store),selectedCard(),argThat(ps->ps.length()==1&&ps.optJSONObject(0).optBoolean("enabled")));
    }
    @Test public void activeProfileDoesNotOfferRedundantSwitch() throws Exception {
        command("/esim");tap(choice(0,CHAT));JSONObject output=lastEdit();
        assertTrue(output.getString("text").contains("уже активен"));
        assertFalse(output.getJSONObject("reply_markup").toString().contains("confirm:"));
        verify(adapter,never()).enable(selectedCard(),anyString());
    }
    @Test public void profileActivatedElsewhereBeforeConfirmIsNotSwitchedAgain() throws Exception {
        command("/esim");tap(choice(1,CHAT));profiles.getJSONObject(1).put("enabled",true);
        profiles.getJSONObject(0).put("enabled",false);tap(confirmation());
        verify(adapter,never()).enable(selectedCard(),anyString());assertTrue(lastEdit().getString("text").contains("уже активен"));
    }
    @Test public void repeatedConfirmationSwitchesOnlyOnce() throws Exception {
        command("/esim");tap(choice(1,CHAT));String confirm=confirmation();tap(confirm);tap(confirm);
        verify(adapter,times(1)).enable(selectedCard(),eq(iccid(2)));
    }
    @Test public void removedProfileCannotBeSwitchedFromStaleCard() throws Exception {
        command("/esim");tap(choice(1,CHAT));profiles=new JSONArray().put(profiles.getJSONObject(0));tap(confirmation());
        verify(adapter,never()).enable(selectedCard(),anyString());verify(api).send(eq(CHAT),contains("Профиль больше не найден"),isNull());
    }
    @Test public void changedAdapterCannotUseOldSelection() throws Exception {
        command("/esim");when(adapter.card(anyString())).thenReturn(new JSONObject().put("eid","different"));tap(choice(1,CHAT));
        verify(adapter,never()).enable(selectedCard(),anyString());verify(api).send(eq(CHAT),contains("Адаптер изменился"),isNull());
    }
    @Test public void expiredListAndRevokedControlCannotSwitch() throws Exception {
        command("/esim");String selection=choice(1,CHAT);
        JSONObject cache=new JSONObject(settings.get("profile_menu:"+CHAT)).put("expires",1);
        settings.put("profile_menu:"+CHAT,cache.toString());tap(selection);
        verify(api).send(eq(CHAT),contains("Обнови список профилей"),isNull());
        command("/esim");tap(choice(1,CHAT));settings.put("esim_control","false");tap(confirmation());
        verify(adapter,never()).enable(selectedCard(),anyString());verify(api).send(eq(CHAT),contains("Управление адаптером выключено"),isNull());
    }
    @Test public void unconfirmedSwitchNeverClaimsSuccess() throws Exception {
        doAnswer(i->new JSONArray(profiles.toString())).when(adapter).enable(selectedCard(),anyString());command("/esim");tap(choice(1,CHAT));tap(confirmation());
        verify(api,never()).send(anyLong(),contains("Активирован профиль"),any());
        verify(api).send(eq(CHAT),contains("активация не подтверждена"),isNull());
        assertEquals("true",settings.get("switching:0"));
    }
    @Test public void adapterFailureNeverClaimsSuccess() throws Exception {
        doThrow(new UserError("Переключение не подтверждено")).when(adapter).enable(selectedCard(),anyString());
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
    private String deletion() throws Exception {return "delete:"+new JSONObject(settings.get("draft:"+CHAT)).getString("nonce");}
    private void chooseDelete(int index) throws Exception {command("/esim");tap(choice(index,CHAT));tap(deletion());}
    private void successfulDelete() throws Exception {
        when(adapter.delete(eq(EID),anyString(),anyBoolean())).thenAnswer(i->{
            String id=i.getArgument(1);JSONArray remaining=new JSONArray();
            for(int j=0;j<profiles.length();j++)if(!profiles.getJSONObject(j).getString("iccid").equals(id))remaining.put(profiles.getJSONObject(j));
            profiles=remaining;return new JSONObject().put("success",true);
        });
        doAnswer(i->{numbers.remove(i.getArgument(0));return null;}).when(store).forgetNumber(anyString());
    }
    @Test public void listShowsFreeMemoryWithoutImposingProfileCountLimit() throws Exception {
        when(adapter.info(any())).thenReturn(new JSONObject().put("free_nvram_bytes",65536));
        add(3,false);add(4,false);command("/esim");
        verify(api).send(eq(CHAT),contains("65536 байт"),notNull());
        command("/add");command("+79991234567");command("LPA:1$example.com$sample-token");
        assertEquals("confirm_add",new JSONObject(settings.get("draft:"+CHAT)).getString("stage"));
        verify(api).send(eq(CHAT),contains("QR-код получен"),notNull());
        when(adapter.download(eq(EID),anyString(),anyString())).thenAnswer(i->{add(5,false);return new JSONObject().put("iccid",iccid(5)).put("eid",EID);});
        tap(confirmation());verify(adapter).download(EID,"LPA:1$example.com$sample-token","");
        assertEquals(5,profiles.length());verify(adapter).enable(selectedCard(),eq(iccid(5)));
    }
    @Test public void unavailableMemoryDoesNotHideProfiles() throws Exception {
        when(adapter.info(any())).thenThrow(new UserError("No info"));command("/esim");
        verify(api).send(eq(CHAT),argThat(t->t.contains("карта не сообщила")&&t.contains("Line 2")),notNull());
    }
    @Test public void openingDeletionRequiresSeparateConfirmation() throws Exception {
        chooseDelete(1);assertTrue(lastEdit().getString("text").contains(iccid(2)));
        assertTrue(lastEdit().getString("text").contains("Старый QR-код"));
        assertTrue(lastEdit().getJSONObject("reply_markup").toString().contains("Подтвердить удаление"));
        verify(adapter,never()).delete(anyString(),anyString(),anyBoolean());
    }
    @Test public void deleteUsesConfirmedIccidAfterOrderChangesAndOnlyClearsItsNumber() throws Exception {
        successfulDelete();String key1=Rules.profileKey(EID,iccid(1)),key2=Rules.profileKey(EID,iccid(2));
        numbers.put(key1,"+79991111111");numbers.put(key2,"+79992222222");chooseDelete(1);
        profiles=new JSONArray().put(profiles.getJSONObject(1)).put(profiles.getJSONObject(0));tap(confirmation());
        verify(adapter).delete(EID,iccid(2),false);verify(store).forgetNumber(key2);verify(store,never()).forgetNumber(key1);
        assertEquals("+79991111111",numbers.get(key1));assertFalse(numbers.containsKey(key2));assertEquals(1,profiles.length());
        assertEquals("true",settings.get("switching:0"));verify(store).enqueueNotice(anyString(),eq(CHAT),contains("Профиль удалён"));
        verify(adapter,never()).refresh(store);
    }
    @Test public void activeProfileConfirmationExplicitlyAuthorizesDisable() throws Exception {
        successfulDelete();chooseDelete(0);assertTrue(lastEdit().getString("text").contains("Этот профиль активен"));
        assertTrue(lastEdit().getJSONObject("reply_markup").toString().contains("Отключить и удалить"));
        tap(confirmation());verify(adapter).delete(EID,iccid(1),true);
    }
    @Test public void profileThatBecameActiveRequiresFreshConsent() throws Exception {
        chooseDelete(1);profiles.getJSONObject(1).put("enabled",true);tap(confirmation());
        verify(adapter,never()).delete(anyString(),anyString(),anyBoolean());verify(api).send(eq(CHAT),contains("Профиль стал активным"),isNull());
    }
    @Test public void repeatedDeleteConfirmationDoesNotRepeatMutation() throws Exception {
        successfulDelete();chooseDelete(1);String confirm=confirmation();tap(confirm);tap(confirm);
        verify(adapter,times(1)).delete(EID,iccid(2),false);
    }
    @Test public void cancelledDeletionCannotUseOldConfirmation() throws Exception {
        chooseDelete(1);String confirm=confirmation();tap("cancel");tap(confirm);
        verify(adapter,never()).delete(anyString(),anyString(),anyBoolean());
    }
    @Test public void navigatingBackInvalidatesDeletion() throws Exception {
        chooseDelete(1);String confirm=confirmation();tap("adapter");tap(confirm);
        verify(adapter,never()).delete(anyString(),anyString(),anyBoolean());
    }
    @Test public void staleDeleteButtonCannotSelectDifferentProfile() throws Exception {
        command("/esim");tap(choice(1,CHAT));String old=deletion();tap(choice(0,CHAT));tap(old);
        assertEquals("confirm_enable",new JSONObject(settings.get("draft:"+CHAT)).getString("stage"));
        verify(adapter,never()).delete(anyString(),anyString(),anyBoolean());
    }
    @Test public void changedAdapterRejectsDeleteConfirmation() throws Exception {
        chooseDelete(1);when(adapter.card(anyString())).thenReturn(new JSONObject().put("eid","other"));tap(confirmation());
        verify(adapter,never()).delete(anyString(),anyString(),anyBoolean());verify(store,never()).forgetNumber(anyString());
    }
    @Test public void disappearedProfileRejectsDeletionWithoutRemovingNumberBinding() throws Exception {
        chooseDelete(1);profiles=new JSONArray().put(profiles.getJSONObject(0));tap(confirmation());
        verify(adapter,never()).delete(anyString(),anyString(),anyBoolean());verify(store,never()).forgetNumber(anyString());
    }
    @Test public void deleteFailurePreservesNumberAndNeverClaimsSuccess() throws Exception {
        when(adapter.delete(anyString(),anyString(),anyBoolean())).thenThrow(new UserError("Профиль остался на карте"));
        chooseDelete(1);tap(confirmation());verify(store,never()).forgetNumber(anyString());
        verify(api,never()).send(anyLong(),contains("Профиль удалён"),any());verify(api).send(eq(CHAT),contains("остался на карте"),isNull());
    }
    @Test public void missingDeleteConfirmationFromProviderIsNotSuccess() throws Exception {
        when(adapter.delete(anyString(),anyString(),anyBoolean())).thenReturn(new JSONObject().put("success",false));
        chooseDelete(1);tap(confirmation());verify(store,never()).forgetNumber(anyString());verify(api,never()).send(anyLong(),contains("Профиль удалён"),any());
    }
    @Test public void verifiedDeletePersistsResultWithoutWaitingForAnotherAdapterRead() throws Exception {
        successfulDelete();doThrow(new UserError("No card")).when(adapter).refresh(store);chooseDelete(1);tap(confirmation());
        verify(store).enqueueNotice(anyString(),eq(CHAT),argThat(t->t.contains("Профиль удалён")&&t.contains("проверен отдельно")));
        verify(adapter,never()).refresh(store);assertTrue(settings.get("esim_last_result").contains("Профиль удалён"));
        verify(store).forgetNumber(Rules.profileKey(EID,iccid(2)));
    }
    @Test public void unauthorizedAccountCannotUseOwnersDeleteConfirmation() throws Exception {
        chooseDelete(1);send("",confirmation(),999L);send("",confirmation(),789L);
        verify(adapter,never()).delete(anyString(),anyString(),anyBoolean());
    }
    @Test public void revokedControlAndExpiredDraftRejectDeletion() throws Exception {
        chooseDelete(1);String confirm=confirmation();settings.put("esim_control","false");tap(confirm);
        settings.put("esim_control","true");JSONObject d=new JSONObject(settings.get("draft:"+CHAT));
        settings.put("draft:"+CHAT,d.put("expires",1).toString());tap(confirm);
        verify(adapter,never()).delete(anyString(),anyString(),anyBoolean());
    }
    @Test public void downloadErrorSurvivesLaterStatusAndContainsActualMemoryCode() throws Exception {
        String error=EsimErrors.describe(EsimErrors.download(null,200,
            "{\"header\":{\"functionExecutionStatus\":{\"statusCodeData\":{\"subjectCode\":\"8.1\",\"reasonCode\":\"4.8\"}}}}".getBytes(java.nio.charset.StandardCharsets.UTF_8),null,null));
        when(adapter.download(eq(EID),anyString(),anyString())).thenThrow(new UserError(error));
        command("/add");command("+79991234567");command("LPA:1$example.com$sample-token");tap(confirmation());command("/status");
        assertEquals(error,settings.get("esim_last_error"));verify(api).send(eq(CHAT),argThat(t->t.contains("Последняя ошибка загрузки")&&t.contains("8.1 / 4.8")),isNull());
    }
    @Test public void failedDownloadDoesNotPermanentlyBlockFollowingInstall() throws Exception {
        when(adapter.download(eq(EID),eq("LPA:1$example.com$first"),anyString())).thenThrow(new UserError("Соединение прервалось"));
        command("/add");command("+79991111111");command("LPA:1$example.com$first");String failed=confirmation();tap(failed);
        assertEquals("true",settings.get("switching:0"));bot.reconcile();assertEquals("false",settings.get("switching:0"));
        // The service can recover and process a new command; the first QR is never replayed.
        when(adapter.download(eq(EID),eq("LPA:1$example.com$second"),anyString())).thenAnswer(i->{add(3,false);return new JSONObject().put("iccid",iccid(3)).put("eid",EID);});
        command("/add");command("+79992222222");command("LPA:1$example.com$second");tap(confirmation());tap(failed);
        verify(adapter,times(1)).download(EID,"LPA:1$example.com$first","");verify(adapter,times(1)).download(EID,"LPA:1$example.com$second","");
        verify(adapter).enable(selectedCard(),eq(iccid(3)));assertEquals("false",settings.get("switching:0"));assertEquals("",settings.get("esim_last_error"));
        verify(api).send(eq(CHAT),contains("Активирован профиль +79992222222"),notNull());
    }
    @Test public void installedProfileAfterLostResponseIsNotDownloadedAgainAutomatically() throws Exception {
        when(adapter.download(eq(EID),anyString(),anyString())).thenAnswer(i->{add(3,false);throw new UserError("Ответ после установки потерялся");});
        command("/add");command("+79993333333");command("LPA:1$example.com$uncertain");String confirm=confirmation();tap(confirm);
        bot.reconcile();command("/esim");tap(confirm);
        assertEquals(3,profiles.length());verify(adapter,times(1)).download(EID,"LPA:1$example.com$uncertain","");
        verify(adapter,never()).enable(selectedCard(),anyString());verify(store,never()).number(anyString(),anyString());
        verify(api).send(eq(CHAT),contains("Всего: 3"),notNull());
    }
}
