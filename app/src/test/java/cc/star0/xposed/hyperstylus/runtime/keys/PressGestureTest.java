package cc.star0.xposed.hyperstylus.runtime.keys;


import org.junit.Test;
import static org.junit.Assert.*;

public class PressGestureTest {
    private static final class Clock implements PressGesture.Scheduler {
        public Runnable pending;
        public long delay;
        public int posts;
        public void post(Runnable task, long delay) { pending = task; this.delay = delay; posts++; }
        public void remove(Runnable task) { if (pending == task) pending = null; }
    }
    private static class Listener implements PressGesture.Listener {
        public int shorts, longs, releases;
        public void execute(boolean held) { if (held) longs++; else shorts++; }
        public void release() { releases++; }
    }
    @Test public void timerAndDelayedUpProduceOneLongAction() {
        Clock clock = new Clock(); Listener listener = new Listener();
        PressGesture press = new PressGesture(clock, listener, 100, 380);
        press.start(120); press.start(130);
        assertEquals(1, clock.posts); assertEquals(360, clock.delay);
        clock.pending.run(); clock.pending.run();
        press.up(100, 600, false); press.cancel();
        assertEquals(1, listener.longs); assertEquals(0, listener.shorts); assertEquals(1, listener.releases);
    }
    @Test public void delayedMainLooperClassifiesDurationWithoutTimer() {
        Clock clock = new Clock(); Listener listener = new Listener();
        PressGesture press = new PressGesture(clock, listener, 100, 380);
        press.start(110);
        press.up(100, 500, false);
        assertEquals(1, listener.longs); assertEquals(1, listener.releases); assertNull(clock.pending);
    }
    @Test public void differentPressCannotReleaseActivePress() {
        Clock clock = new Clock(); Listener listener = new Listener();
        PressGesture press = new PressGesture(clock, listener, 100, 380);
        press.start(100); press.up(99, 200, false);
        assertNotNull(clock.pending); assertEquals(0, listener.releases);
        press.up(100, 200, false);
        assertEquals(1, listener.shorts); assertEquals(1, listener.releases);
    }
    @Test public void cancellationDoesNotFireAndThrowingActionStillReleases() {
        Clock clock = new Clock(); Listener listener = new Listener();
        PressGesture canceled = new PressGesture(clock, listener, 100, 380);
        canceled.start(100); canceled.cancel();
        assertEquals(0, listener.shorts + listener.longs); assertEquals(1, listener.releases);
        Listener failing = new Listener() {
            @Override public void execute(boolean held) { throw new IllegalStateException("action failed"); }
        };
        PressGesture press = new PressGesture(clock, failing, 100, 380);
        press.start(100);
        try { press.up(100, 200, false); fail("Expected action failure"); }
        catch (IllegalStateException expected) { assertEquals("action failed", expected.getMessage()); }
        press.cancel(); assertEquals(1, failing.releases);
    }
}
