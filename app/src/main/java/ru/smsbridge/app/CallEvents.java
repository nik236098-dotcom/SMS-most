package ru.smsbridge.app;

import org.json.JSONObject;
import java.util.UUID;

/** Persistent state for one subscription. Duplicate PHONE_STATE broadcasts share one event. */
final class CallEvents {
    static JSONObject transition(JSONObject old,String state,String number,boolean numberPresent,long now) throws Exception {
        JSONObject next=new JSONObject(old.toString());next.put("notify",false);
        String caller=number==null?"":number.trim();
        if("RINGING".equals(state)) {
            boolean differentCaller=!caller.isEmpty()&&!next.optString("number").isEmpty()&&!caller.equals(next.optString("number"));
            if(!next.optBoolean("active") || now-next.optLong("received")>10*60*1000L || differentCaller || next.optString("phase").equals("OFFHOOK"))
                next=new JSONObject().put("active",true).put("event",UUID.randomUUID().toString()).put("received",now).put("notified",false);
            if(!caller.isEmpty())next.put("number",caller);
            // A broadcast without the extra may precede the copy containing the caller ID.
            // Present-but-empty is Android's explicit "unknown/hidden number" value.
            if(!next.optBoolean("notified") && (numberPresent||!next.optString("number").isEmpty()))next.put("notify",true);
        } else if(("OFFHOOK".equals(state)||"IDLE".equals(state)) && next.optBoolean("active")) {
            if(!next.optBoolean("notified"))next.put("notify",true);
            if("IDLE".equals(state))next.put("active",false);
        }
        next.put("phase",state);
        return next;
    }
}
