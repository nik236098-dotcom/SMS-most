package ru.smsbridge.app;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.io.InterruptedIOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

final class Telegram {
    static final class ApiError extends Exception {
        final int code; final long retry; final boolean formatting,notModified;
        ApiError(int code, long retry) {
            this(code,retry,"");
        }
        ApiError(int code,long retry,String description) {
            super(message(code,description));
            this.code=code;this.retry=retry;this.formatting=code==400 && isFormatting(description);
            this.notModified=code==400&&description.toLowerCase(java.util.Locale.ROOT).contains("message is not modified");
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
    private static final OkHttpClient HTTP=new OkHttpClient.Builder()
        .dns(BoundedDns.system())
        .connectTimeout(12,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS)
        .writeTimeout(12,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    private final OkHttpClient http;private final String endpoint;
    private final ConcurrentHashMap<Call,String> active=new ConcurrentHashMap<>();
    private volatile boolean closed;
    Telegram(String token) {
        this(token,HTTP,"https://api.telegram.org/");
    }
    Telegram(String token,OkHttpClient http,String endpoint) {
        if (!token.matches("[0-9]{5,20}:[A-Za-z0-9_-]{20,}")) throw new IllegalArgumentException("Неверный формат токена бота");
        this.token=token;this.http=http;this.endpoint=endpoint;
    }
    boolean closed(){return closed;}
    void close(){closed=true;for(Call call:active.keySet())call.cancel();}
    void reconnectPolling(){for(java.util.Map.Entry<Call,String> e:active.entrySet())if(e.getValue().equals("getUpdates"))e.getKey().cancel();http.connectionPool().evictAll();}
    private byte[] request(String path,String method,JSONObject payload,int max) throws Exception {
        if(closed||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Telegram request cancelled");
        Request.Builder request=new Request.Builder().url(endpoint+path);
        if(payload!=null)request.post(RequestBody.create(payload.toString().getBytes(StandardCharsets.UTF_8),MediaType.get("application/json; charset=utf-8")));
        Call call=http.newCall(request.build());active.put(call,method);
        if(closed){active.remove(call);call.cancel();throw new InterruptedIOException("Telegram request cancelled");}
        try(Response response=call.execute()) {
            if(closed)throw new InterruptedIOException("Telegram request cancelled");
            if(response.body()==null)throw new ApiError(response.code(),0);
            byte[] data=read(response.body().byteStream(),max);
            if(response.code()!=200) {
                JSONObject error;
                try{error=new JSONObject(new String(data,StandardCharsets.UTF_8));}catch(Exception ignored){throw new ApiError(response.code(),0);}
                JSONObject parameters=error.optJSONObject("parameters");
                throw new ApiError(error.optInt("error_code",response.code()),parameters==null?0:parameters.optLong("retry_after"),error.optString("description"));
            }
            return data;
        } finally {active.remove(call);}
    }
    JSONObject call(String method, JSONObject payload) throws Exception {
        if (!method.matches("[A-Za-z]+")) throw new IllegalArgumentException("Bad method");
            JSONObject obj=new JSONObject(new String(request("bot"+token+"/"+method,method,payload,3*1024*1024),StandardCharsets.UTF_8));
            if (!obj.optBoolean("ok")) {
                JSONObject parameters=obj.optJSONObject("parameters");
                throw new ApiError(obj.optInt("error_code",500),parameters==null?0:parameters.optLong("retry_after"),obj.optString("description"));
            } return obj;
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
        return request("file/bot"+token+"/"+path,"file",null,2*1024*1024);
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
        if(e instanceof InterruptedIOException)return "Запрос Telegram прерван или превысил время ожидания. Соединение будет установлено заново.";
        if(e instanceof javax.net.ssl.SSLException) return "Ошибка защищённого соединения с Telegram. Проверь дату и время на Android и настройки VPN.";
        return "Операция не завершена. Проверь интернет и состояние приложения.";
    }
}
