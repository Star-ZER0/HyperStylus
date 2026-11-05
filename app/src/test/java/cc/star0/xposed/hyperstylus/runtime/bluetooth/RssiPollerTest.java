package cc.star0.xposed.hyperstylus.runtime.bluetooth;

import cc.star0.xposed.hyperstylus.model.pen.SignalState;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.os.Handler;
import android.os.Looper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class RssiPollerTest {
    @Test public void timeoutDoesNotStackRequestsAndLateReplyIsUnknown() {
        AtomicInteger reads = new AtomicInteger();
        List<Integer> signal = new ArrayList<>();
        Object gatt = new Object();
        RssiPoller poller = new RssiPoller(new Handler(Looper.getMainLooper()), connection -> {
            reads.incrementAndGet(); return true;
        }, signal::add);
        poller.bind(gatt); poller.enable(true);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, reads.get());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RssiPoller.TIMEOUT_MS * 3));
        assertEquals(1, reads.get());
        poller.received(gatt, -50, 0);
        assertEquals(SignalState.UNKNOWN, (int) signal.get(signal.size() - 1));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RssiPoller.INTERVAL_MS));
        assertEquals(2, reads.get());
        poller.enable(false);
    }

    @Test public void replacingGattRejectsOldCallbackAndPausingKeepsOutstandingIdentity() {
        List<Integer> signal = new ArrayList<>();
        AtomicInteger reads = new AtomicInteger();
        Object old = new Object(), current = new Object();
        RssiPoller poller = new RssiPoller(new Handler(Looper.getMainLooper()), connection -> {
            reads.incrementAndGet(); return true;
        }, signal::add);
        poller.bind(old); poller.enable(true); shadowOf(Looper.getMainLooper()).idle();
        poller.bind(current); shadowOf(Looper.getMainLooper()).idle();
        int size = signal.size();
        poller.received(old, -45, 0);
        assertEquals(size, signal.size());
        poller.enable(false); poller.enable(true); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, reads.get());
        poller.received(current, -60, 0);
        assertEquals(-60, (int) signal.get(signal.size() - 1));
        poller.enable(false);
    }

    @Test public void rejectedReadCanRetryAndInvalidSignalNeverBecomesVisible() {
        AtomicInteger reads = new AtomicInteger();
        List<Integer> signal = new ArrayList<>();
        Object gatt = new Object();
        RssiPoller poller = new RssiPoller(new Handler(Looper.getMainLooper()), connection -> reads.incrementAndGet() > 1, signal::add);
        poller.bind(gatt); poller.enable(true); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(SignalState.UNKNOWN, (int) signal.get(signal.size() - 1));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RssiPoller.INTERVAL_MS));
        assertEquals(2, reads.get());
        poller.received(gatt, 42, 0);
        assertEquals(SignalState.UNKNOWN, (int) signal.get(signal.size() - 1));
        poller.enable(false);
    }
    @Test public void closingSamplerRemovesTimersAndIgnoresOutstandingCallback() {
        AtomicInteger reads = new AtomicInteger(); List<Integer> signal = new ArrayList<>(); Object gatt = new Object();
        RssiPoller poller = new RssiPoller(new Handler(Looper.getMainLooper()), connection -> { reads.incrementAndGet(); return true; }, signal::add);
        poller.bind(gatt); poller.enable(true); shadowOf(Looper.getMainLooper()).idle();
        int count = signal.size(); poller.close(); poller.close();
        poller.received(gatt, -40, 0); poller.enable(true); poller.bind(new Object());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RssiPoller.TIMEOUT_MS * 3));
        assertEquals(1, reads.get()); assertEquals(count, signal.size());
    }
}
