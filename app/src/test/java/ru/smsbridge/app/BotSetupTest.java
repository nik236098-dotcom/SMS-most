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
        when(s.running()).thenReturn(true);when(s.enabled()).thenReturn(false);
        when(s.chat()).thenAnswer(i->owner);
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
        verify(s,never()).recent();
    }
    @Test public void configuredOwnerAnswersEvenWhenSmsDisabled() throws Exception {
        owner=456;updates(message("/start",456,"private"));bot.poll(api,0);
        verify(api).send(eq(456L),contains("SMS Мост"),notNull());
    }
    @Test public void otherAccountCannotAccessConfiguredBot() throws Exception {
        owner=789;updates(message("/start",456,"private"));bot.poll(api,0);
        verify(api,never()).send(anyLong(),anyString(),any());verify(s,never()).recent();
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
}
