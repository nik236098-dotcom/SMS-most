package ru.smsbridge.app;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class BotQrTest {
    Store s;Telegram api;LpaClient lpa;Bot bot;Map<String,String> values;
    @Before public void setup() throws Exception {
        s=mock(Store.class);api=mock(Telegram.class);lpa=mock(LpaClient.class);bot=new Bot(s,lpa);values=new HashMap<>();TestStoreRouting.attach(s);
        when(s.running()).thenReturn(true);when(s.epoch()).thenReturn("qr-session");when(s.chat()).thenReturn(456L);when(s.chats()).thenReturn(java.util.Collections.singletonList(456L));when(s.beginOperation(anyLong())).thenReturn(true);
        when(s.get(anyString(),anyString())).thenAnswer(i->values.getOrDefault(i.getArgument(0),i.getArgument(1)));
        doAnswer(i->{values.put(i.getArgument(0),i.getArgument(1));return null;}).when(s).put(anyString(),anyString());
        values.put("draft:456",draft("qr").put("number","+79991234567").toString());
    }
    JSONObject draft(String stage) throws Exception{return new JSONObject().put("stage",stage).put("nonce","test").put("epoch","qr-session").put("expires",System.currentTimeMillis()+60000);}
    void input(String key,Object attachment) throws Exception {
        JSONObject m=new JSONObject().put("message_id",5).put("chat",new JSONObject().put("id",456).put("type","private"))
            .put("from",new JSONObject().put("id",456).put("is_bot",false)).put(key,attachment);
        when(api.call(eq("getUpdates"),any())).thenReturn(new JSONObject().put("result",new JSONArray().put(new JSONObject().put("update_id",1).put("message",m))));bot.poll(api,0);
    }
    void accepted() throws Exception {
        JSONObject draft=new JSONObject(values.get("draft:456"));assertEquals("confirm_add",draft.getString("stage"));assertEquals(QrDecoderTest.CODE,draft.getString("code"));
        verify(api).send(eq(456L),contains("QR-код получен"),notNull());verifyNoInteractions(lpa);
    }
    @Test public void squareTelegramPhotoReachesImageDecoderAndConfirmation() throws Exception {
        byte[] bytes={1};when(api.file("square")).thenReturn(bytes);
        try(MockedStatic<QrImage> images=mockStatic(QrImage.class)) {
            images.when(()->QrImage.decode(bytes)).thenReturn(QrDecoderTest.CODE);
            input("photo",new JSONArray().put(new JSONObject().put("file_id","small").put("width",50).put("height",50)).put(new JSONObject().put("file_id","square").put("width",300).put("height",300)));
            accepted();verify(api,never()).file("small");images.verify(()->QrImage.decode(bytes));
        }
    }
    @Test public void documentWithGenericMimeUsesActualImageBytes() throws Exception {
        byte[] bytes={2};when(api.file("file")).thenReturn(bytes);
        try(MockedStatic<QrImage> images=mockStatic(QrImage.class)) {
            images.when(()->QrImage.decode(bytes)).thenReturn(QrDecoderTest.CODE);
            input("document",new JSONObject().put("file_id","file").put("file_name","cropped.png").put("mime_type","application/octet-stream"));accepted();
        }
    }
    @Test public void documentWithoutMimeAlsoReachesConfirmation() throws Exception {
        byte[] bytes={3};when(api.file("file")).thenReturn(bytes);
        try(MockedStatic<QrImage> images=mockStatic(QrImage.class)) {
            images.when(()->QrImage.decode(bytes)).thenReturn("  "+QrDecoderTest.CODE+"\n");input("document",new JSONObject().put("file_id","file"));accepted();
        }
    }
    @Test public void unreadableImageKeepsPhoneAndAllowsAnotherQr() throws Exception {
        byte[] bytes={4};when(api.file("file")).thenReturn(bytes);
        try(MockedStatic<QrImage> images=mockStatic(QrImage.class)) {
            images.when(()->QrImage.decode(bytes)).thenThrow(new UserError("Не удалось прочитать QR-код"));input("document",new JSONObject().put("file_id","file"));
            JSONObject draft=new JSONObject(values.get("draft:456"));assertEquals("qr",draft.getString("stage"));assertEquals("+79991234567",draft.getString("number"));verifyNoInteractions(lpa);
        }
    }
    @Test public void ordinaryWebQrCannotBecomeEsimInstallation() throws Exception {
        byte[] bytes={5};when(api.file("file")).thenReturn(bytes);
        try(MockedStatic<QrImage> images=mockStatic(QrImage.class)) {
            images.when(()->QrImage.decode(bytes)).thenReturn("https://example.test");input("document",new JSONObject().put("file_id","file"));
            assertEquals("qr",new JSONObject(values.get("draft:456")).getString("stage"));verifyNoInteractions(lpa);verify(api,never()).send(anyLong(),contains("QR-код получен"),any());
        }
    }
    @Test public void flexiblePhoneIsNormalizedBeforeQrPrompt() throws Exception {
        values.put("draft:456",draft("phone").toString());
        input("text","8 (900) 000‑11‑11");JSONObject draft=new JSONObject(values.get("draft:456"));
        assertEquals("qr",draft.getString("stage"));assertEquals("+79000001111",draft.getString("number"));
        verify(api).send(eq(456L),contains("Номер сохранён: +79000001111"),notNull());verifyNoInteractions(lpa);
    }
}
