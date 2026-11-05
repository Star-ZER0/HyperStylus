package cc.star0.xposed.hyperstylus.config;

import static org.junit.Assert.*;

import cc.star0.xposed.hyperstylus.module.FocusStylusModule;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Exercise the actual reader queue against generation retirement, without a Binder test double. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class ConfigReaderRetirementTest {
    @Test public void concurrentSubmissionsStopWithoutThrowingIntoTheProducer() throws Exception {
        FocusStylusModule module = new FocusStylusModule();
        ConfigBridge.bind(module);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicInteger completed = new AtomicInteger();
        AtomicReference<Throwable> rejected = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            started.countDown();
            try {
                while (!finished.get()) {
                    ConfigBridge.submitRead(completed::incrementAndGet);
                    Thread.yield();
                }
            } catch (Throwable failure) { rejected.set(failure); }
        }, "Config-retirement-producer");
        producer.start();
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertTrue("Retirement must wait for the module-owned reader to exit", module.session().close(2_000));
            assertFalse(ConfigBridge.submitRead(() -> fail("A retired reader cannot execute new work")));
        } finally {
            finished.set(true);
            producer.join(2_000);
        }
        assertFalse(producer.isAlive());
        assertNull("An executor shutdown race must not escape into a host callback", rejected.get());
    }
}
