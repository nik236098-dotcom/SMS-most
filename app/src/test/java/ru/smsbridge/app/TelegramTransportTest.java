package ru.smsbridge.app;

import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.json.JSONObject;
import org.junit.*;
import java.io.IOException;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real local sockets reproduce stalled headers/body and connection cancellation. */
public class TelegramTransportTest {
    static final String TOKEN="123456:abcdefghijklmnopqrstuv";
    MockWebServer server;OkHttpClient client;Telegram api;ExecutorService worker;
    @Before public void setup() throws Exception {
        server=new MockWebServer();server.start();worker=Executors.newSingleThreadExecutor();
        client=new OkHttpClient.Builder().connectTimeout(1,TimeUnit.SECONDS).readTimeout(3,TimeUnit.SECONDS).writeTimeout(1,TimeUnit.SECONDS)
            .callTimeout(400,TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
        // MockWebServer listens on IPv4 here; localhost also resolves to an unbound IPv6 port.
        api=new Telegram(TOKEN,client,server.url("/").newBuilder().host("127.0.0.1").build().toString());
    }
    @After public void finish() throws Exception {api.close();worker.shutdownNow();client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();server.shutdown();assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS));}
    MockResponse ok(){return new MockResponse().setBody("{\"ok\":true,\"result\":[]}");}
    @Test public void silentServerTimesOutAndFollowingCallRecovers() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));server.enqueue(ok());
        long start=System.nanoTime();assertThrows(IOException.class,()->api.call("getUpdates",new JSONObject()));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2500);
        assertTrue(api.call("getUpdates",new JSONObject()).getBoolean("ok"));assertEquals(2,server.getRequestCount());
    }
    @Test public void slowTrickleCannotExtendCallForever() {
        server.enqueue(ok().throttleBody(1,50,TimeUnit.MILLISECONDS));long start=System.nanoTime();
        assertThrows(IOException.class,()->api.call("getUpdates",new JSONObject()));assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2500);
    }
    @Test public void closingSessionCancelsActiveRequestAndRejectsNewCalls() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        Future<?> pending=worker.submit(()->{try{api.call("getUpdates",new JSONObject());throw new AssertionError("request succeeded");}catch(IOException expected){}catch(Exception e){throw new RuntimeException(e);}});
        assertNotNull(server.takeRequest(2,TimeUnit.SECONDS));api.close();pending.get(2,TimeUnit.SECONDS);
        assertThrows(IOException.class,()->api.call("getUpdates",new JSONObject()));assertEquals(1,server.getRequestCount());
    }
    @Test public void networkReconnectionCancelsPollAndAllowsNewPoll() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));server.enqueue(ok());
        Future<?> pending=worker.submit(()->{try{api.call("getUpdates",new JSONObject());throw new AssertionError();}catch(IOException expected){}catch(Exception e){throw new RuntimeException(e);}});
        assertNotNull(server.takeRequest(2,TimeUnit.SECONDS));api.reconnectPolling();pending.get(2,TimeUnit.SECONDS);
        assertFalse(api.closed());assertTrue(api.call("getUpdates",new JSONObject()).getBoolean("ok"));
    }
    @Test public void reconnectingPollDoesNotCancelSendingMessage() throws Exception {
        server.enqueue(ok().setBodyDelay(150,TimeUnit.MILLISECONDS));
        Future<JSONObject> pending=worker.submit(()->api.call("sendMessage",new JSONObject().put("text","test")));
        assertNotNull(server.takeRequest(2,TimeUnit.SECONDS));api.reconnectPolling();assertTrue(pending.get(2,TimeUnit.SECONDS).getBoolean("ok"));
    }
    @Test public void lostSendResponseDoesNotTriggerInvisibleTransportRetry() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));server.enqueue(ok());
        assertThrows(IOException.class,()->api.call("sendMessage",new JSONObject().put("text","test")));assertEquals(1,server.getRequestCount());
    }
    @Test public void redirectsDoNotForwardTokenToAnotherEndpoint() {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location",server.url("/untrusted")));
        assertEquals(302,assertThrows(Telegram.ApiError.class,()->api.call("getUpdates",new JSONObject())).code);assertEquals(1,server.getRequestCount());
    }
    @Test public void floodWaitIsRetainedWithoutRetryOrSensitiveErrorText() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"ok\":false,\"error_code\":429,\"description\":\"private token\",\"parameters\":{\"retry_after\":123}}"));
        Telegram.ApiError error=assertThrows(Telegram.ApiError.class,()->api.call("sendMessage",new JSONObject()));assertEquals(123,error.retry);assertFalse(Telegram.safe(error).contains("private"));assertEquals(1,server.getRequestCount());
    }
    @Test public void malformedResponseDoesNotPreventNextRequest() throws Exception {
        server.enqueue(new MockResponse().setBody("not json"));server.enqueue(ok());
        assertThrows(Exception.class,()->api.call("getUpdates",new JSONObject()));assertTrue(api.call("getUpdates",new JSONObject()).getBoolean("ok"));
    }
}
