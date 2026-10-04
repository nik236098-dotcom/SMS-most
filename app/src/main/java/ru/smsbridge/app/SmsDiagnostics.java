package ru.smsbridge.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class SmsDiagnostics {
    private SmsDiagnostics() {}
    static String report(Store s) {
        String result="Разрешение SMS: "+(s.smsPermission()?"есть":"нет")
            +"\nПриём SMS: "+(!s.running()?"остановлен":s.enabled()?"включён":"нужно разрешение Android")
            +"\nСигнал о новом SMS: "+time(s.get("sms_last_broadcast","0"))
            +"\nПоследнее сохранение: "+time(s.get("sms_last_saved","0"))
            +"\nРабота сервиса: "+time(s.get("service_last_work","0"))
            +"\nЗапрос запуска сервиса: "+time(s.get("service_last_request","0"))
            +"\nРезервная отправка: "+time(s.get("service_last_retry","0"));
        String state=s.get("sms_result","");if(!state.isEmpty())result+="\n"+state;
        String warning=s.get("sms_sim_warning","");if(!warning.isEmpty())result+="\n"+warning;
        String error=s.get("sms_receive_error","");if(!error.isEmpty())result+="\nОшибка приёма: "+error;
        for(String key:new String[]{"service_start_error","service_schedule_error"}) {
            String failure=s.get(key,"");if(!failure.isEmpty())result+="\n"+failure;
        }
        return result;
    }
    static String time(String value) {
        try {long t=Long.parseLong(value);return t>0?new SimpleDateFormat("dd.MM HH:mm:ss",Locale.getDefault()).format(new Date(t)):"пока не было";}
        catch(NumberFormatException e){return "неизвестно";}
    }
}
