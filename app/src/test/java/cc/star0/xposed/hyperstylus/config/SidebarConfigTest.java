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

/** The sidebar bit persists independently and participates in complete application overrides. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SidebarConfigTest {
    private static final String APP = "com.example.reader";

    @Test public void sidebarDefaultsOffRejectsWrongTypesAndPersistsWithoutChangingOtherGestureRules() {
        assertFalse(EnhanceConfig.defaults().ignoreStylusSidebar);
        Bundle values = EnhanceConfig.defaults().toBundle();
        values.putString("ignore_stylus_sidebar", "true");
        assertFalse(new EnhanceConfig(values).ignoreStylusSidebar);
        values.putBoolean("ignore_stylus_sidebar", true);
        values.putBoolean("ignore_stylus_split_divider", true);
        SharedPreferences storage = RuntimeEnvironment.getApplication().createDeviceProtectedStorageContext()
                .getSharedPreferences("sidebar_test", Context.MODE_PRIVATE);
        storage.edit().clear().commit();
        new EnhanceConfig(values).write(storage);
        EnhanceConfig restored = EnhanceConfig.read(storage);
        assertTrue(restored.ignoreStylusSidebar);
        assertTrue(restored.ignoreStylusSplitDivider);
        assertEquals(GestureAppConfig.SIDEBAR | GestureAppConfig.SPLIT_DIVIDER, restored.gestureMask());
        values = restored.toBundle(); values.putBoolean("ignore_stylus_sidebar", false);
        new EnhanceConfig(values).write(storage);
        restored = EnhanceConfig.read(storage);
        assertFalse(restored.ignoreStylusSidebar);
        assertTrue(restored.ignoreStylusSplitDivider);
    }

    @Test public void selectedZeroProfileAllowsTheSidebarWhileUnselectedApplicationsKeepTheGlobalPolicy() {
        Bundle values = EnhanceConfig.defaults().toBundle(); values.putBoolean("ignore_stylus_sidebar", true);
        EnhanceConfig config = new EnhanceConfig(values);
        GestureAppConfig rules = config.gestureApps.withEnabled(true).withPackages(Set.of(APP), true, config.gestureMask());
        assertEquals(GestureAppConfig.SIDEBAR, rules.profileMask(APP, 0));
        rules = rules.withProfile(APP, 0);
        EnhanceConfig restored = new EnhanceConfig(config.withGestureApps(rules));
        assertTrue(restored.ignoreStylusSidebar);
        assertEquals(0, restored.gestureApps.mask(APP, restored.gestureMask()));
        assertEquals(GestureAppConfig.SIDEBAR, restored.gestureApps.mask("com.example.other", restored.gestureMask()));
        assertEquals(GestureAppConfig.SIDEBAR,
                restored.gestureApps.withPackages(Set.of(APP), false, 0).mask(APP, restored.gestureMask()));
    }

    @Test public void currentVersionTwoProfilesKeepTheirSavedMaskAndAcceptTheSidebarBitWithoutConversion() {
        GestureAppConfig saved = new GestureAppConfig("{\"version\":2,\"enabled\":true,\"selected\":[\"com.example.reader\"],\"profiles\":{\"com.example.reader\":127}}");
        assertEquals(127, saved.mask(APP, GestureAppConfig.ALL));
        assertEquals(0, saved.mask(APP, GestureAppConfig.ALL) & GestureAppConfig.SIDEBAR);
        GestureAppConfig changed = new GestureAppConfig(saved.withProfile(APP, GestureAppConfig.SIDEBAR).encode());
        assertEquals(GestureAppConfig.SIDEBAR, changed.mask(APP, GestureAppConfig.ALL));
        assertEquals(GestureAppConfig.SIDEBAR, GestureAppConfig.feature("sidebar"));
        assertTrue(changed.enabled);
        assertEquals(Set.of(APP), changed.selectedPackages());
    }
}
