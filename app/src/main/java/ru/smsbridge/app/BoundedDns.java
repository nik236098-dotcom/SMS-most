package ru.smsbridge.app;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.*;
import okhttp3.Dns;

/** An OS resolver can ignore socket timeouts. Bound both wait time and resolver thread count. */
final class BoundedDns implements Dns {
    private final Dns delegate;private final ExecutorService executor;private final long timeoutMillis;
    static BoundedDns system() {
        ThreadPoolExecutor pool=new ThreadPoolExecutor(0,2,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"smsbridge-dns");t.setDaemon(true);return t;});
        return new BoundedDns(Dns.SYSTEM,pool,8000);
    }
    BoundedDns(Dns delegate,ExecutorService executor,long timeoutMillis){this.delegate=delegate;this.executor=executor;this.timeoutMillis=timeoutMillis;}
    @Override public List<InetAddress> lookup(String host) throws UnknownHostException {
        Future<List<InetAddress>> task=null;
        try{task=executor.submit(()->delegate.lookup(host));return task.get(timeoutMillis,TimeUnit.MILLISECONDS);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new UnknownHostException("DNS cancelled");}
        catch(ExecutionException e){if(e.getCause() instanceof UnknownHostException)throw (UnknownHostException)e.getCause();throw new UnknownHostException("DNS failed");}
        catch(TimeoutException|RejectedExecutionException e){throw new UnknownHostException("DNS unavailable or timed out");}
        finally{if(task!=null&&!task.isDone())task.cancel(true);}
    }
}
