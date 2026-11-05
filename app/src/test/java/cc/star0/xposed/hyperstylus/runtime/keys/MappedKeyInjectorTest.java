package cc.star0.xposed.hyperstylus.runtime.keys;


import static org.junit.Assert.*;
import android.os.Process;
import android.view.InputDevice;
import android.view.InputEvent;
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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class MappedKeyInjectorTest {
    private int uid;
    @Before public void serverIdentity() { uid = Process.myUid(); ShadowProcess.setUid(Process.SYSTEM_UID); }
    @After public void restoreIdentity() { ShadowProcess.setUid(uid); }

    public static final class NativeQueue {
        public final List<Integer> deviceIds = new ArrayList<>(), actions = new ArrayList<>();
        public boolean downRejected, downThrows;
        public int injectInputEvent(InputEvent event, boolean targeted, int targetUid, int mode, int timeout, int flags) {
            assertFalse(targeted); assertEquals(-1, targetUid); assertEquals(0, mode); assertEquals(0, timeout);
            assertTrue((flags & MappedKeyInjector.PASS_TO_USER) != 0);
            assertTrue((flags & MappedKeyInjector.DISABLE_REPEAT) != 0);
            KeyEvent key = (KeyEvent) event;
            // Replay the actual OTA's bit-26 device-selection branch (0x160414..0x160430).
            deviceIds.add((flags & MappedKeyInjector.FILTERED) != 0 ? key.getDeviceId() : -1);
            actions.add(key.getAction());
            if (key.getAction() == KeyEvent.ACTION_DOWN && downThrows) throw new IllegalStateException("DOWN failure");
            return key.getAction() == KeyEvent.ACTION_DOWN && downRejected ? 2 : 0;
        }
    }

    private static KeyEvent[] pair() {
        return StylusKeyInjector.packet(195, 53, InputDevice.SOURCE_STYLUS | InputDevice.SOURCE_KEYBOARD);
    }

    @Test public void nativePairKeepsPhysicalDeviceInsteadOfVirtualKeyboard() throws Throwable {
        NativeQueue queue = new NativeQueue();
        new MappedKeyInjector(queue).deliver(pair());
        assertEquals(List.of(53, 53), queue.deviceIds);
        assertEquals(List.of(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), queue.actions);
    }

    @Test public void rejectedDownStillAttemptsUpWithoutAnotherInjectionPath() throws Exception {
        NativeQueue queue = new NativeQueue(); queue.downRejected = true;
        assertThrows(IllegalStateException.class, () -> new MappedKeyInjector(queue).deliver(pair()));
        assertEquals(List.of(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), queue.actions);
    }

    @Test public void exceptionalDownStillReleasesTheKeyAndPreservesTheCause() throws Exception {
        NativeQueue queue = new NativeQueue(); queue.downThrows = true;
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> new MappedKeyInjector(queue).deliver(pair()));
        assertEquals("DOWN failure", failure.getMessage());
        assertEquals(List.of(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), queue.actions);
    }

    @Test public void keyboardAndMalformedPairsCannotUseThePrivilegedRoute() throws Exception {
        NativeQueue queue = new NativeQueue(); MappedKeyInjector sender = new MappedKeyInjector(queue);
        assertThrows(IllegalArgumentException.class, () -> sender.deliver(StylusKeyInjector.packet(195, 53, InputDevice.SOURCE_KEYBOARD)));
        assertThrows(IllegalArgumentException.class, () -> sender.deliver(StylusKeyInjector.packet(195, -1, InputDevice.SOURCE_STYLUS)));
        KeyEvent[] mixed = pair(); mixed[1] = KeyEvent.changeAction(mixed[1], KeyEvent.ACTION_DOWN);
        assertThrows(IllegalArgumentException.class, () -> sender.deliver(mixed));
        assertTrue(queue.actions.isEmpty());
    }

    @Test public void applicationUidCannotInvokeTheNativeRoute() throws Exception {
        NativeQueue queue = new NativeQueue(); ShadowProcess.setUid(19001);
        assertThrows(SecurityException.class, () -> new MappedKeyInjector(queue).deliver(pair()));
        assertTrue(queue.actions.isEmpty());
    }

    public static final class WrongAbi {
        public int injectInputEvent(InputEvent event, int pid, int uid, int mode, int timeout, int flags) { return 0; }
    }
    @Test public void incompatibleNativeAbiIsRejectedBeforeDelivery() {
        assertThrows(NoSuchMethodException.class, () -> new MappedKeyInjector(new WrongAbi()));
    }
}
