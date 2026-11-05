package cc.star0.xposed.hyperstylus.runtime.charge;

import android.os.Bundle;
import cc.star0.xposed.hyperstylus.config.EnhanceConfig;
import cc.star0.xposed.hyperstylus.model.pen.BatteryState;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static cc.star0.xposed.hyperstylus.runtime.charge.ChargeLimitPolicy.Command.*;
import static cc.star0.xposed.hyperstylus.runtime.charge.ChargeLimitPolicy.OneShot;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ChargeNotificationPolicyTest {
    private static EnhanceConfig config(boolean limit) {
        Bundle data = EnhanceConfig.defaults().toBundle();
        data.putBoolean("charge_limit_enabled", limit);
        data.putBoolean("charging_notification_enabled", true);
        data.putBoolean("attached_notification_enabled", true);
        return new EnhanceConfig(data);
    }
    private static BatteryState sample(int level, boolean charging) {
        BatteryState pen = new BatteryState(); pen.battery(level, charging ? 4 : 2, 1_000); return pen;
    }

    @Test public void stopWorksWithoutThresholdControlAndLastsUntilUndocked() {
        ChargeLimitPolicy policy = new ChargeLimitPolicy(); BatteryState pen = sample(20, true);
        policy.request(OneShot.STOP);
        assertEquals(STOP, policy.evaluate(config(false), pen, 1_000, true));
        policy.applied(STOP, true); pen.battery(10, 2, 2_000);
        assertEquals(NONE, policy.evaluate(config(false), pen, 2_000, true)); assertTrue(policy.blocked);
        policy.resetCycle();
        assertEquals(RESTORE, policy.evaluate(config(false), pen, 2_000, true));
    }

    @Test public void startBypassesLowerLimitAndOldStoppedSampleButStopsAtUpperLimit() {
        ChargeLimitPolicy policy = new ChargeLimitPolicy(); BatteryState pen = sample(65, false);
        policy.ownsStop = true; policy.request(OneShot.START);
        assertEquals(RESTORE, policy.evaluate(config(true), pen, 1_000, true));
        policy.applied(RESTORE, true);
        assertEquals(NONE, policy.evaluate(config(true), pen, 1_001, true)); assertFalse(policy.blocked);
        pen.battery(79, 4, 2_000);
        assertEquals(NONE, policy.evaluate(config(true), pen, 2_000, true));
        pen.battery(80, 4, 3_000);
        assertEquals(STOP, policy.evaluate(config(true), pen, 3_000, true));
        assertEquals(OneShot.STOP, policy.oneShot());
    }

    @Test public void fullIgnoresConfiguredUpperLimitAndStopsAtOneHundred() {
        ChargeLimitPolicy policy = new ChargeLimitPolicy(); BatteryState pen = sample(80, true);
        policy.request(OneShot.FULL);
        assertEquals(NONE, policy.evaluate(config(true), pen, 1_000, true)); assertEquals(100, policy.target(config(true)));
        pen.battery(99, 4, 2_000); assertEquals(NONE, policy.evaluate(config(true), pen, 2_000, true));
        pen.battery(100, 4, 3_000); assertEquals(STOP, policy.evaluate(config(true), pen, 3_000, true));
        assertEquals(80, config(true).chargeStop);
    }

    @Test public void startWithoutLimitChargesToFull() {
        ChargeLimitPolicy policy = new ChargeLimitPolicy(); policy.request(OneShot.START);
        assertEquals(100, policy.target(config(false)));
        assertEquals(NONE, policy.evaluate(config(false), sample(90, true), 1_000, true));
        assertEquals(STOP, policy.evaluate(config(false), sample(100, true), 1_000, true));
    }

    @Test public void startAtOrAboveUpperLimitCannotReenableSupply() {
        for (int level : new int[]{80, 90, 100}) {
            ChargeLimitPolicy policy = new ChargeLimitPolicy(); policy.ownsStop = true; policy.request(OneShot.START);
            assertEquals(NONE, policy.evaluate(config(true), sample(level, false), 1_000, true));
            assertTrue(policy.blocked); assertTrue(policy.ownsStop);
        }
    }

    @Test public void allNotificationsOffEndsTemporaryOverrideAndRestoresNormalPolicy() {
        ChargeLimitPolicy policy = new ChargeLimitPolicy(); policy.request(OneShot.STOP); policy.ownsStop = true;
        assertEquals(RESTORE, policy.evaluate(EnhanceConfig.defaults(), sample(60, false), 1_000, true));
        assertEquals(OneShot.NONE, policy.oneShot());
        policy.request(OneShot.FULL);
        Bundle data = config(true).toBundle();
        data.putBoolean("charging_notification_enabled", false); data.putBoolean("attached_notification_enabled", false);
        assertEquals(STOP, policy.evaluate(new EnhanceConfig(data), sample(90, true), 1_000, true));
    }

    @Test public void nativeStopAndTabletProtectionCannotBeOverriddenByUserRequests() {
        for (OneShot request : new OneShot[]{OneShot.START, OneShot.FULL}) {
            ChargeLimitPolicy policy = new ChargeLimitPolicy(); policy.ownsStop = true; policy.request(request);
            assertEquals(NONE, policy.evaluate(config(true), sample(60, false), 1_000, false));
            policy.nativeStop();
            assertEquals(NONE, policy.evaluate(config(true), sample(60, false), 1_000, true));
        }
    }

    @Test public void temporaryOperationsSurviveReloadButNotNewDockSession() {
        for (OneShot request : new OneShot[]{OneShot.STOP, OneShot.START, OneShot.FULL}) {
            ChargeLimitPolicy before = new ChargeLimitPolicy(); before.request(request);
            ChargeLimitPolicy after = new ChargeLimitPolicy(); after.restoreState(before.saveState());
            assertEquals(request, after.oneShot());
            after.resetCycle(); assertEquals(OneShot.NONE, after.oneShot());
            assertEquals(STOP, after.evaluate(config(true), sample(90, true), 1_000, true));
        }
    }

    @Test public void cancellingFullRestoresThePreviouslyAdmittedCycleUntilItsConfiguredLimit() {
        ChargeLimitPolicy policy = new ChargeLimitPolicy(); BatteryState pen = sample(45, true);
        policy.evaluate(config(true), pen, 1_000, true);
        policy.request(OneShot.FULL); pen.battery(65, 4, 2_000);
        policy.request(OneShot.CANCEL_FULL);
        assertEquals(OneShot.NONE, policy.oneShot());
        assertEquals(NONE, policy.evaluate(config(true), pen, 2_000, true)); assertFalse(policy.blocked);
        pen.battery(80, 4, 3_000); assertEquals(STOP, policy.evaluate(config(true), pen, 3_000, true));
    }

    @Test public void cancellingFullAboveTheNormalLimitStopsAndRestoresManualStartWhenPresent() {
        ChargeLimitPolicy policy = new ChargeLimitPolicy(); policy.request(OneShot.START);
        policy.request(OneShot.FULL); policy.request(OneShot.CANCEL_FULL);
        assertEquals(OneShot.START, policy.oneShot());
        assertEquals(NONE, policy.evaluate(config(true), sample(65, true), 1_000, true));
        assertEquals(STOP, policy.evaluate(config(true), sample(90, true), 1_000, true));
    }

    @Test public void repeatedFullRequestsAndReloadRetainTheCancellationTarget() {
        ChargeLimitPolicy policy = new ChargeLimitPolicy(); BatteryState pen = sample(45, true);
        policy.evaluate(config(true), pen, 1_000, true);
        policy.request(OneShot.FULL); policy.request(OneShot.FULL);
        ChargeLimitPolicy restored = new ChargeLimitPolicy(); restored.restoreState(policy.saveState());
        restored.request(OneShot.CANCEL_FULL); restored.request(OneShot.CANCEL_FULL);
        assertEquals(OneShot.NONE, restored.oneShot());
        assertEquals(NONE, restored.evaluate(config(true), sample(65, true), 1_000, true));
        assertFalse(restored.blocked);
        policy.resetCycle(); policy.request(OneShot.CANCEL_FULL);
        assertEquals(STOP, policy.evaluate(config(true), sample(65, true), 1_000, true));
    }

    @Test public void manualTargetsUseKnownLevelsWithoutRelaxingAutomaticLowThresholdAdmission() {
        ChargeLimitPolicy automatic = new ChargeLimitPolicy();
        assertEquals(NONE, automatic.evaluate(config(true), sample(45, false), 241_000, true));
        assertTrue(automatic.blocked);
        for (OneShot action : new OneShot[]{OneShot.START, OneShot.FULL}) {
            ChargeLimitPolicy policy = new ChargeLimitPolicy(); policy.ownsStop = true; policy.request(action);
            assertEquals(NONE, policy.evaluate(config(true), sample(action == OneShot.START ? 80 : 100, false), 241_000, true));
            assertTrue(policy.blocked); assertTrue(policy.ownsStop);
        }
    }

    @Test public void cancellingFullCannotKeepChargingAboveTheNormalLimitWithAnOlderKnownLevel() {
        ChargeLimitPolicy policy = new ChargeLimitPolicy();
        policy.evaluate(config(true), sample(45, true), 1_000, true);
        policy.request(OneShot.FULL); policy.request(OneShot.CANCEL_FULL);
        assertEquals(STOP, policy.evaluate(config(true), sample(90, true), 241_000, true));
        assertTrue(policy.blocked);
    }
}
