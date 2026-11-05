package cc.star0.xposed.hyperstylus.runtime.gesture;


import static org.junit.Assert.*;
import android.view.InputDevice;
import android.view.MotionEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class FocusNotificationToolTest {
    private static MotionEvent event(int... tools) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[tools.length];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[tools.length];
        for (int index = 0; index < tools.length; index++) {
            properties[index] = new MotionEvent.PointerProperties(); properties[index].id = index; properties[index].toolType = tools[index];
            coordinates[index] = new MotionEvent.PointerCoords(); coordinates[index].x = 20; coordinates[index].y = 10;
        }
        return MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, tools.length, properties, coordinates,
                0, 0, 1, 1, 3, 0, InputDevice.SOURCE_STYLUS | InputDevice.SOURCE_TOUCHSCREEN, 0);
    }
    @Test public void penAndEraserAreDetectedAcrossAllPointersWhileFingerSourceDoesNotImplyPen() {
        MotionEvent finger = event(MotionEvent.TOOL_TYPE_FINGER);
        MotionEvent pen = event(MotionEvent.TOOL_TYPE_STYLUS);
        MotionEvent eraser = event(MotionEvent.TOOL_TYPE_ERASER);
        MotionEvent mixed = event(MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_ERASER);
        MotionEvent mouse = event(MotionEvent.TOOL_TYPE_MOUSE);
        try {
            assertFalse(MotionGestureFilter.stylus(finger));
            assertFalse(MotionGestureFilter.stylus(mouse));
            assertTrue(MotionGestureFilter.stylus(pen));
            assertTrue(MotionGestureFilter.stylus(eraser));
            assertTrue(MotionGestureFilter.stylus(mixed));
        } finally { finger.recycle(); pen.recycle(); eraser.recycle(); mixed.recycle(); mouse.recycle(); }
    }

    private static final class Window {
        public Object big, small, expanded, temporary;
        public Object getCurrentBigIslandState() { return big; }
        public Object getCurrentSmallIslandState() { return small; }
        public Object getCurrentExpandedState() { return expanded; }
        public Object getCurrentTempShowBigIslandState() { return temporary; }
    }

    @Test public void onlyAnIslandWithNoVisibleContentIsHidden() throws Throwable {
        Window window = new Window();
        assertTrue(FocusNotificationGestureHook.isHidden(window));
        window.big = new Object();
        assertFalse(FocusNotificationGestureHook.isHidden(window));
        window.big = null; window.small = new Object();
        assertFalse(FocusNotificationGestureHook.isHidden(window));
        window.small = null; window.expanded = new Object();
        assertFalse(FocusNotificationGestureHook.isHidden(window));
        window.expanded = null; window.temporary = new Object();
        assertFalse("A temporarily shown notification must keep its native controls",
                FocusNotificationGestureHook.isHidden(window));
        window.temporary = null;
        assertTrue(FocusNotificationGestureHook.isHidden(window));
    }

    private static final class IncompleteWindow {
        public Object getCurrentBigIslandState() { return null; }
        public Object getCurrentSmallIslandState() { return null; }
        public Object getCurrentExpandedState() { return null; }
    }

    @Test public void anUnknownWindowApiCannotBeMistakenForAHiddenIsland() {
        assertThrows(ReflectiveOperationException.class,
                () -> FocusNotificationGestureHook.isHidden(new IncompleteWindow()));
    }
}
