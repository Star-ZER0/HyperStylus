package cc.star0.xposed.hyperstylus.runtime.battery;

import android.os.Handler;
import android.os.Looper;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class WakeRefreshTest {
    @Test public void wakeAndUnlockUseOneRetryBurstThenScreenOffCancelsIt() {
        AtomicInteger calls = new AtomicInteger();
        WakeRefresh refresh = new WakeRefresh(new Handler(Looper.getMainLooper()), calls::incrementAndGet);
        refresh.wake(); assertEquals(1, calls.get());
        refresh.wake(); assertEquals(2, calls.get());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3)); assertEquals(3, calls.get());
        refresh.cancel();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30)); assertEquals(3, calls.get());
    }
    @Test public void retiringTheGenerationCancelsRetriesAndIgnoresLaterWakeCallbacks() {
        AtomicInteger calls = new AtomicInteger();
        WakeRefresh refresh = new WakeRefresh(new Handler(Looper.getMainLooper()), calls::incrementAndGet);
        refresh.wake(); refresh.close(); refresh.wake();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30)); assertEquals(1, calls.get());
    }
    @Test public void anImmediateRefreshCanCancelOrRetireWithoutReposting() {
        for (boolean retire : new boolean[]{false, true}) {
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<WakeRefresh> owned = new AtomicReference<>();
            WakeRefresh refresh = new WakeRefresh(new Handler(Looper.getMainLooper()), () -> {
                calls.incrementAndGet();
                if (retire) owned.get().close(); else owned.get().cancel();
            });
            owned.set(refresh); refresh.wake();
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30)); assertEquals(1, calls.get());
        }
    }
    @Test public void reentrantWakeKeepsOnlyTheNewerRetryBurst() {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean first = new AtomicBoolean(true);
        AtomicReference<WakeRefresh> owned = new AtomicReference<>();
        WakeRefresh refresh = new WakeRefresh(new Handler(Looper.getMainLooper()), () -> {
            calls.incrementAndGet();
            if (first.getAndSet(false)) owned.get().wake();
        });
        owned.set(refresh); refresh.wake(); assertEquals(2, calls.get());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3)); assertEquals(3, calls.get());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12)); assertEquals(4, calls.get());
    }
}
