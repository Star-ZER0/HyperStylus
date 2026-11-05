package cc.star0.xposed.hyperstylus.ui.navigation

import androidx.compose.runtime.saveable.Saver
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf

internal enum class MainPage { MODULE_STATUS, SETTINGS, ABOUT }
internal data object MainRoot : NavKey
internal data class SettingsRoute(val page: FeaturePage, val domain: AppDomain = AppDomain.KEYS,
    val packageName: String = "") : NavKey

/** Only value routes enter saved state; module service handles stay in the service owner. */
internal val MainRouteSaver = Saver<NavBackStack, List<String>>(
    save = { stack -> stack.map { key ->
        when (key) {
            MainRoot -> "main"
            is SettingsRoute -> "settings|" + key.page.name + "|" + key.domain.name + "|" + key.packageName
            else -> error("Unknown navigation route")
        }
    } },
    restore = { values -> navBackStackOf(*values.map { encoded ->
        val parts = encoded.split('|')
        when (parts[0]) {
            "main" -> MainRoot
            "settings" -> SettingsRoute(FeaturePage.valueOf(parts[1]), AppDomain.valueOf(parts[2]), parts[3])
            else -> error("Unknown saved navigation route")
        }
    }.toTypedArray()) },
)
