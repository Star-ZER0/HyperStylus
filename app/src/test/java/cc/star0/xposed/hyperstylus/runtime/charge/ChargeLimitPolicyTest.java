package cc.star0.xposed.hyperstylus.runtime.charge;

import cc.star0.xposed.hyperstylus.config.EnhanceConfig;
import cc.star0.xposed.hyperstylus.model.pen.BatteryState;
import cc.star0.xposed.hyperstylus.model.pen.ChargeLinkState;

import static cc.star0.xposed.hyperstylus.runtime.charge.ChargeLimitPolicy.Command.*;
import static org.junit.Assert.*;

import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ChargeLimitPolicyTest {
    private static EnhanceConfig enabled() {
        Bundle values = EnhanceConfig.defaults().toBundle(); values.putBoolean("charge_limit_enabled", true);
        return new EnhanceConfig(values);
    }

    private static BatteryState sample(int level, boolean charging, long time) {
        BatteryState state = new BatteryState(); state.battery(level, charging ? 4 : 2, time); return state;
    }

    @Test public void lowBoundaryAdmitsWholeCycleUntilHighBoundary() {
        ChargeLimitPolicy policy = new ChargeLimitPolicy();
        EnhanceConfig config = enabled();
        BatteryState pen = sample(50, true, 1_000);
        assertEquals(NONE, policy.evaluate(config, pen, 1_000, true)); assertFalse(policy.blocked);
        pen.battery(79, 4, 2_000);
        assertEquals(NONE, policy.evaluate(config, pen, 2_000, true)); assertFalse(policy.blocked);
        pen.battery(80, 4, 3_000);
        assertEquals(STOP, policy.evaluate(config, pen, 3_000, true)); assertTrue(policy.blocked);
        policy.applied(STOP, true);
        pen.battery(50, 2, 4_000);
        assertEquals(NONE, policy.evaluate(config, pen, 4_000, false));
        assertEquals(RESTORE, policy.evaluate(config, pen, 4_000, true));
        policy.applied(RESTORE, true); assertFalse(policy.ownsStop);
    }

    @Test public void unknownAndExpiredBatteryCannotStartAChargingCycle() {
        ChargeLimitPolicy unknown = new ChargeLimitPolicy();
        assertEquals(NONE, unknown.evaluate(enabled(), new BatteryState(), 10_000, true));
        ChargeLimitPolicy expired = new ChargeLimitPolicy();
        BatteryState pen = sample(45, false, 1_000);
        assertEquals(NONE, expired.evaluate(enabled(), pen, 1_001 + ChargeLinkState.FRESH_BATTERY_MS, true));
        assertTrue(expired.blocked);
        ChargeLinkState awaitingTelemetry = new ChargeLinkState();
        assertTrue(awaitingTelemetry.connection(1, 1_000, 1_000, "pen", 10_000));
        assertFalse(awaitingTelemetry.ready());
        assertFalse(awaitingTelemetry.sample(45, 1_000, 1_001 + ChargeLinkState.FRESH_BATTERY_MS));
        assertFalse(awaitingTelemetry.ready());
    }

    @Test public void admittedLowSampleAgingKeepsGateArmed() {
        ChargeLimitPolicy policy = new ChargeLimitPolicy();
        BatteryState pen = sample(45, true, 1_000);
        policy.evaluate(enabled(), pen, 1_000, true);
        assertEquals(NONE, policy.evaluate(enabled(), pen, 1_001 + ChargeLinkState.FRESH_BATTERY_MS, true));
        assertFalse(policy.blocked);
    }

    @Test public void oneMinuteBoundaryControlsNewCycleAdmission() {
        BatteryState pen = sample(45, false, 1_000);
        for (long age : new long[]{59_999, 60_000}) {
            ChargeLimitPolicy policy = new ChargeLimitPolicy();
            assertEquals(NONE, policy.evaluate(enabled(), pen, 1_000 + age, true));
            assertFalse(policy.blocked);
        }
        for (long age : new long[]{60_001, 90_000}) {
            ChargeLimitPolicy policy = new ChargeLimitPolicy();
            assertEquals(NONE, policy.evaluate(enabled(), pen, 1_000 + age, true));
            assertTrue(policy.blocked);
            assertFalse(policy.ownsStop);
        }
    }

    @Test public void nativeChargingEndAboveLowThresholdEndsTheAdmittedCycle() {
        ChargeLimitPolicy policy = new ChargeLimitPolicy();
        BatteryState pen = sample(45, true, 1_000);
        policy.evaluate(enabled(), pen, 1_000, true);
        pen.battery(77, 2, 2_000);
        assertEquals(STOP, policy.evaluate(enabled(), pen, 2_000, true));
        assertTrue(policy.blocked);
    }

    @Test public void restoreNeverTakesOwnershipOfANativeStop() {
        ChargeLimitPolicy policy = new ChargeLimitPolicy();
        BatteryState pen = sample(90, true, 1_000);
        assertEquals(STOP, policy.evaluate(enabled(), pen, 1_000, true));
        policy.applied(STOP, true); assertTrue(policy.ownsStop);
        policy.nativeStop(); assertFalse(policy.ownsStop);
        pen.battery(45, 2, 2_000);
        assertEquals(NONE, policy.evaluate(enabled(), pen, 2_000, true));
        assertEquals(NONE, policy.evaluate(EnhanceConfig.defaults(), pen, 2_000, true));
    }

    @Test public void failedCommandsDoNotClaimOwnershipAndFirmwareRestartStillStops() {
        ChargeLimitPolicy policy = new ChargeLimitPolicy();
        BatteryState pen = sample(90, true, 1_000);
        assertEquals(STOP, policy.evaluate(enabled(), pen, 1_000, false));
        policy.applied(STOP, false); assertFalse(policy.ownsStop);
        assertEquals(STOP, policy.evaluate(enabled(), pen, 1_000, false));
        policy.applied(STOP, true);
        // Fresh firmware charging resumes after a successful module stop.
        pen.battery(90, 4, 2_000);
        assertEquals(STOP, policy.evaluate(enabled(), pen, 2_000, false));
    }

    @Test public void reconnectDropsBatteryAndAdmissionFromPreviousPen() {
        ChargeLinkState link = new ChargeLinkState();
        ChargeLimitPolicy policy = new ChargeLimitPolicy();
        assertTrue(link.connection(1, 1_000, 1_000, "first", 10_000));
        assertTrue(link.sample(45, 2_000, 10_000)); assertTrue(link.ready());
        policy.evaluate(enabled(), sample(45, true, 2_000), 2_000, true);
        assertFalse(policy.blocked);
        assertTrue(link.connection(0, 3_000, 0, null, 10_000));
        assertTrue(link.connection(1, 4_000, 4_000, "second", 10_000));
        policy.resetCycle();
        assertFalse(link.ready()); assertFalse(link.sample(45, 2_000, 10_000));
        assertFalse(link.session(1_000, "first"));
        assertEquals(NONE, policy.evaluate(enabled(), new BatteryState(), 10_000, true));
        assertTrue(link.sample(77, 4_500, 10_000));
        assertEquals(STOP, policy.evaluate(enabled(), sample(77, false, 4_500), 10_000, true));
        assertTrue(policy.blocked);
    }
}
