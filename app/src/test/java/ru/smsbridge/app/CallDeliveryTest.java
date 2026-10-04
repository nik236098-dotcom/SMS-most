package ru.smsbridge.app;

import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class CallDeliveryTest {
    @Test public void formattedCallIsCopyableAndNotPresentedAsSmsOrCode() throws Exception {
        JSONObject call=new JSONObject().put("kind","call").put("sender","+79991234567").put("recipient","+79997654321").put("received",1780000000000L).put("id",7).put("body","");
        JSONObject message=SmsText.messages(call,456L).get(0);String text=message.getString("text");
        assertTrue(text.contains("📞 Входящий звонок"));assertTrue(text.contains("Абонент: +79991234567"));
        assertTrue(text.contains("На номер: +79997654321"));assertFalse(text.contains("Новое SMS"));assertFalse(text.contains("Сервис:"));
        assertFalse(message.getBoolean("protect_content"));assertFalse(message.has("entities"));assertEquals(456L,message.getLong("chat_id"));
    }
    @Test public void stateAndQueuedCallCommitTogether() throws Exception {
        Store store=mock(Store.class);SQLiteDatabase db=mock(SQLiteDatabase.class);when(store.getWritableDatabase()).thenReturn(db);
        doCallRealMethod().when(store).saveCall(anyString(),any(),any());
        JSONObject next=new JSONObject().put("event","unique-call");JSONObject payload=new JSONObject().put("kind","call");
        store.saveCall("call_state:sub:7",next,payload);
        InOrder order=inOrder(db,store);order.verify(db).beginTransaction();order.verify(store).enqueue("call|unique-call",payload);
        order.verify(store).put(eq("call_state:sub:7"),contains("\"notified\":true"));order.verify(db).setTransactionSuccessful();order.verify(db).endTransaction();
    }
    @Test public void failedEnqueueRollsBackWithoutSavingNotifiedState() throws Exception {
        Store store=mock(Store.class);SQLiteDatabase db=mock(SQLiteDatabase.class);when(store.getWritableDatabase()).thenReturn(db);
        doCallRealMethod().when(store).saveCall(anyString(),any(),any());when(store.enqueue(anyString(),any())).thenThrow(new IllegalStateException());
        try{store.saveCall("call_state:sub:7",new JSONObject().put("event","unique-call"),new JSONObject());fail();}catch(IllegalStateException expected){}
        verify(db).endTransaction();verify(db,never()).setTransactionSuccessful();verify(store,never()).put(anyString(),anyString());
    }
    @Test public void outgoingStatesAloneNeverRequestNotification() throws Exception {
        JSONObject state=CallEvents.transition(new JSONObject(),"OFFHOOK","+79991234567",true,1000);
        assertFalse(state.optBoolean("notify"));state=CallEvents.transition(state,"IDLE",null,false,2000);assertFalse(state.optBoolean("notify"));
    }
    @Test public void waitingCallWithAnotherNumberCreatesNewEvent() throws Exception {
        JSONObject state=CallEvents.transition(new JSONObject(),"RINGING","+79991111111",true,1000);String first=state.getString("event");
        state.put("notified",true);state=CallEvents.transition(state,"OFFHOOK",null,false,2000);
        state=CallEvents.transition(state,"RINGING","+79992222222",true,3000);
        assertNotEquals(first,state.getString("event"));assertTrue(state.optBoolean("notify"));assertEquals("+79992222222",state.getString("number"));
    }
}
