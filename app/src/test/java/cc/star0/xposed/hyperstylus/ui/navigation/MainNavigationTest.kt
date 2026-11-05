package cc.star0.xposed.hyperstylus.ui.navigation

import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import cc.star0.xposed.hyperstylus.BuildConfig
import cc.star0.xposed.hyperstylus.R
import cc.star0.xposed.hyperstylus.config.EnhanceConfig
import cc.star0.xposed.hyperstylus.ui.theme.FocusTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Drive the real miuix navigator, keeping feature pages and their editable state reachable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String) {
        val directory = System.getenv("HYPERSTYLUS_PREVIEW_DIR") ?: return
        File(directory, "$name.png").apply { parentFile?.mkdirs() }.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun display(config: MutableState<EnhanceConfig>, size: MutableState<DpSize>? = null) {
        compose.setContent {
            FocusTheme {
                if (size == null) {
                    SettingsNavigator(config.value, catalogRevision = 0,
                        onChange = { config.value = EnhanceConfig(it) })
                } else {
                    val dimensions = size.value
                    Box(Modifier.requiredSize(dimensions.width, dimensions.height)) {
                        SettingsNavigator(config.value, catalogRevision = 0,
                            onChange = { config.value = EnhanceConfig(it) })
                    }
                }
            }
        }
    }

    private fun hidden(tag: String) {
        if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag(tag).assertIsNotDisplayed()
        }
    }

    private fun openFeature(name: String) {
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("open_$name"))
        compose.onNodeWithTag("open_$name").performClick()
    }

    @Test
    fun moduleStatusSettingsAndAboutAreSeparateMainDestinations() {
        display(mutableStateOf(EnhanceConfig.defaults()))
        compose.onNodeWithTag("main_status_page").assertIsDisplayed()
        hidden("main_settings_page")

        compose.onNodeWithTag("main_settings_tab").performClick()
        compose.onNodeWithTag("main_settings_page").assertIsDisplayed()
        compose.onNodeWithTag("open_battery").assertExists()
        compose.onNodeWithTag("open_keys").assertExists()

        compose.onNodeWithTag("main_about_tab").performClick()
        compose.onNodeWithTag("main_about_page").assertIsDisplayed()
        hidden("main_settings_page")

        compose.onNodeWithTag("main_status_tab").performClick()
        compose.onNodeWithTag("main_status_page").assertIsDisplayed()
        hidden("main_about_page")
    }

    @Test
    fun phoneUsesBottomNavigationAndHorizontalSwipesSynchronizeAllThreeTabs() {
        display(mutableStateOf(EnhanceConfig.defaults()))
        compose.onNodeWithTag("main_bottom_navigation").assertIsDisplayed()
        hidden("main_side_navigation")
        compose.onNodeWithTag("main_status_tab").assertIsSelected()
        compose.onNodeWithTag("main_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("main_settings_page").assertIsDisplayed()
        compose.onNodeWithTag("main_settings_tab").assertIsSelected()
        hidden("main_status_page")
        compose.onNodeWithTag("main_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("main_about_page").assertIsDisplayed()
        compose.onNodeWithTag("main_about_tab").assertIsSelected()
        compose.onNodeWithTag("main_pager").performTouchInput { swipeRight() }
        compose.onNodeWithTag("main_settings_page").assertIsDisplayed()
        compose.onNodeWithTag("main_settings_tab").assertIsSelected()
        compose.onNodeWithTag("main_status_tab").performClick()
        compose.onNodeWithTag("main_status_page").assertIsDisplayed()
        compose.onNodeWithTag("main_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("main_settings_page").assertIsDisplayed()
        compose.onNodeWithTag("main_settings_tab").assertIsSelected()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w840dp-h1000dp")
    fun tabletNavigationIsOnTheLeftAndResizeUsesActualAvailableWidthWithoutResettingSelection() {
        val size = mutableStateOf(DpSize(599.dp, 700.dp))
        display(mutableStateOf(EnhanceConfig.defaults()), size)
        compose.onNodeWithTag("main_bottom_navigation").assertIsDisplayed()
        hidden("main_side_navigation")
        compose.onNodeWithTag("main_about_tab").performClick()
        compose.onNodeWithTag("main_about_page").assertIsDisplayed()
        compose.runOnIdle { size.value = DpSize(600.dp, 700.dp) }
        compose.onNodeWithTag("main_side_navigation").assertIsDisplayed()
        hidden("main_bottom_navigation")
        compose.onNodeWithTag("main_about_tab").assertIsSelected()
        compose.onNodeWithTag("main_about_page").assertIsDisplayed()
        val rail = compose.onNodeWithTag("main_side_navigation").fetchSemanticsNode().boundsInRoot
        val pager = compose.onNodeWithTag("main_pager").fetchSemanticsNode().boundsInRoot
        assertTrue("The native rail must occupy the left side of the pager", rail.right <= pager.left + 1f)
        compose.runOnIdle { size.value = DpSize(480.dp, 700.dp) }
        compose.onNodeWithTag("main_bottom_navigation").assertIsDisplayed()
        hidden("main_side_navigation")
        compose.onNodeWithTag("main_about_tab").assertIsSelected()
        compose.onNodeWithTag("main_about_page").assertIsDisplayed()
        compose.runOnIdle { size.value = DpSize(760.dp, 700.dp) }
        compose.onNodeWithTag("main_side_navigation").assertIsDisplayed()
        compose.onNodeWithTag("main_settings_tab").performClick()
        compose.onNodeWithTag("main_settings_page").assertIsDisplayed()
        openFeature("keys")
        capture("settings-keys-tablet")
        compose.onNodeWithTag("key_settings").assertIsDisplayed()
        compose.onNodeWithTag("main_side_navigation").assertIsDisplayed()
        compose.onNodeWithTag("main_settings_tab").assertIsSelected()
        compose.onNodeWithTag("main_about_tab").performClick()
        compose.onNodeWithTag("main_about_page").assertIsDisplayed()
        hidden("key_settings")
        compose.onNodeWithTag("main_side_navigation").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w840dp-h1000dp")
    fun expandedTabletNavigationSurvivesNarrowWindowsAndRestorationAndStillOpensMainPages() {
        val config = mutableStateOf(EnhanceConfig.defaults())
        val size = mutableStateOf(DpSize(600.dp, 700.dp))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            FocusTheme {
                Box(Modifier.requiredSize(size.value.width, size.value.height)) {
                    SettingsNavigator(config.value, 0) { config.value = EnhanceConfig(it) }
                }
            }
        }
        compose.onNodeWithTag("main_about_tab").performTouchInput { click(center) }
        compose.onNodeWithContentDescription("展开导航栏").performTouchInput { click(center) }
        compose.onNodeWithContentDescription("收起导航栏").assertIsDisplayed()
        compose.onNodeWithTag("main_about_tab").assertIsSelected()

        compose.runOnIdle { size.value = DpSize(480.dp, 700.dp) }
        compose.onNodeWithTag("main_bottom_navigation").assertIsDisplayed()
        compose.onNodeWithTag("main_about_tab").assertIsSelected()
        compose.runOnIdle { size.value = DpSize(600.dp, 700.dp) }
        compose.onNodeWithContentDescription("收起导航栏").assertIsDisplayed()
        compose.onNodeWithTag("main_about_tab").assertIsSelected()

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("收起导航栏").assertIsDisplayed()
        compose.onNodeWithTag("main_about_tab").assertIsSelected()
        compose.onNodeWithTag("main_about_page").assertIsDisplayed()
        compose.onNodeWithTag("main_settings_tab").performTouchInput { click(center) }
        openFeature("keys")
        compose.onNodeWithTag("key_settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("收起导航栏").assertIsDisplayed()
        compose.onNodeWithTag("main_about_tab").performTouchInput { click(center) }
        compose.onNodeWithTag("main_about_page").assertIsDisplayed()
        compose.onNodeWithTag("main_about_tab").assertIsSelected()
        hidden("key_settings")
    }

    @Test
    fun bottomNavigationLeavesTheSettingsFooterVisibleAndAllThreeTabsRespondToTouch() {
        display(mutableStateOf(EnhanceConfig.defaults()))
        compose.onNodeWithTag("main_settings_tab").performTouchInput { click(center) }
        val footer = "${compose.activity.getString(R.string.subtitle)} · ${BuildConfig.VERSION_NAME}"
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText(footer))
        compose.onNodeWithText(footer).assertIsDisplayed()
        val footerBounds = compose.onNodeWithText(footer).fetchSemanticsNode().boundsInRoot
        val barBounds = compose.onNodeWithTag("main_bottom_navigation").fetchSemanticsNode().boundsInRoot
        val pagerBounds = compose.onNodeWithTag("main_pager").fetchSemanticsNode().boundsInRoot
        assertEquals("The bottom bar must span the window", pagerBounds.width, barBounds.width, 1f)
        assertEquals("The bottom bar must sit at the bottom edge", pagerBounds.bottom, barBounds.bottom, 1f)
        assertTrue("The last settings row must remain above the bottom navigation", footerBounds.bottom <= barBounds.top)

        compose.onNodeWithTag("main_about_tab").performTouchInput { click(androidx.compose.ui.geometry.Offset(width - 2f, center.y)) }
        compose.onNodeWithTag("main_about_page").assertIsDisplayed()
        compose.onNodeWithTag("main_about_tab").assertIsSelected()
        compose.onNodeWithTag("main_status_tab").performTouchInput { click(androidx.compose.ui.geometry.Offset(2f, center.y)) }
        compose.onNodeWithTag("main_status_page").assertIsDisplayed()
        compose.onNodeWithTag("main_status_tab").assertIsSelected()
        compose.onNodeWithTag("main_settings_tab").performTouchInput { click(center) }
        compose.onNodeWithTag("main_settings_page").assertIsDisplayed()
        compose.onNodeWithTag("main_settings_tab").assertIsSelected()
        compose.onNodeWithText(footer).assertIsDisplayed()
    }

    @Test
    fun settingsHaveNoIntroBannerAndAllFourFeaturesStayReachable() {
        display(mutableStateOf(EnhanceConfig.defaults()))
        compose.onNodeWithTag("main_settings_tab").performClick()
        compose.onAllNodes(hasText("电量一眼可见，按键随你设定。") and
            hasAnyAncestor(hasTestTag("main_settings_page"))).assertCountEquals(0)
        val battery = compose.onNodeWithTag("open_battery").fetchSemanticsNode().boundsInRoot
        val keys = compose.onNodeWithTag("open_keys").fetchSemanticsNode().boundsInRoot
        assertTrue("Battery is the first settings card", battery.top < keys.top)
        capture("settings-light")
        listOf("battery" to "battery_settings", "keys" to "key_settings",
            "gestures" to "gesture_settings", "brush" to "brush_settings").forEach { (entry, page) ->
            openFeature(entry)
            compose.onNodeWithTag(page).assertIsDisplayed()
            compose.onNodeWithTag("subpage_back").performClick()
            compose.onNodeWithTag("main_settings_page").assertIsDisplayed()
            compose.onNodeWithTag("main_settings_tab").assertIsSelected()
        }
    }

    @Test
    fun everyExistingFeaturePageReturnsToTheSettingsDestination() {
        display(mutableStateOf(EnhanceConfig.defaults()))
        compose.onNodeWithTag("main_settings_tab").performClick()
        listOf("battery" to "battery_settings", "keys" to "key_settings",
            "gestures" to "gesture_settings", "brush" to "brush_settings").forEach { (entry, page) ->
            openFeature(entry)
            compose.onNodeWithTag(page).assertExists()
            compose.onNodeWithTag("subpage_back").performClick()
            compose.onNodeWithTag("main_settings_page").assertIsDisplayed()
        }
    }

    @Test
    fun aRapidFeatureDoubleTapDoesNotCreateDuplicateNavigationKeys() {
        display(mutableStateOf(EnhanceConfig.defaults()))
        compose.onNodeWithTag("main_settings_tab").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("open_keys"))
        compose.onNodeWithTag("open_keys").performTouchInput { doubleClick(center) }
        compose.onNodeWithTag("key_settings").assertIsDisplayed()
        compose.onNodeWithTag("subpage_back").performClick()
        compose.onNodeWithTag("main_settings_page").assertIsDisplayed()
    }

    @Test
    fun theSelectedRootAndFeatureRouteSurviveSavedStateRestoration() {
        val config = mutableStateOf(EnhanceConfig.defaults())
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            FocusTheme { SettingsNavigator(config.value, 0) { config.value = EnhanceConfig(it) } }
        }
        compose.onNodeWithTag("main_settings_tab").performClick()
        openFeature("gestures")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("gesture_settings").assertIsDisplayed()
        compose.onNodeWithTag("subpage_back").performClick()
        compose.onNodeWithTag("main_settings_page").assertIsDisplayed()
    }

    @Test
    fun editingAFeatureSurvivesSwitchingThroughTheOtherMainPages() {
        val config = mutableStateOf(EnhanceConfig.defaults())
        val previous = config.value.proSettings
        display(config)
        compose.onNodeWithTag("main_settings_tab").performClick()
        openFeature("keys")
        compose.onNodeWithTag("key_settings").performScrollToNode(hasTestTag("pro_settings"))
        compose.onNodeWithTag("pro_settings").performClick()
        compose.runOnIdle { assertEquals(!previous, config.value.proSettings) }
        compose.onNodeWithTag("subpage_back").performClick()

        compose.onNodeWithTag("main_about_tab").performClick()
        compose.onNodeWithTag("main_about_page").assertIsDisplayed()
        compose.onNodeWithTag("main_status_tab").performClick()
        compose.onNodeWithTag("main_status_page").assertIsDisplayed()
        compose.onNodeWithTag("main_settings_tab").performClick()
        openFeature("keys")
        compose.onNodeWithTag("pro_settings").assertExists()
        compose.runOnIdle { assertEquals(!previous, config.value.proSettings) }
    }
}
