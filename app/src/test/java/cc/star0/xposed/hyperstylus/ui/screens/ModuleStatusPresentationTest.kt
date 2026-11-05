package cc.star0.xposed.hyperstylus.ui.screens

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import cc.star0.xposed.hyperstylus.ui.navigation.MainPage
import cc.star0.xposed.hyperstylus.ui.state.ModuleReloadOutcome
import cc.star0.xposed.hyperstylus.ui.state.ModuleScopeStatus
import cc.star0.xposed.hyperstylus.ui.state.ModuleStatus
import cc.star0.xposed.hyperstylus.ui.state.ModuleTargetStatus
import cc.star0.xposed.hyperstylus.ui.theme.FocusTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModuleStatusPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String) {
        val directory = System.getenv("HYPERSTYLUS_PREVIEW_DIR") ?: return
        File(directory, "$name.png").apply { parentFile?.mkdirs() }.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun display(status: ModuleStatus) {
        compose.setContent {
            FocusTheme { MainDestinationScreen(MainPage.MODULE_STATUS, status, {}, {}, {}, {}) }
        }
    }

    @Test
    fun sameProcessAcrossFrameworksKeepsItsOwnReloadResult() {
        val process = "com.android.systemui"
        display(ModuleStatus(connected = true, frameworkName = "LSPosed · Other", frameworkApi = 102,
            targets = listOf(
                ModuleTargetStatus(1, process, 123, 54, "UP_TO_DATE", "LSPosed", 1000),
                ModuleTargetStatus(2, process, 123, 53, "STALE", "Other", 1000)),
            outcomes = listOf(ModuleReloadOutcome(process, "UNSUPPORTED", "此框架不支持热重载", "Other", 1000, 123))))
        capture("module-status-light")
        compose.onNodeWithTag("main_status_page").performScrollToNode(hasTestTag("module_target_1"))
        compose.onAllNodes(hasText("此框架不支持热重载") and hasAnyAncestor(hasTestTag("module_target_1"))).assertCountEquals(0)
        compose.onNodeWithTag("main_status_page").performScrollToNode(hasText("此框架不支持热重载"))
        compose.onNode(hasText("此框架不支持热重载") and hasAnyAncestor(hasTestTag("module_target_2"))).assertIsDisplayed()
        compose.onNodeWithText("重载结果：不支持重新加载").assertIsDisplayed()
        capture("module-process-results")
    }

    @Test
    fun oneFrameworkGrantNeverHidesAnotherFrameworkRejection() {
        display(ModuleStatus(connected = true, frameworkName = "LSPosed · Other", frameworkApi = 102,
            scope = setOf("system"), desiredScope = setOf("system"), scopeChanges = listOf(
                ModuleScopeStatus("LSPosed", setOf("system"), setOf("system"), emptySet(), emptySet(), false, null),
                ModuleScopeStatus("Other", emptySet(), setOf("system"), emptySet(), setOf("system"), false, "请求被拒绝"))))
        compose.onNodeWithTag("main_status_page").performScrollToNode(hasTestTag("module_scope_LSPosed"))
        compose.onNode(hasText("已授权") and hasAnyAncestor(hasTestTag("module_scope_LSPosed"))).assertExists()
        compose.onNodeWithTag("main_status_page").performScrollToNode(hasTestTag("module_scope_Other"))
        compose.onNode(hasText("未批准") and hasAnyAncestor(hasTestTag("module_scope_Other"))).assertExists()
        compose.onNodeWithText("请求被拒绝").assertIsDisplayed()
        compose.onNodeWithTag("main_status_page").performScrollToNode(hasTestTag("scope_retry"))
        compose.onNodeWithTag("scope_retry").assertIsEnabled()
        capture("module-scope")
    }

    @Test
    fun resultsForExitedProcessesRetainTheirIdentityAndDoNotEnableReload() {
        display(ModuleStatus(connected = true, frameworkName = "LSPosed", frameworkApi = 102,
            outcomes = listOf(ModuleReloadOutcome("com.miui.home", "PROCESS_DIED", null, "LSPosed", 10123, 7312))))
        compose.onNodeWithTag("module_reload").assertIsNotEnabled()
        compose.onNodeWithTag("main_status_page").performScrollToNode(hasText("重载结果：进程已退出"))
        compose.onNodeWithText("LSPosed · PID 7312 · UID 10123").assertIsDisplayed()
        compose.onNodeWithText("重载结果：进程已退出").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp-night")
    fun largeTextKeepsBothActionsReachableAndPreventsDisconnectedReload() {
        var refreshed = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.7f)) {
                FocusTheme {
                    MainDestinationScreen(MainPage.MODULE_STATUS, ModuleStatus(), {}, {}, { refreshed++ }, {})
                }
            }
        }
        compose.onNodeWithTag("main_status_page").performScrollToNode(hasTestTag("module_refresh"))
        compose.onNodeWithTag("module_refresh").performClick()
        compose.runOnIdle { assertEquals(1, refreshed) }
        compose.onNodeWithTag("main_status_page").performScrollToNode(hasTestTag("module_reload"))
        compose.onNodeWithTag("module_reload").assertIsNotEnabled()
        capture("module-large-text-dark")
    }
}
