package cc.star0.xposed.hyperstylus.runtime.charge;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import cc.star0.xposed.hyperstylus.common.Protocol;
import cc.star0.xposed.hyperstylus.config.ConfigBridge;
import cc.star0.xposed.hyperstylus.config.EnhanceConfig;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;
import cc.star0.xposed.hyperstylus.storage.ChargeLimitStore;
import cc.star0.xposed.hyperstylus.storage.SystemDataFiles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static cc.star0.xposed.hyperstylus.runtime.charge.ChargeLimitPolicy.OneShot;
import static org.junit.Assert.*;

/** Exercises the notification PendingIntent through the runtime to a captured HAL and its journal. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ChargeNotificationRuntimeTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    private final Context context = RuntimeEnvironment.getApplication();
    private final WirelessChargeControl control = new WirelessChargeControl();
    private final Hardware hardware = new Hardware();
    private FocusStylusModule module;
    private ChargeLimitRuntime runtime;
    private ChargeLimitPolicy policy;
    private ChargeLimitStore storage;
    private long start;
    private boolean previousReady;
    private EnhanceConfig previousConfig;

    private static final class Hardware {
        final List<Boolean> commands = new ArrayList<>();
        int result;
        public int setWirelessChargingEnabled(boolean enabled) { commands.add(enabled); return result; }
        public int getWirelessChargingStatus() { return 0; }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private void set(String name, Object value) throws Exception { field(ChargeLimitRuntime.class, name).set(runtime, value); }
    private void accept(Intent event) throws Exception {
        Method method = ChargeLimitRuntime.class.getDeclaredMethod("accept", Intent.class); method.setAccessible(true);
        method.invoke(runtime, event);
    }
    private static EnhanceConfig config(boolean limit) {
        Bundle data = EnhanceConfig.defaults().toBundle(); data.putBoolean("charge_limit_enabled", limit);
        data.putBoolean("charging_notification_enabled", true); data.putBoolean("attached_notification_enabled", true);
        return new EnhanceConfig(data);
    }
    @Before public void setup() throws Exception {
        previousReady = ConfigBridge.ready(); previousConfig = ConfigBridge.current();
        field(ConfigBridge.class, "ready").setBoolean(null, false);
        module = new FocusStylusModule();
        module.attachFramework((io.github.libxposed.api.XposedInterface) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{io.github.libxposed.api.XposedInterface.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("log")) return null;
                    throw new UnsupportedOperationException(method.getName());
                }), () -> { });
        runtime = new ChargeLimitRuntime(module, new Handler(Looper.getMainLooper()), control);
        storage = new ChargeLimitStore(new SystemDataFiles(files.newFolder()));
        set("storage", storage); set("context", context); set("config", config(true));
        set("tabletLevel", 80); set("tabletHealth", BatteryManager.BATTERY_HEALTH_GOOD);
        policy = (ChargeLimitPolicy) field(ChargeLimitRuntime.class, "policy").get(runtime);
        control.capture(hardware);
        start = System.currentTimeMillis() - 1_000;
    }
    @After public void cleanup() throws Exception {
        assertTrue(module.session().close(2_000));
        field(ConfigBridge.class, "ready").setBoolean(null, previousReady);
        field(ConfigBridge.class, "current").set(null, previousConfig);
    }
    private Intent identity(String source, long time) {
        return new Intent(Protocol.RELAY).putExtra("source", source).putExtra("pen_connected", 1)
                .putExtra("pen_connection_time", start).putExtra("pen_session_time", start)
                .putExtra("pen_session_id", "pen").putExtra("event_time", time).putExtra("time", time);
    }
    private void sample(int level, boolean charging, long offset) throws Exception {
        long time = start + offset;
        accept(identity(Protocol.BATTERY, time).putExtra("battery", level).putExtra("state", charging ? 4 : 2)
                .putExtra("charge_time", time).putExtra("attached", 1).putExtra("attachment_time", start + 1));
    }
    private Notification notification() {
        return Shadows.shadowOf(context.getSystemService(NotificationManager.class)).getNotification(ChargeNotifications.CHANNEL, ChargeNotifications.ID);
    }
    private void click(int index) throws Exception {
        assertNotNull(notification()); notification().actions[index].actionIntent.send();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void ageCurrentSample(long age) throws Exception {
        long now = System.currentTimeMillis(); start = now - age - 1_000;
        var link = (cc.star0.xposed.hyperstylus.model.pen.ChargeLinkState) field(ChargeLimitRuntime.class, "link").get(runtime);
        Bundle saved = link.saveState(); saved.putLong("sessionAt", start); link.restoreState(saved);
        var pen = (cc.star0.xposed.hyperstylus.model.pen.BatteryState) field(ChargeLimitRuntime.class, "pen").get(runtime);
        pen.updatedAt = now - age;
    }

    @Test public void upperLimitOffersFullAndCanResumeWithTheUnchangedCurrentSessionSample() throws Exception {
        set("tabletLevel", 41); sample(80, false, 100); ageCurrentSample(240_000); runtime.evaluate();
        assertEquals("本次充满", notification().actions[0].title);
        assertFalse(notification().extras.getString(Notification.EXTRA_TEXT).contains("等待当前手写笔"));
        click(0); assertEquals(List.of(false, true), hardware.commands);
        assertEquals(OneShot.FULL, policy.oneShot());
    }

    @Test public void olderKnownBatteryDoesNotMakeTheChargingFullButtonOrHintUnavailable() throws Exception {
        sample(45, true, 100); ageCurrentSample(240_000); runtime.evaluate();
        assertFalse(notification().extras.getString(Notification.EXTRA_TEXT).contains("等待当前手写笔"));
        click(1); assertEquals(OneShot.FULL, policy.oneShot());
        assertEquals("取消本次充满", notification().actions[1].title);
    }

    @Test public void startResumesTheModulesStopAndEndsAtConfiguredUpperLimit() throws Exception {
        sample(65, false, 100); assertEquals(List.of(false), hardware.commands); assertTrue(policy.ownsStop);
        click(0); assertEquals(List.of(false, true), hardware.commands); assertFalse(policy.ownsStop);
        assertEquals(OneShot.START, policy.oneShot());
        sample(79, true, 200); assertEquals(2, hardware.commands.size());
        sample(80, true, 300); assertEquals(List.of(false, true, false), hardware.commands);
        assertEquals(3, storage.read() & 3);
    }

    @Test public void stopWorksWithoutLimitsAndHallDetachReleasesItsOwnership() throws Exception {
        set("config", config(false)); sample(35, true, 100);
        click(0); assertEquals(List.of(false), hardware.commands); assertEquals(OneShot.STOP, policy.oneShot());
        PendingIntent stale = notification().actions[0].actionIntent;
        accept(identity("hall", start + 200).putExtra("attached", 0).putExtra("attachment_time", start + 200));
        assertEquals(List.of(false, true), hardware.commands); assertEquals(OneShot.NONE, policy.oneShot());
        assertNull(notification()); assertThrows(PendingIntent.CanceledException.class, stale::send);
    }

    @Test public void fullFromChargingNotificationOverridesOnlyThisCycle() throws Exception {
        sample(45, true, 100); click(1);
        sample(90, true, 200); assertTrue(hardware.commands.isEmpty());
        sample(100, true, 300); assertEquals(List.of(false), hardware.commands);
        assertEquals(80, ((EnhanceConfig) field(ChargeLimitRuntime.class, "config").get(runtime)).chargeStop);
    }

    @Test public void systemOffAndTabletProtectionBothBlockStart() throws Exception {
        sample(65, false, 100);
        runtime.nativeCompleted(control.nativeRequest(hardware, false), 0);
        Shadows.shadowOf(Looper.getMainLooper()).idle(); click(0);
        assertEquals(List.of(false), hardware.commands); assertFalse(policy.ownsStop);
        assertTrue(notification().extras.getString(Notification.EXTRA_TEXT).contains("系统保护"));
    }

    @Test public void lowTabletBadHealthAndDisabledReverseChargingNeverRestore() throws Exception {
        sample(65, false, 100);
        set("tabletLevel", 10); click(0); assertEquals(List.of(false), hardware.commands);
        set("tabletLevel", 80); set("tabletHealth", BatteryManager.BATTERY_HEALTH_OVERHEAT);
        click(0); assertEquals(List.of(false), hardware.commands);
        set("tabletHealth", BatteryManager.BATTERY_HEALTH_GOOD);
        Settings.Global.putInt(context.getContentResolver(), "OPEN_WIRELESS_CHARGE", 0);
        click(0); assertEquals(List.of(false), hardware.commands);
    }

    @Test public void disablingBothNotificationsRestoresManualStopAndRejectsQueuedOldAction() throws Exception {
        set("config", config(false)); sample(35, true, 100); click(0);
        Intent queued = new Intent(Shadows.shadowOf(notification().actions[1].actionIntent).getSavedIntent());
        set("config", EnhanceConfig.defaults()); runtime.evaluate();
        assertEquals(List.of(false, true), hardware.commands); assertNull(notification());
        context.sendBroadcast(queued); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(OneShot.NONE, policy.oneShot()); assertEquals(2, hardware.commands.size());
    }

    @Test public void newlyPublishedSwitchStateWinsOverAnAlreadyQueuedButton() throws Exception {
        set("config", config(false)); sample(35, true, 100);
        Bundle newer = EnhanceConfig.defaults().toBundle(); newer.putLong("revision", 1);
        field(ConfigBridge.class, "current").set(null, new EnhanceConfig(newer));
        field(ConfigBridge.class, "ready").setBoolean(null, true);
        click(0); assertTrue(hardware.commands.isEmpty()); assertNull(notification());
    }

    @Test public void failedStopDoesNotClaimOwnershipAndDisconnectEndsOverride() throws Exception {
        set("config", config(false)); hardware.result = 1; sample(35, true, 100); click(0);
        assertFalse(policy.ownsStop); assertEquals(0, storage.read() & 2);
        accept(identity("gatt", start + 200).putExtra("pen_connected", 0).putExtra("pen_connection_time", start + 200));
        assertEquals(OneShot.NONE, policy.oneShot()); assertNull(notification());
        assertEquals(List.of(false), hardware.commands);
    }

    @Test public void previousSessionBatteryCannotStartEvenAfterTheCurrentSessionWasOnceReady() throws Exception {
        sample(65, false, 100);
        var pen = (cc.star0.xposed.hyperstylus.model.pen.BatteryState) field(ChargeLimitRuntime.class, "pen").get(runtime);
        pen.updatedAt = System.currentTimeMillis() - 60_001;
        click(0);
        assertEquals(List.of(false), hardware.commands); assertEquals(OneShot.NONE, policy.oneShot());
        assertTrue(notification().extras.getString(Notification.EXTRA_TEXT).contains("有效电量"));
    }

    @Test public void oldSessionAndOutOfOrderHallCannotCancelTheCurrentFullCycle() throws Exception {
        sample(45, true, 100); click(1);
        accept(identity("hall", start + 200).putExtra("pen_session_time", start - 100)
                .putExtra("pen_session_id", "old-pen").putExtra("attached", 0).putExtra("attachment_time", start + 200));
        assertEquals(OneShot.FULL, policy.oneShot()); assertNotNull(notification());
        accept(identity("hall", start + 50).putExtra("attached", 0).putExtra("attachment_time", start + 50));
        assertEquals(OneShot.FULL, policy.oneShot()); assertNotNull(notification());
        assertTrue(hardware.commands.isEmpty());
    }

    @Test public void lowTabletHintKeepsTheExistingGateAndClearsWhenBatteryRecovers() throws Exception {
        set("tabletLevel", 22); sample(65, false, 100);
        click(0);
        assertEquals(List.of(false), hardware.commands);
        assertTrue(notification().extras.getString(Notification.EXTRA_TEXT).contains("设备电量 22%，低于 30%"));
        set("tabletLevel", 30); runtime.evaluate();
        assertEquals(List.of(false, true), hardware.commands);
        assertFalse(notification().extras.getString(Notification.EXTRA_TEXT).contains("低于"));
    }

    @Test public void lowTabletHintFollowsTheSystemSettingAndRetainsOtherProtectionMessages() throws Exception {
        set("tabletLevel", 35);
        Settings.Global.putInt(context.getContentResolver(), "wireless_reverse_charging", 40);
        sample(65, false, 100); click(0);
        assertEquals(List.of(false), hardware.commands);
        assertTrue(notification().extras.getString(Notification.EXTRA_TEXT).contains("设备电量 35%，低于 40%"));
        Settings.Global.putInt(context.getContentResolver(), "wireless_reverse_charging", 30);
        Settings.Global.putInt(context.getContentResolver(), "OPEN_WIRELESS_CHARGE", 0);
        runtime.evaluate();
        assertEquals(List.of(false), hardware.commands);
        assertFalse(notification().extras.getString(Notification.EXTRA_TEXT).contains("低于"));
        assertTrue(notification().extras.getString(Notification.EXTRA_TEXT).contains("系统保护"));
    }

    @Test public void cancellingFullFromTheNotificationRestoresNormalChargingAndButtonLabel() throws Exception {
        sample(45, true, 100); click(1);
        assertEquals("取消本次充满", notification().actions[1].title);
        sample(65, true, 200); click(1);
        assertEquals(OneShot.NONE, policy.oneShot()); assertTrue(hardware.commands.isEmpty());
        assertEquals("本次充满", notification().actions[1].title);
        sample(80, true, 300); assertEquals(List.of(false), hardware.commands);
    }

    @Test public void cancellingFullIsAllowedWithAnOlderSampleAndDoesNotBypassTabletProtection() throws Exception {
        sample(45, true, 100); click(1);
        var pen = (cc.star0.xposed.hyperstylus.model.pen.BatteryState) field(ChargeLimitRuntime.class, "pen").get(runtime);
        pen.updatedAt = System.currentTimeMillis() - 60_001;
        set("tabletLevel", 22); pen.charge(2, System.currentTimeMillis()); runtime.evaluate();
        click(0);
        assertEquals(OneShot.NONE, policy.oneShot()); assertTrue(hardware.commands.isEmpty());
        assertTrue(notification().extras.getString(Notification.EXTRA_TEXT).contains("低于 30%"));
        assertEquals(1, notification().actions.length);
    }

    @Test public void explicitStartBelowUpperLimitAcceptsAnUnchangedCurrentSessionSample() throws Exception {
        sample(65, false, 100); ageCurrentSample(240_000); runtime.evaluate();
        assertEquals("本次开始充电", notification().actions[0].title);
        assertFalse(notification().extras.getString(Notification.EXTRA_TEXT).contains("等待当前手写笔"));
        click(0); assertEquals(List.of(false, true), hardware.commands);
        assertEquals(OneShot.START, policy.oneShot());
    }

    @Test public void fullAtUpperLimitStillHonorsTabletThresholdAndCanBeCancelledWithoutStarting() throws Exception {
        set("tabletLevel", 22); sample(80, false, 100); ageCurrentSample(240_000); runtime.evaluate();
        assertEquals("本次充满", notification().actions[0].title); click(0);
        assertEquals(List.of(false), hardware.commands); assertEquals(OneShot.FULL, policy.oneShot());
        assertTrue(notification().extras.getString(Notification.EXTRA_TEXT).contains("低于 30%"));
        assertEquals("取消本次充满", notification().actions[0].title); click(0);
        assertEquals(OneShot.NONE, policy.oneShot()); assertEquals(List.of(false), hardware.commands);
        assertEquals("本次充满", notification().actions[0].title);
    }

    @Test public void expiredFutureAndUnknownSamplesStillCannotStartOrClaimReadiness() throws Exception {
        sample(65, false, 100);
        var pen = (cc.star0.xposed.hyperstylus.model.pen.BatteryState) field(ChargeLimitRuntime.class, "pen").get(runtime);
        ageCurrentSample(cc.star0.xposed.hyperstylus.model.pen.BatteryState.MAX_AGE_MS);
        runtime.evaluate(); click(0); assertEquals(List.of(false), hardware.commands);
        pen.updatedAt = System.currentTimeMillis() + 1_000;
        runtime.evaluate(); click(0); assertEquals(List.of(false), hardware.commands);
        pen.updatedAt = System.currentTimeMillis(); pen.level = -1;
        runtime.evaluate(); click(0); assertEquals(List.of(false), hardware.commands);
        assertTrue(notification().extras.getString(Notification.EXTRA_TEXT).contains("等待当前手写笔"));
        assertEquals(OneShot.NONE, policy.oneShot());
    }

    @Test public void fullAboveTheCutoffKeepsChargingUntilStopAndRejectsTheOldCancelButton() throws Exception {
        sample(80, false, 100); ageCurrentSample(240_000); runtime.evaluate(); click(0);
        Intent oldCancel = new Intent(Shadows.shadowOf(notification().actions[0].actionIntent).getSavedIntent());
        long now = System.currentTimeMillis();
        accept(identity(cc.star0.xposed.hyperstylus.common.Protocol.BATTERY, now)
                .putExtra("battery", 90).putExtra("state", 4).putExtra("charge_time", now));
        assertEquals(List.of(false, true), hardware.commands); assertEquals(OneShot.FULL, policy.oneShot());
        assertEquals(1, notification().actions.length); assertEquals("停止充电", notification().actions[0].title);
        context.sendBroadcast(oldCancel); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(OneShot.FULL, policy.oneShot()); assertEquals(List.of(false, true), hardware.commands);
        ageCurrentSample(240_000); runtime.evaluate(); click(0);
        assertEquals(List.of(false, true, false), hardware.commands); assertEquals(OneShot.STOP, policy.oneShot());
    }
}
