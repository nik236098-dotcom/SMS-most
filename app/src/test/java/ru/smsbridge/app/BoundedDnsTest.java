package ru.smsbridge.app;

import okhttp3.Dns;
import org.junit.Test;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class BoundedDnsTest {
    @Test public void stuckSystemDnsCannotHangCallerOrCreateUnlimitedThreads() throws Exception {
        CountDownLatch release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        ThreadPoolExecutor pool=new ThreadPoolExecutor(0,2,1,TimeUnit.SECONDS,new SynchronousQueue<>());
        Dns blocked=host->{calls.incrementAndGet();for(;;)try{release.await();break;}catch(InterruptedException ignored){}return java.util.Collections.singletonList(InetAddress.getLoopbackAddress());};
        try {
            BoundedDns dns=new BoundedDns(blocked,pool,50);
            for(int i=0;i<20;i++)assertThrows(UnknownHostException.class,()->dns.lookup("example.test"));
            assertEquals(2,calls.get());assertEquals(2,pool.getLargestPoolSize());
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test public void resolverFailureDoesNotPoisonNextLookup() throws Exception {
        ExecutorService pool=Executors.newSingleThreadExecutor();AtomicInteger calls=new AtomicInteger();
        try{BoundedDns dns=new BoundedDns(host->{if(calls.incrementAndGet()==1)throw new UnknownHostException();return java.util.Collections.singletonList(InetAddress.getLoopbackAddress());},pool,1000);
            assertThrows(UnknownHostException.class,()->dns.lookup("example.test"));assertEquals(1,dns.lookup("example.test").size());
        }finally{pool.shutdownNow();}
    }
}
