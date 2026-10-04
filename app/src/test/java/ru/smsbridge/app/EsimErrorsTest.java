package ru.smsbridge.app;

import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class EsimErrorsTest {
    private byte[] server(String subject,String reason) throws Exception {
        return new JSONObject().put("header",new JSONObject().put("functionExecutionStatus",new JSONObject()
            .put("statusCodeData",new JSONObject().put("subjectCode",subject).put("reasonCode",reason)
                .put("message","LPA:1$secret.example$private-token").put("subjectIdentifier","private-eid"))))
            .toString().getBytes(StandardCharsets.UTF_8);
    }
    @Test public void nativeMemoryErrorNamesRealCause() throws Exception {
        JSONObject r=EsimErrors.download("ES10B_ERROR_REASON_INSTALL_FAILED_DUE_TO_INSUFFICIENT_MEMORY_FOR_PROFILE",200,null,null,null);
        assertEquals("memory",r.getString("category"));assertTrue(EsimErrors.describe(r).contains("Недостаточно памяти"));
    }
    @Test public void http200CanContainServerMemoryRefusal() throws Exception {
        JSONObject r=EsimErrors.download("ES10B_ERROR_REASON_UNDEFINED",200,server("8.1","4.8"),null,null);
        assertEquals("memory",r.getString("category"));assertTrue(EsimErrors.describe(r).contains("8.1 / 4.8"));
    }
    @Test public void duplicateIsNotReportedAsMemory() throws Exception {
        JSONObject r=EsimErrors.download("ES10B_ERROR_REASON_INSTALL_FAILED_DUE_TO_ICCID_ALREADY_EXISTS_ON_EUICC",200,null,null,null);
        assertEquals("already_installed",r.getString("category"));
    }
    @Test public void operatorErrorsRetainDistinctCauses() throws Exception {
        String[][] cases={{"8.1.1","3.8","eid_mismatch"},{"8.2.6","3.8","activation_refused"},
            {"8.2.7","2.2","pin_missing"},{"8.2.7","3.8","pin_refused"},{"8.8.5","4.10","expired"},
            {"8.2.5","4.3","unavailable"},{"8.8.5","6.4","retries"},{"8.2","1.2","not_released"}};
        for(String[] c:cases)assertEquals(c[2],EsimErrors.download(null,200,server(c[0],c[1]),null,null).getString("category"));
    }
    @Test public void networkFailureDoesNotBlameOperatorOrExposeMessage() throws Exception {
        JSONObject r=EsimErrors.download(null,0,null,new java.net.SocketTimeoutException("private-token"),null);
        assertEquals("network",r.getString("category"));assertFalse(r.toString().contains("private-token"));
    }
    @Test public void tlsAndDnsHaveSeparateDiagnostics() throws Exception {
        assertEquals("tls",EsimErrors.download(null,0,null,new javax.net.ssl.SSLException("secret"),null).getString("category"));
        assertEquals("dns",EsimErrors.download(null,0,null,new java.net.UnknownHostException("secret"),null).getString("category"));
    }
    @Test public void nativeFailureTakesPrecedenceOverEarlierTransportError() throws Exception {
        assertEquals("memory",EsimErrors.download("ES10B_ERROR_REASON_INSTALL_FAILED_DUE_TO_INSUFFICIENT_MEMORY_FOR_PROFILE",0,null,new java.net.SocketException(),null).getString("category"));
    }
    @Test public void malformedBodyStillShowsHttpFailure() throws Exception {
        JSONObject r=EsimErrors.download(null,503,"<html>private-token</html>".getBytes(StandardCharsets.UTF_8),null,null);
        assertEquals("http",r.getString("category"));assertTrue(EsimErrors.describe(r).contains("HTTP: 503"));assertFalse(r.toString().contains("private-token"));
    }
    @Test public void unknownFailureDoesNotInventCause() throws Exception {
        String text=EsimErrors.describe(EsimErrors.download(null,200,null,null,new byte[]{(byte)0x90,0}));
        assertTrue(text.contains("Точная причина"));assertFalse(text.contains("Оператор отклонил"));assertFalse(text.contains("Недостаточно памяти"));
    }
    @Test public void rawResponseFieldsAndActivationSecretsAreNotReturned() throws Exception {
        JSONObject r=EsimErrors.download("private-token",200,server("8.1","4.8"),null,null);
        assertFalse(r.toString().contains("private"));assertFalse(r.has("lpa_reason"));assertFalse(EsimErrors.describe(r).contains("secret"));
    }
    @Test public void unexpectedServerCodesCannotSmuggleResponseText() throws Exception {
        JSONObject r=EsimErrors.download(null,200,server("secret.example","private-token"),null,null);
        assertFalse(r.has("subject_code"));assertFalse(r.has("reason_code"));assertEquals("unknown",r.getString("category"));
    }
    @Test public void apduExportsOnlyTwoStatusBytes() throws Exception {
        JSONObject r=EsimErrors.download(null,0,null,null,new byte[]{1,2,3,4,(byte)0x6A,(byte)0x84});
        assertEquals("6A84",r.getString("card_status"));assertEquals("card_error",r.getString("category"));assertFalse(r.toString().contains("01020304"));
    }
    @Test public void memoryIsBytesAndZeroIsValid() throws Exception {
        assertTrue(EsimErrors.memory(new JSONObject().put("free_nvram_bytes",2048)).contains("2,0 КиБ (2048 байт)"));
        assertTrue(EsimErrors.memory(new JSONObject().put("free_nvram_bytes",0)).contains("0 байт"));
        assertTrue(EsimErrors.memory(null).contains("не сообщила"));
    }
}
