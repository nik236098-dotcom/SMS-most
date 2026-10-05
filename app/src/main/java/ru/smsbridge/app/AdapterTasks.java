package ru.smsbridge.app;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One adapter task per process. Waiting for the card never occupies Telegram polling. */
final class AdapterTasks {
    private static final class Shared {
        static final AdapterTasks INSTANCE=new AdapterTasks(Executors.newSingleThreadExecutor(),
            Executors.newSingleThreadScheduledExecutor(),45000);
    }
    static AdapterTasks shared(){return Shared.INSTANCE;}
    private final Executor worker;
    private final ScheduledExecutorService timer;
    private final long warningMillis;
    private final AtomicReference<Object> active=new AtomicReference<>();
    AdapterTasks(Executor worker,ScheduledExecutorService timer,long warningMillis) {
        this.worker=worker;this.timer=timer;this.warningMillis=warningMillis;
    }
    boolean busy(){return active.get()!=null;}
    boolean submit(Runnable accepted,Runnable work,Runnable slow,Runnable finished) {
        Object ticket=new Object();if(!active.compareAndSet(null,ticket))return false;
        try {
            accepted.run();
            ScheduledFuture<?> alarm=timer.schedule(()->{if(active.get()==ticket)slow.run();},warningMillis,TimeUnit.MILLISECONDS);
            try {worker.execute(()->{
                try {work.run();}
                finally {
                    alarm.cancel(false);
                    try {finished.run();}finally {active.compareAndSet(ticket,null);}
                }
            });}catch(RuntimeException e){alarm.cancel(false);throw e;}
            return true;
        } catch(RuntimeException e) {
            try {finished.run();}finally {active.compareAndSet(ticket,null);}
            throw e;
        }
    }
}
