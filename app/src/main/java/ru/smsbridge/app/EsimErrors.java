package ru.smsbridge.app;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Only structured error codes leave the LPA; activation credentials and raw responses stay private. */
public final class EsimErrors {
    private EsimErrors() {}
    public static JSONObject download(String reason,int http,byte[] body,Exception network,byte[] apdu) throws Exception {
        JSONObject out=new JSONObject().put("error","profile_download_failed");
        String code="unknown";
        if(reason!=null&&reason.matches("ES10B_ERROR_REASON_[A-Z0-9_]{1,120}"))out.put("lpa_reason",reason);
        else reason="";
        if(reason.endsWith("INSUFFICIENT_MEMORY_FOR_PROFILE"))code="memory";
        else if(reason.endsWith("ICCID_ALREADY_EXISTS_ON_EUICC"))code="already_installed";
        else if(reason.contains("UNSUPPORTED_"))code="unsupported";
        else if(reason.endsWith("PPR_NOT_ALLOWED"))code="policy";
        else if(!reason.isEmpty()&&!reason.endsWith("UNDEFINED"))code="card_error";
        String subject="",serverReason="";
        if(body!=null&&body.length>0&&body.length<=1024*1024)try {
            JSONObject root=new JSONObject(new String(body,StandardCharsets.UTF_8));
            JSONObject header=root.optJSONObject("header"),status=header==null?null:header.optJSONObject("functionExecutionStatus");
            JSONObject data=status==null?null:status.optJSONObject("statusCodeData");
            if(data!=null){subject=numericCode(data.optString("subjectCode"));serverReason=numericCode(data.optString("reasonCode"));}
        } catch(Exception ignored) { /* HTML/error bodies are not forwarded. */ }
        if(!subject.isEmpty())out.put("subject_code",subject);
        if(!serverReason.isEmpty())out.put("reason_code",serverReason);
        if(code.equals("unknown")) {
            String pair=subject+"/"+serverReason;
            switch(pair) {
                case "8.1/4.8":code="memory";break;
                case "8.1.1/3.8":code="eid_mismatch";break;
                case "8.1.1/2.1":case "8.8/4.2":code="eid_unsupported";break;
                case "8.2/1.2":code="not_released";break;
                case "8.2/3.7":case "8.2.5/4.3":code="unavailable";break;
                case "8.2.6/3.8":code="activation_refused";break;
                case "8.2.7/2.2":code="pin_missing";break;
                case "8.2.7/3.8":code="pin_refused";break;
                case "8.2.7/6.4":case "8.8.5/6.4":code="retries";break;
                case "8.8.5/4.10":code="expired";break;
                default:if(!subject.isEmpty()&&!serverReason.isEmpty())code="server_error";
            }
        }
        if(code.equals("unknown")) {
            if(network instanceof javax.net.ssl.SSLException)code="tls";
            else if(network instanceof java.net.UnknownHostException)code="dns";
            else if(network!=null)code="network";
        }
        if(http>=100&&http<=599){out.put("http_status",http);if(code.equals("unknown")&&http!=200)code="http";}
        if(apdu!=null&&apdu.length>=2){int last=apdu.length-1,sw=((apdu[last-1]&255)<<8)|(apdu[last]&255);
            out.put("card_status",String.format(Locale.ROOT,"%04X",sw));
            if(sw!=0x9000&&code.equals("unknown"))code="card_error";
        }
        return out.put("category",code);
    }
    private static String numericCode(String value) {return value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){0,5}")?value:"";}
    static String memory(JSONObject info) {
        long bytes=info==null?-1:info.optLong("free_nvram_bytes",-1);
        return bytes<0?"Свободная память: карта не сообщила объём":"Свободная память: "+String.format(Locale.forLanguageTag("ru"),"%.1f",bytes/1024.0)+" КиБ ("+bytes+" байт)";
    }
    static String describe(JSONObject out) {
        String text;
        switch(out.optString("category")) {
            case "memory":text="Недостаточно памяти адаптера для этого профиля. Выбери ненужный профиль в /esim и удали его после подтверждения.";break;
            case "already_installed":text="Профиль с этим ICCID уже есть на адаптере. Обнови /esim.";break;
            case "unsupported":text="Карта или сервер не поддерживает параметры этого профиля.";break;
            case "eid_mismatch":text="Оператор ожидает другой EID: профиль привязан к другой карте.";break;
            case "eid_unsupported":text="Оператор не принимает EID этой карты.";break;
            case "not_released":text="Оператор ещё не выпустил профиль для загрузки.";break;
            case "unavailable":text="Оператор сообщает, что профиль недоступен для этой установки.";break;
            case "activation_refused":text="Оператор отклонил идентификатор активации из QR-кода.";break;
            case "pin_missing":text="Оператор требует код подтверждения профиля.";break;
            case "pin_refused":text="Оператор отклонил код подтверждения профиля.";break;
            case "retries":text="Оператор сообщил о превышении числа попыток загрузки или ввода кода.";break;
            case "expired":text="Срок действия заказа eSIM истёк по ответу оператора.";break;
            case "policy":text="Правила профиля запрещают эту операцию.";break;
            case "tls":text="Не удалось установить защищённое соединение с сервером eSIM.";break;
            case "dns":text="Телефон не может найти сервер eSIM. Проверь интернет и адрес сервера в QR.";break;
            case "network":text="Соединение с сервером eSIM прервалось или истекло время ожидания.";break;
            case "http":text="Сервер eSIM вернул ошибку HTTP.";break;
            case "server_error":text="Сервер eSIM отклонил запрос. Коды ответа приведены ниже.";break;
            case "card_error":text="Карта не завершила установку профиля. Код карты приведён ниже.";break;
            default:text="Установка eSIM не завершена. Точная причина по полученным данным не определена.";
        }
        if(out.has("free_nvram_bytes"))text+="\n"+memory(out);
        if(out.has("http_status"))text+="\nHTTP: "+out.optInt("http_status");
        String subject=numericCode(out.optString("subject_code")),reason=numericCode(out.optString("reason_code"));
        if(!subject.isEmpty()&&!reason.isEmpty())text+="\nКод сервера: "+subject+" / "+reason;
        String lpa=out.optString("lpa_reason");if(lpa.matches("ES10B_ERROR_REASON_[A-Z0-9_]{1,120}"))text+="\nКод LPA: "+lpa;
        String sw=out.optString("card_status");if(sw.matches("[0-9A-F]{4}"))text+="\nКод карты: "+sw;
        return text+"\nПеред новой попыткой проверь /esim: профиль мог сохраниться, даже если ответ потерялся.";
    }
}
