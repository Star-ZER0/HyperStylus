package cc.star0.xposed.hyperstylus.ui.state

import org.junit.Assert.*

import cc.star0.xposed.hyperstylus.config.EnhanceConfig
import cc.star0.xposed.hyperstylus.config.GestureAppConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the edits emitted by shared UI controls against immutable global and app snapshots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GestureSettingsEditsTest {
    @Test
    fun enablingTheGlobalIslandRuleDoesNotReplaceAnExistingIndependentAppProfile() {
        var config = EnhanceConfig(GestureSettingsEdits.global(EnhanceConfig.defaults(), StylusGestureFeature.BACK, true))
        config = EnhanceConfig(config.withGestureApps(config.gestureApps.withEnabled(true)))
        config = EnhanceConfig(config.withGestureAppPackages(setOf("app.notes"), true))
        config = EnhanceConfig(GestureSettingsEdits.global(config, StylusGestureFeature.FOCUS_NOTIFICATION, true))
        assertTrue(config.ignoreStylusFocusNotification)
        assertTrue(config.ignoreStylusBack)
        assertEquals(GestureAppConfig.BACK or GestureAppConfig.FOCUS_NOTIFICATION, config.gestureMask())
        assertEquals(GestureAppConfig.BACK, config.gestureApps.mask("app.notes", config.gestureMask()))
        assertEquals(GestureAppConfig.BACK or GestureAppConfig.FOCUS_NOTIFICATION,
            config.gestureApps.mask("app.other", config.gestureMask()))
    }

    @Test
    fun editingTheAppIslandRulePreservesGlobalAndOtherAppRulesAndCanBeDisabledAgain() {
        var config = EnhanceConfig(GestureSettingsEdits.global(EnhanceConfig.defaults(), StylusGestureFeature.BACK, true))
        config = EnhanceConfig(config.withGestureApps(config.gestureApps.withEnabled(true)))
        config = EnhanceConfig(config.withGestureAppPackages(setOf("app.notes", "app.browser"), true))
        config = EnhanceConfig(GestureSettingsEdits.appProfile(config, "app.notes", StylusGestureFeature.FOCUS_NOTIFICATION, true))
        assertFalse(config.ignoreStylusFocusNotification)
        assertEquals(GestureAppConfig.BACK, config.gestureMask())
        assertEquals(GestureAppConfig.BACK or GestureAppConfig.FOCUS_NOTIFICATION,
            config.gestureApps.mask("app.notes", config.gestureMask()))
        assertEquals(GestureAppConfig.BACK, config.gestureApps.mask("app.browser", config.gestureMask()))
        config = EnhanceConfig(GestureSettingsEdits.appProfile(config, "app.notes", StylusGestureFeature.FOCUS_NOTIFICATION, false))
        assertEquals(GestureAppConfig.BACK, config.gestureApps.mask("app.notes", config.gestureMask()))
        assertTrue("Disabling a feature keeps the independent application profile selected", "app.notes" in config.gestureApps.selectedPackages())
    }

    @Test
    fun globalDividerEditsLeaveExistingProfilesIndependentAndAppEditsRetainOtherBits() {
        var config = EnhanceConfig(GestureSettingsEdits.global(EnhanceConfig.defaults(), StylusGestureFeature.CAPTION, true))
        config = EnhanceConfig(config.withGestureApps(config.gestureApps.withEnabled(true)))
        config = EnhanceConfig(config.withGestureAppPackages(setOf("app.notes", "app.browser"), true))
        config = EnhanceConfig(GestureSettingsEdits.global(config, StylusGestureFeature.SPLIT_DIVIDER, true))
        assertTrue(config.ignoreStylusSplitDivider)
        assertTrue(config.ignoreStylusCaption)
        assertEquals(GestureAppConfig.CAPTION, config.gestureApps.mask("app.notes", config.gestureMask()))
        config = EnhanceConfig(GestureSettingsEdits.appProfile(config, "app.notes", StylusGestureFeature.SPLIT_DIVIDER, true))
        assertEquals(GestureAppConfig.CAPTION or GestureAppConfig.SPLIT_DIVIDER,
            config.gestureApps.mask("app.notes", config.gestureMask()))
        assertEquals(GestureAppConfig.CAPTION, config.gestureApps.mask("app.browser", config.gestureMask()))
        config = EnhanceConfig(GestureSettingsEdits.appProfile(config, "app.notes", StylusGestureFeature.SPLIT_DIVIDER, false))
        assertEquals(GestureAppConfig.CAPTION, config.gestureApps.mask("app.notes", config.gestureMask()))
        assertTrue(config.ignoreStylusSplitDivider)
        assertTrue("app.notes" in config.gestureApps.selectedPackages())
    }

    @Test
    fun sidebarEditsPreserveGlobalAndOtherApplicationBitsIncludingADeselectedSavedProfile() {
        var config = EnhanceConfig(GestureSettingsEdits.global(EnhanceConfig.defaults(), StylusGestureFeature.CAPTION, true))
        config = EnhanceConfig(config.withGestureApps(config.gestureApps.withEnabled(true)))
        config = EnhanceConfig(config.withGestureAppPackages(setOf("app.notes", "app.browser"), true))
        config = EnhanceConfig(GestureSettingsEdits.global(config, StylusGestureFeature.SIDEBAR, true))
        assertTrue(config.ignoreStylusSidebar)
        assertEquals(GestureAppConfig.CAPTION, config.gestureApps.mask("app.notes", config.gestureMask()))
        config = EnhanceConfig(GestureSettingsEdits.appProfile(config, "app.notes", StylusGestureFeature.SIDEBAR, true))
        assertEquals(GestureAppConfig.CAPTION or GestureAppConfig.SIDEBAR,
            config.gestureApps.mask("app.notes", config.gestureMask()))
        assertEquals(GestureAppConfig.CAPTION, config.gestureApps.mask("app.browser", config.gestureMask()))
        config = EnhanceConfig(config.withGestureAppPackages(setOf("app.notes"), false))
        assertEquals(GestureAppConfig.CAPTION or GestureAppConfig.SIDEBAR, config.gestureApps.profileMask("app.notes", 0))
        config = EnhanceConfig(config.withGestureAppPackages(setOf("app.notes"), true))
        config = EnhanceConfig(GestureSettingsEdits.appProfile(config, "app.notes", StylusGestureFeature.SIDEBAR, false))
        assertEquals(GestureAppConfig.CAPTION, config.gestureApps.mask("app.notes", config.gestureMask()))
        assertTrue(config.ignoreStylusSidebar)
        assertTrue(config.ignoreStylusCaption)
    }
}
