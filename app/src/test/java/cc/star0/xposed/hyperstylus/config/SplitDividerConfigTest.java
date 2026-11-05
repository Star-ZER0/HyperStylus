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

/** The divider rule remains independent across persistence and complete application overrides. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SplitDividerConfigTest {
    private static final String APP = "com.example.reader";

    @Test public void theDividerSwitchDefaultsOffRejectsWrongTypesAndPersistsWithoutChangingCaption() {
        assertFalse(EnhanceConfig.defaults().ignoreStylusSplitDivider);
        Bundle values = new Bundle();
        values.putString("ignore_stylus_split_divider", "true");
        assertFalse(new EnhanceConfig(values).ignoreStylusSplitDivider);
        values.putBoolean("ignore_stylus_split_divider", true);
        values.putBoolean("ignore_stylus_caption", true);
        SharedPreferences storage = RuntimeEnvironment.getApplication().createDeviceProtectedStorageContext()
                .getSharedPreferences("split_divider_test", Context.MODE_PRIVATE);
        storage.edit().clear().commit();
        new EnhanceConfig(values).write(storage);
        EnhanceConfig restored = EnhanceConfig.read(storage);
        assertTrue(restored.ignoreStylusSplitDivider);
        assertTrue(restored.ignoreStylusCaption);
        assertEquals(GestureAppConfig.CAPTION | GestureAppConfig.SPLIT_DIVIDER, restored.gestureMask());
        values = restored.toBundle();
        values.putBoolean("ignore_stylus_split_divider", false);
        new EnhanceConfig(values).write(storage);
        restored = EnhanceConfig.read(storage);
        assertFalse(restored.ignoreStylusSplitDivider);
        assertTrue(restored.ignoreStylusCaption);
    }

    @Test public void aSelectedZeroProfileAllowsDividerOperationsWhileTheGlobalRuleStaysEnabled() {
        Bundle values = EnhanceConfig.defaults().toBundle();
        values.putBoolean("ignore_stylus_split_divider", true);
        EnhanceConfig config = new EnhanceConfig(values);
        GestureAppConfig rules = config.gestureApps.withEnabled(true).withPackages(Set.of(APP), true, config.gestureMask());
        assertEquals(GestureAppConfig.SPLIT_DIVIDER, rules.profileMask(APP, 0));
        rules = rules.withProfile(APP, 0);
        EnhanceConfig restored = new EnhanceConfig(config.withGestureApps(rules));
        assertTrue(restored.ignoreStylusSplitDivider);
        assertEquals(0, restored.gestureApps.mask(APP, restored.gestureMask()));
        assertEquals(GestureAppConfig.SPLIT_DIVIDER, restored.gestureApps.mask("com.example.other", restored.gestureMask()));
        assertEquals(GestureAppConfig.SPLIT_DIVIDER,
                restored.gestureApps.withPackages(Set.of(APP), false, 0).mask(APP, restored.gestureMask()));
    }

    @Test public void currentVersionTwoProfilesRetainTheirSavedMasksAndAcceptTheDividerBit() {
        GestureAppConfig saved = new GestureAppConfig("{\"version\":2,\"enabled\":true,\"selected\":[\"com.example.reader\"],\"profiles\":{\"com.example.reader\":63}}");
        assertEquals(63, saved.mask(APP, GestureAppConfig.ALL));
        assertEquals(0, saved.mask(APP, GestureAppConfig.ALL) & GestureAppConfig.SPLIT_DIVIDER);
        GestureAppConfig changed = new GestureAppConfig(saved.withProfile(APP, GestureAppConfig.SPLIT_DIVIDER).encode());
        assertEquals(GestureAppConfig.SPLIT_DIVIDER, changed.mask(APP, GestureAppConfig.ALL));
        assertEquals(GestureAppConfig.SPLIT_DIVIDER, GestureAppConfig.feature("split-divider"));
        assertTrue(changed.enabled);
        assertEquals(Set.of(APP), changed.selectedPackages());
    }
}
