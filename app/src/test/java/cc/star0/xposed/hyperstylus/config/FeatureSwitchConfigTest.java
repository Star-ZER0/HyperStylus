package cc.star0.xposed.hyperstylus.config;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** A feature gate suppresses dispatch, rather than deleting the configuration it protects. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class FeatureSwitchConfigTest {
    private static final String APP = "com.example.reader";
    private SharedPreferences storage;

    @Before public void setUp() {
        storage = RuntimeEnvironment.getApplication().createDeviceProtectedStorageContext()
                .getSharedPreferences("feature_switch_config_test", Context.MODE_PRIVATE);
        storage.edit().clear().commit();
    }

    @Test public void missingFeatureSwitchesPreserveThePreviouslyEnabledBehavior() {
        EnhanceConfig config = new EnhanceConfig(new Bundle());
        for (int button = 0; button < 3; button++) assertTrue(config.keyEnabled(button));
        assertTrue(config.anyKeyEnabled());
        assertTrue(config.signalEnabled);
        assertArrayEquals(new String[]{"pinch", "double_tap"}, config.actionsFor(null, 0));
    }

    @Test public void wrongPersistedTypesDoNotMasqueradeAsDisabledBooleanSwitches() {
        Bundle values = EnhanceConfig.defaults().toBundle();
        values.putString(EnhanceConfig.KEY_ENABLED_KEYS[0], "false");
        values.putInt(EnhanceConfig.KEY_ENABLED_KEYS[1], 0);
        values.putLong(EnhanceConfig.KEY_ENABLED_KEYS[2], 0L);
        values.putString("signal_enabled", "false");
        EnhanceConfig config = new EnhanceConfig(values);
        for (int button = 0; button < 3; button++) assertTrue(config.keyEnabled(button));
        assertTrue(config.signalEnabled);

        storage.edit().putString(EnhanceConfig.KEY_ENABLED_KEYS[0], "false")
                .putInt(EnhanceConfig.KEY_ENABLED_KEYS[1], 0)
                .putLong(EnhanceConfig.KEY_ENABLED_KEYS[2], 0L)
                .putString("signal_enabled", "false").commit();
        EnhanceConfig restored = EnhanceConfig.read(storage);
        for (int button = 0; button < 3; button++) assertTrue(restored.keyEnabled(button));
        assertTrue(restored.signalEnabled);
    }

    @Test public void allFourSwitchesRoundTripAsBooleansWithoutChangingOtherSettings() {
        for (boolean inverted : new boolean[]{false, true}) {
            Bundle values = EnhanceConfig.defaults().toBundle();
            for (int button = 0; button < 3; button++)
                values.putBoolean(EnhanceConfig.KEY_ENABLED_KEYS[button], (button == 1) != inverted);
            values.putBoolean("signal_enabled", inverted);
            values.putInt("signal_one", -85);
            values.putInt("signal_two", -55);
            EnhanceConfig saved = new EnhanceConfig(values);
            saved.write(storage);
            EnhanceConfig restored = EnhanceConfig.read(storage);
            for (int button = 0; button < 3; button++) {
                boolean expected = (button == 1) != inverted;
                assertEquals(expected, restored.keyEnabled(button));
                assertEquals(expected, storage.getBoolean(EnhanceConfig.KEY_ENABLED_KEYS[button], !expected));
            }
            assertEquals(inverted, restored.signalEnabled);
            assertEquals(-85, restored.signalOne);
            assertEquals(-55, restored.signalTwo);
            for (int binding = 0; binding < ActionConfig.KEYS.length; binding++)
                assertEquals(ActionConfig.DEFAULTS[binding], restored.action(binding));
            Bundle exported = restored.toBundle();
            exported.putBoolean("signal_enabled", !inverted);
            assertEquals(inverted, restored.signalEnabled);
        }
    }

    @Test public void aDisabledButtonPreservesGlobalAndApplicationActionsAndTargets() {
        Bundle values = EnhanceConfig.defaults().toBundle();
        values.putString(ActionConfig.KEYS[0], "home");
        values.putString(ActionConfig.KEYS[1], "back");
        values.putString(ActionConfig.FULL_KEYS[0], "keyboard:66");
        EnhanceConfig configured = new EnhanceConfig(values);
        configured = new EnhanceConfig(configured.withAppEnabled(APP, true));
        configured = new EnhanceConfig(configured.withAppAction(APP, 0, "volume_up"));
        configured = new EnhanceConfig(configured.withAppAction(APP, 1, "volume_down"));
        configured = new EnhanceConfig(configured.withAppFullAction(APP, 0, "keyboard:59"));
        String profile = configured.toBundle().getString(EnhanceConfig.APP_MAPPINGS);
        Bundle disabledValues = configured.toBundle();
        disabledValues.putBoolean(EnhanceConfig.KEY_ENABLED_KEYS[0], false);
        EnhanceConfig disabled = new EnhanceConfig(disabledValues);
        assertArrayEquals(new String[]{ActionConfig.DEFAULT, ActionConfig.DEFAULT}, disabled.actionsFor(APP, 0));
        assertArrayEquals(new String[]{ActionConfig.DEFAULT, ActionConfig.DEFAULT}, disabled.actionsFor(null, 0));
        assertEquals(0, disabled.fullKeyCodeFor(APP, 0));
        assertEquals(0, disabled.fullKeyCodeFor(null, 0));
        assertEquals("home", disabled.action(0));
        assertEquals("back", disabled.action(1));
        assertEquals("keyboard:66", disabled.fullAction(0));
        assertEquals("volume_up", disabled.appAction(APP, 0));
        assertEquals("volume_down", disabled.appAction(APP, 1));
        assertEquals("keyboard:59", disabled.appFullAction(APP, 0));
        assertEquals(Set.of(APP), disabled.appPackages());
        assertTrue(disabled.appEnabled(APP));
        assertEquals(profile, disabled.toBundle().getString(EnhanceConfig.APP_MAPPINGS));
        assertTrue(configured.keyEnabled(0));

        disabled.write(storage);
        EnhanceConfig restored = EnhanceConfig.read(storage);
        assertFalse(restored.keyEnabled(0));
        assertEquals(profile, restored.toBundle().getString(EnhanceConfig.APP_MAPPINGS));
        Bundle enabledValues = restored.toBundle();
        enabledValues.putBoolean(EnhanceConfig.KEY_ENABLED_KEYS[0], true);
        EnhanceConfig enabled = new EnhanceConfig(enabledValues);
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT, enabled.fullKeyCodeFor(APP, 0));
        assertEquals(KeyEvent.KEYCODE_ENTER, enabled.fullKeyCodeFor("com.example.other", 0));
        assertArrayEquals(new String[]{"volume_up", "volume_down"}, enabled.actionsFor(APP, 0));
        assertArrayEquals(new String[]{"home", "back"}, enabled.actionsFor(null, 0));
    }

    @Test public void eachButtonGateIsIndependentOfTheOtherStoredMappings() {
        for (int disabledButton = 0; disabledButton < 3; disabledButton++) {
            Bundle values = EnhanceConfig.defaults().toBundle();
            int[] targets = {KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN};
            for (int button = 0; button < 3; button++)
                values.putString(ActionConfig.FULL_KEYS[button], ActionConfig.customKey(ActionConfig.KEYBOARD, targets[button]));
            values.putBoolean(EnhanceConfig.KEY_ENABLED_KEYS[disabledButton], false);
            EnhanceConfig config = new EnhanceConfig(values);
            for (int button = 0; button < 3; button++) {
                assertEquals(button == disabledButton ? 0 : targets[button], config.fullKeyCodeFor(null, button));
                assertEquals(ActionConfig.customKey(ActionConfig.KEYBOARD, targets[button]), config.fullAction(button));
            }
            assertTrue(config.anyKeyEnabled());
        }
    }

    @Test public void buttonSwitchIndicesHaveTheSameBoundsAsMappings() {
        EnhanceConfig config = EnhanceConfig.defaults();
        assertThrows(IllegalArgumentException.class, () -> config.keyEnabled(-1));
        assertThrows(IllegalArgumentException.class, () -> config.keyEnabled(3));
    }

    @Test public void notificationSwitchesDefaultOffAndPersistIndependently() {
        assertFalse(EnhanceConfig.defaults().chargeNotificationsEnabled());
        for (boolean charging : new boolean[]{false, true}) for (boolean attached : new boolean[]{false, true}) {
            Bundle data = EnhanceConfig.defaults().toBundle();
            data.putBoolean("charging_notification_enabled", charging);
            data.putBoolean("attached_notification_enabled", attached);
            new EnhanceConfig(data).write(storage);
            EnhanceConfig restored = EnhanceConfig.read(storage);
            assertEquals(charging, restored.chargingNotificationEnabled);
            assertEquals(attached, restored.attachedNotificationEnabled);
            assertEquals(charging || attached, restored.chargeNotificationsEnabled());
            assertFalse(restored.chargeLimitEnabled); assertEquals(80, restored.chargeStop);
        }
    }

    @Test public void notificationFlagsRejectNonBooleanPersistedValues() {
        storage.edit().putString("charging_notification_enabled", "true")
                .putInt("attached_notification_enabled", 1).commit();
        assertFalse(EnhanceConfig.read(storage).chargeNotificationsEnabled());
        Bundle data = EnhanceConfig.defaults().toBundle();
        data.putString("charging_notification_enabled", "true");
        data.putInt("attached_notification_enabled", 1);
        assertFalse(new EnhanceConfig(data).chargeNotificationsEnabled());
    }
}
