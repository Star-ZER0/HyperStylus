package cc.star0.xposed.hyperstylus.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import cc.star0.xposed.hyperstylus.config.EnhanceConfig
import cc.star0.xposed.hyperstylus.config.GestureAppConfig
import cc.star0.xposed.hyperstylus.ui.apps.AppEntry
import cc.star0.xposed.hyperstylus.ui.navigation.AppDomain
import cc.star0.xposed.hyperstylus.ui.theme.FocusTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercise the actual new global and application switches through their shared settings pages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SplitDividerSwitchUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun reveal(list: String, tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    @Test
    fun globalDividerSwitchIsReachableAndDoesNotChangeTheCaptionRule() {
        val config = mutableStateOf(EnhanceConfig(EnhanceConfig.defaults().toBundle().apply {
            putBoolean("ignore_stylus_caption", true)
        }))
        compose.setContent {
            FocusTheme {
                GesturesScreen(config.value, onBack = {}, onOpenApps = {},
                    onChange = { config.value = EnhanceConfig(it) })
            }
        }
        reveal("gesture_settings", "ignore_stylus_split_divider").performClick()
        compose.runOnIdle {
            assertTrue(config.value.ignoreStylusSplitDivider)
            assertTrue(config.value.ignoreStylusCaption)
            assertEquals(GestureAppConfig.CAPTION or GestureAppConfig.SPLIT_DIVIDER, config.value.gestureMask())
        }
        reveal("gesture_settings", "ignore_stylus_split_divider").performClick()
        compose.runOnIdle {
            assertFalse(config.value.ignoreStylusSplitDivider)
            assertTrue(config.value.ignoreStylusCaption)
        }
    }

    @Test
    fun applicationDividerSwitchOverridesTheGlobalRuleAndRetainsItsValueWhenDeselected() {
        val pkg = "test.notes"
        var initial = EnhanceConfig(EnhanceConfig.defaults().toBundle().apply {
            putBoolean("ignore_stylus_split_divider", true)
            putBoolean("ignore_stylus_back", true)
        })
        initial = EnhanceConfig(initial.withGestureApps(initial.gestureApps.withEnabled(true)))
        initial = EnhanceConfig(initial.withGestureAppPackages(setOf(pkg, "test.browser"), true))
        initial = EnhanceConfig(initial.withGestureApps(initial.gestureApps.withProfile(pkg, 0)))
        val config = mutableStateOf(initial)
        compose.setContent {
            FocusTheme {
                ApplicationProfileScreen(config.value, AppDomain.GESTURES, AppEntry(pkg, "Notes"),
                    onChange = { config.value = EnhanceConfig(it) }, onBack = {})
            }
        }
        reveal("gesture_app_profile", "gesture_profile_6").performClick()
        compose.runOnIdle {
            assertTrue(config.value.ignoreStylusSplitDivider)
            assertEquals(GestureAppConfig.SPLIT_DIVIDER, config.value.gestureApps.mask(pkg, config.value.gestureMask()))
            assertEquals(GestureAppConfig.BACK or GestureAppConfig.SPLIT_DIVIDER,
                config.value.gestureApps.mask("test.browser", config.value.gestureMask()))
        }
        reveal("gesture_app_profile", "gesture_profile_selected").performClick()
        // The preference wrapper has no Disabled semantic; verify its actual disabled click behavior.
        reveal("gesture_app_profile", "gesture_profile_6").performClick()
        compose.runOnIdle {
            assertEquals(GestureAppConfig.SPLIT_DIVIDER, config.value.gestureApps.profileMask(pkg, 0))
            assertEquals(config.value.gestureMask(), config.value.gestureApps.mask(pkg, config.value.gestureMask()))
        }
        reveal("gesture_app_profile", "gesture_profile_selected").performClick()
        reveal("gesture_app_profile", "gesture_profile_6").performClick()
        compose.runOnIdle {
            assertEquals(0, config.value.gestureApps.mask(pkg, config.value.gestureMask()))
            assertTrue(config.value.ignoreStylusSplitDivider)
        }
    }
}
