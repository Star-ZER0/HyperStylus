package cc.star0.xposed.hyperstylus.ui.state

import android.os.Bundle

import cc.star0.xposed.hyperstylus.R
import cc.star0.xposed.hyperstylus.config.EnhanceConfig
import cc.star0.xposed.hyperstylus.config.GestureAppConfig

/** Explicit feature identities bind the shared controls to the global key and per-app mask bit. */
internal enum class StylusGestureFeature(val bit: Int, val key: String, val title: Int, val summary: Int) {
    BACK(GestureAppConfig.BACK, "ignore_stylus_back", R.string.ignore_stylus_back, R.string.ignore_stylus_back_summary),
    SYSTEM_BARS(GestureAppConfig.SYSTEM_BARS, "ignore_stylus_system_bars", R.string.ignore_stylus_system_bars, R.string.ignore_stylus_system_bars_summary),
    HOME_RECENTS(GestureAppConfig.HOME_RECENTS, "ignore_stylus_home_recents", R.string.ignore_stylus_home_recents, R.string.ignore_stylus_home_recents_summary),
    CAPTION(GestureAppConfig.CAPTION, "ignore_stylus_caption", R.string.ignore_stylus_caption, R.string.ignore_stylus_caption_summary),
    CORNER(GestureAppConfig.CORNER, "ignore_stylus_corner_freeform", R.string.ignore_stylus_corner_freeform, R.string.ignore_stylus_corner_freeform_summary),
    FOCUS_NOTIFICATION(GestureAppConfig.FOCUS_NOTIFICATION, "ignore_stylus_focus_notification",
        R.string.ignore_stylus_focus_notification, R.string.ignore_stylus_focus_notification_summary),
    SPLIT_DIVIDER(GestureAppConfig.SPLIT_DIVIDER, "ignore_stylus_split_divider",
        R.string.ignore_stylus_split_divider, R.string.ignore_stylus_split_divider_summary),
    SIDEBAR(GestureAppConfig.SIDEBAR, "ignore_stylus_sidebar",
        R.string.ignore_stylus_sidebar, R.string.ignore_stylus_sidebar_summary),
}

internal object GestureSettingsEdits {
    fun global(config: EnhanceConfig, feature: StylusGestureFeature, checked: Boolean): Bundle =
        config.toBundle().apply { putBoolean(feature.key, checked) }

    fun appProfile(config: EnhanceConfig, packageName: String, feature: StylusGestureFeature, checked: Boolean): Bundle {
        val rules = config.gestureApps
        val previous = rules.profileMask(packageName, config.gestureMask())
        val changed = if (checked) previous or feature.bit else previous and feature.bit.inv()
        return config.withGestureApps(rules.withProfile(packageName, changed))
    }
}
