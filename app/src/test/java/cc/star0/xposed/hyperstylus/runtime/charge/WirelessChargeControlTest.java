package cc.star0.xposed.hyperstylus.runtime.charge;


import org.junit.Test;
import static org.junit.Assert.*;

public class WirelessChargeControlTest {
    private static final class Hardware {
        public int calls;
        public boolean enabled;
        private int setWirelessChargingEnabled(boolean value) { calls++; enabled = value; return 0; }
        private int getWirelessChargingStatus() { return enabled ? 1 : 0; }
    }
    @Test public void nativeStopAlwaysPreventsModuleRestoration() throws Throwable {
        Hardware hardware = new Hardware(); WirelessChargeControl control = new WirelessChargeControl();
        control.capture(hardware);
        control.nativeRequest(hardware, false);
        assertSame(WirelessChargeControl.SKIPPED, control.command(ChargeLimitPolicy.Command.RESTORE));
        assertEquals(0, hardware.calls);
        control.nativeRequest(hardware, true);
        assertEquals(0, control.command(ChargeLimitPolicy.Command.RESTORE));
        assertTrue(hardware.enabled); assertEquals(1, control.status());
    }
    @Test public void gateVetoNeverDeniesNativeOffOrUnrelatedHardware() {
        Hardware pen = new Hardware(), unrelated = new Hardware(); WirelessChargeControl control = new WirelessChargeControl();
        control.capture(pen); control.veto(true);
        assertTrue(control.nativeRequest(pen, true).denied());
        assertFalse(control.nativeRequest(pen, false).denied());
        assertFalse(control.nativeRequest(unrelated, true).denied());
        assertTrue(control.stoppedByNative());
    }
    @Test public void persistedOwnershipCannotOverrideNewerNativeIntentAndShutdownSkipsCommands() throws Throwable {
        Hardware hardware = new Hardware(); WirelessChargeControl control = new WirelessChargeControl();
        control.nativeRequest(hardware, false); control.capture(hardware); control.restore(0);
        assertTrue(control.stoppedByNative());
        control.nativeRequest(hardware, true); control.restore(4);
        assertFalse(control.stoppedByNative());
        control.shutdown();
        assertSame(WirelessChargeControl.SKIPPED, control.command(ChargeLimitPolicy.Command.RESTORE));
        assertSame(WirelessChargeControl.SKIPPED, control.command(ChargeLimitPolicy.Command.STOP));
        assertEquals(0, hardware.calls);
    }
}
