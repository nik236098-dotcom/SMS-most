package ru.smsbridge.app;

final class BotHealth {
    static String summary(Store s) {
        if(!s.running())return "Бот остановлен";
        long wait=s.telegramRemaining();if(wait>0)return "Пауза Telegram: ещё "+((wait+999)/1000)+" сек.";
        long now=System.currentTimeMillis(),loop=time(s,"bot_loop_seen"),seen=time(s,"bot_last_seen");
        if(loop==0||now-loop>90000)return "Приём команд не подтверждён";
        if(!s.get("bot_error","").isEmpty())return "Восстанавливаем связь с Telegram";
        return seen>0&&now-seen<60000?"Бот на связи":"Подключаемся к Telegram";
    }
    static String details(Store s) {
        return summary(s)+"\nПоследний ответ на приём команд: "+SmsDiagnostics.time(s.get("bot_last_seen","0"))
            +"\nПоследняя доставка: "+SmsDiagnostics.time(s.get("last_delivery","0"))
            +(s.get("connection_recovery","").isEmpty()?"":"\n"+s.get("connection_recovery",""));
    }
    private static long time(Store s,String name){try{return Long.parseLong(s.get(name,"0"));}catch(NumberFormatException e){return 0;}}
}
