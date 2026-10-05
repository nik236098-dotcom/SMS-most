package ru.smsbridge.app;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class RelayRecoveryTest {
    @Test public void repeatedStartDoesNotPostponePendingPeriodicRetry() {
        Context c=mock(Context.class);JobScheduler jobs=mock(JobScheduler.class);
        when(c.getSystemService(JobScheduler.class)).thenReturn(jobs);
        when(jobs.getPendingJob(7701)).thenReturn(mock(JobInfo.class));
        RelayService.schedule(c);RelayService.schedule(c);
        verify(jobs,never()).schedule(any());
    }
    @Test public void schedulerFailureDoesNotPreventForegroundStart() {
        Context c=mock(Context.class);Store s=mock(Store.class);when(s.running()).thenReturn(true);
        try(MockedStatic<BridgeApp> app=mockStatic(BridgeApp.class);
            MockedStatic<RelayService> relay=mockStatic(RelayService.class,CALLS_REAL_METHODS);
            MockedConstruction<Intent> intents=mockConstruction(Intent.class)) {
            app.when(BridgeApp::store).thenReturn(s);
            relay.when(()->RelayService.schedule(c)).thenThrow(new IllegalStateException("private details"));
            RelayService.start(c);
            verify(c).startForegroundService(any(Intent.class));
            verify(s).put(eq("service_schedule_error"),argThat(text->text.contains("IllegalStateException")&&!text.contains("private details")));
        }
    }
    @Test public void rejectedStartRecordsReasonWithoutClearingRunningSetting() {
        Context c=mock(Context.class);Store s=mock(Store.class);when(s.running()).thenReturn(true);
        when(c.startForegroundService(any())).thenThrow(new IllegalStateException("private details"));
        try(MockedStatic<BridgeApp> app=mockStatic(BridgeApp.class);
            MockedStatic<RelayService> relay=mockStatic(RelayService.class,CALLS_REAL_METHODS);
            MockedConstruction<Intent> intents=mockConstruction(Intent.class)) {
            app.when(BridgeApp::store).thenReturn(s);relay.when(()->RelayService.schedule(c)).thenAnswer(i->null);
            assertThrows(IllegalStateException.class,()->RelayService.start(c));
            verify(s).put(eq("service_start_error"),contains("IllegalStateException"));
            verify(s,never()).put(eq("bot_enabled"),anyString());
        }
    }
    @Test public void stoppedBotDoesNotRestartFromRecovery() {
        Context c=mock(Context.class);Store s=mock(Store.class);
        try(MockedStatic<BridgeApp> app=mockStatic(BridgeApp.class)) {
            app.when(BridgeApp::store).thenReturn(s);RelayService.start(c);verifyNoInteractions(c);
        }
    }
    @Test public void unavailableDeviceStatusDoesNotPreventOutboxDelivery() {
        Store s=mock(Store.class);when(s.running()).thenReturn(true);
        RelayService service=mock(RelayService.class);doCallRealMethod().when(service).deliver();
        try(MockedStatic<BridgeApp> app=mockStatic(BridgeApp.class);MockedStatic<Outbox> outbox=mockStatic(Outbox.class)) {
            app.when(BridgeApp::store).thenReturn(s);service.deliver();
            outbox.verify(()->Outbox.drain(service));
        }
    }
}
