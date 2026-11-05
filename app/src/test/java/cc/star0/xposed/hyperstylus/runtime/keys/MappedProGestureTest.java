package cc.star0.xposed.hyperstylus.runtime.keys;


import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class MappedProGestureTest {
    private static final class Endpoint implements MappedProGesture.Endpoint {
        public boolean panel = true, laser;
        public int panels, laserCalls;
        public final List<String> appKeys = new ArrayList<>();
        @Override public boolean usesShortcutPanel() { return panel; }
        @Override public void showShortcutPanel(int device) { panels++; }
        @Override public boolean closeVirtualLaser(int device) { laserCalls++; return laser; }
        @Override public void sendApplicationKey(int code, int device) { appKeys.add(code + ":" + device); }
    }

    @Test public void shortcutPanelCallbackIsNotReplayedAsAnotherPinchKey() throws Throwable {
        Endpoint endpoint = new Endpoint();
        MappedProGesture.execute(194, 53, endpoint);
        assertEquals(1, endpoint.panels);
        assertTrue(endpoint.appKeys.isEmpty());
        assertEquals(0, endpoint.laserCalls);
    }

    @Test public void applicationPinchKeepsTheOrdinaryFocusPenDevice() throws Throwable {
        Endpoint endpoint = new Endpoint(); endpoint.panel = false;
        MappedProGesture.execute(194, 53, endpoint);
        assertEquals(List.of("194:53"), endpoint.appKeys);
        assertEquals(0, endpoint.panels);
    }

    @Test public void nativeVirtualLaserConsumesDoubleTapBeforeApplicationDelivery() throws Throwable {
        Endpoint endpoint = new Endpoint(); endpoint.laser = true;
        MappedProGesture.execute(195, 53, endpoint);
        assertEquals(1, endpoint.laserCalls);
        assertTrue(endpoint.appKeys.isEmpty());
        endpoint.laser = false;
        MappedProGesture.execute(195, 53, endpoint);
        assertEquals(List.of("195:53"), endpoint.appKeys);
    }

    @Test public void applicationSlidesDoNotTriggerPanelOrLaser() throws Throwable {
        Endpoint endpoint = new Endpoint();
        MappedProGesture.execute(196, 53, endpoint);
        MappedProGesture.execute(197, 53, endpoint);
        assertEquals(List.of("196:53", "197:53"), endpoint.appKeys);
        assertEquals(0, endpoint.panels); assertEquals(0, endpoint.laserCalls);
    }

    @Test public void invalidDeviceAndNonGestureKeysNeverReachTheNativeEndpoint() {
        Endpoint endpoint = new Endpoint();
        assertThrows(IllegalArgumentException.class, () -> MappedProGesture.execute(195, -1, endpoint));
        assertThrows(IllegalArgumentException.class, () -> MappedProGesture.execute(92, 53, endpoint));
        assertTrue(endpoint.appKeys.isEmpty()); assertEquals(0, endpoint.panels);
    }
}
