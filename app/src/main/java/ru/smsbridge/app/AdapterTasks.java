package ru.smsbridge.app;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    enum Submission { STARTED, QUEUED, BUSY }
    private static final class Task {
        final Runnable accepted,work,slow,finished;final boolean background;final AtomicBoolean completed=new AtomicBoolean();
        Task(boolean background,Runnable accepted,Runnable work,Runnable slow,Runnable finished){this.background=background;this.accepted=accepted;this.work=work;this.slow=slow;this.finished=finished;}
    }
    private Task active,waiting;
    AdapterTasks(Executor worker,ScheduledExecutorService timer,long warningMillis) {
        this.worker=worker;this.timer=timer;this.warningMillis=warningMillis;
    }
    synchronized boolean busy(){return active!=null;}
    boolean submit(Runnable accepted,Runnable work,Runnable slow,Runnable finished) {
        return submit(new Task(false,accepted,work,slow,finished),false)==Submission.STARTED;
    }
    boolean submitBackground(Runnable accepted,Runnable work,Runnable slow,Runnable finished) {
        return submit(new Task(true,accepted,work,slow,finished),false)==Submission.STARTED;
    }
    Submission submitUser(Runnable accepted,Runnable work,Runnable slow,Runnable finished) {
        return submit(new Task(false,accepted,work,slow,finished),true);
    }
    private Submission submit(Task ticket,boolean queueBehindBackground) {
        synchronized(this) {
            if(active!=null){
                if(queueBehindBackground&&active.background&&waiting==null){waiting=ticket;return Submission.QUEUED;}
                return Submission.BUSY;
            }
            active=ticket;
        }
        launch(ticket);return Submission.STARTED;
    }
    private synchronized boolean owns(Task ticket){return active==ticket;}
    private void launch(Task ticket) {
        try {
            ticket.accepted.run();
            ScheduledFuture<?> alarm=timer.schedule(()->{if(owns(ticket))ticket.slow.run();},warningMillis,TimeUnit.MILLISECONDS);
            try {worker.execute(()->{
                try {ticket.work.run();}
                finally {
                    try {alarm.cancel(false);}finally {complete(ticket);}
                }
            });}catch(RuntimeException e){alarm.cancel(false);throw e;}
        } catch(RuntimeException e) {
            complete(ticket);
            throw e;
        }
    }
    private void complete(Task ticket) {
        if(!ticket.completed.compareAndSet(false,true))return;
        try {ticket.finished.run();}
        finally {
            Task next=null;
            synchronized(this){if(active==ticket){next=waiting;waiting=null;active=next;}}
            // Transfer ownership directly: another background scan cannot get ahead of this user.
            if(next!=null)launch(next);
        }
    }
}
