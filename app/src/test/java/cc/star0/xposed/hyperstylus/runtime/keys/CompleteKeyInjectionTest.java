package cc.star0.xposed.hyperstylus.runtime.keys;

import android.os.Process;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowProcess;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CompleteKeyInjectionTest {
    private int uid;
    @Before public void systemServer() { uid = Process.myUid(); ShadowProcess.setUid(Process.SYSTEM_UID); }
    @After public void restoreIdentity() { ShadowProcess.setUid(uid); }

    /** Android 15 dropInboundEventLocked cancels by device, without restricting the key code. */
    public static final class PolicyQueue {
        final List<KeyEvent> events = new ArrayList<>();
        final Map<String, KeyEvent> held = new LinkedHashMap<>();
        public int injectInputEvent(InputEvent input, boolean targeted, int targetUid, int mode, int timeout, int flags) {
            KeyEvent event = (KeyEvent) input;
            int device = (flags & MappedKeyInjector.FILTERED) != 0 ? event.getDeviceId() : -1;
            KeyEvent delivered = new KeyEvent(event.getDownTime(), event.getEventTime(), event.getAction(),
                    event.getKeyCode(), event.getRepeatCount(), event.getMetaState(), device,
                    event.getScanCode(), event.getFlags(), event.getSource());
            events.add(delivered);
            String key = device + ":" + event.getKeyCode();
            if (event.getAction() == KeyEvent.ACTION_DOWN) held.put(key, delivered);
            else held.remove(key);
            return 0;
        }
        void dropPhysical(KeyEvent physical) {
            held.values().removeIf(down -> {
                if (down.getDeviceId() != physical.getDeviceId()) return false;
                events.add(KeyEvent.changeFlags(KeyEvent.changeAction(down, KeyEvent.ACTION_UP),
                        down.getFlags() | KeyEvent.FLAG_CANCELED));
                return true;
            });
        }
    }

    @Test public void droppingTheOwnedPhysicalKeyCannotReleaseItsMappedTarget() throws Throwable {
        PolicyQueue queue = new PolicyQueue();
        FullKeyDispatcher mapper = new FullKeyDispatcher(new MappedKeyInjector(queue)::deliverFull);
        KeyEvent down = physical(KeyEvent.ACTION_DOWN, 0, 100);
        mapper.accept(down, KeyEvent.KEYCODE_5);
        queue.dropPhysical(down); // The asynchronous mapped DOWN can arrive before the original is dropped.
        assertEquals("Policy cancellation of the pen must not emit an early keyboard UP", 1, queue.events.size());
        mapper.accept(physical(KeyEvent.ACTION_DOWN, 1, 500), KeyEvent.KEYCODE_5);
        queue.dropPhysical(physical(KeyEvent.ACTION_DOWN, 0, 550));
        assertEquals(2, queue.events.size());
        mapper.accept(physical(KeyEvent.ACTION_UP, 0, 900), KeyEvent.KEYCODE_5);
        assertEquals(List.of(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP),
                queue.events.stream().map(KeyEvent::getAction).toList());
        assertEquals(1, queue.events.get(1).getRepeatCount());
        assertTrue(queue.held.isEmpty());
    }

    @Test public void nativeCancellationAffectsAllHeldKeysOnThePhysicalDevice() {
        PolicyQueue queue = new PolicyQueue();
        KeyEvent pen = physical(KeyEvent.ACTION_DOWN, 0, 100);
        KeyEvent sameDeviceKey = new KeyEvent(100, 100, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_5,
                0, 0, pen.getDeviceId(), KeyboardMapping.scanCode(KeyEvent.KEYCODE_5), 0, InputDevice.SOURCE_KEYBOARD);
        queue.injectInputEvent(sameDeviceKey, false, -1, 0, 0, MappedKeyInjector.FLAGS);
        queue.dropPhysical(pen);
        assertEquals(List.of(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP),
                queue.events.stream().map(KeyEvent::getAction).toList());
        assertTrue(queue.events.get(1).isCanceled());
    }

    public static final class NativeQueue {
        final List<KeyEvent> events = new ArrayList<>();
        boolean rejectDown;
        public int injectInputEvent(InputEvent event, boolean targeted, int targetUid, int mode, int timeout, int flags) {
            assertFalse(targeted); assertEquals(-1, targetUid); assertEquals(0, mode); assertEquals(0, timeout);
            assertEquals("Keyboard policy must run before queueing", 0, flags & MappedKeyInjector.FILTERED);
            assertTrue("The complete mapping owns its repeat timer", (flags & MappedKeyInjector.DISABLE_REPEAT) != 0);
            assertEquals("Policy decides whether a system key reaches the application", 0, flags & MappedKeyInjector.PASS_TO_USER);
            KeyEvent key = (KeyEvent) event;
            events.add(new KeyEvent(key));
            return rejectDown && key.getAction() == KeyEvent.ACTION_DOWN ? 2 : 0;
        }
    }
    private static KeyEvent physical(int action, int repeat, long time) {
        return new KeyEvent(100, time, action, KeyEvent.KEYCODE_PAGE_UP, repeat, KeyEvent.META_CTRL_ON,
                53, 104, KeyEvent.FLAG_FROM_SYSTEM | (repeat == 1 ? KeyEvent.FLAG_LONG_PRESS : 0), InputDevice.SOURCE_STYLUS);
    }
    private static KeyEvent mapped(int code, int action) {
        return new KeyEvent(100, 120, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, KeyboardMapping.scanCode(code),
                0, InputDevice.SOURCE_KEYBOARD);
    }
    @Test public void heldEdgesAndRepeatReachNativeQueueWithTheirMetadata() throws Throwable {
        NativeQueue queue = new NativeQueue();
        FullKeyDispatcher mapper = new FullKeyDispatcher(new MappedKeyInjector(queue)::deliverFull);
        mapper.accept(physical(KeyEvent.ACTION_DOWN, 0, 100), KeyEvent.KEYCODE_DPAD_RIGHT);
        mapper.accept(physical(KeyEvent.ACTION_DOWN, 1, 500), KeyEvent.KEYCODE_DPAD_LEFT);
        mapper.accept(physical(KeyEvent.ACTION_UP, 0, 900), KeyEvent.KEYCODE_DPAD_LEFT);
        assertEquals(3, queue.events.size());
        for (KeyEvent event : queue.events) {
            assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, event.getKeyCode());
            assertEquals(KeyCharacterMap.VIRTUAL_KEYBOARD, event.getDeviceId()); assertEquals(100, event.getDownTime());
            assertEquals(KeyEvent.META_CTRL_ON, event.getMetaState());
            assertEquals(InputDevice.SOURCE_KEYBOARD, event.getSource());
            assertTrue(KeyboardMapping.isEvent(event));
        }
        assertEquals(KeyEvent.ACTION_DOWN, queue.events.get(0).getAction());
        assertEquals(1, queue.events.get(1).getRepeatCount());
        assertTrue(queue.events.get(1).isLongPress());
        assertEquals(KeyEvent.ACTION_UP, queue.events.get(2).getAction());
        assertEquals(900, queue.events.get(2).getEventTime());
    }
    @Test public void aRejectedDownIsFollowedByACanceledRelease() throws Throwable {
        NativeQueue queue = new NativeQueue(); queue.rejectDown = true;
        FullKeyDispatcher mapper = new FullKeyDispatcher(new MappedKeyInjector(queue)::deliverFull, () -> 150);
        assertThrows(IllegalStateException.class,
                () -> mapper.accept(physical(KeyEvent.ACTION_DOWN, 0, 100), KeyEvent.KEYCODE_ENTER));
        assertEquals(2, queue.events.size());
        assertEquals(KeyEvent.ACTION_UP, queue.events.get(1).getAction());
        assertTrue(queue.events.get(1).isCanceled());
        mapper.close(); assertEquals(2, queue.events.size());
    }
    @Test public void mappedPenKeyCodesCannotReenterThePhysicalOrProActionPaths() throws Throwable {
        NativeQueue queue = new NativeQueue(); MappedKeyInjector injector = new MappedKeyInjector(queue);
        for (int code : new int[]{193, 194, 195, 196, 197, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN}) {
            KeyEvent event = mapped(code, KeyEvent.ACTION_DOWN);
            injector.deliverFull(event);
            assertTrue(KeyboardMapping.isEvent(event));
            assertEquals(-1, StylusKeyHook.button(event));
            assertFalse(ProGestureAdapter.mappedAppGesture(event));
        }
        assertEquals(7, queue.events.size());
    }
    @Test public void unmarkedOrMalformedEventsCannotUseTheFullMappingRoute() throws Throwable {
        NativeQueue queue = new NativeQueue(); MappedKeyInjector injector = new MappedKeyInjector(queue);
        assertThrows(IllegalArgumentException.class, () -> injector.deliverFull(physical(KeyEvent.ACTION_DOWN, 0, 100)));
        assertThrows(IllegalArgumentException.class, () -> injector.deliverFull(mapped(KeyEvent.KEYCODE_UNKNOWN, KeyEvent.ACTION_DOWN)));
        assertThrows(IllegalArgumentException.class, () -> injector.deliverFull(mapped(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_MULTIPLE)));
        assertTrue(queue.events.isEmpty());
    }
    @Test public void applicationIdentityCannotInjectHeldPenMappings() throws Throwable {
        NativeQueue queue = new NativeQueue(); ShadowProcess.setUid(19001);
        assertThrows(SecurityException.class,
                () -> new MappedKeyInjector(queue).deliverFull(mapped(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN)));
        assertTrue(queue.events.isEmpty());
    }

    @Test public void keyboardPositionsAndVirtualCharacterMapReachConsumersForEveryEdge() throws Throwable {
        int[][] positions = {{KeyEvent.KEYCODE_5, 6}, {KeyEvent.KEYCODE_NUMPAD_5, 76},
                {KeyEvent.KEYCODE_DPAD_UP, 103}, {KeyEvent.KEYCODE_SHIFT_LEFT, 42},
                {KeyEvent.KEYCODE_SHIFT_RIGHT, 54}, {KeyEvent.KEYCODE_F5, 63}};
        for (int[] position : positions) {
            NativeQueue queue = new NativeQueue();
            FullKeyDispatcher mapper = new FullKeyDispatcher(new MappedKeyInjector(queue)::deliverFull);
            mapper.accept(physical(KeyEvent.ACTION_DOWN, 0, 100), position[0]);
            mapper.accept(physical(KeyEvent.ACTION_DOWN, 1, 500), position[0]);
            mapper.accept(physical(KeyEvent.ACTION_UP, 0, 900), position[0]);
            assertEquals(3, queue.events.size());
            for (KeyEvent event : queue.events) {
                assertEquals(position[1], event.getScanCode());
                assertEquals(KeyCharacterMap.VIRTUAL_KEYBOARD, event.getDeviceId());
            }
            if (position[0] == KeyEvent.KEYCODE_5) assertEquals('5', queue.events.get(0).getUnicodeChar(0));
        }
    }

    @Test public void wrongKeyboardPositionCannotBeInjectedAsTheSelectedTarget() throws Exception {
        NativeQueue queue = new NativeQueue(); MappedKeyInjector injector = new MappedKeyInjector(queue);
        KeyEvent wrong = new KeyEvent(100, 100, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_5,
                0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, KeyboardMapping.scanCode(KeyEvent.KEYCODE_6), 0, InputDevice.SOURCE_KEYBOARD);
        assertThrows(IllegalArgumentException.class, () -> injector.deliverFull(wrong));
        assertTrue(queue.events.isEmpty());
    }
}
