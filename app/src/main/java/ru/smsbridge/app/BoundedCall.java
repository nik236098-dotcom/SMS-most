package ru.smsbridge.app;

import java.util.concurrent.*;

/**
 * Telephony binder calls can stall while the modem reloads a SIM. Commands answered on the
 * polling thread must never wait for that: bound the wait and the number of stuck threads.
 */
final class BoundedCall {
    private static final ThreadPoolExecutor POOL=new ThreadPoolExecutor(0,2,30,TimeUnit.SECONDS,new SynchronousQueue<>(),
        r->{Thread t=new Thread(r,"smsbridge-telephony");t.setDaemon(true);return t;});
    private BoundedCall() {}
    static <T> T run(Callable<T> call,long millis) throws Exception {
        Future<T> task;
        try{task=POOL.submit(call);}catch(RejectedExecutionException e){throw new UserError("Android сейчас не отвечает на запрос SIM-карт. Повтори через минуту.");}
        try{return task.get(millis,TimeUnit.MILLISECONDS);}
        catch(TimeoutException e){task.cancel(true);throw new UserError("Android сейчас не отвечает на запрос SIM-карт. Повтори через минуту.");}
        catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IllegalStateException(cause);}
    }
}
