package ru.smsbridge.app;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

final class Telegram {
    static final class ApiError extends Exception {
        final int code; final long retry; final boolean formatting;
        ApiError(int code, long retry) {
            this(code,retry,"");
        }
        ApiError(int code,long retry,String description) {
            super(message(code,description));
            this.code=code;this.retry=retry;this.formatting=code==400 && isFormatting(description);
        }
        private static boolean isFormatting(String text) {
            String d=text.toLowerCase(java.util.Locale.ROOT);
            return d.contains("can't parse entities") || d.contains("cannot parse entities") || d.contains("entity bounds") || d.contains("entity beginning") || d.contains("entity end");
        }
        private static String message(int code,String description) {
            String d=description.toLowerCase(java.util.Locale.ROOT);
            if(code==400) {
                if(isFormatting(d))return "Telegram отклонил оформление сообщения (400)";
                if(d.contains("chat not found"))return "Telegram не нашёл чат получателя (400): получатель должен нажать «Начать» у бота";
                if(d.contains("message is too long"))return "Telegram отклонил длину сообщения (400)";
                if(d.contains("message text is empty"))return "Telegram получил пустой текст сообщения (400)";
                return "Telegram отклонил запрос (400)";
            }
            return code==401?"Неверный токен бота (401)":code==403?"Получатель недоступен для бота (403): проверь блокировку бота и нажми «Начать»":code==409?"Бот используется другим приложением или webhook":code==429?"Telegram ограничил частоту отправки": "Сеть или Telegram временно недоступны ("+code+")";
        }
    }
    private final String token;
    Telegram(String token) {
        if (!token.matches("[0-9]{5,20}:[A-Za-z0-9_-]{20,}")) throw new IllegalArgumentException("Неверный формат токена бота");
        this.token=token;
    }
    JSONObject call(String method, JSONObject payload) throws Exception {
        if (!method.matches("[A-Za-z]+")) throw new IllegalArgumentException("Bad method");
        HttpsURLConnection c=(HttpsURLConnection)new URL("https://api.telegram.org/bot"+token+"/"+method).openConnection();
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(12000); c.setReadTimeout(35000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json; charset=utf-8");
        byte[] raw=payload.toString().getBytes(StandardCharsets.UTF_8); c.setFixedLengthStreamingMode(raw.length);
        try {
            try(java.io.OutputStream out=c.getOutputStream()) { out.write(raw); }
            int status=c.getResponseCode(); InputStream in=status>=400?c.getErrorStream():c.getInputStream();
            if(in==null) throw new ApiError(status,0);
            JSONObject obj=new JSONObject(new String(read(in,3*1024*1024),StandardCharsets.UTF_8));
            if (!obj.optBoolean("ok")) {
                JSONObject parameters=obj.optJSONObject("parameters");
                throw new ApiError(obj.optInt("error_code",status),parameters==null?0:parameters.optLong("retry_after"),obj.optString("description"));
            } return obj;
        } finally { c.disconnect(); }
    }
    void send(long chat,String text,JSONObject keyboard) throws Exception {
        java.util.List<String> parts=Rules.chunks(text);
        for(int i=0;i<parts.size();i++) {
            JSONObject p=new JSONObject().put("chat_id",chat).put("text",parts.get(i)).put("protect_content",false);
            if(i==parts.size()-1 && keyboard!=null) p.put("reply_markup",keyboard);
            call("sendMessage",p);
        }
    }
    byte[] file(String fileId) throws Exception {
        JSONObject f=call("getFile",new JSONObject().put("file_id",fileId)).getJSONObject("result");
        if(f.optLong("file_size")>2*1024*1024) throw new IllegalArgumentException("Пришли QR-код картинкой размером до 2 МБ");
        String path=f.getString("file_path");
        if(!path.matches("[A-Za-z0-9_/.-]+")||path.contains("..")) throw new IllegalArgumentException("Неверный путь изображения");
        HttpsURLConnection c=(HttpsURLConnection)new URL("https://api.telegram.org/file/bot"+token+"/"+path).openConnection();
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(12000);c.setReadTimeout(25000);
        try { if(c.getResponseCode()!=200) throw new ApiError(c.getResponseCode(),0); return read(c.getInputStream(),2*1024*1024); }
        finally { c.disconnect(); }
    }
    static byte[] read(InputStream in,int max) throws Exception {
        try(InputStream stream=in; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buf=new byte[8192];int n;while((n=stream.read(buf))!=-1) {
                if(out.size()+n>max) throw new IllegalArgumentException("Слишком большой ответ");out.write(buf,0,n);
            }return out.toByteArray();
        }
    }
    static String safe(Exception e) {
        if(e instanceof ApiError || e instanceof IllegalArgumentException || e instanceof UserError) return e.getMessage();
        if(e instanceof java.net.UnknownHostException) return "Не удаётся найти сервер Telegram. Проверь DNS, Wi-Fi или VPN на Android.";
        if(e instanceof java.net.SocketTimeoutException || e instanceof java.net.ConnectException) return "Телефон не может подключиться к api.telegram.org. Наличие интернета не означает доступ к Telegram: проверь VPN или другую сеть на Android.";
        if(e instanceof javax.net.ssl.SSLException) return "Ошибка защищённого соединения с Telegram. Проверь дату и время на Android и настройки VPN.";
        return "Операция не завершена. Проверь интернет и состояние приложения.";
    }
}
