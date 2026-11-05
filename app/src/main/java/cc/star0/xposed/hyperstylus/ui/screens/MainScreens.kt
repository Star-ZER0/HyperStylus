package cc.star0.xposed.hyperstylus.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.star0.xposed.hyperstylus.ui.components.MainBottomNavigation
import cc.star0.xposed.hyperstylus.ui.components.LocalMainNavigationPadding
import cc.star0.xposed.hyperstylus.ui.navigation.FeaturePage
import cc.star0.xposed.hyperstylus.ui.navigation.MainPage
import cc.star0.xposed.hyperstylus.ui.state.ModuleStatus
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Three independent root destinations share native navigation chrome. */
@Composable
internal fun MainDestinationScreen(
    page: MainPage,
    status: ModuleStatus,
    onSelect: (MainPage) -> Unit,
    onOpenFeature: (FeaturePage) -> Unit,
    onRefresh: () -> Unit,
    onReload: () -> Unit,
    showBottomNavigation: Boolean = true,
    pager: PagerState = rememberPagerState(initialPage = page.ordinal, pageCount = { MainPage.entries.size }),
    onScopeRetry: () -> Unit = {},
) {
    val surfaceColor = MiuixTheme.colorScheme.surface
    val shaderSupported = remember { isRuntimeShaderSupported() }
    val backdrop = if (showBottomNavigation && shaderSupported) rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    } else null
    Scaffold(bottomBar = {
        if (showBottomNavigation) {
            MainBottomNavigation(page, backdrop, onSelect)
        }
    }) { padding ->
        val navigationPadding = if (showBottomNavigation) padding.calculateBottomPadding() else 0.dp
        CompositionLocalProvider(LocalMainNavigationPadding provides navigationPadding) {
            HorizontalPager(state = pager, key = { MainPage.entries[it].name },
                modifier = Modifier.fillMaxSize().testTag("main_pager")
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) { index ->
                when (MainPage.entries[index]) {
                    MainPage.MODULE_STATUS -> ModuleStatusScreen(status, onRefresh, onReload, onScopeRetry)
                    MainPage.SETTINGS -> Box(Modifier.fillMaxSize().testTag("main_settings_page")) { OverviewScreen(onOpenFeature) }
                    MainPage.ABOUT -> AboutScreen()
                }
            }
        }
    }
}
