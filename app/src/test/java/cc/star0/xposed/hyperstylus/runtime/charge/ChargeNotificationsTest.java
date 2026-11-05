package cc.star0.xposed.hyperstylus.runtime.charge;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import cc.star0.xposed.hyperstylus.common.Protocol;
import cc.star0.xposed.hyperstylus.config.EnhanceConfig;
import cc.star0.xposed.hyperstylus.model.pen.BatteryState;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static cc.star0.xposed.hyperstylus.runtime.charge.ChargeLimitPolicy.OneShot;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ChargeNotificationsTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private final List<OneShot> actions = new ArrayList<>();
    private ChargeNotifications notifications;
    private final BatteryState pen = new BatteryState();
    private final ChargeLimitPolicy policy = new ChargeLimitPolicy();
    private boolean active = true;

    private static EnhanceConfig config(boolean charging, boolean attached) {
        Bundle data = EnhanceConfig.defaults().toBundle(); data.putBoolean("charging_notification_enabled", charging);
        data.putBoolean("attached_notification_enabled", attached); return new EnhanceConfig(data);
    }
    @Before public void setup() {
        notifications = new ChargeNotifications(context, context, new Handler(Looper.getMainLooper()),
                work -> { if (active) work.run(); }, actions::add);
        pen.attachment(1, 900); pen.battery(60, 4, 1_000);
    }
    @After public void cleanup() throws Throwable { notifications.close(); }
    private void update(EnhanceConfig config) { notifications.update(config, pen, true, true, policy, true, 80, 30, 2_000); }
    private Notification notification() {
        return Shadows.shadowOf(context.getSystemService(NotificationManager.class)).getNotification(ChargeNotifications.CHANNEL, ChargeNotifications.ID);
    }
    private void send(Intent event) { context.sendBroadcast(event); Shadows.shadowOf(Looper.getMainLooper()).idle(); }

    @Test public void switchesAreIndependentAndChargingContainsBothRequestedActions() {
        update(config(false, true)); assertNull(notification());
        update(config(true, false));
        Notification shown = notification(); assertNotNull(shown);
        assertEquals("手写笔正在充电", shown.extras.getString(Notification.EXTRA_TITLE));
        assertEquals("停止充电", shown.actions[0].title); assertEquals("本次充满", shown.actions[1].title);
        assertEquals(2, shown.actions.length);
        assertEquals(Protocol.MODULE, shown.getSmallIcon().getResPackage());
        assertTrue(shown.actions[0].actionIntent.isImmutable());
        pen.charge(2, 1_500); update(config(true, false)); assertNull(notification());
        update(config(false, true)); assertEquals("本次开始充电", notification().actions[0].title);
        assertEquals(1, notification().actions.length);
    }

    @Test public void actualPendingIntentsDispatchStopFullAndStart() throws Exception {
        for (int index = 0; index < 2; index++) {
            update(config(true, true)); notification().actions[index].actionIntent.send();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
        pen.charge(2, 1_500); update(config(true, true)); notification().actions[0].actionIntent.send();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of(OneShot.STOP, OneShot.FULL, OneShot.START), actions);
    }

    @Test public void replayedAndForgedOperationsCannotActOnTheNextNotification() {
        update(config(true, true));
        Intent valid = new Intent(Shadows.shadowOf(notification().actions[0].actionIntent).getSavedIntent());
        send(new Intent(valid).putExtra("token", "forged"));
        send(new Intent(valid).putExtra("operation", "START"));
        send(new Intent(valid).setAction("unrelated"));
        assertTrue(actions.isEmpty());
        send(valid); assertEquals(List.of(OneShot.STOP), actions);
        update(config(true, true)); send(valid); assertEquals(1, actions.size());
    }

    @Test public void detachAndStateChangesInvalidateOutstandingButtons() {
        update(config(true, true)); PendingIntent old = notification().actions[0].actionIntent;
        pen.charge(2, 1_400); update(config(true, true));
        assertThrows(PendingIntent.CanceledException.class, old::send);
        old = notification().actions[0].actionIntent;
        Intent stale = new Intent(Shadows.shadowOf(old).getSavedIntent());
        pen.attachment(0, 1_500); update(config(true, true)); assertNull(notification());
        assertThrows(PendingIntent.CanceledException.class, old::send);
        pen.attachment(1, 1_600); pen.charge(2, 1_700); update(config(true, true));
        send(stale); assertTrue(actions.isEmpty());
    }

    @Test public void disablingSwitchCancelsNotificationAndPendingIntents() {
        update(config(true, true)); PendingIntent old = notification().actions[0].actionIntent;
        update(config(false, true)); assertNull(notification());
        assertThrows(PendingIntent.CanceledException.class, old::send);
    }

    @Test public void retirementSuppressesDeliveryAndCloseRemovesNotificationAndReceiver() throws Throwable {
        update(config(true, true)); PendingIntent old = notification().actions[0].actionIntent;
        Intent stale = new Intent(Shadows.shadowOf(old).getSavedIntent());
        active = false; send(stale); assertTrue(actions.isEmpty());
        notifications.close(); assertNull(notification());
        assertThrows(PendingIntent.CanceledException.class, old::send);
        active = true; send(stale); assertTrue(actions.isEmpty());
    }

    @Test public void unknownDetachedDisconnectedAndExpiredStatesDoNotGuessIdleCharging() {
        BatteryState unknown = new BatteryState(); unknown.battery(60, 2, 1_000);
        assertEquals(ChargeNotifications.Kind.NONE, ChargeNotifications.kind(config(true, true), unknown, true, 2_000));
        pen.attachment(0, 1_100); pen.charge(4, 1_200);
        assertEquals(ChargeNotifications.Kind.NONE, ChargeNotifications.kind(config(true, true), pen, true, 2_000));
        pen.attachment(1, 1_300); pen.charge(2, 1_400);
        assertEquals(ChargeNotifications.Kind.NONE, ChargeNotifications.kind(config(true, true), pen, false, 2_000));
        assertEquals(ChargeNotifications.Kind.NONE, ChargeNotifications.kind(config(true, true), pen, true, BatteryState.MAX_AGE_MS + 2_000));
        assertEquals(ChargeNotifications.Kind.NONE, ChargeNotifications.kind(config(true, true), pen, true, 500));
    }

    @Test public void attachingRequiresNewChargeStateAndProtectionIsExplained() {
        pen.attachment(0, 1_100); pen.attachment(1, 1_500); update(config(true, true)); assertNull(notification());
        pen.charge(2, 1_600);
        notifications.update(config(true, true), pen, true, true, policy, false, 80, 30, 2_000);
        assertTrue(notification().extras.getString(Notification.EXTRA_TEXT).contains("系统保护"));
    }

    @Test public void repeatedPositiveHallReportsKeepTheCurrentIdleNotificationAndButtons() {
        pen.charge(2, 1_000); update(config(true, true));
        PendingIntent start = notification().actions[0].actionIntent;
        pen.attachment(1, 1_500); update(config(true, true));
        assertNotNull(notification()); assertEquals(start, notification().actions[0].actionIntent);
        assertEquals(900, pen.attachmentChangedAt); assertEquals(1_500, pen.lastAttachmentAt);
        BatteryState copy = new BatteryState(); copy.restoreState(pen.saveState());
        assertEquals(900, copy.attachmentChangedAt);
        assertEquals(ChargeNotifications.Kind.ATTACHED, ChargeNotifications.kind(config(true, true), copy, true, 2_000));
    }

    @Test public void lowTabletHintTracksBatteryAndClearsAtTheExactThreshold() {
        pen.charge(2, 1_000);
        notifications.update(config(true, true), pen, true, true, policy, false, 22, 30, 2_000);
        assertTrue(notification().extras.getString(Notification.EXTRA_TEXT).contains("设备电量 22%，低于 30%"));
        PendingIntent action = notification().actions[0].actionIntent;
        notifications.update(config(true, true), pen, true, true, policy, false, 29, 30, 2_000);
        assertTrue(notification().extras.getString(Notification.EXTRA_TEXT).contains("设备电量 29%，低于 30%"));
        assertEquals(action, notification().actions[0].actionIntent);
        notifications.update(config(true, true), pen, true, true, policy, true, 30, 30, 2_000);
        assertFalse(notification().extras.getString(Notification.EXTRA_TEXT).contains("低于"));
        assertEquals(action, notification().actions[0].actionIntent);
    }

    @Test public void lowTabletHintUsesTheActualThresholdAndPrecedesWaitingForPenBattery() {
        pen.charge(2, 1_000);
        notifications.update(config(true, true), pen, true, false, policy, false, 35, 40, 2_000);
        assertTrue(notification().extras.getString(Notification.EXTRA_TEXT).contains("设备电量 35%，低于 40%"));
        notifications.update(config(true, true), pen, true, false, policy, true, 40, 40, 2_000);
        assertTrue(notification().extras.getString(Notification.EXTRA_TEXT).contains("有效电量"));
        assertFalse(notification().extras.getString(Notification.EXTRA_TEXT).contains("低于"));
    }

    @Test public void unknownDeviceBatteryAndActiveChargingDoNotClaimTheLowThresholdWasHit() {
        pen.charge(2, 1_000);
        notifications.update(config(true, true), pen, true, true, policy, false, -1, 30, 2_000);
        assertTrue(notification().extras.getString(Notification.EXTRA_TEXT).contains("系统保护"));
        assertFalse(notification().extras.getString(Notification.EXTRA_TEXT).contains("低于"));
        pen.charge(4, 1_500);
        notifications.update(config(true, true), pen, true, true, policy, false, 22, 30, 2_000);
        assertEquals("手写笔正在充电", notification().extras.getString(Notification.EXTRA_TITLE));
        assertFalse(notification().extras.getString(Notification.EXTRA_TEXT).contains("暂时无法开始充电"));
    }

    @Test public void fullButtonBecomesCancelAndOldButtonsCannotOperateTheNewMode() throws Exception {
        update(config(true, true)); PendingIntent full = notification().actions[1].actionIntent;
        policy.request(OneShot.FULL); update(config(true, true));
        assertEquals("取消本次充满", notification().actions[1].title);
        assertThrows(PendingIntent.CanceledException.class, full::send);
        PendingIntent cancel = notification().actions[1].actionIntent;
        Intent replay = new Intent(Shadows.shadowOf(cancel).getSavedIntent());
        cancel.send(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of(OneShot.CANCEL_FULL), actions);
        policy.request(OneShot.CANCEL_FULL); update(config(true, true));
        assertEquals("本次充满", notification().actions[1].title);
        send(replay); assertEquals(1, actions.size());
    }

    @Test public void cancelRemainsAvailableWhenFullChargingIsPausedByTheTabletThreshold() throws Exception {
        policy.request(OneShot.FULL); pen.charge(2, 1_000);
        notifications.update(config(true, true), pen, true, false, policy, false, 22, 30, 2_000);
        assertTrue(notification().extras.getString(Notification.EXTRA_TEXT).contains("低于 30%"));
        assertEquals(1, notification().actions.length);
        assertEquals("取消本次充满", notification().actions[0].title);
        notification().actions[0].actionIntent.send(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of(OneShot.CANCEL_FULL), actions);
    }

    @Test public void idleActionSwitchesAtTheConfiguredUpperLimitAndRevokesTheOldStartButton() throws Exception {
        Bundle values = config(true, true).toBundle(); values.putBoolean("charge_limit_enabled", true);
        EnhanceConfig limits = new EnhanceConfig(values);
        pen.battery(79, 2, 1_000); update(limits);
        PendingIntent old = notification().actions[0].actionIntent;
        assertEquals("本次开始充电", notification().actions[0].title);
        pen.battery(80, 2, 1_100); update(limits);
        assertEquals("本次充满", notification().actions[0].title);
        assertThrows(PendingIntent.CanceledException.class, old::send);
        notification().actions[0].actionIntent.send(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of(OneShot.FULL), actions);
        policy.request(OneShot.FULL); update(limits);
        assertEquals("取消本次充满", notification().actions[0].title);
        assertTrue(notification().extras.getString(Notification.EXTRA_TEXT).contains("本次充至 100%"));
        policy.request(OneShot.CANCEL_FULL); update(limits);
        assertEquals("本次充满", notification().actions[0].title);
    }

    @Test public void editingOrDisablingTheLimitRecomputesTheActualIdleAction() {
        Bundle values = config(true, true).toBundle(); values.putBoolean("charge_limit_enabled", true);
        pen.battery(85, 2, 1_000); update(new EnhanceConfig(values));
        assertEquals("本次充满", notification().actions[0].title);
        values.putInt("charge_stop", 90); update(new EnhanceConfig(values));
        assertEquals("本次开始充电", notification().actions[0].title);
        values.putBoolean("charge_limit_enabled", false); values.putInt("charge_stop", 80);
        update(new EnhanceConfig(values)); assertEquals("本次开始充电", notification().actions[0].title);
    }

    @Test public void reachingTheCutoffWhileChargingLeavesOnlyStopAndRevokesCancel() throws Exception {
        Bundle values = config(true, true).toBundle(); values.putBoolean("charge_limit_enabled", true);
        EnhanceConfig limits = new EnhanceConfig(values); policy.request(OneShot.FULL);
        pen.battery(79, 4, 1_000); update(limits);
        assertEquals("取消本次充满", notification().actions[1].title);
        PendingIntent cancel = notification().actions[1].actionIntent;
        Intent replay = new Intent(Shadows.shadowOf(cancel).getSavedIntent());
        for (int level : new int[]{80, 81, 90, 100}) {
            pen.battery(level, 4, 1_000 + level); update(limits);
            assertEquals(1, notification().actions.length);
            assertEquals("停止充电", notification().actions[0].title);
        }
        assertThrows(PendingIntent.CanceledException.class, cancel::send);
        send(replay); assertTrue(actions.isEmpty());
        notification().actions[0].actionIntent.send(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of(OneShot.STOP), actions);
    }

    @Test public void pausedChargingOrDisabledLimitsRetainTheCancelChoice() {
        Bundle values = config(true, true).toBundle(); values.putBoolean("charge_limit_enabled", true);
        policy.request(OneShot.FULL); pen.battery(90, 4, 1_000); update(new EnhanceConfig(values));
        assertEquals(1, notification().actions.length);
        pen.charge(2, 1_200); update(new EnhanceConfig(values));
        assertEquals("取消本次充满", notification().actions[0].title);
        values.putBoolean("charge_limit_enabled", false); pen.charge(4, 1_400); update(new EnhanceConfig(values));
        assertEquals(2, notification().actions.length);
        assertEquals("停止充电", notification().actions[0].title);
        assertEquals("取消本次充满", notification().actions[1].title);
    }
}
