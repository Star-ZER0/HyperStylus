package cc.star0.xposed.hyperstylus.config;


import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class FocusNotificationConfigTest {
    private static final String APP = "com.example.reader";

    @Test public void focusNotificationStartsDisabledAndItsSwitchSurvivesPersistence() {
        EnhanceConfig defaults = EnhanceConfig.defaults();
        assertFalse(defaults.ignoreStylusFocusNotification); assertEquals(0, defaults.gestureMask());
        Bundle values = defaults.toBundle(); values.putBoolean("ignore_stylus_focus_notification", true);
        EnhanceConfig enabled = new EnhanceConfig(values);
        SharedPreferences storage = RuntimeEnvironment.getApplication().createDeviceProtectedStorageContext()
                .getSharedPreferences("focus_notification_test", Context.MODE_PRIVATE);
        storage.edit().clear().commit(); enabled.write(storage);
        EnhanceConfig restored = EnhanceConfig.read(storage);
        assertTrue(restored.ignoreStylusFocusNotification);
        assertTrue(restored.toBundle().getBoolean("ignore_stylus_focus_notification"));
        assertEquals(GestureAppConfig.FOCUS_NOTIFICATION, restored.gestureMask());
        assertFalse(restored.ignoreStylusBack); assertFalse(restored.ignoreStylusCaption);
    }

    @Test public void aZeroApplicationMaskCanAllowNotificationsWhileGlobalsSuppressThem() {
        Bundle values = EnhanceConfig.defaults().toBundle();
        values.putBoolean("ignore_stylus_back", true); values.putBoolean("ignore_stylus_focus_notification", true);
        EnhanceConfig config = new EnhanceConfig(values);
        GestureAppConfig rules = config.gestureApps.withEnabled(true).withPackages(Set.of(APP), true, config.gestureMask())
                .withProfile(APP, 0);
        EnhanceConfig restored = new EnhanceConfig(config.withGestureApps(rules));
        assertTrue(restored.ignoreStylusFocusNotification);
        assertEquals(0, restored.gestureApps.mask(APP, restored.gestureMask()));
        assertEquals(GestureAppConfig.BACK | GestureAppConfig.FOCUS_NOTIFICATION,
                restored.gestureApps.mask("com.example.other", restored.gestureMask()));
    }

    @Test public void anIndependentFocusBitDoesNotChangeOtherApplicationGestureBits() {
        int base = GestureAppConfig.BACK | GestureAppConfig.CAPTION;
        GestureAppConfig rules = new GestureAppConfig("{}").withEnabled(true).withPackages(Set.of(APP), true, base);
        rules = rules.withProfile(APP, base | GestureAppConfig.FOCUS_NOTIFICATION);
        GestureAppConfig restored = new GestureAppConfig(rules.encode());
        int mask = restored.mask(APP, 0);
        assertEquals(base, mask & ~GestureAppConfig.FOCUS_NOTIFICATION);
        assertNotEquals(0, mask & GestureAppConfig.feature("focus-notification"));
        assertEquals(base, restored.withProfile(APP, mask & ~GestureAppConfig.FOCUS_NOTIFICATION).mask(APP, 0));
        assertEquals(GestureAppConfig.HOME_RECENTS, restored.mask("com.example.other", GestureAppConfig.HOME_RECENTS));
        assertEquals(GestureAppConfig.FOCUS_NOTIFICATION, restored.withProfile(APP, GestureAppConfig.FOCUS_NOTIFICATION).mask(APP, base));
    }

    @Test public void allCurrentMasksRoundTripAndOutOfRangeMasksAreRejected() {
        GestureAppConfig rules = new GestureAppConfig("{}").withEnabled(true).withPackages(Set.of(APP), true, 0);
        for (int mask = 0; mask <= GestureAppConfig.ALL; mask++) {
            assertEquals(mask, new GestureAppConfig(rules.withProfile(APP, mask).encode()).mask(APP, 0));
        }
        for (int invalid : new int[]{-1, 256, 512, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> rules.withProfile(APP, invalid));
        }
        GestureAppConfig decoded = new GestureAppConfig("{\"version\":2,\"enabled\":true,\"selected\":[\"com.example.reader\"],\"profiles\":{\"com.example.reader\":256}}");
        assertEquals(GestureAppConfig.BACK, decoded.mask(APP, GestureAppConfig.BACK));
    }

    @Test public void currentMasksWithoutTheNewBitKeepItClearWithoutFormatConversion() {
        Bundle values = new Bundle(); values.putBoolean("ignore_stylus_back", true);
        EnhanceConfig config = new EnhanceConfig(values);
        assertFalse(config.ignoreStylusFocusNotification); assertEquals(GestureAppConfig.BACK, config.gestureMask());
        GestureAppConfig decoded = new GestureAppConfig("{\"version\":2,\"enabled\":true,\"selected\":[\"com.example.reader\"],\"profiles\":{\"com.example.reader\":31}}");
        assertEquals(31, decoded.mask(APP, GestureAppConfig.ALL));
        assertEquals(0, decoded.mask(APP, GestureAppConfig.ALL) & GestureAppConfig.FOCUS_NOTIFICATION);
    }
}
