package cc.star0.xposed.hyperstylus.config;

import static org.junit.Assert.*;

import android.os.Bundle;
import cc.star0.xposed.hyperstylus.common.Protocol;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Compute required hosts from all enabled consumers, including retained application selections. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ScopePolicyTest {
    private static final String APP = "com.example.reader";

    private static EnhanceConfig off() {
        Bundle values = EnhanceConfig.defaults().toBundle();
        values.putBoolean("battery_enabled", false);
        values.putBoolean("signal_enabled", false);
        values.putBoolean("pro_settings", false);
        for (String key : EnhanceConfig.KEY_ENABLED_KEYS) values.putBoolean(key, false);
        return new EnhanceConfig(values);
    }

    private static EnhanceConfig flag(EnhanceConfig config, String key, boolean enabled) {
        Bundle values = config.toBundle(); values.putBoolean(key, enabled);
        return new EnhanceConfig(values);
    }

    @Test public void allOffNeedsNoScopeWhileDefaultsKeepTheirExistingConsumers() {
        assertEquals(Set.of(), ScopePolicy.required(off()));
        assertEquals(Set.of(ScopePolicy.SYSTEM, Protocol.SYSTEM_UI, Protocol.BLUETOOTH, Protocol.SECURITY_CORE),
                ScopePolicy.required(EnhanceConfig.defaults()));
    }

    @Test public void eitherNotificationNeedsSystemAndBluetoothIndependentlyOfIconAndLimit() {
        for (String key : new String[]{"charging_notification_enabled", "attached_notification_enabled"}) {
            EnhanceConfig config = flag(off(), key, true);
            assertEquals(Set.of(ScopePolicy.SYSTEM, Protocol.BLUETOOTH), ScopePolicy.required(config));
            config = flag(config, "charge_limit_enabled", true);
            config = flag(config, key, false);
            assertEquals(Set.of(ScopePolicy.SYSTEM, Protocol.BLUETOOTH), ScopePolicy.required(config));
            config = flag(config, "charge_limit_enabled", false);
            assertTrue(ScopePolicy.required(config).isEmpty());
        }
        EnhanceConfig both = flag(flag(off(), "charging_notification_enabled", true), "attached_notification_enabled", true);
        assertEquals(Set.of(ScopePolicy.SYSTEM, Protocol.BLUETOOTH),
                ScopePolicy.required(flag(both, "charging_notification_enabled", false)));
    }

    @Test public void anyIndividualButtonNeedsOnlySystemIncludingTheDefaultPinchMapping() {
        for (int button = 0; button < 3; button++) {
            Bundle values = off().toBundle();
            values.putBoolean(EnhanceConfig.KEY_ENABLED_KEYS[button], true);
            EnhanceConfig config = new EnhanceConfig(values);
            assertEquals(Set.of(ScopePolicy.SYSTEM), ScopePolicy.required(config));
        }
    }

    @Test public void systemScopeRemainsUntilTheLastKeyOrOtherSystemConsumerIsDisabled() {
        EnhanceConfig config = flag(off(), EnhanceConfig.KEY_ENABLED_KEYS[1], true);
        config = flag(config, EnhanceConfig.KEY_ENABLED_KEYS[2], true);
        config = flag(config, "charge_limit_enabled", true);
        config = flag(config, EnhanceConfig.KEY_ENABLED_KEYS[1], false);
        assertEquals(Set.of(ScopePolicy.SYSTEM, Protocol.BLUETOOTH), ScopePolicy.required(config));
        config = flag(config, "charge_limit_enabled", false);
        assertEquals(Set.of(ScopePolicy.SYSTEM), ScopePolicy.required(config));
        config = flag(config, EnhanceConfig.KEY_ENABLED_KEYS[2], false);
        assertTrue(ScopePolicy.required(config).isEmpty());
    }

    @Test public void batteryAndChargeShareBluetoothWithoutOwningEachOthersHosts() {
        EnhanceConfig both = flag(flag(off(), "battery_enabled", true), "charge_limit_enabled", true);
        assertEquals(Set.of(ScopePolicy.SYSTEM, Protocol.SYSTEM_UI, Protocol.BLUETOOTH), ScopePolicy.required(both));
        EnhanceConfig chargeOnly = flag(both, "battery_enabled", false);
        assertEquals(Set.of(ScopePolicy.SYSTEM, Protocol.BLUETOOTH), ScopePolicy.required(chargeOnly));
        EnhanceConfig batteryOnly = flag(both, "charge_limit_enabled", false);
        assertEquals(Set.of(Protocol.SYSTEM_UI, Protocol.BLUETOOTH), ScopePolicy.required(batteryOnly));
        assertTrue(ScopePolicy.required(flag(chargeOnly, "charge_limit_enabled", false)).isEmpty());
    }

    @Test public void signalIsAChildOfTheBatteryDisplayAndDoesNotKeepHostsByItself() {
        assertTrue(ScopePolicy.required(flag(off(), "signal_enabled", true)).isEmpty());
        EnhanceConfig battery = flag(off(), "battery_enabled", true);
        assertEquals(ScopePolicy.required(battery), ScopePolicy.required(flag(battery, "signal_enabled", true)));
    }

    @Test public void backAndRecentsShareHomeWhileOnlyBackNeedsSystemUi() {
        EnhanceConfig both = flag(flag(off(), "ignore_stylus_back", true), "ignore_stylus_home_recents", true);
        assertEquals(Set.of(Protocol.SYSTEM_UI, Protocol.HOME), ScopePolicy.required(both));
        assertEquals(Set.of(Protocol.HOME), ScopePolicy.required(flag(both, "ignore_stylus_back", false)));
        assertEquals(Set.of(Protocol.SYSTEM_UI, Protocol.HOME),
                ScopePolicy.required(flag(both, "ignore_stylus_home_recents", false)));
    }

    @Test public void sharedSystemUiIsRetainedByCaptionAfterBatteryIsDisabled() {
        EnhanceConfig config = flag(flag(off(), "battery_enabled", true), "ignore_stylus_caption", true);
        config = flag(config, "battery_enabled", false);
        assertEquals(Set.of(Protocol.SYSTEM_UI), ScopePolicy.required(config));
        assertTrue(ScopePolicy.required(flag(config, "ignore_stylus_caption", false)).isEmpty());
    }

    @Test public void theGlobalDividerRuleNeedsOnlySystemUiAndReleasesItWhenDisabled() {
        EnhanceConfig config = flag(off(), "ignore_stylus_split_divider", true);
        assertEquals(Set.of(Protocol.SYSTEM_UI), ScopePolicy.required(config));
        assertTrue(ScopePolicy.required(flag(config, "ignore_stylus_split_divider", false)).isEmpty());
    }

    @Test public void sidebarOnlyNeedsItsActualHostAndDoesNotAddSystemUiOrSecurityCore() {
        EnhanceConfig config = flag(off(), "ignore_stylus_sidebar", true);
        assertEquals(Set.of(Protocol.SECURITY_CENTER), ScopePolicy.required(config));
        assertTrue(ScopePolicy.required(flag(config, "ignore_stylus_sidebar", false)).isEmpty());
        assertEquals(Set.of(ScopePolicy.SYSTEM, Protocol.SYSTEM_UI, Protocol.BLUETOOTH,
                Protocol.SECURITY_CORE, Protocol.HOME, Protocol.SECURITY_CENTER), ScopePolicy.MANAGED);
    }

    @Test public void applicationOnlySidebarNeedsFocusAndItsRetainedUnselectedProfileDoesNotKeepScope() {
        EnhanceConfig config = off();
        GestureAppConfig rules = config.gestureApps.withEnabled(true).withPackages(Set.of(APP), true, 0)
                .withProfile(APP, GestureAppConfig.SIDEBAR);
        config = new EnhanceConfig(config.withGestureApps(rules));
        assertEquals(0, config.gestureMask());
        assertEquals(Set.of(ScopePolicy.SYSTEM, Protocol.SECURITY_CENTER), ScopePolicy.required(config));
        config = new EnhanceConfig(config.withGestureApps(rules.withPackages(Set.of(APP), false, 0)));
        assertEquals(GestureAppConfig.SIDEBAR, config.gestureApps.profileMask(APP, 0));
        assertTrue(ScopePolicy.required(config).isEmpty());
    }

    @Test public void aSelectedZeroSidebarOverrideRetainsFocusWhileGlobalSuppressionNeedsTheSidebarHost() {
        EnhanceConfig config = flag(off(), "ignore_stylus_sidebar", true);
        GestureAppConfig rules = config.gestureApps.withEnabled(true).withPackages(Set.of(APP), true, config.gestureMask())
                .withProfile(APP, 0);
        config = new EnhanceConfig(config.withGestureApps(rules));
        assertEquals(0, config.gestureApps.mask(APP, config.gestureMask()));
        assertEquals(Set.of(ScopePolicy.SYSTEM, Protocol.SECURITY_CENTER), ScopePolicy.required(config));
        config = new EnhanceConfig(config.withGestureApps(rules.withEnabled(false)));
        assertEquals(Set.of(Protocol.SECURITY_CENTER), ScopePolicy.required(config));
    }

    @Test public void disablingSidebarLeavesOtherFeatureHostsAndTheirRulesIntact() {
        EnhanceConfig config = flag(flag(off(), "battery_enabled", true), "ignore_stylus_sidebar", true);
        assertEquals(Set.of(Protocol.SYSTEM_UI, Protocol.BLUETOOTH, Protocol.SECURITY_CENTER), ScopePolicy.required(config));
        config = flag(config, "ignore_stylus_sidebar", false);
        assertEquals(Set.of(Protocol.SYSTEM_UI, Protocol.BLUETOOTH), ScopePolicy.required(config));
        assertTrue(config.batteryEnabled);
    }

    @Test public void anApplicationOnlyDividerRuleAddsFocusUntilItsSelectionIsRemoved() {
        EnhanceConfig config = off();
        GestureAppConfig apps = config.gestureApps.withEnabled(true).withPackages(Set.of(APP), true, 0)
                .withProfile(APP, GestureAppConfig.SPLIT_DIVIDER);
        config = new EnhanceConfig(config.withGestureApps(apps));
        assertEquals(0, config.gestureMask());
        assertEquals(GestureAppConfig.SPLIT_DIVIDER, ScopePolicy.gestureFeatures(config));
        assertEquals(Set.of(ScopePolicy.SYSTEM, Protocol.SYSTEM_UI), ScopePolicy.required(config));
        config = new EnhanceConfig(config.withGestureApps(apps.withPackages(Set.of(APP), false, 0)));
        assertEquals(GestureAppConfig.SPLIT_DIVIDER, config.gestureApps.profileMask(APP, 0));
        assertTrue(ScopePolicy.required(config).isEmpty());
    }

    @Test public void aZeroDividerOverrideStillNeedsFocusWhileTheGlobalRuleIsEnabled() {
        EnhanceConfig config = flag(off(), "ignore_stylus_split_divider", true);
        GestureAppConfig apps = config.gestureApps.withEnabled(true).withPackages(Set.of(APP), true, config.gestureMask())
                .withProfile(APP, 0);
        config = new EnhanceConfig(config.withGestureApps(apps));
        assertEquals(0, config.gestureApps.mask(APP, config.gestureMask()));
        assertEquals(Set.of(ScopePolicy.SYSTEM, Protocol.SYSTEM_UI), ScopePolicy.required(config));
        config = new EnhanceConfig(config.withGestureApps(apps.withEnabled(false)));
        assertEquals(Set.of(Protocol.SYSTEM_UI), ScopePolicy.required(config));
    }

    @Test public void proSettingsNeedOnlySecurityCore() {
        assertEquals(Set.of(Protocol.SECURITY_CORE), ScopePolicy.required(flag(off(), "pro_settings", true)));
    }

    @Test public void selectedApplicationFeaturesJoinGlobalsAndAddTheWmsFocusSource() {
        EnhanceConfig config = flag(off(), "ignore_stylus_system_bars", true);
        GestureAppConfig apps = config.gestureApps.withEnabled(true).withPackages(Set.of(APP), true, 0)
                .withProfile(APP, GestureAppConfig.BACK | GestureAppConfig.CAPTION);
        config = new EnhanceConfig(config.withGestureApps(apps));
        assertEquals(GestureAppConfig.SYSTEM_BARS | GestureAppConfig.BACK | GestureAppConfig.CAPTION,
                ScopePolicy.gestureFeatures(config));
        assertEquals(Set.of(ScopePolicy.SYSTEM, Protocol.SYSTEM_UI, Protocol.HOME), ScopePolicy.required(config));
    }

    @Test public void applicationOnlyFeaturesStillNeedTheirHostsWhenGlobalSwitchesAreOff() {
        EnhanceConfig config = off();
        GestureAppConfig apps = config.gestureApps.withEnabled(true).withPackages(Set.of(APP), true, 0)
                .withProfile(APP, GestureAppConfig.CAPTION | GestureAppConfig.HOME_RECENTS);
        config = new EnhanceConfig(config.withGestureApps(apps));
        assertEquals(0, config.gestureMask());
        assertEquals(Set.of(ScopePolicy.SYSTEM, Protocol.SYSTEM_UI, Protocol.HOME), ScopePolicy.required(config));
    }

    @Test public void zeroApplicationMaskStillRequiresFocusToOverrideGlobalBackSuppression() {
        EnhanceConfig config = flag(off(), "ignore_stylus_back", true);
        GestureAppConfig apps = config.gestureApps.withEnabled(true).withPackages(Set.of(APP), true, config.gestureMask())
                .withProfile(APP, 0);
        config = new EnhanceConfig(config.withGestureApps(apps));
        assertEquals(0, config.gestureApps.mask(APP, config.gestureMask()));
        assertEquals(GestureAppConfig.BACK, ScopePolicy.gestureFeatures(config));
        assertEquals(Set.of(ScopePolicy.SYSTEM, Protocol.SYSTEM_UI, Protocol.HOME), ScopePolicy.required(config));
        config = new EnhanceConfig(config.withGestureApps(apps.withEnabled(false)));
        assertEquals(Set.of(Protocol.SYSTEM_UI, Protocol.HOME), ScopePolicy.required(config));
    }

    @Test public void unselectedRetainedProfilesDoNotKeepGestureHostsAlive() {
        EnhanceConfig config = off();
        GestureAppConfig apps = config.gestureApps.withEnabled(true).withPackages(Set.of(APP), true, 0)
                .withProfile(APP, GestureAppConfig.ALL).withPackages(Set.of(APP), false, 0);
        config = new EnhanceConfig(config.withGestureApps(apps));
        assertEquals(GestureAppConfig.ALL, config.gestureApps.profileMask(APP, 0));
        assertEquals(0, ScopePolicy.gestureFeatures(config));
        assertTrue(ScopePolicy.required(config).isEmpty());
    }

    @Test public void globalPinchAndCompleteMappingsNeedOnlySystem() {
        EnhanceConfig normal = flag(off(), EnhanceConfig.KEY_ENABLED_KEYS[0], true);
        assertEquals("pinch", normal.action(0));
        assertEquals(Set.of(ScopePolicy.SYSTEM), ScopePolicy.required(normal));
        Bundle values = normal.toBundle(); values.putString(ActionConfig.FULL_KEYS[0], "keyboard:66");
        EnhanceConfig full = new EnhanceConfig(values);
        assertEquals("pinch", full.action(0));
        assertEquals(Set.of(ScopePolicy.SYSTEM), ScopePolicy.required(full));
        values = full.toBundle(); values.putString(ActionConfig.FULL_KEYS[0], ActionConfig.NONE);
        assertEquals(Set.of(ScopePolicy.SYSTEM), ScopePolicy.required(new EnhanceConfig(values)));
        values.putBoolean(EnhanceConfig.KEY_ENABLED_KEYS[0], false);
        assertTrue(ScopePolicy.required(new EnhanceConfig(values)).isEmpty());
    }

    @Test public void applicationPinchMappingsNeedOnlySystemWhileRetainingTheirSelectionAndActions() {
        EnhanceConfig config = flag(off(), EnhanceConfig.KEY_ENABLED_KEYS[1], true);
        config = new EnhanceConfig(config.withAppAction(APP, 2, "pinch"));
        assertFalse(config.appEnabled(APP));
        assertEquals(Set.of(ScopePolicy.SYSTEM), ScopePolicy.required(config));
        config = new EnhanceConfig(config.withAppEnabled(APP, true));
        assertEquals(Set.of(ScopePolicy.SYSTEM), ScopePolicy.required(config));
        config = new EnhanceConfig(config.withAppEnabled(APP, false));
        assertEquals("pinch", config.appAction(APP, 2));
        assertEquals(Set.of(ScopePolicy.SYSTEM), ScopePolicy.required(config));
    }

    @Test public void fullAndNormalApplicationPinchMappingsBothNeedOnlySystem() {
        EnhanceConfig config = flag(off(), EnhanceConfig.KEY_ENABLED_KEYS[1], true);
        config = new EnhanceConfig(config.withAppAction(APP, 2, "pinch"));
        config = new EnhanceConfig(config.withAppFullAction(APP, 1, "keyboard:24"));
        config = new EnhanceConfig(config.withAppEnabled(APP, true));
        assertEquals(Set.of(ScopePolicy.SYSTEM), ScopePolicy.required(config));
        config = new EnhanceConfig(config.withAppFullAction(APP, 1, ActionConfig.NONE));
        assertEquals("pinch", config.appAction(APP, 2));
        assertEquals(Set.of(ScopePolicy.SYSTEM), ScopePolicy.required(config));
    }

    @Test public void globalNativePinchPanelChoiceNeedsOnlySystemWhileBatteryKeepsItsOwnHosts() {
        EnhanceConfig config = off();
        StylusGestureConfig rule = config.pinch.withGlobalAction(5);
        config = new EnhanceConfig(config.withStylusGesture(rule));
        assertTrue(ScopePolicy.required(config).isEmpty());
        config = new EnhanceConfig(config.withStylusGesture(rule.withEnabled(true)));
        assertEquals(Set.of(ScopePolicy.SYSTEM), ScopePolicy.required(config));
        EnhanceConfig battery = flag(config, "battery_enabled", true);
        assertEquals(Set.of(ScopePolicy.SYSTEM, Protocol.SYSTEM_UI, Protocol.BLUETOOTH), ScopePolicy.required(battery));
        assertEquals(Set.of(ScopePolicy.SYSTEM), ScopePolicy.required(flag(battery, "battery_enabled", false)));
    }

    @Test public void selectedNativePinchPanelChoiceNeedsOnlySystemWhileTakeoverIsEnabled() {
        EnhanceConfig config = off();
        StylusGestureConfig rule = config.pinch.withGlobalAction(1).withEnabled(true)
                .withPackages(Set.of(APP), true).withAppAction(APP, 5);
        config = new EnhanceConfig(config.withStylusGesture(rule));
        assertEquals(Set.of(ScopePolicy.SYSTEM), ScopePolicy.required(config));
        config = new EnhanceConfig(config.withStylusGesture(rule.withPackages(Set.of(APP), false)));
        assertEquals(5, config.pinch.appAction(APP));
        assertEquals(Set.of(ScopePolicy.SYSTEM), ScopePolicy.required(config));
        config = new EnhanceConfig(config.withStylusGesture(rule.withEnabled(false)));
        assertTrue(ScopePolicy.required(config).isEmpty());
        assertEquals(5, config.pinch.appAction(APP));
    }

    @Test public void nativeDoubleTapNeedsOnlySystem() {
        EnhanceConfig config = off();
        config = new EnhanceConfig(config.withStylusGesture(config.doubleTap.withEnabled(true)));
        assertEquals(Set.of(ScopePolicy.SYSTEM), ScopePolicy.required(config));
    }

    @Test public void scopeSnapshotsAndTheManagedHostSetAreImmutable() {
        EnhanceConfig config = EnhanceConfig.defaults();
        Set<String> required = ScopePolicy.required(config);
        assertThrows(UnsupportedOperationException.class, () -> required.remove(ScopePolicy.SYSTEM));
        assertThrows(UnsupportedOperationException.class, () -> required.add(Protocol.HOME));
        assertThrows(UnsupportedOperationException.class, () -> ScopePolicy.MANAGED.remove(Protocol.HOME));
        assertFalse(required.contains(Protocol.HOME));
        assertTrue(ScopePolicy.MANAGED.contains(Protocol.HOME));
        assertEquals(ScopePolicy.required(config), required);
    }
}
