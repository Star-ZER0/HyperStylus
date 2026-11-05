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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CurrentConfigValidationTest {
    private SharedPreferences storage;

    @Before public void setUp() {
        storage = RuntimeEnvironment.getApplication().createDeviceProtectedStorageContext()
                .getSharedPreferences("current_config_validation_test", Context.MODE_PRIVATE);
        storage.edit().clear().commit();
    }

    @Test public void emptyConfigurationUsesCurrentChargeAndSignalDefaults() {
        EnhanceConfig value = EnhanceConfig.read(storage);
        assertEquals(50, value.chargeStart); assertEquals(80, value.chargeStop);
        assertEquals(-80, value.signalOne); assertEquals(-60, value.signalTwo);
        assertTrue(value.batteryEnabled); assertFalse(value.chargeLimitEnabled);
        assertFalse(value.pinch.enabled); assertFalse(value.doubleTap.enabled);
        assertTrue(value.appPackages().isEmpty());
        value.write(storage);
        assertEquals(50, EnhanceConfig.read(storage).chargeStart);
    }

    @Test public void corruptTypesAndInvalidRangesReturnValidatedCurrentDefaults() {
        storage.edit().putString("battery_enabled", "false").putBoolean("charge_start", true)
                .putInt("charge_stop", 1).putInt("signal_one", -30).putInt("signal_two", -90)
                .putString("revision", "broken").putInt("display_mode", 98).putString("app_mappings", "not-json").commit();
        EnhanceConfig value = EnhanceConfig.read(storage);
        assertTrue(value.batteryEnabled);
        assertEquals(50, value.chargeStart); assertEquals(80, value.chargeStop);
        assertEquals(-80, value.signalOne); assertEquals(-60, value.signalTwo);
        assertEquals(DisplayPolicy.BOTH, value.displayMode); assertEquals(0, value.revision);
        assertTrue(value.appPackages().isEmpty());
        value.write(storage);
        assertTrue(storage.getBoolean("battery_enabled", false)); assertEquals(50, storage.getInt("charge_start", 0));
    }

    @Test public void completeConfigurationRoundTripKeepsRulesAndIndependentActions() {
        Bundle values = EnhanceConfig.defaults().toBundle();
        values.putInt("charge_start", 50); values.putInt("charge_stop", 80);
        values.putInt("signal_one", -80); values.putInt("signal_two", -60);
        values.putBoolean("charge_limit_enabled", true); values.putBoolean("ignore_stylus_back", true);
        values.putString(ActionConfig.KEYS[2], "keyboard:66");
        EnhanceConfig configured = new EnhanceConfig(values);
        configured = new EnhanceConfig(configured.withAppAction("com.example.reader", 0, "volume_up"));
        configured = new EnhanceConfig(configured.withAppEnabled("com.example.reader", true));
        configured = new EnhanceConfig(configured.withStylusGesture(configured.doubleTap.withEnabled(true)
                .withPackages(Set.of("com.example.reader"), true).withAppAction("com.example.reader", 2)));
        configured.write(storage);
        EnhanceConfig restored = EnhanceConfig.read(storage);
        assertTrue(restored.chargeLimitEnabled); assertTrue(restored.ignoreStylusBack);
        assertEquals("keyboard:66", restored.action(2));
        assertEquals("volume_up", restored.action("com.example.reader", 0));
        assertTrue(restored.doubleTap.enabled); assertEquals(2, restored.doubleTap.action("com.example.reader"));
        assertEquals(configured.toBundle().getString(EnhanceConfig.APP_MAPPINGS), restored.toBundle().getString(EnhanceConfig.APP_MAPPINGS));
    }

    @Test public void incompleteOrInvalidApplicationBindingsAreDiscardedWithoutFillingGlobals() {
        storage.edit().putString("upper_click", "volume_up")
                .putString("app_mappings", "{\"com.example.short\":{\"enabled\":true,\"actions\":[\"pinch\",\"keyboard:66\"]},"
                        + "\"com.example.old\":{\"enabled\":true,\"actions\":[\"stylus_menu\",\"none\",\"none\",\"none\",\"none\",\"none\"]},"
                        + "\"com.example.extra\":{\"enabled\":true,\"actions\":[\"none\",\"none\",\"none\",\"none\",\"none\",\"none\",\"none\"]}}")
                .commit();
        EnhanceConfig value = EnhanceConfig.read(storage);
        assertTrue(value.appPackages().isEmpty());
        assertFalse(value.appEnabled("com.example.short"));
        assertEquals("volume_up", value.action("com.example.short", 2));
        assertFalse(ActionConfig.valid("stylus_menu"));
        assertEquals(ActionConfig.DEFAULTS[1], ActionConfig.sanitize(1, "stylus_menu"));
    }

    @Test public void boundaryValuesAndReturnedBundlesCannotMutateTheSnapshot() {
        Bundle values = new Bundle(); values.putInt("charge_start", 95); values.putInt("charge_stop", 100);
        values.putInt("signal_one", -120); values.putInt("signal_two", -20);
        EnhanceConfig value = new EnhanceConfig(values); value.write(storage);
        EnhanceConfig restored = EnhanceConfig.read(storage);
        assertEquals(95, restored.chargeStart); assertEquals(100, restored.chargeStop);
        assertEquals(-120, restored.signalOne); assertEquals(-20, restored.signalTwo);
        values.putInt("charge_start", 99);
        Bundle escaped = restored.toBundle(); escaped.putInt("charge_start", 2);
        assertEquals(95, restored.toBundle().getInt("charge_start"));
    }

    @Test public void disabledApplicationUsesGlobalsAndRetainsItsCurrentRuleForReactivation() {
        EnhanceConfig original = EnhanceConfig.defaults();
        EnhanceConfig custom = new EnhanceConfig(original.withAppAction("com.example.reader", 0, "volume_up"));
        custom = new EnhanceConfig(custom.withAppEnabled("com.example.reader", true));
        assertEquals("volume_up", custom.action("com.example.reader", 0));
        EnhanceConfig disabled = new EnhanceConfig(custom.withAppEnabled("com.example.reader", false));
        assertEquals(original.action(0), disabled.action("com.example.reader", 0));
        assertEquals("volume_up", disabled.appAction("com.example.reader", 0));
        EnhanceConfig enabled = new EnhanceConfig(disabled.withAppsEnabled(Set.of("com.example.reader", "invalid package"), true));
        assertEquals("volume_up", enabled.action("com.example.reader", 0));
        assertFalse(original.appEnabled("com.example.reader")); assertEquals(Set.of("com.example.reader"), enabled.appPackages());
    }

    @Test public void keyboardParserRejectsTheRemovedKeycodeFormatAndInvalidValues() {
        assertEquals(KeyEvent.KEYCODE_ENTER, ActionConfig.keyCode("keyboard:66"));
        assertEquals(ActionConfig.KEYBOARD, ActionConfig.option("keyboard:66"));
        for (String invalid : new String[]{"keyboard", "keycode", "keycode:66", "keyboard:0", "keyboard:-1",
                "keyboard:9999", "keyboard:1.5", "keyboard:+66", "keyboard:66:2", "keyboard:00066"}) {
            assertFalse(invalid, ActionConfig.valid(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> ActionConfig.customKey("keycode", 66));
        assertThrows(IllegalArgumentException.class, () -> ActionConfig.sanitize(6, "none"));
    }

    @Test public void currentPreferencesNeverReadTheRetiredPrivatePreferenceFile() {
        Context context = RuntimeEnvironment.getApplication().createDeviceProtectedStorageContext();
        context.getSharedPreferences("focus_stylus_enhance", Context.MODE_PRIVATE).edit()
                .putBoolean("battery_enabled", false).putInt("charge_start", 75).commit();
        context.getSharedPreferences("hyperstylus", Context.MODE_PRIVATE).edit().clear().commit();
        EnhanceConfig current = EnhancePreferences.load(context);
        assertTrue(current.batteryEnabled); assertEquals(50, current.chargeStart);
    }
}
