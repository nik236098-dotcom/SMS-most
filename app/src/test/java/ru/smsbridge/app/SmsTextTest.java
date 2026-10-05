package ru.smsbridge.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class SmsTextTest {
    @Test public void adapterNoticeIsDeliveredAsPlainResultWithoutSmsOrOtpFormatting() throws Exception {
        String text="Профиль удалён. ICCID: 8900000000000000001";
        JSONObject p=SmsText.messages(new JSONObject().put("kind","notice").put("body",text),456).get(0);
        assertEquals(text,p.getString("text"));assertEquals(456,p.getLong("chat_id"));
        assertFalse(p.has("entities"));assertFalse(p.getBoolean("protect_content"));
    }
    private JSONObject sms(String body) throws Exception {
        return new JSONObject().put("id",123456).put("sender","12345").put("recipient","+79991234567")
            .put("body",body).put("received",1780000000000L);
    }
    @Test public void codeHasExactOffsetsAfterEmojiAndKeepsLeadingZero() throws Exception {
        JSONObject part=SmsText.messages(sms("🔐 Ваш код: 001234. Никому не сообщайте."),456).get(0);
        JSONObject entity=part.getJSONArray("entities").getJSONObject(0);
        String text=part.getString("text");assertEquals("001234",text.substring(entity.getInt("offset"),entity.getInt("offset")+entity.getInt("length")));
        assertEquals("code",entity.getString("type"));assertFalse(part.getBoolean("protect_content"));assertFalse(part.has("parse_mode"));
    }
    @Test public void metadataPhoneAndDateAreNotFormattedAsCodes() throws Exception {
        JSONObject part=SmsText.messages(sms("Дата 04.10.2026, телефон +79991234567. Текст <b>без кода</b>."),456).get(0);
        assertFalse(part.has("entities"));assertTrue(part.getString("text").contains("<b>без кода</b>"));
    }
    @Test public void allNumericCodesInBodyCanBeCopiedSeparately() throws Exception {
        JSONObject part=SmsText.messages(sms("Код 1234, резервный 87654321"),456).get(0);
        assertEquals(2,part.getJSONArray("entities").length());
    }
    @Test public void longSmsKeepsCodeWholeAcrossPartBoundary() throws Exception {
        JSONObject sms=sms("");int head=Bot.header(sms).length();sms.put("body","x".repeat(3700-head-4)+" 001234 end");
        List<JSONObject> parts=SmsText.messages(sms,456);assertEquals(2,parts.size());assertFalse(parts.get(0).has("entities"));
        JSONObject e=parts.get(1).getJSONArray("entities").getJSONObject(0);
        String text=parts.get(1).getString("text");assertEquals("001234",text.substring(e.getInt("offset"),e.getInt("offset")+e.getInt("length")));
        assertTrue(text.contains("часть 2/2"));
    }
    @Test public void textWithoutCodesIsPreservedAndDeliverable() throws Exception {
        JSONObject sms=sms("Баланс обновлён");JSONObject part=SmsText.messages(sms,456).get(0);
        assertEquals(Bot.format(sms),part.getString("text"));assertEquals(456,part.getLong("chat_id"));assertFalse(part.has("entities"));
    }
}
