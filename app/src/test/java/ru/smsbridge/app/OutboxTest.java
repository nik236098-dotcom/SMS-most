package ru.smsbridge.app;

import android.content.Context;
import android.os.PowerManager;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import java.net.SocketTimeoutException;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class OutboxTest {
    Context context; Store store; Telegram api; PowerManager power; PowerManager.WakeLock lock;
    @Before public void setup() throws Exception {
        context=mock(Context.class);store=mock(Store.class);api=mock(Telegram.class);
        power=mock(PowerManager.class);lock=mock(PowerManager.WakeLock.class);
        when(context.getSystemService(PowerManager.class)).thenReturn(power);
        when(power.newWakeLock(anyInt(),anyString())).thenReturn(lock);when(lock.isHeld()).thenReturn(true);
        when(store.running()).thenReturn(true);when(store.chat()).thenReturn(456L);
        when(store.chats()).thenReturn(java.util.Arrays.asList(456L,789L));when(store.epoch()).thenReturn("session");
        when(store.pending(anyLong())).thenReturn(true);
    }
    private JSONObject sms(long id,long chat,String body) throws Exception {
        return new JSONObject().put("id",id).put("chat_id",chat).put("epoch","session").put("part",0)
            .put("attempts",0).put("body",body).put("sender","Carrier").put("recipient","+79990000000");
    }
    @Test public void rejectedSmsDoesNotBlockNextSmsForSameRecipient() throws Exception {
        when(store.next()).thenReturn(sms(43,456,"first"),sms(44,456,"second"),null);
        when(api.call(eq("sendMessage"),any())).thenThrow(new Telegram.ApiError(400,0)).thenReturn(new JSONObject());
        Outbox.drain(context,store,api);
        verify(store).failed(eq(43L),eq(0),eq(0L),contains("400"));verify(store).delivered(44);
        verify(store,never()).delivered(43);verify(store,never()).recipientUnavailable(anyLong());
    }
    @Test public void rejectedFormattingRetriesSameTextWithoutEntities() throws Exception {
        when(store.next()).thenReturn(sms(43,456,"Ваш код 123456"),null);
        when(api.call(eq("sendMessage"),any())).thenThrow(new Telegram.ApiError(400,0,"Bad Request: can't parse entities"))
            .thenReturn(new JSONObject());
        Outbox.drain(context,store,api);
        ArgumentCaptor<JSONObject> payloads=ArgumentCaptor.forClass(JSONObject.class);
        verify(api,times(2)).call(eq("sendMessage"),payloads.capture());
        JSONObject formatted=payloads.getAllValues().get(0),plain=payloads.getAllValues().get(1);
        assertTrue(formatted.has("entities"));assertFalse(plain.has("entities"));
        assertEquals(formatted.getString("text"),plain.getString("text"));assertEquals(456L,plain.getLong("chat_id"));
        assertFalse(plain.getBoolean("protect_content"));verify(store).delivered(43);verify(store).progress(43,1);
    }
    @Test public void fallbackFailureRemainsQueuedAndDoesNotLoop() throws Exception {
        when(store.next()).thenReturn(sms(43,456,"Код 123456"),null);
        when(api.call(eq("sendMessage"),any())).thenThrow(new Telegram.ApiError(400,0,"can't parse entities"));
        Outbox.drain(context,store,api);verify(api,times(2)).call(eq("sendMessage"),any());
        verify(store).failed(eq(43L),eq(0),eq(0L),anyString());verify(store,never()).progress(anyLong(),anyInt());
        verify(store,never()).delivered(anyLong());
    }
    @Test public void unknown400DoesNotBlindlySendDuplicateFallback() throws Exception {
        when(store.next()).thenReturn(sms(43,456,"Код 123456"),null);
        when(api.call(eq("sendMessage"),any())).thenThrow(new Telegram.ApiError(400,0,"chat not found"));
        Outbox.drain(context,store,api);verify(api,times(1)).call(eq("sendMessage"),any());
        verify(store).failed(eq(43L),eq(0),eq(0L),contains("чат получателя"));
    }
    @Test public void blockedRecipientDoesNotPreventAnotherRecipient() throws Exception {
        when(store.next()).thenReturn(sms(43,456,"first"),sms(44,789,"second"),null);
        when(api.call(eq("sendMessage"),any())).thenThrow(new Telegram.ApiError(403,0)).thenReturn(new JSONObject());
        Outbox.drain(context,store,api);verify(store).recipientUnavailable(456);verify(store).delivered(44);
        verify(store,never()).telegramWait(anyLong());
    }
    @Test public void telegramRetryAfterPausesAllQueueAndIsNotLocalDayDelay() throws Exception {
        when(store.next()).thenReturn(sms(43,456,"first"),sms(44,789,"second"));
        when(api.call(eq("sendMessage"),any())).thenThrow(new Telegram.ApiError(429,42));
        Outbox.drain(context,store,api);verify(store).telegramWait(42);
        verify(store).failed(eq(43L),eq(0),eq(42L),anyString());verify(api,times(1)).call(eq("sendMessage"),any());
        verify(store,never()).delivered(anyLong());
    }
    @Test public void networkFailureUsesShortBackoffWithoutLosingMessage() throws Exception {
        when(store.next()).thenReturn(sms(43,456,"first"));when(api.call(eq("sendMessage"),any())).thenThrow(new SocketTimeoutException());
        Outbox.drain(context,store,api);verify(store).failed(eq(43L),eq(0),eq(0L),contains("api.telegram.org"));
        verify(store,never()).delivered(anyLong());verify(store,never()).progress(anyLong(),anyInt());
        assertEquals(2000,Rules.retryMillis(0,0));assertEquals(15000,Rules.retryMillis(100,0));
    }
    @Test public void multipartProgressPacesAndResumesWithoutReplayingFirstPart() throws Exception {
        JSONObject p=sms(43,456,"a".repeat(5000));when(store.next()).thenReturn(p,(JSONObject)null);
        Outbox.drain(context,store,api);verify(store).progress(43,1);verify(store).pace(456);verify(store,never()).delivered(43);
        p.put("part",1);when(store.next()).thenReturn(p,(JSONObject)null);Outbox.drain(context,store,api);
        ArgumentCaptor<JSONObject> payloads=ArgumentCaptor.forClass(JSONObject.class);verify(api,times(2)).call(eq("sendMessage"),payloads.capture());
        assertTrue(payloads.getAllValues().get(0).getString("text").endsWith("часть 1/2]"));
        assertTrue(payloads.getAllValues().get(1).getString("text").endsWith("часть 2/2]"));verify(store).delivered(43);
    }
    @Test public void wakeLockCreationFailureDoesNotPermanentlyLockSender() throws Exception {
        when(power.newWakeLock(anyInt(),anyString())).thenThrow(new IllegalStateException()).thenReturn(lock);
        Outbox.drain(context,store,api);when(store.next()).thenReturn(sms(43,456,"retry"),null);
        Outbox.drain(context,store,api);verify(store).delivered(43);
    }
    @Test public void wakeLockReleaseFailureDoesNotPermanentlyLockSender() throws Exception {
        doThrow(new IllegalStateException()).doNothing().when(lock).release();Outbox.drain(context,store,api);
        when(store.next()).thenReturn(sms(43,456,"retry"),null);Outbox.drain(context,store,api);verify(store).delivered(43);
    }
    @Test public void changedSessionNeverRedirectsQueuedSms() throws Exception {
        when(store.next()).thenReturn(sms(43,456,"old").put("epoch","old-session"),null);
        Outbox.drain(context,store,api);verifyNoInteractions(api);verify(store,never()).delivered(anyLong());
    }
    @Test public void stoppedOrRemovedMessageNeverSends() throws Exception {
        when(store.next()).thenReturn(sms(43,456,"removed"));when(store.pending(43)).thenReturn(false);
        Outbox.drain(context,store,api);verifyNoInteractions(api);verify(store,never()).delivered(anyLong());
    }
    @Test public void errorDescriptionCannotExposeArbitraryServerContent() {
        Telegram.ApiError e=new Telegram.ApiError(400,0,"private token 12345 and private SMS body");
        assertEquals("Telegram отклонил запрос (400)",e.getMessage());assertFalse(e.formatting);
    }
}
