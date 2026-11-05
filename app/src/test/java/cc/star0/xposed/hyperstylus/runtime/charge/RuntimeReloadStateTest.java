package cc.star0.xposed.hyperstylus.runtime.charge;

import static org.junit.Assert.*;
import android.os.Bundle;
import cc.star0.xposed.hyperstylus.config.EnhanceConfig;
import cc.star0.xposed.hyperstylus.model.pen.BatteryState;
import cc.star0.xposed.hyperstylus.model.pen.ChargeLinkState;
import cc.star0.xposed.hyperstylus.model.pen.HallState;
import cc.star0.xposed.hyperstylus.model.pen.SignalState;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class RuntimeReloadStateTest {
    private static EnhanceConfig configuration() {
        Bundle value = EnhanceConfig.defaults().toBundle(); value.putBoolean("charge_limit_enabled", true); return new EnhanceConfig(value);
    }
    @Test public void admittedCycleAtSixtyPercentSurvivesNewPolicyInstance() {
        ChargeLimitPolicy before = new ChargeLimitPolicy(); BatteryState pen = new BatteryState();
        pen.battery(50, 4, 1_000); assertEquals(ChargeLimitPolicy.Command.NONE, before.evaluate(configuration(), pen, 1_000, false));
        pen.battery(60, 4, 2_000); before.evaluate(configuration(), pen, 2_000, false);
        ChargeLimitPolicy after = new ChargeLimitPolicy(); after.restoreState(before.saveState());
        BatteryState restored = new BatteryState(); restored.restoreState(pen.saveState());
        assertEquals(ChargeLimitPolicy.Command.NONE, after.evaluate(configuration(), restored, 2_001, false));
        assertFalse(after.blocked);
    }
    @Test public void ownedStopAndNativeOffRemainDistinctAcrossReload() {
        ChargeLimitPolicy before = new ChargeLimitPolicy(); BatteryState pen = new BatteryState(); pen.battery(90, 4, 1_000);
        assertEquals(ChargeLimitPolicy.Command.STOP, before.evaluate(configuration(), pen, 1_000, true));
        before.applied(ChargeLimitPolicy.Command.STOP, true);
        ChargeLimitPolicy after = new ChargeLimitPolicy(); after.restoreState(before.saveState());
        assertTrue(after.blocked); assertTrue(after.ownsStop);
        Object hardware = new Object(); WirelessChargeControl first = new WirelessChargeControl(); first.capture(hardware);
        first.nativeRequest(hardware, false);
        WirelessChargeControl second = new WirelessChargeControl(); second.capture(hardware); second.restoreState(first.saveState());
        assertTrue(second.stoppedByNative());
    }
    @Test public void telemetryRoundTripRetainsOrderingAndOfficialSessionIdentity() {
        ChargeLinkState link = new ChargeLinkState(); link.connection(1, 1_200, 1_000, "official-session", 1_300); link.sample(60, 1_250, 1_300);
        ChargeLinkState restored = new ChargeLinkState(); restored.restoreState(link.saveState());
        assertTrue(restored.ready()); assertTrue(restored.session(1_000, "official-session"));
        assertFalse(restored.connection(1, 1_400, 900, "older-session", 1_400));
        BatteryState battery = new BatteryState(); battery.battery(60, 4, 1_250); battery.attachment(1, 1_280);
        BatteryState copy = new BatteryState(); copy.restoreState(battery.saveState());
        assertFalse(copy.battery(50, 2, 1_000)); assertEquals(60, copy.displayLevel(1_300)); assertTrue(copy.attached);
        SignalState signal = new SignalState(); signal.accept(-65, 1_250, 1_300);
        SignalState signalCopy = new SignalState(); signalCopy.restoreState(signal.saveState()); assertEquals(-65, signalCopy.rssi);
        HallState hall = new HallState(); hall.update(0, 1); HallState hallCopy = new HallState(); hallCopy.restoreState(hall.saveState());
        assertEquals(1, hallCopy.update(-1, 1));
    }
}
