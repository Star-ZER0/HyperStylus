package cc.star0.xposed.hyperstylus.config;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import java.util.Arrays;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class FullKeyConfigTest {
    private SharedPreferences storage;

    @Before public void clearStorage() {
        storage = RuntimeEnvironment.getApplication().createDeviceProtectedStorageContext()
                .getSharedPreferences("full_key_config_test", Context.MODE_PRIVATE);
        storage.edit().clear().commit();
    }
    private static EnhanceConfig global(String focus, String upper, String lower) {
        Bundle values = EnhanceConfig.defaults().toBundle();
        String[] full = {focus, upper, lower};
        for (int button = 0; button < full.length; button++) values.putString(ActionConfig.FULL_KEYS[button], full[button]);
        return new EnhanceConfig(values);
    }
    private static JSONObject profile() throws Exception {
        return new JSONObject().put("enabled", true)
                .put("actions", new JSONArray(Arrays.asList(ActionConfig.DEFAULTS)));
    }

    @Test public void fullMappingsStartDisabledAndDoNotReplaceExistingClickOrLongActions() {
        EnhanceConfig defaults = EnhanceConfig.defaults();
        for (int button = 0; button < 3; button++) {
            assertEquals(ActionConfig.NONE, defaults.fullAction(button));
            assertEquals(0, defaults.fullKeyCodeFor(null, button));
        }
        EnhanceConfig mapped = global("keyboard:66", "keyboard:24", "keyboard:25");
        assertEquals(KeyEvent.KEYCODE_ENTER, mapped.fullKeyCodeFor(null, 0));
        assertEquals(KeyEvent.KEYCODE_VOLUME_UP, mapped.fullKeyCodeFor(null, 1));
        assertEquals(KeyEvent.KEYCODE_VOLUME_DOWN, mapped.fullKeyCodeFor(null, 2));
        for (int binding = 0; binding < ActionConfig.KEYS.length; binding++)
            assertEquals(defaults.action(binding), mapped.action(binding));
    }

    @Test public void globalAndApplicationMappingsSurviveDurableRoundTripAndProfileDisable() {
        EnhanceConfig configured = global("keyboard:66", "keyboard:24", "keyboard:25");
        configured = new EnhanceConfig(configured.withAppFullAction("com.example.reader", 0, "keyboard:59"));
        configured = new EnhanceConfig(configured.withAppAction("com.example.reader", 0, "volume_down"));
        configured = new EnhanceConfig(configured.withAppEnabled("com.example.reader", true));
        configured.write(storage);
        EnhanceConfig restored = EnhanceConfig.read(storage);
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT, restored.fullKeyCodeFor("com.example.reader", 0));
        assertEquals(KeyEvent.KEYCODE_ENTER, restored.fullKeyCodeFor("com.example.other", 0));
        assertEquals("volume_down", restored.appAction("com.example.reader", 0));
        assertEquals(configured.toBundle().getString(EnhanceConfig.APP_MAPPINGS),
                restored.toBundle().getString(EnhanceConfig.APP_MAPPINGS));
        EnhanceConfig disabled = new EnhanceConfig(restored.withAppEnabled("com.example.reader", false));
        assertEquals(KeyEvent.KEYCODE_ENTER, disabled.fullKeyCodeFor("com.example.reader", 0));
        assertEquals("keyboard:59", disabled.appFullAction("com.example.reader", 0));
        EnhanceConfig enabled = new EnhanceConfig(disabled.withAppEnabled("com.example.reader", true));
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT, enabled.fullKeyCodeFor("com.example.reader", 0));
    }

    @Test public void malformedGlobalMappingsNormalizeToDisabledWithExactPersistedTypes() {
        storage.edit().putInt(ActionConfig.FULL_KEYS[0], 66)
                .putString(ActionConfig.FULL_KEYS[1], "pinch")
                .putString(ActionConfig.FULL_KEYS[2], "keyboard:9999").commit();
        EnhanceConfig restored = EnhanceConfig.read(storage);
        for (int button = 0; button < 3; button++) assertEquals(ActionConfig.NONE, restored.fullAction(button));
        restored.write(storage);
        for (String key : ActionConfig.FULL_KEYS) assertEquals(ActionConfig.NONE, storage.getString(key, null));
        for (String value : new String[]{"keyboard", "default", "volume_up", "keyboard:0", "keyboard:-1", "keyboard:66:2", null}) {
            assertFalse(ActionConfig.validFullKey(value));
            assertEquals(ActionConfig.NONE, ActionConfig.sanitizeFullKey(value));
        }
        assertTrue(ActionConfig.validFullKey("none"));
        assertTrue(ActionConfig.validFullKey("keyboard:" + KeyEvent.getMaxKeyCode()));
    }

    @Test public void aProfileWithoutFullKeysKeepsClickActionsAndDisablesFullMapping() throws Exception {
        JSONObject partial = profile();
        Bundle values = global("keyboard:66", "none", "none").toBundle();
        values.putString(EnhanceConfig.APP_MAPPINGS, new JSONObject().put("com.example.reader", partial).toString());
        EnhanceConfig config = new EnhanceConfig(values);
        assertTrue(config.appEnabled("com.example.reader"));
        assertEquals(ActionConfig.DEFAULTS[0], config.action("com.example.reader", 0));
        assertEquals(0, config.fullKeyCodeFor("com.example.reader", 0));
        assertEquals("keyboard:66", config.fullAction("com.example.other", 0));
        AppKeyConfig decoded = AppKeyConfig.decode(partial);
        assertNotNull(decoded);
        for (int button = 0; button < 3; button++) assertEquals(ActionConfig.NONE, decoded.fullAction(button));
    }

    @Test public void invalidNewProfileFieldIsRejectedBeforeItCanOwnAButton() throws Exception {
        for (Object full : new Object[]{JSONObject.NULL, "keyboard:66", new JSONArray(),
                new JSONArray().put("keyboard:66").put("none"),
                new JSONArray().put("keyboard:66").put(24).put("none"),
                new JSONArray().put("keyboard:66").put("pinch").put("none")}) {
            JSONObject invalid = profile().put("fullKeys", full);
            assertNull(AppKeyConfig.decode(invalid));
            Bundle values = global("keyboard:66", "none", "none").toBundle();
            values.putString(EnhanceConfig.APP_MAPPINGS, new JSONObject().put("com.example.reader", invalid).toString());
            EnhanceConfig config = new EnhanceConfig(values);
            assertFalse(config.appEnabled("com.example.reader"));
            assertEquals(KeyEvent.KEYCODE_ENTER, config.fullKeyCodeFor("com.example.reader", 0));
        }
    }

    @Test public void enablingNewProfilesCopiesGlobalsWhileExistingProfilesKeepTheirOwnTargets() {
        EnhanceConfig config = global("keyboard:66", "keyboard:24", "none");
        config = new EnhanceConfig(config.withAppFullAction("com.example.reader", 0, "keyboard:59"));
        config = new EnhanceConfig(config.withAppsEnabled(Set.of("com.example.reader", "com.example.viewer", "invalid package"), true));
        assertEquals("keyboard:59", config.fullAction("com.example.reader", 0));
        assertEquals("keyboard:66", config.fullAction("com.example.viewer", 0));
        assertEquals("keyboard:24", config.fullAction("com.example.viewer", 1));
        assertEquals(Set.of("com.example.reader", "com.example.viewer"), config.appPackages());
        Bundle changedGlobals = config.toBundle(); changedGlobals.putString(ActionConfig.FULL_KEYS[0], "keyboard:25");
        EnhanceConfig changed = new EnhanceConfig(changedGlobals);
        assertEquals("keyboard:66", changed.appFullAction("com.example.viewer", 0));
        assertEquals("keyboard:25", changed.fullAction(0));
    }

    @Test public void clearingCompleteMappingRestoresBothStoredActionsWithoutChangingOtherButtons() {
        EnhanceConfig config = global("keyboard:66", "keyboard:24", "none");
        config = new EnhanceConfig(config.withAppFullAction("com.example.reader", 0, "keyboard:59"));
        config = new EnhanceConfig(config.withAppAction("com.example.reader", 0, "volume_up"));
        config = new EnhanceConfig(config.withAppAction("com.example.reader", 1, "volume_down"));
        config = new EnhanceConfig(config.withAppEnabled("com.example.reader", true));
        config = new EnhanceConfig(config.withAppFullAction("com.example.reader", 0, ActionConfig.NONE));
        assertEquals(0, config.fullKeyCodeFor("com.example.reader", 0));
        assertEquals("volume_up", config.action("com.example.reader", 0));
        assertEquals("volume_down", config.action("com.example.reader", 1));
        assertEquals("keyboard:24", config.fullAction("com.example.reader", 1));
        assertEquals("keyboard:66", config.fullAction(0));
    }

    @Test public void profileSnapshotsAndReturnedBundlesDoNotExposeMutableMappingArrays() throws Exception {
        String[] actions = ActionConfig.DEFAULTS.clone();
        String[] full = {"keyboard:66", "none", "none"};
        AppKeyConfig profile = new AppKeyConfig(true, actions, full);
        actions[0] = "volume_up"; full[0] = "keyboard:25";
        assertEquals(ActionConfig.DEFAULTS[0], profile.action(0));
        assertEquals("keyboard:66", profile.fullAction(0));
        assertEquals("keyboard:66", profile.action(0, "volume_down").fullAction(0));
        assertEquals(ActionConfig.DEFAULTS[0], profile.fullAction(0, "none").action(0));
        EnhanceConfig config = global("keyboard:66", "none", "none");
        Bundle escaped = config.toBundle(); escaped.putString(ActionConfig.FULL_KEYS[0], "keyboard:25");
        assertEquals("keyboard:66", config.fullAction(0));
        assertEquals("keyboard:66", AppKeyConfig.decode(profile.toJson()).fullAction(0));
    }

    @Test public void completeMappingPreservesTheIndependentNativePinchChoice() {
        EnhanceConfig config = global("keyboard:66", "none", "none");
        config = new EnhanceConfig(config.withAppFullAction("com.example.reader", 0, "none"));
        config = new EnhanceConfig(config.withAppEnabled("com.example.reader", true));
        assertEquals(0, config.fullKeyCodeFor("com.example.reader", 0));
        assertEquals("pinch", config.action("com.example.reader", 0));
        config = new EnhanceConfig(config.withAppFullAction("com.example.reader", 0, "keyboard:59"));
        config = new EnhanceConfig(config.withStylusGesture(config.pinch.withEnabled(true).withGlobalAction(5)));
        config.write(storage);
        EnhanceConfig restored = EnhanceConfig.read(storage);
        assertTrue(restored.pinch.enabled);
        assertEquals(5, restored.pinch.globalAction);
        assertEquals(KeyEvent.KEYCODE_ENTER, restored.fullKeyCodeFor(null, 0));
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT, restored.fullKeyCodeFor("com.example.reader", 0));
        assertEquals("pinch", restored.action("com.example.reader", 0));
    }

    @Test public void buttonBoundsAndProfileArrayLengthsAreValidated() {
        EnhanceConfig config = EnhanceConfig.defaults();
        assertThrows(IllegalArgumentException.class, () -> config.fullAction(-1));
        assertThrows(IllegalArgumentException.class, () -> config.fullKeyCodeFor(null, 3));
        assertThrows(IllegalArgumentException.class, () -> config.withAppFullAction("invalid package", 0, "keyboard:66"));
        assertThrows(IllegalArgumentException.class, () -> new AppKeyConfig(true, ActionConfig.DEFAULTS, new String[]{"none"}));
    }
}
