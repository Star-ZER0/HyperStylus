package cc.star0.xposed.hyperstylus.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import cc.star0.xposed.hyperstylus.ui.navigation.MainPage
import cc.star0.xposed.hyperstylus.ui.state.ModuleStatus
import cc.star0.xposed.hyperstylus.ui.state.ModuleTargetStatus
import cc.star0.xposed.hyperstylus.ui.theme.FocusTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** UI reload eligibility follows the reported target state without fabricating service handles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModuleStatusScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun reloadOnlyOffersReportedPendingTargetsAndIsDisabledDuringAnActiveRequest() {
        val status = mutableStateOf(ModuleStatus())
        var refreshed = 0
        var reloaded = 0
        compose.setContent {
            FocusTheme {
                MainDestinationScreen(MainPage.MODULE_STATUS, status.value, {}, {},
                    onRefresh = { refreshed++ }, onReload = { reloaded++ })
            }
        }
        compose.onNodeWithTag("module_reload").assertIsNotEnabled()
        compose.onNodeWithTag("module_refresh").performClick()
        compose.runOnIdle {
            assertEquals(1, refreshed)
            status.value = ModuleStatus(connected = true, frameworkApi = 102,
                targets = listOf(ModuleTargetStatus(1, "com.android.systemui", 1234, 43, "STALE")))
        }
        compose.onNodeWithTag("module_reload").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, reloaded); status.value = status.value.copy(reloading = true) }
        compose.onNodeWithTag("module_reload").assertIsNotEnabled()
        compose.onNodeWithTag("module_refresh").assertIsNotEnabled()
        compose.runOnIdle { status.value = status.value.copy(reloading = false,
            targets = listOf(ModuleTargetStatus(2, "com.android.systemui", 1234, 44, "UP_TO_DATE"))) }
        compose.onNodeWithTag("module_reload").assertIsNotEnabled()
    }
}
