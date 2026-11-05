package cc.star0.xposed.hyperstylus.runtime.gesture;

import static org.junit.Assert.*;

import android.app.Activity;
import android.os.Bundle;
import android.os.Looper;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** Native sidebar CANCEL must release its work without taking the APK's UP/settle action path. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SidebarTouchCancellationTest {
    private static final class Touch {
        private boolean n;
        private final View q;
        private final Runnable D;
        private final VelocityTracker g = VelocityTracker.obtain();
        private long h = 100;
        final List<String> calls = new ArrayList<>();
        int delayedRuns, opens, fades, fingerDowns, fingerUps;
        boolean visible = true;
        boolean failMoving;

        Touch(View view) {
            q = view;
            D = () -> delayedRuns++;
        }
        private void v() {
            calls.add("moving");
            if (failMoving) throw new IllegalStateException("moving cleanup unavailable");
        }
        private void u() { calls.add("finish"); }
        void nativeCancel(View owner, MotionEvent event) {
            assertSame(q, owner);
            assertEquals(MotionEvent.ACTION_CANCEL, event.getActionMasked());
            calls.add("cancel");
            if (!n) {
                opens++; fades++; visible = false;
            } // The verified native terminal path otherwise settles or hides the sidebar.
        }
        void nativeFinger(View owner, MotionEvent event) {
            assertSame(q, owner);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                h = event.getDownTime(); n = false; fingerDowns++;
                q.postDelayed(D, 300);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                fingerUps++; q.removeCallbacks(D);
                // The native listener deliberately retains its last DOWN identity after UP.
            } else nativeCancel(owner, event);
        }
    }

    private static final class Wrapper {
        private Touch u;
        boolean failIdle;
        Wrapper(Touch touch) { u = touch; }
        public void P() {
            u.calls.add("idle");
            if (failIdle) throw new IllegalArgumentException("idle cleanup unavailable");
        }
    }

    private static View attachedView() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        View view = new View(activity);
        activity.setContentView(view);
        assertNotNull(view.getHandler());
        return view;
    }

    private static MotionEvent cancel(long down) { return MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_CANCEL, 20, 30, 0); }

    private static MotionEvent input(int action, long down, int device, int tool) {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0; properties.toolType = tool;
        MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
        coordinates.x = 20; coordinates.y = 30; coordinates.pressure = 1;
        return MotionEvent.obtain(down, down + 50, action, 1,
                new MotionEvent.PointerProperties[]{properties}, new MotionEvent.PointerCoords[]{coordinates},
                0, 0, 1, 1, device, 0, InputDevice.SOURCE_STYLUS | InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    private static boolean dispatch(SplitTouchFilter filter, Touch touch, int action, long down,
                                    int device, int tool) throws Throwable {
        MotionEvent event = input(action, down, device, tool);
        try {
            boolean consumed = filter.consume(touch.q, event, true);
            if (!consumed) touch.nativeFinger(touch.q, event);
            return consumed;
        } finally { event.recycle(); }
    }

    private static void populate(VelocityTracker tracker) {
        MotionEvent first = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 0, 0, 0);
        MotionEvent second = MotionEvent.obtain(100, 120, MotionEvent.ACTION_MOVE, 100, 100, 0);
        try { tracker.addMovement(first); tracker.addMovement(second); }
        finally { first.recycle(); second.recycle(); }
    }

    @Test public void cancellationQuarantinesNativeActionsRemovesTheDelayedPressAndKeepsItsTracker() throws Throwable {
        View view = attachedView();
        Touch touch = new Touch(view); Wrapper wrapper = new Wrapper(touch);
        MotionEvent input = cancel(100);
        VelocityTracker originalTracker = touch.g;
        populate(touch.g);
        view.postDelayed(touch.D, 300);
        try {
            SidebarTouchCancellation adapter = new SidebarTouchCancellation(Wrapper.class, Touch.class);
            adapter.prepare(wrapper, view, input, touch::nativeCancel).run();
            assertTrue(touch.n);
            assertEquals(List.of("cancel", "idle", "moving", "finish"), touch.calls);
            assertEquals(0, touch.opens);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
            assertEquals("The old 300 ms task cannot resurrect sidebar work", 0, touch.delayedRuns);
            assertSame("The tracker belongs to the host and must remain usable", originalTracker, touch.g);
            touch.g.computeCurrentVelocity(1000);
            assertEquals(0f, touch.g.getXVelocity(), 0f);
            populate(touch.g); // Clear, rather than recycle, leaves a usable native object.
        } finally { input.recycle(); view.removeCallbacks(touch.D); touch.g.recycle(); }
    }

    @Test public void aDifferentDownTimeDoesNotCancelAnotherNativeLinePress() throws Throwable {
        View view = attachedView(); Touch touch = new Touch(view); Wrapper wrapper = new Wrapper(touch);
        MotionEvent input = cancel(200);
        view.postDelayed(touch.D, 300);
        int[] originalCancels = {0};
        try {
            SidebarTouchCancellation adapter = new SidebarTouchCancellation(Wrapper.class, Touch.class);
            adapter.prepare(wrapper, view, input, (owner, event) -> originalCancels[0]++).run();
            assertEquals("Mismatched CANCEL must never reach cached native finger state", 0, originalCancels[0]);
            assertFalse(touch.n);
            assertTrue(touch.calls.isEmpty());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
            assertEquals("A different live press keeps its own delayed work", 1, touch.delayedRuns);
        } finally { input.recycle(); view.removeCallbacks(touch.D); touch.g.recycle(); }
    }

    @Test public void identityIsRecheckedWhenCleanupRunsRatherThanOnlyWhenItIsPrepared() throws Throwable {
        View view = attachedView(); Touch touch = new Touch(view); Wrapper wrapper = new Wrapper(touch);
        MotionEvent input = cancel(100);
        int[] originalCancels = {0};
        try {
            SidebarTouchCancellation adapter = new SidebarTouchCancellation(Wrapper.class, Touch.class);
            SplitTouchFilter.Cancellation pending = adapter.prepare(wrapper, view, input, (owner, event) -> originalCancels[0]++);
            touch.h = 200;
            pending.run();
            assertEquals("Cleanup prepared for the previous press cannot cancel its replacement", 0, originalCancels[0]);
            assertFalse(touch.n);
            assertTrue(touch.calls.isEmpty());
        } finally { input.recycle(); touch.g.recycle(); }
    }

    @Test public void redirectCancelAndLatePenUpDoNotFadeTheStripAfterACompletedFingerPress() throws Throwable {
        View view = attachedView(); Touch touch = new Touch(view); Wrapper wrapper = new Wrapper(touch);
        SidebarTouchCancellation adapter = new SidebarTouchCancellation(Wrapper.class, Touch.class);
        SplitTouchFilter filter = new SplitTouchFilter((owner, event) -> adapter.prepare(wrapper, owner, event, touch::nativeCancel), false, true);
        try {
            assertFalse(dispatch(filter, touch, MotionEvent.ACTION_DOWN, 100, 3, MotionEvent.TOOL_TYPE_FINGER));
            assertFalse(dispatch(filter, touch, MotionEvent.ACTION_UP, 100, 3, MotionEvent.TOOL_TYPE_FINGER));
            assertEquals(100, touch.h);
            assertFalse(touch.n);
            assertTrue(dispatch(filter, touch, MotionEvent.ACTION_DOWN, 200, 4, MotionEvent.TOOL_TYPE_STYLUS));
            // Native event-ID redirection may cancel this window before a residual pen UP arrives.
            assertTrue(dispatch(filter, touch, MotionEvent.ACTION_CANCEL, 200, 4, MotionEvent.TOOL_TYPE_STYLUS));
            assertTrue(dispatch(filter, touch, MotionEvent.ACTION_UP, 200, 4, MotionEvent.TOOL_TYPE_STYLUS));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
            assertTrue("A pen tail cannot hide a strip whose native finger press already ended", touch.visible);
            assertEquals(0, touch.fades); assertEquals(0, touch.opens);
            assertEquals("No native cancel, idle animation or movement cleanup belongs to this pen press", List.of(), touch.calls);
            assertFalse(touch.n);
            assertEquals(0, touch.delayedRuns);
            assertFalse(dispatch(filter, touch, MotionEvent.ACTION_DOWN, 300, 3, MotionEvent.TOOL_TYPE_FINGER));
            assertFalse(dispatch(filter, touch, MotionEvent.ACTION_UP, 300, 3, MotionEvent.TOOL_TYPE_FINGER));
            assertEquals(2, touch.fingerDowns); assertEquals(2, touch.fingerUps);
            assertTrue(filter.snapshot().isEmpty());
        } finally { view.removeCallbacks(touch.D); touch.g.recycle(); }
    }

    @Test public void aFirstObservedPenMoveCannotSettleThePreviousCompletedFingerPress() throws Throwable {
        View view = attachedView(); Touch touch = new Touch(view); Wrapper wrapper = new Wrapper(touch);
        SidebarTouchCancellation adapter = new SidebarTouchCancellation(Wrapper.class, Touch.class);
        SplitTouchFilter filter = new SplitTouchFilter((owner, event) -> adapter.prepare(wrapper, owner, event, touch::nativeCancel), false, true);
        try {
            assertFalse(dispatch(filter, touch, MotionEvent.ACTION_DOWN, 100, 3, MotionEvent.TOOL_TYPE_FINGER));
            assertFalse(dispatch(filter, touch, MotionEvent.ACTION_UP, 100, 3, MotionEvent.TOOL_TYPE_FINGER));
            assertTrue(dispatch(filter, touch, MotionEvent.ACTION_MOVE, 200, 4, MotionEvent.TOOL_TYPE_ERASER));
            assertTrue(dispatch(filter, touch, MotionEvent.ACTION_UP, 200, 4, MotionEvent.TOOL_TYPE_ERASER));
            assertTrue(touch.visible);
            assertEquals(0, touch.fades); assertEquals(0, touch.opens);
            assertTrue(touch.calls.isEmpty());
            assertFalse(touch.n);
            assertTrue(filter.snapshot().isEmpty());
        } finally { view.removeCallbacks(touch.D); touch.g.recycle(); }
    }

    @Test public void redirectCancelledPenIdentitySurvivesReloadAndConsumesAFingerTailAfterDisabling() throws Throwable {
        View view = attachedView(); Touch touch = new Touch(view); Wrapper wrapper = new Wrapper(touch);
        SidebarTouchCancellation adapter = new SidebarTouchCancellation(Wrapper.class, Touch.class);
        int[] prepared = {0};
        SplitTouchFilter.Canceller canceller = (owner, event) -> {
            prepared[0]++;
            return adapter.prepare(wrapper, owner, event, touch::nativeCancel);
        };
        SplitTouchFilter old = new SplitTouchFilter(canceller, false, true);
        try {
            assertFalse(dispatch(old, touch, MotionEvent.ACTION_DOWN, 100, 3, MotionEvent.TOOL_TYPE_FINGER));
            assertFalse(dispatch(old, touch, MotionEvent.ACTION_UP, 100, 3, MotionEvent.TOOL_TYPE_FINGER));
            assertTrue(dispatch(old, touch, MotionEvent.ACTION_DOWN, 200, 4, MotionEvent.TOOL_TYPE_STYLUS));
            assertTrue(dispatch(old, touch, MotionEvent.ACTION_CANCEL, 200, 4, MotionEvent.TOOL_TYPE_STYLUS));
            Map<View, Bundle> saved = old.snapshot();
            SplitTouchFilter replacement = new SplitTouchFilter(canceller, false, true);
            replacement.restore(saved);
            MotionEvent lateFingerUp = input(MotionEvent.ACTION_UP, 200, 4, MotionEvent.TOOL_TYPE_FINGER);
            try {
                boolean consumed = replacement.consume(view, lateFingerUp, false);
                assertTrue("A cancelled pen stream keeps ownership even after its tool and setting change", consumed);
                if (!consumed) touch.nativeFinger(view, lateFingerUp);
            } finally { lateFingerUp.recycle(); }
            assertEquals("An unstarted or redirected pen press has no native work to cancel", 0, prepared[0]);
            assertEquals("The tail cannot replay the old native finger UP", 1, touch.fingerUps);
            assertTrue(touch.visible);
            assertEquals(0, touch.fades); assertEquals(0, touch.opens);
            assertTrue(touch.calls.isEmpty());
            assertTrue(replacement.snapshot().isEmpty());
        } finally { view.removeCallbacks(touch.D); touch.g.recycle(); }
    }

    @Test public void originalFailureDoesNotSkipOtherReleasesAndSecondaryFailuresAreReported() throws Throwable {
        View view = attachedView(); Touch touch = new Touch(view); Wrapper wrapper = new Wrapper(touch);
        wrapper.failIdle = true; touch.failMoving = true;
        MotionEvent input = cancel(100);
        IllegalStateException originalFailure = new IllegalStateException("native CANCEL failed");
        try {
            SidebarTouchCancellation adapter = new SidebarTouchCancellation(Wrapper.class, Touch.class);
            Throwable failure = assertThrows(IllegalStateException.class, () -> adapter.prepare(wrapper, view, input, (owner, event) -> {
                touch.nativeCancel(owner, event);
                throw originalFailure;
            }).run());
            assertSame(originalFailure, failure);
            assertEquals(2, failure.getSuppressed().length);
            assertEquals(List.of("cancel", "idle", "moving", "finish"), touch.calls);
            assertTrue(touch.n);
            assertEquals(0, touch.opens);
        } finally { input.recycle(); touch.g.recycle(); }
    }

    private static final class WrongWrapper { private Object u; }
    private static final class WrongTouch {
        private int n;
    }
    private static final class WrongTouchWrapper { private WrongTouch u; }
    private static final class WrongIdleWrapper {
        private Touch u;
        public boolean P() { return true; }
    }

    @Test public void unavailableListenersAndMismatchedNativeAbisAreRejected() throws Throwable {
        assertThrows(NoSuchFieldException.class, () -> new SidebarTouchCancellation(WrongWrapper.class, Touch.class));
        assertThrows(NoSuchFieldException.class, () -> new SidebarTouchCancellation(WrongTouchWrapper.class, WrongTouch.class));
        assertThrows(NoSuchMethodException.class, () -> new SidebarTouchCancellation(WrongIdleWrapper.class, Touch.class));
        SidebarTouchCancellation adapter = new SidebarTouchCancellation(Wrapper.class, Touch.class);
        View view = attachedView(); MotionEvent input = cancel(100);
        try { assertThrows(IllegalStateException.class, () -> adapter.prepare(new Wrapper(null), view, input, (owner, event) -> { })); }
        finally { input.recycle(); }
    }
}
