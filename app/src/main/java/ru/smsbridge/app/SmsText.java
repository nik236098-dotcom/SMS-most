package ru.smsbridge.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Telegram entity offsets, like Java string offsets, use UTF-16 code units. */
final class SmsText {
    private static final Pattern CODE=Pattern.compile("(?<![\\p{L}\\p{N}+])(?<![0-9][./-])[0-9]{4,8}(?![\\p{L}\\p{N}]|[./-][0-9])");
    static List<JSONObject> messages(JSONObject sms,long chat) throws Exception {
        String body=sms.optString("body"),text=Bot.format(sms);int bodyStart=Bot.header(sms).length();
        List<int[]> codes=new ArrayList<>();Matcher matcher=CODE.matcher(body);
        while(matcher.find())codes.add(new int[]{bodyStart+matcher.start(),bodyStart+matcher.end()});
        List<JSONObject> result=new ArrayList<>();int start=0;
        while(start<text.length()) {
            int end=Math.min(start+3700,text.length());
            if(end<text.length() && Character.isHighSurrogate(text.charAt(end-1)))end--;
            for(int[] code:codes)if(code[0]<end && code[1]>end){end=code[0];break;}
            JSONArray entities=new JSONArray();
            for(int[] code:codes)if(code[0]>=start && code[1]<=end)
                entities.put(new JSONObject().put("type","code").put("offset",code[0]-start).put("length",code[1]-code[0]));
            JSONObject part=new JSONObject().put("chat_id",chat).put("text",text.substring(start,end)).put("protect_content",false);
            if(entities.length()>0)part.put("entities",entities);
            result.add(part);start=end;
        }
        if(result.size()>1)for(int i=0;i<result.size();i++) {
            JSONObject part=result.get(i);part.put("text",part.getString("text")+"\n[SMS #"+sms.optLong("id")+" · часть "+(i+1)+"/"+result.size()+"]");
        }
        return result;
    }
}
