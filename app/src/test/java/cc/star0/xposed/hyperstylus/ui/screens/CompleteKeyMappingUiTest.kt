package cc.star0.xposed.hyperstylus.ui.screens

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import cc.star0.xposed.hyperstylus.R
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

/** Choose real keyboard targets and switch modes through the native miuix controls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompleteKeyMappingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun display(config: MutableState<EnhanceConfig>, app: String? = null) {
        compose.setContent {
            FocusTheme {
                if (app == null) {
                    KeysScreen(config.value, onBack = {}, onOpenApps = {},
                        onChange = { config.value = EnhanceConfig(it) })
                } else {
                    ApplicationProfileScreen(config.value, AppDomain.KEYS, AppEntry(app, "Notes"),
                        onChange = { config.value = EnhanceConfig(it) }, onBack = {})
                }
            }
        }
    }

    private val placed = SemanticsMatcher("placed accessibility target") { it.layoutInfo.isPlaced }
    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private fun mode(button: Int, full: Boolean, list: String = "key_settings") {
        compose.onNodeWithTag(list).performScrollToNode(hasTestTag("key_mode_$button"))
        compose.onNodeWithTag("key_mode_$button").performClick()
        val name = compose.activity.resources.getStringArray(R.array.key_mapping_modes)[if (full) 1 else 0]
        compose.onNode(hasText(name) and radio and placed).performClick()
    }

    private fun choose(code: Int) {
        compose.onNodeWithTag("custom_key_dialog").assertIsDisplayed()
        compose.onNodeWithTag("custom_key_input").performTextInput(ActionConfig.keyLabel(code))
        compose.onNodeWithTag("keyboard_key_$code").assertIsDisplayed().performClick()
        compose.onNodeWithTag("custom_key_dialog").assertDoesNotExist()
    }

    @Test
    fun everyPhysicalButtonChoosesAnIndependentFullTarget() {
        val config = mutableStateOf(EnhanceConfig.defaults())
        display(config)
        val targets = listOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP)
        targets.forEachIndexed { button, code ->
            mode(button, full = true)
            choose(code)
            compose.onNodeWithTag("key_settings").performScrollToNode(hasTestTag(ActionConfig.FULL_KEYS[button]))
            compose.onNodeWithTag(ActionConfig.FULL_KEYS[button]).assertIsDisplayed()
            compose.onNodeWithTag(ActionConfig.KEYS[button * 2]).assertIsNotEnabled()
            compose.onNodeWithTag(ActionConfig.KEYS[button * 2 + 1]).assertIsNotEnabled()
            compose.runOnIdle {
                targets.forEachIndexed { index, expected ->
                    assertEquals(if (index <= button) ActionConfig.customKey(ActionConfig.KEYBOARD, expected) else ActionConfig.NONE,
                        config.value.fullAction(index))
                }
            }
        }
    }

    @Test
    fun switchingBackRestoresTheSavedClickAndLongActions() {
        val values = EnhanceConfig.defaults().toBundle().apply {
            putString(ActionConfig.KEYS[0], "volume_up")
            putString(ActionConfig.KEYS[1], "back")
        }
        val config = mutableStateOf(EnhanceConfig(values))
        display(config)
        mode(0, full = true)
        choose(KeyEvent.KEYCODE_SPACE)
        compose.onNodeWithTag(ActionConfig.KEYS[0]).assertIsNotEnabled()
        compose.onNodeWithTag(ActionConfig.KEYS[1]).assertIsNotEnabled()
        compose.onNodeWithTag("key_settings").performScrollToNode(hasTestTag(ActionConfig.KEYS[0]))
        compose.onNodeWithTag(ActionConfig.KEYS[0]).assertIsDisplayed().performTouchInput { click(center) }
        compose.onAllNodes(radio and placed).assertCountEquals(0)
        compose.runOnIdle {
            assertEquals("volume_up", config.value.action(0))
            assertEquals("back", config.value.action(1))
        }
        mode(0, full = false)
        compose.onNodeWithTag(ActionConfig.FULL_KEYS[0]).assertDoesNotExist()
        compose.onNodeWithTag(ActionConfig.KEYS[0]).assertIsEnabled()
        compose.onNodeWithTag(ActionConfig.KEYS[1]).assertIsEnabled()
        compose.runOnIdle {
            assertEquals(ActionConfig.NONE, config.value.fullAction(0))
            assertEquals("volume_up", config.value.action(0))
            assertEquals("back", config.value.action(1))
        }
    }

    @Test
    fun cancelingTheTargetPickerDoesNotEnableFullMapping() {
        val config = mutableStateOf(EnhanceConfig.defaults())
        display(config)
        mode(1, full = true)
        compose.onNodeWithTag("cancel_keycode").performClick()
        compose.onNodeWithTag("custom_key_dialog").assertDoesNotExist()
        compose.onNodeWithTag(ActionConfig.FULL_KEYS[1]).assertDoesNotExist()
        compose.onNodeWithTag(ActionConfig.KEYS[2]).assertIsEnabled()
        compose.onNodeWithTag(ActionConfig.KEYS[3]).assertIsEnabled()
        compose.runOnIdle {
            repeat(3) { assertEquals(ActionConfig.NONE, config.value.fullAction(it)) }
            ActionConfig.DEFAULTS.forEachIndexed { index, value -> assertEquals(value, config.value.action(index)) }
        }
    }

    @Test
    fun changingTheTargetKeepsFullModeAndExistingActions() {
        val values = EnhanceConfig.defaults().toBundle().apply {
            putString(ActionConfig.FULL_KEYS[0], ActionConfig.customKey(ActionConfig.KEYBOARD, KeyEvent.KEYCODE_SPACE))
        }
        val config = mutableStateOf(EnhanceConfig(values))
        display(config)
        compose.onNodeWithTag("key_settings").performScrollToNode(hasTestTag(ActionConfig.FULL_KEYS[0]))
        compose.onNodeWithTag(ActionConfig.FULL_KEYS[0]).performClick()
        choose(KeyEvent.KEYCODE_TAB)
        compose.onNodeWithTag(ActionConfig.KEYS[0]).assertIsNotEnabled()
        compose.onNodeWithTag(ActionConfig.KEYS[1]).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(ActionConfig.customKey(ActionConfig.KEYBOARD, KeyEvent.KEYCODE_TAB), config.value.fullAction(0))
            assertEquals(ActionConfig.DEFAULTS[0], config.value.action(0))
            assertEquals(ActionConfig.DEFAULTS[1], config.value.action(1))
        }
    }

    @Test
    fun keyboardRecordingSelectsOneCompleteTarget() {
        val config = mutableStateOf(EnhanceConfig.defaults())
        display(config)
        mode(2, full = true)
        compose.onNodeWithTag("capture_key").performClick()
        compose.onNodeWithTag("custom_key_dialog").performKeyInput { pressKey(Key.Spacebar) }
        compose.onNodeWithTag("custom_key_dialog").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(ActionConfig.customKey(ActionConfig.KEYBOARD, KeyEvent.KEYCODE_SPACE), config.value.fullAction(2))
        }
    }

    @Test
    fun applicationFullMappingDoesNotChangeGlobalOrOtherApplicationTargets() {
        val pkg = "test.notes"
        val global = EnhanceConfig(EnhanceConfig.defaults().toBundle().apply {
            putString(ActionConfig.FULL_KEYS[1], ActionConfig.customKey(ActionConfig.KEYBOARD, KeyEvent.KEYCODE_ENTER))
        })
        val other = EnhanceConfig(global.withAppEnabled("test.browser", true))
        val editedOther = EnhanceConfig(other.withAppFullAction("test.browser", 0,
            ActionConfig.customKey(ActionConfig.KEYBOARD, KeyEvent.KEYCODE_DPAD_DOWN)))
        val config = mutableStateOf(EnhanceConfig(editedOther.withAppEnabled(pkg, true)))
        display(config, pkg)
        mode(0, full = true, list = "app_profile")
        choose(KeyEvent.KEYCODE_SPACE)
        compose.onNodeWithTag(ActionConfig.KEYS[0]).assertIsNotEnabled()
        compose.onNodeWithTag(ActionConfig.KEYS[1]).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(ActionConfig.customKey(ActionConfig.KEYBOARD, KeyEvent.KEYCODE_SPACE), config.value.appFullAction(pkg, 0))
            assertEquals(ActionConfig.NONE, config.value.fullAction(0))
            assertEquals(ActionConfig.customKey(ActionConfig.KEYBOARD, KeyEvent.KEYCODE_ENTER), config.value.fullAction(1))
            assertEquals(ActionConfig.customKey(ActionConfig.KEYBOARD, KeyEvent.KEYCODE_DPAD_DOWN), config.value.appFullAction("test.browser", 0))
        }
        compose.onNodeWithTag("app_profile").performScrollToNode(hasTestTag("profile_enabled"))
        compose.onNodeWithTag("profile_enabled").performClick()
        compose.onNodeWithTag("app_profile").performScrollToNode(hasTestTag("key_mode_0"))
        compose.onNodeWithTag("key_mode_0").assertIsNotEnabled()
        compose.onNodeWithTag(ActionConfig.FULL_KEYS[0]).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(ActionConfig.customKey(ActionConfig.KEYBOARD, KeyEvent.KEYCODE_SPACE), config.value.appFullAction(pkg, 0))
            assertFalse(config.value.appEnabled(pkg))
        }
    }
}
