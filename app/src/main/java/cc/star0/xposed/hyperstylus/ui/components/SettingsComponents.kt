package cc.star0.xposed.hyperstylus.ui.components

import androidx.compose.foundation.Image

import cc.star0.xposed.hyperstylus.R
import cc.star0.xposed.hyperstylus.config.ActionConfig
import cc.star0.xposed.hyperstylus.config.EnhanceConfig
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Root lists can scroll behind the translucent bar and fully clear it at the end. */
internal val LocalMainNavigationPadding = staticCompositionLocalOf { 0.dp }

/** All pages share safe insets, tablet width, scroll behaviour and an accessible back action. */
@Composable
internal fun SettingsPage(
    title: String,
    tag: String,
    onBack: (() -> Unit)? = null,
    state: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    reserveNavigationBarSpace: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val mainNavigationPadding = LocalMainNavigationPadding.current
    Scaffold(topBar = {
        TopAppBar(title = title, actions = actions, navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack, minWidth = 48.dp, minHeight = 48.dp,
                    modifier = Modifier.testTag("subpage_back")) {
                    Image(
                        imageVector = MiuixIcons.Basic.ArrowRight,
                        contentDescription = stringResource(R.string.back),
                        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface),
                        modifier = Modifier.size(12.dp, 20.dp).graphicsLayer { scaleX = if (rtl) 1f else -1f },
                    )
                }
            }
        })
    }) { padding ->
        // The page Scaffold already reserves the system navigation inset.
        val navigationClearance = (mainNavigationPadding - padding.calculateBottomPadding()).coerceAtLeast(0.dp)
        // New root pages keep scroll/focus targets inside the visible area above the bar.
        // Other pages retain their existing inset and backdrop behaviour by default.
        Box(Modifier.fillMaxSize().padding(padding)
            .padding(bottom = if (reserveNavigationBarSpace) navigationClearance else 0.dp),
            contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = state,
                modifier = Modifier.widthIn(max = 800.dp).fillMaxSize().testTag(tag),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp,
                    bottom = 32.dp + if (reserveNavigationBarSpace) 0.dp else navigationClearance),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        SmallTitle(title, insideMargin = PaddingValues(start = 12.dp, top = 8.dp, bottom = 8.dp))
        Card(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
internal fun Note(text: String) {
    Text(text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        fontSize = 12.sp, lineHeight = 19.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

internal fun LazyListScope.keyBindings(
    action: (Int) -> String,
    fullAction: (Int) -> String,
    enabled: Boolean = true,
    buttonEnabled: (Int) -> Boolean = { true },
    onButtonEnabledChange: ((Int, Boolean) -> Unit)? = null,
    changeFull: (Int, String) -> Unit,
    change: (Int, String) -> Unit,
) {
    val titles = listOf(R.string.focus_key, R.string.upper_key, R.string.lower_key)
    titles.forEachIndexed { button, title ->
        item(key = "key_$button") {
            SettingsGroup(stringResource(title)) {
                val mappingEnabled = buttonEnabled(button)
                if (onButtonEnabledChange != null) {
                    SwitchPreference(title = stringResource(R.string.key_mapping_enabled),
                        summary = stringResource(R.string.key_mapping_enabled_summary),
                        checked = mappingEnabled, enabled = enabled,
                        onCheckedChange = { onButtonEnabledChange(button, it) },
                        modifier = Modifier.testTag(EnhanceConfig.KEY_ENABLED_KEYS[button]))
                }
                val editorEnabled = enabled && mappingEnabled
                val full = fullAction(button)
                KeyMappingModePreference(button, full, editorEnabled) { changeFull(button, it) }
                repeat(2) { press ->
                    val binding = button * 2 + press
                    KeyActionPreference(binding, action(binding), editorEnabled && ActionConfig.keyCode(full) <= 0) { change(binding, it) }
                }
            }
        }
    }
}
