package net.typeblog.lpac_jni.impl;

import okhttp3.*;
import okhttp3.mockwebserver.*;
import okhttp3.tls.*;
import org.junit.*;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real TLS sockets cover the operator notification path that holds the SIM channel. */
public class LpaHttpTransportTest {
    MockWebServer server;OkHttpClient client;String root;
    @Before public void setup() throws Exception {
        HeldCertificate cert=new HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build();
        HandshakeCertificates serverTls=new HandshakeCertificates.Builder().heldCertificate(cert).build();
        HandshakeCertificates clientTls=new HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate()).build();
        server=new MockWebServer();server.useHttps(serverTls.sslSocketFactory(),false);server.start();
        client=new OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(),clientTls.trustManager())
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
        root=server.url("/").newBuilder().host("127.0.0.1").build().toString();
    }
    @After public void finish() throws Exception {client.connectionPool().evictAll();client.dispatcher().executorService().shutdownNow();server.shutdown();}
    LpaHttpTransport.Response send(String path) throws Exception {return LpaHttpTransport.transmit(client,root+path,"{}".getBytes(),new String[]{"Content-Type: application/json"},500,2500);}
    @Test public void notificationDeadlineReleasesChannelWaitAndNextRequestWorks() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));server.enqueue(new MockResponse().setBody("ok"));
        long start=System.nanoTime();assertThrows(IOException.class,()->send("gsma/rsp2/es9plus/handleNotification"));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2200);
        assertEquals("ok",new String(send("gsma/rsp2/es9plus/handleNotification").body));assertEquals(2,server.getRequestCount());
    }
    @Test public void slowTrickleCannotKeepNotificationAlive() {
        server.enqueue(new MockResponse().setBody("12345678901234567890").throttleBody(1,100,TimeUnit.MILLISECONDS));
        long start=System.nanoTime();assertThrows(IOException.class,()->send("handleNotification"));assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2200);
    }
    @Test public void profileDownloadRetainsLongerBudget() throws Exception {
        server.enqueue(new MockResponse().setBody("profile").setBodyDelay(700,TimeUnit.MILLISECONDS));
        assertEquals("profile",new String(send("getBoundProfilePackage").body));
    }
    @Test public void lostResponseIsNotRetriedBehindCallersBack() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));server.enqueue(new MockResponse().setBody("ok"));
        assertThrows(IOException.class,()->send("handleNotification"));assertEquals(1,server.getRequestCount());
    }
    @Test public void redirectsCannotSendActivationPayloadToAnotherEndpoint() {
        server.enqueue(new MockResponse().setResponseCode(307).setHeader("Location",root+"other"));
        assertThrows(IOException.class,()->send("getBoundProfilePackage"));assertEquals(1,server.getRequestCount());
    }
    @Test public void untrustedCertificateCannotReadOrWriteActivationData() {
        server.enqueue(new MockResponse().setBody("ok"));
        OkHttpClient untrusted=new OkHttpClient.Builder().retryOnConnectionFailure(false).build();
        assertThrows(IOException.class,()->LpaHttpTransport.transmit(untrusted,root+"getBoundProfilePackage",new byte[0],new String[0],500,2500));
        assertEquals(0,server.getRequestCount());untrusted.connectionPool().evictAll();
    }
    @Test public void plaintextEndpointIsRejectedBeforeSending() {
        assertThrows(IOException.class,()->LpaHttpTransport.transmit(client,root.replace("https:","http:"),new byte[0],new String[0],500,2500));assertEquals(0,server.getRequestCount());
    }
    @Test public void dnsThatIgnoresInterruptsHasBoundedWaitAndThreadCount() throws Exception {
        CountDownLatch release=new CountDownLatch(1),entered=new CountDownLatch(1);
        ExecutorService pool=new ThreadPoolExecutor(0,1,30,TimeUnit.SECONDS,new SynchronousQueue<>());
        try {
            Dns dns=LpaHttpTransport.boundedDns(host->{entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}return Dns.SYSTEM.lookup("localhost");},pool,80);
            long start=System.nanoTime();assertThrows(UnknownHostException.class,()->dns.lookup("test.invalid"));assertTrue(entered.await(1,TimeUnit.SECONDS));
            assertThrows(UnknownHostException.class,()->dns.lookup("test.invalid"));assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000);
            assertEquals(1,((ThreadPoolExecutor)pool).getLargestPoolSize());
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test public void oversizedResponseDoesNotExhaustMemory() {
        server.enqueue(new MockResponse().setBody("small").setHeader("Content-Length",5*1024*1024));
        assertThrows(IOException.class,()->send("getBoundProfilePackage"));
    }
    @Test public void operatorErrorResponseRemainsAvailableForDiagnosis() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"test\"}"));
        LpaHttpTransport.Response response=send("getBoundProfilePackage");assertEquals(400,response.code);assertTrue(new String(response.body).contains("test"));
    }
}
