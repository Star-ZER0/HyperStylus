package cc.star0.xposed.hyperstylus.runtime.gesture;

import static org.junit.Assert.*;

import android.os.Bundle;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SplitDividerRoutingTest {
    static final int FINGER = MotionEvent.TOOL_TYPE_FINGER, PEN = MotionEvent.TOOL_TYPE_STYLUS,
            ERASER = MotionEvent.TOOL_TYPE_ERASER;
    static MotionEvent event(int action, long down, int device, int... tools) {
        var properties = new MotionEvent.PointerProperties[tools.length];
        var coords = new MotionEvent.PointerCoords[tools.length];
        for (int i = 0; i < tools.length; i++) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = i; properties[i].toolType = tools[i];
            coords[i] = new MotionEvent.PointerCoords(); coords[i].x = 100 + i; coords[i].y = 20;
        }
        return MotionEvent.obtain(down, down + 10, action, tools.length, properties, coords,
                0, 0, 1, 1, device, 0, InputDevice.SOURCE_STYLUS | InputDevice.SOURCE_TOUCHSCREEN, 0);
    }
    static final class Host implements SplitDividerRouting.Host {
        int features, pilfers, cancels, writes;
        boolean failWrite, failPilfer, failCancel;
        final List<String> calls = new ArrayList<>();
        @Override public int features() { return features; }
        @Override public void features(int value) {
            writes++; features = value;
            if (failWrite) throw new IllegalStateException("relayout failed after submission");
        }
        @Override public void pilfer() {
            pilfers++; calls.add("pilfer");
            if (failPilfer) throw new IllegalStateException("missing channel");
        }
        @Override public void cancel() {
            if (failCancel) throw new IllegalStateException("native cancel failed");
            cancels++;
        }
    }
    private static boolean before(SplitDividerRouting routing, int action, long down, int device, int... tools) throws Throwable {
        MotionEvent event = event(action, down, device, tools);
        try { return routing.before(event); } finally { event.recycle(); }
    }
    private static void capture(SplitDividerRouting routing, long down, int device, int tool) throws Throwable {
        MotionEvent event = event(MotionEvent.ACTION_DOWN, down, device, tool);
        try { routing.capture(event); } finally { event.recycle(); }
    }
    private static void end(SplitDividerRouting routing, long down, int device, int tool) throws Throwable {
        MotionEvent event = event(MotionEvent.ACTION_UP, down, device, tool);
        try { routing.before(event); routing.after(event); } finally { event.recycle(); }
    }

    @Test public void spyOwnsOnlyItsFeatureBitAndRestoresLatestUnrelatedFeatures() throws Throwable {
        Host host = new Host(); host.features = 2;
        SplitDividerRouting routing = new SplitDividerRouting(host);
        routing.configure(true); assertEquals(6, host.features);
        host.features |= 8;
        assertEquals(14, routing.effective(10));
        routing.configure(false); assertEquals(10, host.features);
        assertFalse(routing.ownsSpy());
    }
    @Test public void neverTakesOwnershipOfAnExistingSpy() {
        Host host = new Host(); host.features = 4;
        SplitDividerRouting routing = new SplitDividerRouting(host);
        assertThrows(IllegalStateException.class, () -> routing.configure(true));
        assertEquals(0, host.writes); assertFalse(routing.ownsSpy());
    }
    @Test public void uncertainWindowWriteMustBeCompensatedBeforeRetirement() throws Throwable {
        Host host = new Host(); host.failWrite = true;
        SplitDividerRouting routing = new SplitDividerRouting(host);
        assertThrows(IllegalStateException.class, () -> routing.configure(true));
        assertFalse(routing.enabled()); assertTrue(routing.ownsSpy());
        assertThrows(IllegalStateException.class, routing::close);
        host.failWrite = false; routing.close();
        assertEquals(0, host.features); assertFalse(routing.ownsSpy());
    }
    @Test public void nativeRelayoutFailureForcesARepairEvenIfTheLocalBitAlreadyMatches() throws Throwable {
        Host host = new Host(); SplitDividerRouting routing = new SplitDividerRouting(host);
        routing.configure(true); int writes = host.writes;
        routing.relayoutFailed(); routing.configure(true);
        assertEquals(writes + 1, host.writes);
    }
    @Test public void penAndEraserNeverPilferWhileTheActualConsumerFilterConsumesTheirWholeStream() throws Throwable {
        for (int tool : new int[]{PEN, ERASER}) {
            Host host = new Host(); SplitDividerRouting routing = new SplitDividerRouting(host); routing.configure(true);
            View owner = new View(RuntimeEnvironment.getApplication());
            SplitTouchFilter filter = new SplitTouchFilter((view, cancel) -> { throw new AssertionError("No native DOWN"); });
            for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP}) {
                MotionEvent event = event(action, 100, 3, tool);
                try {
                    assertFalse(routing.before(event));
                    assertTrue(filter.consume(owner, event, routing.enabled()));
                    routing.after(event);
                } finally { event.recycle(); }
            }
            assertEquals(0, host.pilfers); assertEquals(4, host.features);
        }
    }
    @Test public void isolatedFingerCapturesOnceBeforeNativeWorkAndEndsWithoutExtraCancel() throws Throwable {
        Host host = new Host(); SplitDividerRouting routing = new SplitDividerRouting(host); routing.configure(true);
        assertFalse(before(routing, MotionEvent.ACTION_DOWN, 100, 1, FINGER));
        capture(routing, 100, 1, FINGER); host.calls.add("native down");
        assertFalse(before(routing, MotionEvent.ACTION_DOWN, 100, 1, FINGER)); capture(routing, 100, 1, FINGER);
        assertEquals(List.of("pilfer", "native down"), host.calls);
        end(routing, 100, 1, FINGER); routing.close();
        assertEquals(1, host.pilfers); assertEquals(0, host.cancels);
    }
    @Test public void disablingWaitsForThePenUpAndRestoresTheWindowBeforeTheNextPress() throws Throwable {
        Host host = new Host(); SplitDividerRouting routing = new SplitDividerRouting(host); routing.configure(true);
        before(routing, MotionEvent.ACTION_DOWN, 100, 3, PEN); routing.configure(false);
        assertEquals(4, host.features); assertTrue(routing.enabled());
        end(routing, 100, 3, PEN); assertEquals(0, host.features); assertFalse(routing.enabled());
        before(routing, MotionEvent.ACTION_DOWN, 200, 3, PEN); capture(routing, 200, 3, PEN);
        assertEquals(0, host.pilfers);
    }
    @Test public void enablingDuringNativeDragAndReloadStillDeliversItsUpBeforeChangingTheWindow() throws Throwable {
        Host host = new Host(); SplitDividerRouting old = new SplitDividerRouting(host); old.configure(false);
        before(old, MotionEvent.ACTION_DOWN, 100, 1, FINGER); old.configure(true); old.close();
        assertEquals(0, host.cancels); assertEquals(0, host.features);
        SplitDividerRouting next = new SplitDividerRouting(host); next.restore(old.snapshot()); next.configure(true);
        assertEquals(0, host.features); assertFalse(next.enabled());
        assertFalse(before(next, MotionEvent.ACTION_MOVE, 100, 1, FINGER));
        end(next, 100, 1, FINGER); assertEquals(4, host.features);
    }
    @Test public void fingerCannotPilferAnAlreadyWritingPenOnAnotherDevice() throws Throwable {
        Host host = new Host(); SplitDividerRouting routing = new SplitDividerRouting(host); routing.configure(true);
        before(routing, MotionEvent.ACTION_DOWN, 100, 3, PEN);
        assertTrue(before(routing, MotionEvent.ACTION_DOWN, 110, 1, FINGER));
        capture(routing, 110, 1, FINGER); assertEquals(0, host.pilfers);
        end(routing, 100, 3, PEN);
        assertTrue(before(routing, MotionEvent.ACTION_MOVE, 110, 1, FINGER));
        end(routing, 110, 1, FINGER);
        assertFalse(before(routing, MotionEvent.ACTION_DOWN, 200, 1, FINGER)); capture(routing, 200, 1, FINGER);
        assertEquals(1, host.pilfers);
    }
    @Test public void newPenCancelsCapturedFingerAndItsLateTailCannotReviveTheDivider() throws Throwable {
        Host host = new Host(); SplitDividerRouting routing = new SplitDividerRouting(host); routing.configure(true);
        before(routing, MotionEvent.ACTION_DOWN, 100, 1, FINGER); capture(routing, 100, 1, FINGER);
        assertFalse(before(routing, MotionEvent.ACTION_DOWN, 110, 3, PEN)); assertEquals(1, host.cancels);
        assertTrue(before(routing, MotionEvent.ACTION_MOVE, 100, 1, FINGER));
        end(routing, 100, 1, FINGER); end(routing, 110, 3, PEN);
        assertEquals(1, host.pilfers); assertEquals(1, host.cancels);
    }
    @Test public void failedPilferDoesNotForwardOrReviveTheUnstartedFingerPress() throws Throwable {
        Host host = new Host(); SplitDividerRouting routing = new SplitDividerRouting(host); routing.configure(true);
        before(routing, MotionEvent.ACTION_DOWN, 100, 1, FINGER); host.failPilfer = true;
        assertThrows(IllegalStateException.class, () -> capture(routing, 100, 1, FINGER));
        host.failPilfer = false;
        assertTrue(before(routing, MotionEvent.ACTION_MOVE, 100, 1, FINGER));
        end(routing, 100, 1, FINGER); routing.close(); assertEquals(0, host.cancels);
    }
    @Test public void failedCancellationIsRetriedEvenOnAnAlreadyCancelledTail() throws Throwable {
        Host host = new Host(); SplitDividerRouting routing = new SplitDividerRouting(host); routing.configure(true);
        before(routing, MotionEvent.ACTION_DOWN, 100, 1, FINGER); capture(routing, 100, 1, FINGER);
        host.failCancel = true;
        assertThrows(IllegalStateException.class, () -> before(routing, MotionEvent.ACTION_DOWN, 110, 3, PEN));
        assertThrows(IllegalStateException.class, () -> before(routing, MotionEvent.ACTION_UP, 100, 1, FINGER));
        assertThrows(IllegalStateException.class, routing::close);
        host.failCancel = false; routing.close(); assertEquals(1, host.cancels); assertEquals(0, host.features);
    }
    @Test public void deviceRemovalCancelsOnlyModuleCapturedWorkAndPreservesTheOtherPen() throws Throwable {
        Host host = new Host(); SplitDividerRouting routing = new SplitDividerRouting(host); routing.configure(true);
        before(routing, MotionEvent.ACTION_DOWN, 100, 1, FINGER); capture(routing, 100, 1, FINGER);
        routing.forget(1); assertEquals(1, host.cancels);
        before(routing, MotionEvent.ACTION_DOWN, 200, 3, PEN); routing.configure(false); routing.forget(1);
        assertEquals(4, host.features); routing.forget(3); assertEquals(0, host.features);
    }
    @Test public void retirementCancelsCapturedDragAndNewGenerationSuppressesOnlyItsOldIdentity() throws Throwable {
        Host host = new Host(); SplitDividerRouting old = new SplitDividerRouting(host); old.configure(true);
        before(old, MotionEvent.ACTION_DOWN, 100, 1, FINGER); capture(old, 100, 1, FINGER); old.close();
        assertEquals(1, host.cancels); assertEquals(0, host.features);
        Bundle snapshot = old.snapshot(); SplitDividerRouting next = new SplitDividerRouting(host);
        next.restore(snapshot); next.configure(true);
        assertTrue(before(next, MotionEvent.ACTION_UP, 100, 1, FINGER));
        assertFalse(before(next, MotionEvent.ACTION_DOWN, 200, 1, FINGER)); capture(next, 200, 1, FINGER);
        assertTrue(before(next, MotionEvent.ACTION_UP, 100, 1, FINGER));
        end(next, 200, 1, FINGER); assertEquals(2, host.pilfers);
    }
    @Test public void aLateUnobservedSpyTailCannotStartNativeWork() throws Throwable {
        Host host = new Host(); SplitDividerRouting routing = new SplitDividerRouting(host); routing.configure(true);
        assertTrue(before(routing, MotionEvent.ACTION_MOVE, 100, 1, FINGER));
        end(routing, 100, 1, FINGER); assertEquals(0, host.pilfers); assertEquals(0, host.cancels);
    }
    @Test public void ownedCancellationUsesTheConsumerFilterExactlyOnceAndKeepsItsTailSuppressed() throws Throwable {
        View owner = new View(RuntimeEnvironment.getApplication()); int[] cancels = {0};
        SplitTouchFilter filter = new SplitTouchFilter((view, event) -> () -> cancels[0]++);
        MotionEvent finger = event(MotionEvent.ACTION_DOWN, 100, 1, FINGER);
        try {
            assertFalse(filter.consume(owner, finger, true)); filter.cancelOwned(owner, finger);
            MotionEvent pen = event(MotionEvent.ACTION_DOWN, 110, 3, PEN);
            try { assertTrue(filter.consume(owner, pen, true)); } finally { pen.recycle(); }
            assertEquals(1, cancels[0]);
        } finally { finger.recycle(); }
    }
}
