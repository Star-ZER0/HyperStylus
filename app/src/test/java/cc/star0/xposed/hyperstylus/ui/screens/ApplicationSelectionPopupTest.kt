package cc.star0.xposed.hyperstylus.ui.screens

import android.view.View

import cc.star0.xposed.hyperstylus.R
import cc.star0.xposed.hyperstylus.config.EnhanceConfig
import cc.star0.xposed.hyperstylus.config.StylusGestureConfig
import cc.star0.xposed.hyperstylus.ui.apps.AppCatalogResult
import cc.star0.xposed.hyperstylus.ui.apps.AppEntry
import cc.star0.xposed.hyperstylus.ui.apps.AppSortOrder
import cc.star0.xposed.hyperstylus.ui.navigation.AppDomain
import cc.star0.xposed.hyperstylus.ui.theme.FocusTheme
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** Exercise native cascading rows against smaller Scaffold, resize, transient-zero and IME bounds. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApplicationSelectionPopupTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Screen {
        val size = mutableStateOf(DpSize(300.dp, 360.dp))
        val config = mutableStateOf(EnhanceConfig.defaults())
        val showSystem = mutableStateOf(false)
        val sort = mutableStateOf(AppSortOrder.NAME)
        val reversed = mutableStateOf(false)
        val configuredFirst = mutableStateOf(true)
        val domain = mutableStateOf(AppDomain.KEYS)
        val catalog = mutableStateOf(AppCatalogResult(listOf(AppEntry("test.notes", "Notes"),
            AppEntry("test.browser", "Browser"), AppEntry("test.system", "System", system = true),
            AppEntry("test.system.other", "Other system app", system = true))))
        val search = TextFieldState()
        var openedPackage: String? = null
        lateinit var view: View
    }

    private fun display(screen: Screen) {
        compose.setContent {
            FocusTheme {
                val size = screen.size.value
                Box(Modifier.requiredSize(size.width, size.height).testTag("test_app_scaffold")) {
                    val view = LocalView.current
                    SideEffect { screen.view = view }
                    ApplicationSelectionScreen(
                        config = screen.config.value,
                        domain = screen.domain.value,
                        catalog = screen.catalog.value,
                        search = screen.search, scroll = rememberLazyListState(),
                        showSystem = screen.showSystem.value, onShowSystem = { screen.showSystem.value = it },
                        onChange = { screen.config.value = EnhanceConfig(it) }, onBack = {},
                        onOpenProfile = { screen.openedPackage = it }, onRequestPermission = {}, onRetry = {},
                        iconRevision = 0, isRefreshing = false,
                        sortOrder = screen.sort.value, reversed = screen.reversed.value,
                        onSortChange = { screen.sort.value = it }, onReverseChange = { screen.reversed.value = it },
                        sortConfiguredFirst = screen.configuredFirst.value,
                        onSortConfiguredFirstChange = { screen.configuredFirst.value = it },
                    )
                }
            }
        }
    }

    private val placed = SemanticsMatcher("placed accessibility target") { node -> node.layoutInfo.isPlaced }

    private fun option(resource: Int): SemanticsNodeInteraction =
        compose.onNode(hasText(compose.activity.getString(resource)) and placed)

    private fun openOptions() {
        compose.onNodeWithTag("app_filter").performClick()
        compose.onNodeWithTag("app_options_popup").assertIsDisplayed()
    }

    private fun openSort() {
        openOptions()
        option(R.string.apps_sort).performClick()
        option(R.string.apps_sort_package).assertExists()
    }

    private fun collapseSort() {
        val title = compose.activity.getString(R.string.apps_sort)
        compose.onNode(hasText(title) and hasClickAction() and isSelected() and placed).performClick()
        option(R.string.apps_sort_package).assertDoesNotExist()
    }

    private fun dismissOptions() {
        // Native outside-tap behavior collapses an open submenu before dismissing the primary.
        repeat(2) {
            if (compose.onAllNodesWithTag("app_options_popup").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("app_options_popup").performTouchInput { click(Offset(1f, 1f)) }
            }
        }
        compose.onNodeWithTag("app_options_popup").assertDoesNotExist()
    }

    private fun dispatchIme(screen: Screen, heightDp: Int) {
        compose.runOnIdle {
            val bottom = (heightDp * screen.view.resources.displayMetrics.density).roundToInt()
            val ime = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
                .setVisible(WindowInsetsCompat.Type.ime(), heightDp > 0)
                .build()
            ViewCompat.dispatchApplyWindowInsets(screen.view, ime)
        }
    }

    @Test
    fun oneMoreButtonOpensSortingAndSystemApplicationControls() {
        val screen = Screen()
        display(screen)
        compose.onNodeWithTag("app_sort").assertDoesNotExist()
        openSort()
        option(R.string.apps_sort_package).performScrollTo().performClick().assertIsSelected()
        option(R.string.apps_sort_reverse).performScrollTo().performClick().assertIsSelected()
        option(R.string.apps_sort_configured_first).performScrollTo().performClick().assertIsNotSelected()
        compose.runOnIdle {
            assertEquals(AppSortOrder.PACKAGE, screen.sort.value)
            assertTrue(screen.reversed.value)
            assertFalse(screen.configuredFirst.value)
        }
        collapseSort()
        option(R.string.show_system_apps).performScrollTo().performClick()
        compose.onNodeWithTag("app_options_popup").assertDoesNotExist()
        compose.runOnIdle { assertTrue(screen.showSystem.value) }
    }

    @Test
    fun submenuAccessibilityTargetsAreUniqueWhileTheNativeMeasurementProbeIsUnplaced() {
        val screen = Screen()
        display(screen)
        openSort()
        val rows = compose.onAllNodesWithText(compose.activity.getString(R.string.apps_sort_package)).fetchSemanticsNodes()
        assertEquals("Only the real submenu row is laid out for accessibility", 1, rows.count { it.layoutInfo.isPlaced })
        assertTrue("Native size probes are retained but never placed", rows.any { !it.layoutInfo.isPlaced })
        option(R.string.apps_sort_package).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(AppSortOrder.PACKAGE, screen.sort.value) }
        dismissOptions()
    }

    @Test
    fun primaryAndSecondaryClickableRowsKeepTheSameWidthInsideASmallerScaffold() {
        val screen = Screen()
        screen.size.value = DpSize(220.dp, 300.dp)
        display(screen)
        openOptions()
        val primary = option(R.string.apps_sort).fetchSemanticsNode().boundsInRoot
        val safeBounds = compose.onNodeWithTag("app_options_popup").fetchSemanticsNode().boundsInRoot
        option(R.string.apps_sort).performClick()
        val secondary = option(R.string.apps_sort_package).performScrollTo().fetchSemanticsNode().boundsInRoot
        assertEquals(primary.width, secondary.width, 1f)
        assertTrue(secondary.left >= safeBounds.left - 1f)
        assertTrue(secondary.right <= safeBounds.right + 1f)
        // Use the trailing edge too: matching drawn width must also produce a complete click target.
        option(R.string.apps_sort_package).performTouchInput { click(Offset(width - 2f, center.y)) }
        compose.runOnIdle { assertEquals(AppSortOrder.PACKAGE, screen.sort.value) }
        dismissOptions()
    }

    @Test
    fun anOpenMenuRestoresItsIntentThroughLandscapeNarrowAndZeroSizeChanges() {
        val screen = Screen()
        display(screen)
        openSort()
        option(R.string.apps_sort_reverse).performScrollTo().performClick()
        compose.runOnIdle { screen.size.value = DpSize(300.dp, 210.dp) }
        compose.onNodeWithTag("app_options_popup").assertIsDisplayed()
        option(R.string.apps_sort).performClick()
        option(R.string.apps_sort_configured_first).performScrollTo().performClick()
        compose.runOnIdle { screen.size.value = DpSize(0.dp, 0.dp) }
        compose.onNodeWithTag("app_options_popup").assertDoesNotExist()
        compose.runOnIdle { screen.size.value = DpSize(220.dp, 300.dp) }
        compose.onNodeWithTag("app_options_popup").assertIsDisplayed()
        option(R.string.apps_sort).performClick()
        option(R.string.apps_sort_name).performScrollTo().performClick()
        collapseSort()
        option(R.string.show_system_apps).performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(screen.reversed.value)
            assertFalse(screen.configuredFirst.value)
            assertTrue(screen.showSystem.value)
        }
    }

    @Test
    fun theMoreControlStillWorksWhileImeInsetsReduceAvailableSpace() {
        val screen = Screen()
        screen.size.value = DpSize(300.dp, 560.dp)
        display(screen)
        openSort()
        val before = compose.onNodeWithTag("app_options_popup").fetchSemanticsNode().boundsInRoot
        dispatchIme(screen, 280)
        val after = compose.onNodeWithTag("app_options_popup").fetchSemanticsNode().boundsInRoot
        assertTrue("IME must reduce the real popup host", after.bottom < before.bottom - 1f)
        option(R.string.apps_sort).performClick()
        option(R.string.apps_sort_reverse).performScrollTo().assertIsDisplayed().performClick()
        collapseSort()
        option(R.string.show_system_apps).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(screen.showSystem.value) }
        dispatchIme(screen, 0)
        openOptions()
        option(R.string.enable_system_apps).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(screen.config.value.appEnabled("test.system"))
            assertTrue(screen.config.value.appEnabled("test.system.other"))
        }
    }

    @Test
    fun bulkSystemSelectionLivesInMoreAndUpdatesEveryApplicationRuleDomain() {
        val screen = Screen()
        display(screen)
        compose.onNodeWithTag("enable_system_apps").assertDoesNotExist()
        AppDomain.entries.forEach { domain ->
            compose.runOnIdle { screen.domain.value = domain }
            openOptions()
            option(R.string.enable_system_apps).performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithTag("app_options_popup").assertDoesNotExist()
            compose.runOnIdle {
                val selected = when (domain) {
                    AppDomain.KEYS -> screen.config.value.appPackages().filterTo(HashSet()) { screen.config.value.appEnabled(it) }
                    AppDomain.GESTURES -> screen.config.value.gestureApps.selectedPackages()
                    AppDomain.PINCH -> screen.config.value.gesture(StylusGestureConfig.Kind.PINCH).selectedPackages()
                    AppDomain.DOUBLE_TAP -> screen.config.value.gesture(StylusGestureConfig.Kind.DOUBLE_TAP).selectedPackages()
                }
                assertEquals(setOf("test.system", "test.system.other"), selected)
                assertNull(screen.openedPackage)
            }
        }
    }

    @Test
    fun unavailableCatalogsDisableBulkSelectionAndKeepTheSavedRule() {
        val screen = Screen()
        screen.config.value = EnhanceConfig(screen.config.value.withAppsEnabled(setOf("test.system"), true))
        display(screen)
        val apps = screen.catalog.value.apps
        listOf(AppCatalogResult(apps, accessDenied = true), AppCatalogResult(apps, failed = true),
            AppCatalogResult(apps.filterNot { it.system })).forEach { catalog ->
            compose.runOnIdle { screen.catalog.value = catalog }
            openOptions()
            option(R.string.enable_system_apps).performScrollTo().assertIsNotEnabled()
                .performTouchInput { click(center) }
            dismissOptions()
            compose.runOnIdle {
                assertTrue(screen.config.value.appEnabled("test.system"))
                assertFalse(screen.config.value.appEnabled("test.system.other"))
            }
        }
    }

    @Test
    fun theCheckboxEditsTheRuleWithoutNavigating() {
        val screen = Screen()
        display(screen)
        compose.onNodeWithTag("app_list").performScrollToNode(hasTestTag("app_enabled_test.notes"))
        compose.onNodeWithTag("app_enabled_test.notes").performTouchInput { click(center) }
        compose.runOnIdle {
            assertTrue(screen.config.value.appEnabled("test.notes"))
            assertNull(screen.openedPackage)
        }
        compose.onNodeWithTag("app_open_test.notes").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals("test.notes", screen.openedPackage) }
    }

    @Test
    fun expandedSearchKeepsMatchingRowsDisplayedWhileMenusOpenAndClose() {
        val screen = Screen()
        display(screen)
        compose.onNodeWithTag("app_search_bar").assertExists()
        compose.onNodeWithTag("app_search").performClick().performTextInput("Notes")
        compose.onNodeWithTag("app_search_cancel").assertExists()
        compose.onNodeWithTag("app_open_test.notes").assertIsDisplayed()
        openSort()
        dismissOptions()
        compose.onNodeWithTag("app_open_test.notes").assertIsDisplayed()
        openOptions()
        option(R.string.show_system_apps).performClick()
        compose.onNodeWithTag("app_open_test.notes").assertIsDisplayed()
        compose.onNodeWithTag("app_search_cancel").performClick()
        compose.runOnIdle { assertEquals("", screen.search.text.toString()) }
        compose.onNodeWithTag("app_list").performScrollToNode(hasTestTag("app_open_test.notes"))
        compose.onNodeWithTag("app_open_test.notes").assertIsDisplayed()
    }
}
