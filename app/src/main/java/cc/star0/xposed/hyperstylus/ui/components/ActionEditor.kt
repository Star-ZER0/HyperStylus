package cc.star0.xposed.hyperstylus.ui.components

import android.view.KeyEvent

import cc.star0.xposed.hyperstylus.R
import cc.star0.xposed.hyperstylus.config.ActionConfig
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

// miuix removes the disabled click target; also expose the state and labels to accessibility.
private fun Modifier.mappingSemantics(enabled: Boolean) = semantics(mergeDescendants = true) {
    if (!enabled) disabled()
}

/** One action picker serves global bindings and every application profile. */
@Composable
internal fun KeyActionPreference(binding: Int, action: String, enabled: Boolean, onChange: (String) -> Unit) {
    var keyboardEditor by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) keyboardEditor = false }
    val values = ActionConfig.ACTIONS
    val allNames = stringArrayResource(R.array.action_labels)
    val names = allNames.toMutableList()
    if (binding < 2) names[values.indexOf(ActionConfig.DEFAULT)] = stringResource(R.string.native_focus)
    val code = ActionConfig.keyCode(action)
    val selected = values.indexOf(ActionConfig.option(action))
    key(enabled) {
        OverlayDropdownPreference(
            title = stringResource(if (binding % 2 == 0) R.string.click_action else R.string.long_action),
            summary = code.takeIf { it > 0 }?.let(ActionConfig::keyLabel),
            items = names, selectedIndex = selected, enabled = enabled, maxHeight = 440.dp,
            onSelectedIndexChange = { index ->
                if (enabled) {
                    val chosen = values[index]
                    if (chosen == ActionConfig.KEYBOARD) keyboardEditor = true else onChange(chosen)
                }
            },
            modifier = Modifier.testTag(ActionConfig.KEYS[binding]).mappingSemantics(enabled),
        )
    }
    if (keyboardEditor && enabled) {
        KeyboardEditor(code, onDismiss = { keyboardEditor = false }) { value ->
            onChange(ActionConfig.customKey(ActionConfig.KEYBOARD, value))
            keyboardEditor = false
        }
    }
}

@Composable
internal fun KeyMappingModePreference(button: Int, action: String, enabled: Boolean, onChange: (String) -> Unit) {
    var keyboardEditor by remember { mutableStateOf(false) }
    val code = ActionConfig.keyCode(action)
    val fullMapping = code > 0
    LaunchedEffect(enabled) { if (!enabled) keyboardEditor = false }
    key(enabled) {
        OverlayDropdownPreference(
            title = stringResource(R.string.key_mapping_mode),
            items = stringArrayResource(R.array.key_mapping_modes).toList(),
            selectedIndex = if (fullMapping) 1 else 0,
            enabled = enabled,
            onSelectedIndexChange = { index ->
                if (enabled) {
                    if (index == 0) onChange(ActionConfig.NONE) else keyboardEditor = true
                }
            },
            modifier = Modifier.testTag("key_mode_$button").mappingSemantics(enabled),
        )
    }
    if (fullMapping) {
        ArrowPreference(
            title = stringResource(R.string.full_mapping_target),
            summary = ActionConfig.keyLabel(code),
            enabled = enabled,
            onClick = { keyboardEditor = true },
            modifier = Modifier.testTag(ActionConfig.FULL_KEYS[button]).mappingSemantics(enabled),
        )
    }
    if (keyboardEditor && enabled) {
        KeyboardEditor(code, fullMapping = true, onDismiss = { keyboardEditor = false }) { value ->
            onChange(ActionConfig.customKey(ActionConfig.KEYBOARD, value))
            keyboardEditor = false
        }
    }
}

@Composable
private fun KeyboardEditor(oldCode: Int, fullMapping: Boolean = false, onDismiss: () -> Unit, onChoose: (Int) -> Unit) {
    val text = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    var recording by remember { mutableStateOf(false) }
    val allCodes = remember { (1..KeyEvent.getMaxKeyCode()).map { it to ActionConfig.keyLabel(it) } }
    val query = text.text.toString().trim()
    val matching = remember(query) { allCodes.filter { (_, label) -> label.contains(query, ignoreCase = true) } }
    LaunchedEffect(recording) { if (recording) focus.requestFocus() }

    OverlayDialog(show = true,
        title = stringResource(R.string.keyboard_picker),
        onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
            if (!recording) false else {
                val key = event.nativeKeyEvent
                if (key.action == KeyEvent.ACTION_UP && ActionConfig.validKeyCode(key.keyCode)) {
                    recording = false
                    onChoose(key.keyCode)
                }
                true
            }
        }.focusable().testTag("custom_key_dialog")) {
            TextField(state = text, label = stringResource(R.string.keyboard_search),
                lineLimits = TextFieldLineLimits.SingleLine, modifier = Modifier.fillMaxWidth().testTag("custom_key_input"))
            if (oldCode > 0) Note(ActionConfig.keyLabel(oldCode))
            TextButton(text = stringResource(if (recording) R.string.keyboard_listening else R.string.keyboard_capture),
                onClick = { recording = !recording }, modifier = Modifier.fillMaxWidth().testTag("capture_key"))
            LazyColumn(Modifier.fillMaxWidth().height(260.dp).testTag("keyboard_choices")) {
                items(matching, key = { it.first }) { (code, label) ->
                    BasicComponent(title = label, onClick = { onChoose(code) },
                        modifier = Modifier.fillMaxWidth().testTag("keyboard_key_$code"))
                }
            }
            Note(stringResource(if (fullMapping) R.string.full_mapping_note else R.string.custom_key_note))
            TextButton(text = stringResource(R.string.cancel), onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().testTag("cancel_keycode"))
        }
    }
}
