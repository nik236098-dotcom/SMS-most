package ru.smsbridge.app;

/** Timings contain stage names only, never activation codes or SIM identifiers. */
public final class AdapterProgress {
    private static long started;private static boolean switching;private static String stage="";private static final StringBuilder times=new StringBuilder();
    private static void put(String key,String value){try{Store s=BridgeApp.store();if(s!=null)s.put(key,value);}catch(RuntimeException ignored){}}
    public static synchronized void start(String value,boolean isSwitch){times.setLength(0);stage="";switching=isSwitch;phase(value);}
    public static synchronized void phase(String value){
        long now=System.nanoTime();
        if(!stage.isEmpty()){if(times.length()>0)times.append(" · ");times.append(stage).append(": ").append(Math.max(0,(now-started)/1000000000L)).append(" с");}
        started=now;stage=value;put("esim_native_phase",value);
    }
    public static synchronized void finish(){phase("");if(switching)put("esim_switch_timing",times.toString());}
}
