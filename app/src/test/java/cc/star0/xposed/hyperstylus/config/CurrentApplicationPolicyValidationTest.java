package cc.star0.xposed.hyperstylus.config;


import static org.junit.Assert.*;

import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CurrentApplicationPolicyValidationTest {
    @Test public void oldWhitelistBlacklistAndNonCurrentVersionsDoNotLoadApplicationOverrides() {
        for (String json : new String[]{
                "{\"enabled\":true,\"mode\":0,\"blacklist\":[\"com.example.reader\"],\"blacklist_profiles\":{\"com.example.reader\":0}}",
                "{\"version\":1,\"enabled\":true,\"mode\":1,\"whitelist\":[\"com.example.reader\"],\"whitelist_profiles\":{\"com.example.reader\":3}}",
                "{\"version\":3,\"enabled\":true,\"selected\":[\"com.example.reader\"],\"profiles\":{\"com.example.reader\":0}}",
                "{\"version\":\"2\",\"enabled\":true,\"selected\":[\"com.example.reader\"],\"profiles\":{\"com.example.reader\":0}}"}) {
            GestureAppConfig value = new GestureAppConfig(json);
            assertFalse(value.enabled); assertTrue(value.selectedPackages().isEmpty());
            assertEquals(GestureAppConfig.ALL, value.mask("com.example.reader", GestureAppConfig.ALL));
        }
    }

    @Test public void currentZeroMaskOverridesGlobalsAndUnselectedApplicationsUseGlobals() {
        GestureAppConfig value = new GestureAppConfig("{\"version\":2,\"enabled\":true,\"selected\":[\"com.example.reader\"],\"profiles\":{\"com.example.reader\":0}}");
        assertEquals(0, value.mask("com.example.reader", GestureAppConfig.ALL));
        assertEquals(GestureAppConfig.ALL, value.mask("com.example.other", GestureAppConfig.ALL));
        assertEquals(16, value.mask(null, 16));
        GestureAppConfig restored = new GestureAppConfig(value.encode());
        assertEquals(Set.of("com.example.reader"), restored.selectedPackages());
        assertEquals(0, restored.mask("com.example.reader", GestureAppConfig.ALL));
        assertEquals(16, restored.withEnabled(false).mask("com.example.reader", 16));
    }

    @Test public void deselectionRetainsCurrentValuesAndMalformedNumbersAreIgnored() {
        GestureAppConfig value = new GestureAppConfig("{\"version\":2,\"enabled\":true,\"selected\":[\"com.example.reader\"],\"profiles\":{\"com.example.reader\":1.5,\"com.example.text\":\"2\",\"com.example.large\":4294967296}}");
        assertEquals(31, value.mask("com.example.reader", 31));
        value = value.withProfile("com.example.reader", 0).withPackages(Set.of("com.example.reader"), false, 31);
        assertEquals(31, value.mask("com.example.reader", 31));
        value = value.withPackages(Set.of("com.example.reader"), true, 31);
        assertEquals(0, value.mask("com.example.reader", 31));
        assertThrows(IllegalArgumentException.class, () -> new GestureAppConfig("{}").withProfile("bad package", 0));
    }

    @Test public void nativeGesturesUseCurrentVersionIndependentRangesAndRetainApplicationChoice() {
        StylusGestureConfig pinch = new StylusGestureConfig(StylusGestureConfig.Kind.PINCH, "{\"version\":1,\"enabled\":true,\"global_action\":5,\"selected\":[\"com.example.reader\"],\"app_actions\":{\"com.example.reader\":2}}");
        StylusGestureConfig tap = new StylusGestureConfig(StylusGestureConfig.Kind.DOUBLE_TAP, "{\"version\":1,\"enabled\":true,\"global_action\":5,\"app_actions\":{\"com.example.reader\":5}}");
        assertEquals(5, pinch.globalAction); assertEquals(2, pinch.action("com.example.reader"));
        assertEquals(1, tap.globalAction); assertEquals(1, tap.appAction("com.example.reader"));
        pinch = pinch.withPackages(Set.of("com.example.reader"), false).withGlobalAction(4);
        assertEquals(4, pinch.action("com.example.reader")); assertEquals(2, pinch.appAction("com.example.reader"));
        pinch = new StylusGestureConfig(StylusGestureConfig.Kind.PINCH, pinch.withPackages(Set.of("com.example.reader"), true).encode());
        assertEquals(2, pinch.action("com.example.reader"));
        assertThrows(IllegalArgumentException.class, () -> tap.withGlobalAction(5));
    }

    @Test public void missingOrUnsupportedNativeSchemasCannotEnableTakeoverOrRetainOverrides() {
        for (String version : new String[]{"", "\"version\":0,", "\"version\":2,", "\"version\":\"1\","}) {
            StylusGestureConfig value = new StylusGestureConfig(StylusGestureConfig.Kind.PINCH,
                    "{" + version + "\"enabled\":true,\"global_action\":2,\"selected\":[\"com.example.reader\"],\"app_actions\":{\"com.example.reader\":2}}");
            assertFalse(value.enabled); assertEquals(5, value.globalAction); assertTrue(value.selectedPackages().isEmpty());
            assertEquals(5, value.appAction("com.example.reader"));
        }
    }
}
