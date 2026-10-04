package ru.smsbridge.app;

final class CallDiagnostics {
    static String report(Store store) {
        String status=!store.callsEnabled()?"выключены":!store.running()?"бот остановлен":!store.phonePermission()?"нужно разрешение «Телефон»":
            store.callLogPermission()?"включены":"включены, номера недоступны без «Журнала вызовов»";
        String text="Входящие звонки: "+status+"\nРазрешение «Телефон»: "+(store.phonePermission()?"есть":"нет")
            +"\nРазрешение «Журнал вызовов»: "+(store.callLogPermission()?"есть":"нет")
            +"\nСобытие звонка: "+SmsDiagnostics.time(store.get("call_last_broadcast","0"))
            +"\nПоследний сохранённый звонок: "+SmsDiagnostics.time(store.get("call_last_saved","0"));
        for(String key:new String[]{"call_result","call_receive_error","call_sim_warning"}) {
            String value=store.get(key,"");if(!value.isEmpty())text+="\n"+value;
        }
        return text;
    }
}
