package ru.smsbridge.app;

import org.junit.Before;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class BotHealthTest {
    Store s;Map<String,String> values=new HashMap<>();
    @Before public void setup(){s=mock(Store.class);when(s.running()).thenReturn(true);when(s.get(anyString(),anyString())).thenAnswer(i->values.getOrDefault(i.getArgument(0),i.getArgument(1)));}
    @Test public void enabledToggleAloneDoesNotClaimWorkingBot(){assertEquals("Приём команд не подтверждён",BotHealth.summary(s));}
    @Test public void successfulSendAloneDoesNotClaimCommandReception(){values.put("last_delivery",""+System.currentTimeMillis());assertEquals("Приём команд не подтверждён",BotHealth.summary(s));}
    @Test public void freshPollAndLoopConfirmConnection(){String now=""+System.currentTimeMillis();values.put("bot_last_seen",now);values.put("bot_loop_seen",now);assertEquals("Бот на связи",BotHealth.summary(s));}
    @Test public void errorAfterSuccessfulPollIsVisible(){String now=""+System.currentTimeMillis();values.put("bot_last_seen",now);values.put("bot_loop_seen",now);values.put("bot_error","Нет ответа");assertEquals("Восстанавливаем связь с Telegram",BotHealth.summary(s));}
    @Test public void telegramCooldownIsNotDisguisedAsHealthyConnection(){when(s.telegramRemaining()).thenReturn(41000L);assertTrue(BotHealth.summary(s).contains("41 сек."));}
    @Test public void explicitStopIsReflectedDespiteRecentReply(){String now=""+System.currentTimeMillis();values.put("bot_last_seen",now);values.put("bot_loop_seen",now);when(s.running()).thenReturn(false);assertEquals("Бот остановлен",BotHealth.summary(s));}
}
