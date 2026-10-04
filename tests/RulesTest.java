package ru.smsbridge.app;
import java.util.List;

public final class RulesTest {
    private static int count;
    static void check(boolean ok,String message){count++;if(!ok)throw new AssertionError(message);}
    static void rejects(Runnable r){boolean rejected=false;try{r.run();}catch(IllegalArgumentException e){rejected=true;}check(rejected,"Invalid value accepted");}
    public static void main(String[] args) {
        check(Rules.phone("+7 (999) 123-45-67").equals("+79991234567"),"Phone normalization");
        check(Rules.phone("+84901234567").equals("+84901234567"),"International phone");
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
        check(Rules.retryMillis(0,0)==15000,"Initial retry");check(Rules.retryMillis(30,0)==900000,"Backoff cap");
        check(Rules.retryMillis(0,42)==42000,"Honor Telegram retry_after");check(Rules.retryMillis(0,Long.MAX_VALUE)==86400000,"Overflow safe retry");
        check(Rules.hash("one").equals(Rules.hash("one")),"Stable dedup");check(!Rules.hash("one").equals(Rules.hash("two")),"Different SMS IDs");
        check(Rules.service("UnlistedSender").equals("UnlistedSender"),"Unknown service stays original");
        check(Rules.service("12345").equals("12345"),"No guessed numerical service");
        System.out.println("Passed "+count+" core checks");
    }
}
