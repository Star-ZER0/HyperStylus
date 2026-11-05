package cc.star0.xposed.hyperstylus.ui.screens

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import cc.star0.xposed.hyperstylus.config.ActionConfig
import cc.star0.xposed.hyperstylus.config.EnhanceConfig
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

/** Feature switches retain editable profiles while controlling their native miuix editors. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeatureSwitchesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun keys(config: MutableState<EnhanceConfig>) {
        compose.setContent {
            FocusTheme {
                KeysScreen(config.value, onBack = {}, onOpenApps = {},
                    onChange = { config.value = EnhanceConfig(it) })
            }
        }
    }

    private fun battery(config: MutableState<EnhanceConfig>) {
        compose.setContent {
            FocusTheme {
                BatteryScreen(config.value, onBack = {},
                    onChange = { config.value = EnhanceConfig(it) })
            }
        }
    }

    private fun reveal(list: String, tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    @Test
    fun threeButtonSwitchesAreIndependentAndRestoreTheirEditors() {
        val config = mutableStateOf(EnhanceConfig.defaults())
        keys(config)
        repeat(3) { button ->
            reveal("key_settings", EnhanceConfig.KEY_ENABLED_KEYS[button]).performClick()
            reveal("key_settings", "key_mode_$button").assertIsNotEnabled()
            reveal("key_settings", ActionConfig.KEYS[button * 2]).assertIsNotEnabled()
            reveal("key_settings", ActionConfig.KEYS[button * 2 + 1]).assertIsNotEnabled()
            compose.runOnIdle {
                repeat(3) { other -> assertEquals(other > button, config.value.keyEnabled(other)) }
                ActionConfig.DEFAULTS.forEachIndexed { binding, action ->
                    assertEquals(action, config.value.action(binding))
                }
            }
        }
        reveal("key_settings", EnhanceConfig.KEY_ENABLED_KEYS[0]).performClick()
        reveal("key_settings", "key_mode_0").assertIsEnabled()
        reveal("key_settings", ActionConfig.KEYS[0]).assertIsEnabled()
        reveal("key_settings", ActionConfig.KEYS[1]).assertIsEnabled()
        compose.runOnIdle {
            assertTrue(config.value.keyEnabled(0))
            assertFalse(config.value.keyEnabled(1))
            assertFalse(config.value.keyEnabled(2))
        }
    }

    @Test
    fun disablingFullMappingPreservesItsTargetAndSavedPressActions() {
        val target = ActionConfig.customKey(ActionConfig.KEYBOARD, KeyEvent.KEYCODE_SPACE)
        val config = mutableStateOf(EnhanceConfig(EnhanceConfig.defaults().toBundle().apply {
            putString(ActionConfig.FULL_KEYS[0], target)
            putString(ActionConfig.KEYS[0], "volume_up")
            putString(ActionConfig.KEYS[1], "back")
        }))
        keys(config)
        reveal("key_settings", EnhanceConfig.KEY_ENABLED_KEYS[0]).performClick()
        reveal("key_settings", "key_mode_0").assertIsNotEnabled()
        reveal("key_settings", ActionConfig.FULL_KEYS[0]).assertIsNotEnabled()
        reveal("key_settings", ActionConfig.KEYS[0]).assertIsNotEnabled()
        reveal("key_settings", ActionConfig.KEYS[1]).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(target, config.value.fullAction(0))
            assertEquals("volume_up", config.value.action(0))
            assertEquals("back", config.value.action(1))
        }
        reveal("key_settings", EnhanceConfig.KEY_ENABLED_KEYS[0]).performClick()
        reveal("key_settings", "key_mode_0").assertIsEnabled()
        reveal("key_settings", ActionConfig.FULL_KEYS[0]).assertIsEnabled()
        reveal("key_settings", ActionConfig.KEYS[0]).assertIsNotEnabled()
        reveal("key_settings", ActionConfig.KEYS[1]).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(target, config.value.fullAction(0)) }
    }

    @Test
    fun applicationEditorsHonorTheGlobalButtonSwitchWithoutDuplicatingIt() {
        val pkg = "test.notes"
        val disabled = EnhanceConfig(EnhanceConfig.defaults().toBundle().apply {
            putBoolean(EnhanceConfig.KEY_ENABLED_KEYS[0], false)
        })
        val config = mutableStateOf(EnhanceConfig(disabled.withAppEnabled(pkg, true)))
        compose.setContent {
            FocusTheme {
                ApplicationProfileScreen(config.value, AppDomain.KEYS, AppEntry(pkg, "Notes"),
                    onChange = { config.value = EnhanceConfig(it) }, onBack = {})
            }
        }
        reveal("app_profile", "key_mode_0").assertIsNotEnabled()
        reveal("app_profile", ActionConfig.KEYS[0]).assertIsNotEnabled()
        reveal("app_profile", ActionConfig.KEYS[1]).assertIsNotEnabled()
        EnhanceConfig.KEY_ENABLED_KEYS.forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        reveal("app_profile", "key_mode_1").assertIsEnabled()
        compose.runOnIdle {
            config.value = EnhanceConfig(config.value.toBundle().apply {
                putBoolean(EnhanceConfig.KEY_ENABLED_KEYS[0], true)
            })
        }
        reveal("app_profile", "key_mode_0").assertIsEnabled()
        reveal("app_profile", ActionConfig.KEYS[0]).assertIsEnabled()
        compose.runOnIdle { assertTrue(config.value.appEnabled(pkg)) }
    }

    @Test
    fun signalSwitchDisablesThresholdEditingAndRetainsItsValues() {
        val config = mutableStateOf(EnhanceConfig(EnhanceConfig.defaults().toBundle().apply {
            putInt("signal_one", -85)
            putInt("signal_two", -55)
        }))
        battery(config)
        reveal("battery_settings", "signal_range").assertIsEnabled()
        reveal("battery_settings", "signal_enabled").performClick()
        reveal("battery_settings", "signal_range").assertIsNotEnabled()
        compose.runOnIdle {
            assertFalse(config.value.signalEnabled)
            assertTrue(config.value.batteryEnabled)
            assertEquals(-85, config.value.signalOne)
            assertEquals(-55, config.value.signalTwo)
        }
        reveal("battery_settings", "signal_enabled").performClick()
        reveal("battery_settings", "signal_range").assertIsEnabled()
        compose.runOnIdle {
            assertTrue(config.value.signalEnabled)
            assertEquals(-85, config.value.signalOne)
            assertEquals(-55, config.value.signalTwo)
        }
    }

    @Test
    fun batterySwitchSuspendsSignalControlsAndRestoresTheirSavedChoice() {
        val config = mutableStateOf(EnhanceConfig.defaults())
        battery(config)
        reveal("battery_settings", "battery_enabled").performClick()
        reveal("battery_settings", "signal_enabled").assertIsNotEnabled()
        reveal("battery_settings", "signal_range").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(config.value.signalEnabled) }
        reveal("battery_settings", "battery_enabled").performClick()
        reveal("battery_settings", "signal_enabled").assertIsEnabled()
        reveal("battery_settings", "signal_range").assertIsEnabled()
        compose.runOnIdle { assertTrue(config.value.signalEnabled) }
    }

    @Test
    fun proSettingsSwitchIsIndependentOfButtonMappings() {
        val config = mutableStateOf(EnhanceConfig.defaults())
        keys(config)
        reveal("key_settings", "pro_settings").performClick()
        compose.runOnIdle {
            assertFalse(config.value.proSettings)
            repeat(3) { assertTrue(config.value.keyEnabled(it)) }
        }
        reveal("key_settings", "pro_settings").performClick()
        compose.runOnIdle {
            assertTrue(config.value.proSettings)
            ActionConfig.DEFAULTS.forEachIndexed { binding, action ->
                assertEquals(action, config.value.action(binding))
            }
        }
    }

    @Test
    fun chargingNotificationsRemainEditableWithBatteryAndChargeLimitOff() {
        val config = mutableStateOf(EnhanceConfig(EnhanceConfig.defaults().toBundle().apply {
            putBoolean("battery_enabled", false)
        }))
        battery(config)
        reveal("battery_settings", "charging_notification_enabled").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertTrue(config.value.chargingNotificationEnabled)
            assertFalse(config.value.attachedNotificationEnabled)
            assertFalse(config.value.chargeLimitEnabled)
        }
        reveal("battery_settings", "attached_notification_enabled").assertIsEnabled().performClick()
        reveal("battery_settings", "charging_notification_enabled").performClick()
        compose.runOnIdle {
            assertFalse(config.value.chargingNotificationEnabled)
            assertTrue(config.value.attachedNotificationEnabled)
            assertFalse(config.value.batteryEnabled)
            assertEquals(50, config.value.chargeStart)
            assertEquals(80, config.value.chargeStop)
        }
    }
}
