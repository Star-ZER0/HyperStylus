package cc.star0.xposed.hyperstylus.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import cc.star0.xposed.hyperstylus.ui.navigation.MainPage
import cc.star0.xposed.hyperstylus.ui.state.ModuleReloadOutcome
import cc.star0.xposed.hyperstylus.ui.state.ModuleStatus
import cc.star0.xposed.hyperstylus.ui.state.ModuleTargetStatus
import cc.star0.xposed.hyperstylus.ui.theme.FocusTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModuleStatusRowsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aSuccessfulHotReloadDoesNotAddAnotherRunningProcessCard() {
        val process = "com.android.systemui"
        compose.setContent {
            FocusTheme {
                MainDestinationScreen(MainPage.MODULE_STATUS, ModuleStatus(connected = true,
                    targets = listOf(ModuleTargetStatus(1, process, 123, 49, "UP_TO_DATE", "LSPosed", 1000)),
                    outcomes = listOf(ModuleReloadOutcome(process, "SUCCEEDED", null, "LSPosed", 1000, 123))),
                    onSelect = {}, onOpenFeature = {}, onRefresh = {}, onReload = {})
            }
        }
        compose.onNodeWithTag("main_status_page").performScrollToNode(hasText(process))
        compose.onAllNodesWithText(process).assertCountEquals(1)
    }

    @Test
    fun aReloadResultIsDisplayedInsideItsExistingProcessRow() {
        val process = "com.android.systemui"
        val detail = "资源清理失败，请重启"
        compose.setContent {
            FocusTheme {
                MainDestinationScreen(MainPage.MODULE_STATUS, ModuleStatus(connected = true,
                    targets = listOf(ModuleTargetStatus(1, process, 123, 48, "FAILED", "LSPosed", 1000)),
                    outcomes = listOf(ModuleReloadOutcome(process, "FAILED", detail, "LSPosed", 1000, 123))),
                    onSelect = {}, onOpenFeature = {}, onRefresh = {}, onReload = {})
            }
        }
        compose.onNodeWithTag("main_status_page").performScrollToNode(hasText(detail))
        compose.onAllNodesWithText(process).assertCountEquals(1)
        compose.onNodeWithText(detail).assertIsDisplayed()
    }

    @Test
    fun auxiliaryProcessesRemainVisibleWithTheirFrameworkCountAndClearClassification() {
        val auxiliary = "com.miui.securitycore.remote"
        compose.setContent {
            FocusTheme {
                MainDestinationScreen(MainPage.MODULE_STATUS, ModuleStatus(connected = true,
                    targets = listOf(
                        ModuleTargetStatus(1, "com.miui.securitycore", 7122, 54, "UP_TO_DATE"),
                        ModuleTargetStatus(2, auxiliary, 7344, 54, "UP_TO_DATE"))),
                    onSelect = {}, onOpenFeature = {}, onRefresh = {}, onReload = {})
            }
        }
        compose.onNodeWithTag("main_status_page").performScrollToNode(hasText("框架已加载模块的进程"))
        compose.onNodeWithText("共 2 个进程，0 个等待重新加载").assertExists()
        compose.onNodeWithTag("main_status_page").performScrollToNode(hasText(auxiliary))
        compose.onNodeWithText(auxiliary).assertIsDisplayed()
        compose.onNodeWithText("非功能目标进程", substring = true).assertIsDisplayed()
    }
}
