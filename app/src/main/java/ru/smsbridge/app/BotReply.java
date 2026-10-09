package ru.smsbridge.app;

import org.json.JSONObject;

/** Persist UI responses separately from SIM work. Only these two Telegram methods are allowed. */
final class BotReply {
    static void validate(String method,JSONObject request,long chat) throws Exception {
        if(!method.equals("sendMessage")&&!method.equals("editMessageText"))throw new IllegalArgumentException("Unsupported queued reply");
        if(chat<=0||request.optLong("chat_id")!=chat||request.optString("text").isEmpty())throw new IllegalArgumentException("Invalid queued reply");
        if(method.equals("editMessageText")&&request.optLong("message_id")<=0)throw new IllegalArgumentException("Invalid reply message");
    }
    static void deliver(Telegram api,JSONObject row,long chat) throws Exception {
        String method=row.getString("method");JSONObject request=new JSONObject(row.getJSONObject("request").toString());
        validate(method,request,chat);request.put("protect_content",false);
        try {api.call(method,request);}
        catch(Telegram.ApiError e) {
            if(!method.equals("editMessageText")||e.code!=400)throw e;
            if(e.notModified)return;
            // Telegram explicitly rejected the edit (e.g. the old message was deleted).
            // Preserve the new menu and its buttons as a new message instead.
            request.remove("message_id");api.call("sendMessage",request);
        }
    }
}
