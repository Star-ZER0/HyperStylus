package cc.star0.xposed.hyperstylus.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import cc.star0.xposed.hyperstylus.R
import cc.star0.xposed.hyperstylus.ui.navigation.MainPage
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.NavigationRailState
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MainNavigationRail(page: MainPage, state: NavigationRailState, onSelect: (MainPage) -> Unit) {
    NavigationRail(state = state, modifier = Modifier.testTag("main_side_navigation"),
        color = MiuixTheme.colorScheme.surface,
        expandContentDescription = stringResource(R.string.main_navigation_expand),
        collapseContentDescription = stringResource(R.string.main_navigation_collapse)) {
        MainPage.entries.forEach { destination ->
            NavigationRailItem(selected = destination == page, onClick = { onSelect(destination) },
                icon = destination.icon(), label = destination.label(),
                modifier = Modifier.testTag(destination.tag()))
        }
    }
}

@Composable
internal fun MainBottomNavigation(
    page: MainPage,
    backdrop: LayerBackdrop?,
    onSelect: (MainPage) -> Unit,
) {
    val surfaceColor = MiuixTheme.colorScheme.surface
    val blur = if (backdrop != null) Modifier.textureBlur(
        backdrop = backdrop, shape = RectangleShape, blurRadius = 25f,
        colors = BlurColors(blendColors = listOf(BlendColorEntry(color = surfaceColor.copy(alpha = .87f))))
    ) else Modifier
    NavigationBar(
        modifier = Modifier.testTag("main_bottom_navigation").then(blur)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)),
        color = if (backdrop != null) Color.Transparent else surfaceColor,
    ) {
        MainNavigationItems(page, onSelect)
    }
}

@Composable
private fun RowScope.MainNavigationItems(
    page: MainPage, onSelect: (MainPage) -> Unit,
) {
    MainPage.entries.forEach { destination ->
        NavigationBarItem(selected = destination == page, onClick = { onSelect(destination) },
            icon = destination.icon(), label = destination.label(),
            modifier = Modifier.testTag(destination.tag()))
    }
}

private fun MainPage.icon() = when (this) {
    MainPage.MODULE_STATUS -> MiuixIcons.Basic.Check
    MainPage.SETTINGS -> MiuixIcons.Settings
    MainPage.ABOUT -> MiuixIcons.Info
}

@Composable
private fun MainPage.label() = stringResource(when (this) {
    MainPage.MODULE_STATUS -> R.string.main_module_status
    MainPage.SETTINGS -> R.string.main_settings
    MainPage.ABOUT -> R.string.main_about
})

private fun MainPage.tag() = when (this) {
    MainPage.MODULE_STATUS -> "main_status_tab"
    MainPage.SETTINGS -> "main_settings_tab"
    MainPage.ABOUT -> "main_about_tab"
}
