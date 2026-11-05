package cc.star0.xposed.hyperstylus.runtime.keys;

import android.os.Process;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import java.util.ArrayList;
import java.util.List;
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
public class MappedProFallbackTest {
    private int uid;
    @Before public void systemServer() { uid = Process.myUid(); ShadowProcess.setUid(Process.SYSTEM_UID); }
    @After public void restoreIdentity() { ShadowProcess.setUid(uid); }

    /** AOSP afterKeyEventLockedInterruptable's guard plus Generic.kcm BUTTON_7..10 fallback. */
    public static final class UnhandledQueue {
        final List<KeyEvent> appEvents = new ArrayList<>();
        boolean handled;
        public int injectInputEvent(InputEvent input, boolean targeted, int targetUid, int mode, int timeout, int flags) {
            KeyEvent event = (KeyEvent) input;
            appEvents.add(new KeyEvent(event));
            if (!handled && (event.getFlags() & KeyEvent.FLAG_FALLBACK) == 0
                    && event.getKeyCode() >= 194 && event.getKeyCode() <= 197) {
                appEvents.add(new KeyEvent(event.getDownTime(), event.getEventTime(), event.getAction(),
                        KeyEvent.KEYCODE_DPAD_CENTER, event.getRepeatCount(), event.getMetaState(),
                        event.getDeviceId(), event.getScanCode(), event.getFlags() | KeyEvent.FLAG_FALLBACK,
                        event.getSource()));
            }
            return 0;
        }
    }
    private static KeyEvent[] pro(int code) {
        return StylusKeyInjector.packet(code, 19, InputDevice.SOURCE_STYLUS | InputDevice.SOURCE_KEYBOARD);
    }

    @Test public void unhandledSlidesDeliverOnlyTheSelectedProKeyWithoutDpadCenter() throws Throwable {
        UnhandledQueue queue = new UnhandledQueue();
        MappedKeyInjector injector = new MappedKeyInjector(queue);
        injector.deliver(pro(196)); injector.deliver(pro(197));
        assertEquals(List.of(196, 196, 197, 197), queue.appEvents.stream().map(KeyEvent::getKeyCode).toList());
        assertEquals(List.of(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP, KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP),
                queue.appEvents.stream().map(KeyEvent::getAction).toList());
        assertTrue(queue.appEvents.stream().allMatch(ProGestureAdapter::mappedAppGesture));
    }

    @Test public void allMappedProCommandsKeepBothEdgesAndTheirOriginalPenMetadata() throws Throwable {
        UnhandledQueue queue = new UnhandledQueue(); queue.handled = true;
        MappedKeyInjector injector = new MappedKeyInjector(queue);
        for (int code = 194; code <= 197; code++) {
            KeyEvent[] packet = pro(code);
            injector.deliver(packet);
            for (int edge = 0; edge < 2; edge++) {
                KeyEvent delivered = queue.appEvents.get((code - 194) * 2 + edge);
                assertEquals(code, delivered.getKeyCode()); assertEquals(edge, delivered.getAction());
                assertEquals(19, delivered.getDeviceId());
                assertEquals(packet[edge].getSource(), delivered.getSource());
                assertEquals(packet[edge].getScanCode(), delivered.getScanCode());
                assertEquals(packet[edge].getDownTime(), delivered.getDownTime());
                assertEquals(packet[edge].getEventTime(), delivered.getEventTime());
                assertEquals(0, delivered.getRepeatCount());
                assertEquals("Caller-owned events remain unchanged", 0, packet[edge].getFlags());
            }
        }
        assertEquals(8, queue.appEvents.size());
    }

    @Test public void thePolicyModelReproducesTheReportedSecondKeyForUnmarkedProEvents() {
        UnhandledQueue queue = new UnhandledQueue();
        for (KeyEvent event : pro(196)) queue.injectInputEvent(event, false, -1, 0, 0, MappedKeyInjector.FLAGS);
        assertEquals(List.of(196, KeyEvent.KEYCODE_DPAD_CENTER, 196, KeyEvent.KEYCODE_DPAD_CENTER),
                queue.appEvents.stream().map(KeyEvent::getKeyCode).toList());
        assertEquals(KeyEvent.FLAG_FALLBACK, queue.appEvents.get(1).getFlags());
    }

    @Test public void anExplicitKeyboardButtonMappingKeepsItsNormalSystemFallback() throws Throwable {
        UnhandledQueue queue = new UnhandledQueue();
        MappedKeyInjector injector = new MappedKeyInjector(queue);
        for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            injector.deliverFull(new KeyEvent(100, 200, action, KeyEvent.KEYCODE_BUTTON_9, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, KeyboardMapping.scanCode(KeyEvent.KEYCODE_BUTTON_9),
                    0, InputDevice.SOURCE_KEYBOARD));
        }
        assertEquals(List.of(196, KeyEvent.KEYCODE_DPAD_CENTER, 196, KeyEvent.KEYCODE_DPAD_CENTER),
                queue.appEvents.stream().map(KeyEvent::getKeyCode).toList());
    }
}
