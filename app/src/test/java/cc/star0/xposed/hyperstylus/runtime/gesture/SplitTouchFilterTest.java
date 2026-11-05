package cc.star0.xposed.hyperstylus.runtime.gesture;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SplitTouchFilterTest {
    private static final int FINGER = MotionEvent.TOOL_TYPE_FINGER, PEN = MotionEvent.TOOL_TYPE_STYLUS,
            ERASER = MotionEvent.TOOL_TYPE_ERASER, MOUSE = MotionEvent.TOOL_TYPE_MOUSE;

    private static final class NativeCancellation implements SplitTouchFilter.Canceller {
        int preparations, attempts, completed;
        boolean failPrepare, failRun;
        long cancelledDown;
        int cancelledDevice, cancelledAction;
        float cancelledX;
        @Override public SplitTouchFilter.Cancellation prepare(View owner, MotionEvent event) {
            preparations++;
            if (failPrepare) throw new IllegalStateException("prepare unavailable");
            return () -> {
                attempts++;
                cancelledDown = event.getDownTime(); cancelledDevice = event.getDeviceId();
                cancelledAction = event.getActionMasked(); cancelledX = event.getX();
                if (failRun) throw new IllegalStateException("native release unavailable");
                completed++;
            };
        }
    }

    private static View owner() { return new View(RuntimeEnvironment.getApplication()); }

    private static MotionEvent event(int action, long down, int device, float x, int... tools) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[tools.length];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[tools.length];
        for (int index = 0; index < tools.length; index++) {
            properties[index] = new MotionEvent.PointerProperties();
            properties[index].id = index; properties[index].toolType = tools[index];
            coordinates[index] = new MotionEvent.PointerCoords();
            coordinates[index].x = x + index; coordinates[index].y = 20; coordinates[index].pressure = 1;
        }
        // A stylus-capable source does not turn a finger or a mouse pointer into a pen.
        return MotionEvent.obtain(down, down + 10, action, tools.length, properties, coordinates,
                0, 0, 1, 1, device, 0, InputDevice.SOURCE_STYLUS | InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    private static boolean send(SplitTouchFilter filter, View view, int action, long down, int device,
                                boolean enabled, int... tools) throws Throwable {
        MotionEvent input = event(action, down, device, 40, tools);
        try { return filter.consume(view, input, enabled); }
        finally { input.recycle(); }
    }

    @Test public void penAndEraserConsumeTheirEntirePressWithoutStartingNativeWork() throws Throwable {
        NativeCancellation nativeInput = new NativeCancellation();
        SplitTouchFilter filter = new SplitTouchFilter(nativeInput);
        for (int tool : new int[]{PEN, ERASER}) {
            View view = owner();
            assertTrue(send(filter, view, MotionEvent.ACTION_DOWN, 100, 3, true, tool));
            assertTrue(send(filter, view, MotionEvent.ACTION_MOVE, 100, 3, false, tool));
            assertTrue("The tail stays consumed after the pen leaves a mixed stream",
                    send(filter, view, MotionEvent.ACTION_UP, 100, 3, false, FINGER));
        }
        assertEquals(0, nativeInput.preparations);
        assertTrue(filter.snapshot().isEmpty());
    }

    @Test public void fingerAndMouseKeepNativeInputEvenWithAStylusCapableSource() throws Throwable {
        NativeCancellation nativeInput = new NativeCancellation();
        SplitTouchFilter filter = new SplitTouchFilter(nativeInput);
        for (int tool : new int[]{FINGER, MOUSE}) {
            View view = owner();
            assertFalse(send(filter, view, MotionEvent.ACTION_DOWN, 100, 3, true, tool));
            assertFalse(send(filter, view, MotionEvent.ACTION_MOVE, 100, 3, true, tool));
            assertFalse(send(filter, view, MotionEvent.ACTION_UP, 100, 3, true, tool));
        }
        assertEquals(0, nativeInput.preparations);
    }

    @Test public void penInSecondPointerCancelsTheForwardedFingerOnceUsingItsLastCoordinates() throws Throwable {
        NativeCancellation nativeInput = new NativeCancellation();
        SplitTouchFilter filter = new SplitTouchFilter(nativeInput);
        View view = owner();
        MotionEvent finger = event(MotionEvent.ACTION_DOWN, 100, 3, 50, FINGER);
        try {
            assertFalse(filter.consume(view, finger, true));
            // The ROM divider changes event coordinates after the filter has returned.
            finger.setLocation(900, 900);
        } finally { finger.recycle(); }
        MotionEvent joined = event(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                100, 3, 700, FINGER, ERASER);
        try { assertTrue(filter.consume(view, joined, true)); }
        finally { joined.recycle(); }
        assertEquals(1, nativeInput.completed);
        assertEquals(MotionEvent.ACTION_CANCEL, nativeInput.cancelledAction);
        assertEquals(50f, nativeInput.cancelledX, 0f);
        assertEquals(100, nativeInput.cancelledDown);
        assertEquals(3, nativeInput.cancelledDevice);
        assertTrue(send(filter, view, MotionEvent.ACTION_MOVE, 100, 3, false, FINGER, PEN));
        assertTrue(send(filter, view, MotionEvent.ACTION_CANCEL, 100, 3, false, FINGER));
        assertEquals(1, nativeInput.completed);
    }

    @Test public void downPolicyAndSuppressionSurviveDuplicateDownAndSettingChanges() throws Throwable {
        NativeCancellation nativeInput = new NativeCancellation();
        SplitTouchFilter filter = new SplitTouchFilter(nativeInput);
        View disabled = owner(), blocked = owner();
        assertFalse(send(filter, disabled, MotionEvent.ACTION_DOWN, 100, 3, false, PEN));
        assertFalse(send(filter, disabled, MotionEvent.ACTION_DOWN, 100, 3, true, PEN));
        assertFalse(send(filter, disabled, MotionEvent.ACTION_UP, 100, 3, true, PEN));
        assertTrue(send(filter, blocked, MotionEvent.ACTION_DOWN, 100, 3, true, PEN));
        assertTrue(send(filter, blocked, MotionEvent.ACTION_DOWN, 100, 3, false, FINGER));
        assertTrue(send(filter, blocked, MotionEvent.ACTION_UP, 100, 3, false, FINGER));
        assertEquals(0, nativeInput.preparations);
    }

    @Test public void ownersAndDifferentPhysicalIdentitiesDoNotInheritSuppression() throws Throwable {
        NativeCancellation nativeInput = new NativeCancellation();
        SplitTouchFilter filter = new SplitTouchFilter(nativeInput);
        View first = owner(), second = owner();
        assertTrue(send(filter, first, MotionEvent.ACTION_DOWN, 100, 3, true, PEN));
        assertFalse(send(filter, second, MotionEvent.ACTION_DOWN, 100, 3, true, FINGER));
        assertFalse(send(filter, first, MotionEvent.ACTION_MOVE, 100, 4, true, FINGER));
        assertFalse(send(filter, first, MotionEvent.ACTION_MOVE, 200, 4, true, FINGER));
        assertFalse(send(filter, first, MotionEvent.ACTION_UP, 200, 4, true, FINGER));
        assertFalse(send(filter, second, MotionEvent.ACTION_UP, 100, 3, true, FINGER));
        assertEquals(0, nativeInput.preparations);
        assertTrue(filter.snapshot().isEmpty());
    }

    @Test public void newPenPressCancelsAnUnterminatedNativePressUsingItsOriginalIdentity() throws Throwable {
        NativeCancellation nativeInput = new NativeCancellation();
        SplitTouchFilter filter = new SplitTouchFilter(nativeInput);
        View view = owner();
        assertFalse(send(filter, view, MotionEvent.ACTION_DOWN, 100, 3, true, FINGER));
        assertTrue(send(filter, view, MotionEvent.ACTION_DOWN, 200, 4, true, PEN));
        assertEquals(100, nativeInput.cancelledDown);
        assertEquals(3, nativeInput.cancelledDevice);
        assertTrue(send(filter, view, MotionEvent.ACTION_UP, 200, 4, false, PEN));
        assertEquals(1, nativeInput.completed);
    }

    @Test public void aLateOldPenUpCannotCancelANewerFingerPressOrReplaceItsDownPolicy() throws Throwable {
        NativeCancellation nativeInput = new NativeCancellation();
        SplitTouchFilter filter = new SplitTouchFilter(nativeInput);
        View view = owner();
        assertFalse(send(filter, view, MotionEvent.ACTION_DOWN, 200, 4, false, FINGER));
        assertTrue(send(filter, view, MotionEvent.ACTION_UP, 100, 3, true, PEN));
        assertFalse(send(filter, view, MotionEvent.ACTION_MOVE, 200, 4, true, FINGER, PEN));
        assertFalse(send(filter, view, MotionEvent.ACTION_UP, 200, 4, true, FINGER));
        assertEquals(0, nativeInput.completed);
        assertTrue(filter.snapshot().isEmpty());
    }

    @Test public void lateFirstPenMoveCancelsExistingNativeWorkInsteadOfAssumingNoDown() throws Throwable {
        NativeCancellation nativeInput = new NativeCancellation();
        SplitTouchFilter filter = new SplitTouchFilter(nativeInput);
        View view = owner();
        assertTrue(send(filter, view, MotionEvent.ACTION_MOVE, 100, 3, true, PEN));
        assertEquals(1, nativeInput.completed);
        assertTrue(send(filter, view, MotionEvent.ACTION_UP, 100, 3, false, PEN));
        assertEquals(1, nativeInput.completed);
    }

    @Test public void failedNativeReleaseRemainsPendingAndCannotProduceASuccessfulSnapshot() throws Throwable {
        NativeCancellation nativeInput = new NativeCancellation();
        nativeInput.failRun = true;
        SplitTouchFilter filter = new SplitTouchFilter(nativeInput);
        View view = owner();
        assertFalse(send(filter, view, MotionEvent.ACTION_DOWN, 100, 3, true, FINGER));
        assertThrows(IllegalStateException.class,
                () -> send(filter, view, MotionEvent.ACTION_MOVE, 100, 3, true, FINGER, PEN));
        assertThrows(IllegalStateException.class, filter::snapshot);
        assertEquals(0, nativeInput.completed);
        nativeInput.failRun = false;
        assertTrue(send(filter, view, MotionEvent.ACTION_UP, 100, 3, false, FINGER));
        assertEquals(1, nativeInput.completed);
        assertEquals(1, nativeInput.preparations);
        assertTrue(filter.snapshot().isEmpty());
    }

    @Test public void preparationFailureAlsoKeepsTheCancellationAvailableForRetry() throws Throwable {
        NativeCancellation nativeInput = new NativeCancellation();
        nativeInput.failPrepare = true;
        SplitTouchFilter filter = new SplitTouchFilter(nativeInput);
        View view = owner();
        assertFalse(send(filter, view, MotionEvent.ACTION_DOWN, 100, 3, true, FINGER));
        assertThrows(IllegalStateException.class,
                () -> send(filter, view, MotionEvent.ACTION_MOVE, 100, 3, true, FINGER, PEN));
        assertThrows(IllegalStateException.class, filter::snapshot);
        nativeInput.failPrepare = false;
        assertTrue(send(filter, view, MotionEvent.ACTION_UP, 100, 3, false, FINGER));
        assertEquals(1, nativeInput.completed);
        assertEquals(100, nativeInput.cancelledDown);
    }

    @Test public void reloadPreservesBothNativePolicyAndSuppressedTailWithoutACancellationCallback() throws Throwable {
        NativeCancellation nativeInput = new NativeCancellation();
        SplitTouchFilter old = new SplitTouchFilter(nativeInput);
        View nativeView = owner(), blockedView = owner(), mixedView = owner();
        assertFalse(send(old, nativeView, MotionEvent.ACTION_DOWN, 100, 3, false, PEN));
        assertTrue(send(old, blockedView, MotionEvent.ACTION_DOWN, 200, 4, true, ERASER));
        assertFalse(send(old, mixedView, MotionEvent.ACTION_DOWN, 300, 5, true, FINGER));
        Map<View, Bundle> saved = old.snapshot();
        assertTrue(old.snapshot().isEmpty());
        NativeCancellation newNativeInput = new NativeCancellation();
        SplitTouchFilter replacement = new SplitTouchFilter(newNativeInput);
        replacement.restore(saved);
        assertFalse(send(replacement, nativeView, MotionEvent.ACTION_UP, 100, 3, true, PEN));
        assertTrue(send(replacement, blockedView, MotionEvent.ACTION_UP, 200, 4, false, FINGER));
        assertTrue(send(replacement, mixedView, MotionEvent.ACTION_MOVE, 300, 5, false, FINGER, PEN));
        assertEquals(1, newNativeInput.completed);
        assertTrue(send(replacement, mixedView, MotionEvent.ACTION_UP, 300, 5, false, FINGER));
        assertTrue(replacement.snapshot().isEmpty());
    }

    @Test public void forgettingADeviceRetriesPendingReleaseAndClearsOnlyThatDevicesState() throws Throwable {
        NativeCancellation nativeInput = new NativeCancellation();
        nativeInput.failRun = true;
        SplitTouchFilter filter = new SplitTouchFilter(nativeInput);
        View removed = owner(), retained = owner();
        assertFalse(send(filter, removed, MotionEvent.ACTION_DOWN, 100, 3, true, FINGER));
        assertThrows(IllegalStateException.class,
                () -> send(filter, removed, MotionEvent.ACTION_MOVE, 100, 3, true, FINGER, PEN));
        assertTrue(send(filter, retained, MotionEvent.ACTION_DOWN, 200, 4, true, PEN));
        assertThrows(IllegalStateException.class, () -> filter.forget(3));
        nativeInput.failRun = false;
        filter.forget(3);
        assertEquals(1, nativeInput.completed);
        assertTrue(send(filter, retained, MotionEvent.ACTION_UP, 200, 4, false, FINGER));
        assertFalse("The removed device's old suppression cannot affect fresh finger input",
                send(filter, removed, MotionEvent.ACTION_DOWN, 300, 3, true, FINGER));
        filter.forget(3);
        assertTrue(filter.snapshot().isEmpty());
    }

    private static final class Menu extends FrameLayout {
        Menu(Context context) { super(context); }
        @Override public boolean shouldDelayChildPressedState() { return false; }
    }

    private static void dispatchMenu(SplitTouchFilter filter, Menu menu, int action, long down,
                                     int... tools) throws Throwable {
        MotionEvent input = event(action, down, 3, 40, tools);
        try {
            if (!filter.consume(menu, input, true)) menu.dispatchTouchEvent(input);
        } finally { input.recycle(); }
    }

    @Test public void realMenuDispatchCancelsAPressedButtonWithoutClickingAndKeepsLaterFingerClicks() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Menu menu = new Menu(activity);
        ImageButton button = new ImageButton(activity);
        int[] clicks = {0}, cancellations = {0};
        button.setOnClickListener(view -> clicks[0]++);
        menu.addView(button, new FrameLayout.LayoutParams(200, 200));
        activity.setContentView(menu);
        menu.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY));
        menu.layout(0, 0, 200, 200);
        SplitTouchFilter filter = new SplitTouchFilter((view, cancel) -> () -> {
            cancellations[0]++;
            assertEquals(MotionEvent.ACTION_CANCEL, cancel.getActionMasked());
            view.dispatchTouchEvent(cancel);
        });
        dispatchMenu(filter, menu, MotionEvent.ACTION_DOWN, 100, FINGER);
        assertTrue("The native menu button received the initial finger DOWN", button.isPressed());
        dispatchMenu(filter, menu,
                MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                100, FINGER, PEN);
        assertFalse("Native CANCEL clears the child's press", button.isPressed());
        dispatchMenu(filter, menu, MotionEvent.ACTION_UP, 100, FINGER);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, clicks[0]);
        assertEquals(1, cancellations[0]);

        dispatchMenu(filter, menu, MotionEvent.ACTION_DOWN, 200, PEN);
        dispatchMenu(filter, menu, MotionEvent.ACTION_UP, 200, PEN);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("A pen-only press never reaches the clickable child", 0, clicks[0]);

        dispatchMenu(filter, menu, MotionEvent.ACTION_DOWN, 300, FINGER);
        dispatchMenu(filter, menu, MotionEvent.ACTION_UP, 300, FINGER);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("A subsequent native finger press still clicks the menu button", 1, clicks[0]);
        assertEquals(1, cancellations[0]);
        assertTrue(filter.snapshot().isEmpty());
        activity.finish();
    }
}
