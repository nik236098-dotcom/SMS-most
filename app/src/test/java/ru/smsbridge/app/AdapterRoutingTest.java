package ru.smsbridge.app;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class AdapterRoutingTest {
    Store s;Map<String,String> values=new HashMap<>();Cursor row;
    @Before public void setup() throws Exception {
        s=mock(Store.class);row=mock(Cursor.class);SQLiteDatabase db=mock(SQLiteDatabase.class);
        when(s.get(anyString(),anyString())).thenAnswer(i->values.getOrDefault(i.getArgument(0),i.getArgument(1)));
        doAnswer(i->{values.put(i.getArgument(0),i.getArgument(1));return null;}).when(s).put(anyString(),anyString());
        TestStoreRouting.attach(s);doCallRealMethod().when(s).rememberCards(any(JSONArray.class));
        when(s.recipient(anyInt(),anyInt())).thenCallRealMethod();when(s.getReadableDatabase()).thenReturn(db);when(db.rawQuery(anyString(),any())).thenReturn(row);
        values.put("esim_control","true");s.rememberCards(new JSONArray().put(DualAdapterTest.card(DualAdapterTest.A,0)).put(DualAdapterTest.card(DualAdapterTest.B,1)));
        when(row.moveToFirst()).thenReturn(true);when(row.getLong(1)).thenReturn(System.currentTimeMillis());
        when(row.getString(0)).thenReturn(Rules.profileKey(DualAdapterTest.B,DualAdapterTest.ICCID));
        when(s.number(Rules.profileKey(DualAdapterTest.B,DualAdapterTest.ICCID))).thenReturn("+79992222222");
    }
    @Test public void switchingFirstAdapterDoesNotChangeSecondRecipients() throws Exception {
        s.switching(0,true);assertTrue(s.recipient(0,10).contains("переключение"));assertEquals("+79992222222",s.recipient(1,11));
    }
    @Test public void movedCardCannotReuseOtherCardsActiveBinding() throws Exception {
        s.rememberCards(new JSONArray().put(DualAdapterTest.card(DualAdapterTest.A,1)).put(DualAdapterTest.card(DualAdapterTest.B,0)));
        assertTrue(s.recipient(1,11).contains("адаптер изменился"));
    }
    @Test public void staleObservationDoesNotInventRecipient() throws Exception {
        when(row.getLong(1)).thenReturn(System.currentTimeMillis()-60000);assertTrue(s.recipient(1,11).contains("профиль не проверен"));
    }
    @Test public void ordinarySimAlongsideAdapterKeepsPhysicalNumber() throws Exception {
        s.rememberCards(new JSONArray().put(DualAdapterTest.card(DualAdapterTest.A,0)));when(s.number("physical:11")).thenReturn("+79994444444");
        assertFalse(s.managed(1));assertEquals("+79994444444",s.recipient(1,11));
    }
    @Test public void temporaryUnavailableCardRemainsManaged() throws Exception {
        s.rememberCards(new JSONArray().put(DualAdapterTest.card(DualAdapterTest.A,0)).put(new JSONObject().put("slot",1).put("unavailable",true)));
        assertTrue(s.managed(1));assertEquals(DualAdapterTest.B,new JSONObject(values.get("adapter_cards")).getString("1"));
    }
    @Test public void legacySingleCardMappingAndSwitchingMigrateWithoutGuessing() throws Exception {
        values.remove("adapter_cards");values.put("adapter_slot","1");values.put("switching","true");
        assertTrue(s.managed(1));assertFalse(s.managed(0));assertTrue(s.switching(1));assertFalse(s.switching(0));
        s.switching(1,false);assertFalse(s.switching(1));
    }
    @Test public void deletionJournalMigratesLegacyAndFinishesOnlyMatchingOperation() throws Exception {
        JSONObject old=new JSONObject().put("nonce","old").put("iccid",DualAdapterTest.ICCID).put("eid",DualAdapterTest.A);
        values.put("last_delete",old.toString());s.rememberDelete(new JSONObject().put("nonce","new").put("iccid",DualAdapterTest.ICCID).put("eid",DualAdapterTest.B));
        values.put("last_delete",old.toString());assertEquals(2,s.pendingDeletes().length());s.finishDelete("new");
        assertEquals(1,s.pendingDeletes().length());assertEquals("old",s.pendingDeletes().getJSONObject(0).getString("nonce"));
    }
}
