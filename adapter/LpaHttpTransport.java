// SPDX-License-Identifier: GPL-3.0-or-later
package net.typeblog.lpac_jni.impl;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.*;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.*;

/** Bound the complete operator request, including DNS, upload, TLS and slow response bodies. */
public final class LpaHttpTransport {
    private static final ThreadPoolExecutor DNS_POOL=new ThreadPoolExecutor(0,2,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"smsbridge-lpa-dns");t.setDaemon(true);return t;});
    static final Dns DNS=boundedDns(Dns.SYSTEM,DNS_POOL,2000);
    private static final OkHttpClient BASE=new OkHttpClient.Builder().dns(DNS)
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).writeTimeout(15,TimeUnit.SECONDS)
        .callTimeout(45,TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    private static final long MAX_RESPONSE=4*1024*1024;
    static Dns boundedDns(Dns resolver,ExecutorService pool,long timeoutMillis){return host->{
        Future<List<InetAddress>> task=null;
        try{task=pool.submit(()->resolver.lookup(host));return task.get(timeoutMillis,TimeUnit.MILLISECONDS);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new UnknownHostException("Operator DNS cancelled");}
        catch(ExecutionException|TimeoutException|RejectedExecutionException e){throw new UnknownHostException("Operator DNS unavailable");}
        finally{if(task!=null&&!task.isDone())task.cancel(true);}
    };}
    public static final class Response {
        public final int code;public final byte[] body;
        Response(int code,byte[] body){this.code=code;this.body=body;}
    }
    public static Response transmit(String url,byte[] data,String[] headers,SSLSocketFactory sockets,X509TrustManager trust) throws IOException {
        OkHttpClient client=BASE.newBuilder().sslSocketFactory(sockets,trust).build();
        return transmit(client,url,data,headers,5000,45000);
    }
    static Response transmit(OkHttpClient base,String url,byte[] data,String[] headers,long notificationMillis,long ordinaryMillis) throws IOException {
        HttpUrl parsed=HttpUrl.get(url);
        if(!parsed.isHttps())throw new IOException("SM-DP+ requires HTTPS");
        boolean notification=parsed.encodedPath().endsWith("/handleNotification");
        OkHttpClient client=base.newBuilder().callTimeout(notification?notificationMillis:ordinaryMillis,TimeUnit.MILLISECONDS).build();
        Request.Builder request=new Request.Builder().url(parsed).post(RequestBody.create(data,null));
        for(String header:headers){int at=header.indexOf(':');if(at<1)throw new IOException("Invalid operator header");request.addHeader(header.substring(0,at).trim(),header.substring(at+1).trim());}
        try(okhttp3.Response response=client.newCall(request.build()).execute()){
            if(response.code()>=300&&response.code()<400)throw new IOException("Unexpected operator redirect");
            ResponseBody body=response.body();if(body==null)throw new IOException("Empty operator response");
            if(body.contentLength()>MAX_RESPONSE||body.source().request(MAX_RESPONSE+1))throw new IOException("Operator response too large");
            return new Response(response.code(),body.bytes());
        }
    }
}
