package ru.smsbridge.app;
import java.util.List;

public final class RulesTest {
    private static int count;
    static void check(boolean ok,String message){count++;if(!ok)throw new AssertionError(message);}
    static void rejects(Runnable r){boolean rejected=false;try{r.run();}catch(IllegalArgumentException e){rejected=true;}check(rejected,"Invalid value accepted");}
    public static void main(String[] args) {
        check(Rules.phone("+7 (999) 123-45-67").equals("+79991234567"),"Phone normalization");
        check(Rules.phone("+84901234567").equals("+84901234567"),"International phone");
        check(Rules.telegramId(" 123456789 ")==123456789L,"Telegram ID");
        check(Rules.telegramId("7999123456")==7999123456L,"Large Telegram ID");
        check(Rules.telegramIds("123, 456; 123\n789").equals(java.util.Arrays.asList(123L,456L,789L)),"Multiple IDs and duplicate removal");
        check(Rules.telegramIds("").isEmpty(),"Optional code setup");
        check(Rules.authorized(Rules.telegramIds("123,456"),456,"private",false),"Second allowed account");
        check(!Rules.authorized(Rules.telegramIds("123,456"),789,"private",false),"Other account blocked");
        check(!Rules.authorized(Rules.telegramIds("123,456"),456,"group",false),"Allowed ID in group denied");
        rejects(()->Rules.telegramIds("1,2,3,4,5,6,7,8,9,10,11"));
        for(String id:new String[]{"-100123","0","@username","+79991234567","abc","","4503599627370496"})rejects(()->Rules.telegramId(id));
        for(String p:new String[]{"79991234567","+00000","+7","+1234567890123456","+7999abc","","+7999\n/start"})rejects(()->Rules.phone(p));
        String eid="89049032123456789012345678901234",icc="8901234567890123456";
        String a=Rules.profileKey(eid,icc);
        check(!a.equals(Rules.profileKey(eid,"8901234567890123457")),"Two profiles on one adapter must not share number key");
        check(!a.equals(Rules.profileKey("89049032123456789012345678901235",icc)),"Two adapters must not share number key");
        rejects(()->Rules.profileKey(null,icc));rejects(()->Rules.profileKey(eid,""));
        check(Rules.authorized(123,123,"private",false),"Owner access");
        check(!Rules.authorized(123,124,"private",false),"Another user blocked");
        check(!Rules.authorized(123,123,"group",false),"Group blocked");
        check(!Rules.authorized(0,0,"private",false),"Unpaired blocked");
        check(!Rules.authorized(123,123,"private",true),"Bot actor blocked");
        String text="а".repeat(3699)+"📲"+"б".repeat(7400)+"🙂";
        List<String> chunks=Rules.chunks(text);check(String.join("",chunks).equals(text),"Long SMS reassembly");
        for(String part:chunks){check(part.length()<=3700,"Telegram chunk limit");check(!Character.isHighSurrogate(part.charAt(part.length()-1)),"No split emoji");}
        check(Rules.chunks("").size()==1,"Empty chunk");
        check(Rules.retryMillis(0,0)==2000,"Initial retry");check(Rules.retryMillis(30,0)==15000,"Backoff cap");
        check(Rules.retryMillis(0,42)==42000,"Honor Telegram retry_after");check(Rules.retryMillis(0,Long.MAX_VALUE)==(Long.MAX_VALUE/2000L)*1000L,"Overflow safe retry");
        check(Rules.hash("one").equals(Rules.hash("one")),"Stable dedup");check(!Rules.hash("one").equals(Rules.hash("two")),"Different SMS IDs");
        check(Rules.service("UnlistedSender").equals("UnlistedSender"),"Unknown service stays original");
        check(Rules.service("12345").equals("12345"),"No guessed numerical service");
        check(SetupCode.create().matches("[0-9]{8}"),"Random setup code format");
        check(SetupCode.matches("01234567","01234567",2000,1000),"Leading zero preserved");
        check(!SetupCode.matches("01234567","11234567",2000,1000),"Wrong code denied");
        check(!SetupCode.matches("01234567","01234567",1000,1000),"Expired code denied");
        check(!SetupCode.matches("","",2000,1000),"Missing code denied");
        check(!SetupCode.matches(null,"01234567",2000,1000),"Null code denied");
        check(!SetupCode.matches("01234567","/start",2000,1000),"Command cannot authorize");
        System.out.println("Passed "+count+" core checks");
    }
}
