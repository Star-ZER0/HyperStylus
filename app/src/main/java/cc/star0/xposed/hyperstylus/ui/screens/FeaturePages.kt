package cc.star0.xposed.hyperstylus.ui.screens

import android.os.Bundle

import cc.star0.xposed.hyperstylus.BuildConfig
import cc.star0.xposed.hyperstylus.R
import cc.star0.xposed.hyperstylus.config.ActionConfig
import cc.star0.xposed.hyperstylus.config.EnhanceConfig
import cc.star0.xposed.hyperstylus.config.StylusGestureConfig
import cc.star0.xposed.hyperstylus.ui.components.Note
import cc.star0.xposed.hyperstylus.ui.components.SettingsGroup
import cc.star0.xposed.hyperstylus.ui.components.SettingsPage
import cc.star0.xposed.hyperstylus.ui.components.SettingsIcon
import cc.star0.xposed.hyperstylus.ui.components.ThresholdPreference
import cc.star0.xposed.hyperstylus.ui.components.keyBindings
import cc.star0.xposed.hyperstylus.ui.navigation.AppDomain
import cc.star0.xposed.hyperstylus.ui.navigation.FeaturePage
import cc.star0.xposed.hyperstylus.ui.state.GestureSettingsEdits
import cc.star0.xposed.hyperstylus.ui.state.StylusGestureFeature
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme


@Composable
internal fun OverviewScreen(onOpen: (FeaturePage) -> Unit) {
    SettingsPage(stringResource(R.string.main_settings), "settings_list", reserveNavigationBarSpace = true) {
        item(key = "stylus") {
            SettingsGroup(stringResource(R.string.settings_group_stylus)) {
                FeatureEntry(FeaturePage.BATTERY, R.string.overview_battery, R.string.overview_battery_summary, onOpen)
                FeatureEntry(FeaturePage.KEYS, R.string.overview_keys, R.string.overview_keys_summary, onOpen)
            }
        }
        item(key = "gestures") {
            SettingsGroup(stringResource(R.string.settings_group_gestures)) {
                FeatureEntry(FeaturePage.GESTURES, R.string.overview_gestures, R.string.overview_gestures_summary, onOpen)
                FeatureEntry(FeaturePage.BRUSH, R.string.overview_brush, R.string.overview_brush_summary, onOpen)
            }
        }
        item(key = "setup") {
            Note(stringResource(R.string.setup_note))
            Text("${stringResource(R.string.subtitle)} · ${BuildConfig.VERSION_NAME}",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun FeatureEntry(page: FeaturePage, title: Int, summary: Int, onOpen: (FeaturePage) -> Unit) {
    val icon = when (page) {
        FeaturePage.BATTERY -> painterResource(R.drawable.ic_settings_battery)
        FeaturePage.KEYS -> painterResource(R.drawable.ic_settings_keyboard)
        FeaturePage.GESTURES -> rememberVectorPainter(MiuixIcons.Blocklist)
        else -> rememberVectorPainter(MiuixIcons.Edit)
    }
    ArrowPreference(title = stringResource(title), summary = stringResource(summary),
        startAction = { SettingsIcon(icon, Modifier.padding(end = 16.dp)) },
        insideMargin = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        onClick = { onOpen(page) }, modifier = Modifier.testTag("open_${page.name.lowercase()}"))
}

@Composable
internal fun BatteryScreen(config: EnhanceConfig, onBack: () -> Unit, onChange: (Bundle) -> Unit) {
    fun edit(block: Bundle.() -> Unit) = onChange(config.toBundle().apply(block))
    SettingsPage(stringResource(R.string.overview_battery), "battery_settings", onBack) {
        item(key = "display") {
            SettingsGroup(stringResource(R.string.section_battery)) {
                SwitchPreference(title = stringResource(R.string.battery_enabled), summary = stringResource(R.string.battery_summary),
                    checked = config.batteryEnabled, onCheckedChange = { edit { putBoolean("battery_enabled", it) } },
                    modifier = Modifier.testTag("battery_enabled"))
                OverlayDropdownPreference(title = stringResource(R.string.display_title),
                    items = stringArrayResource(R.array.display_modes).toList(), selectedIndex = config.displayMode,
                    enabled = config.batteryEnabled, onSelectedIndexChange = { edit { putInt("display_mode", it) } },
                    modifier = Modifier.testTag("display_mode"))
            }
        }
        item(key = "charge") {
            SettingsGroup(stringResource(R.string.charge_limit_title)) {
                SwitchPreference(title = stringResource(R.string.charge_limit_enabled), summary = stringResource(R.string.charge_limit_summary),
                    checked = config.chargeLimitEnabled, onCheckedChange = { edit { putBoolean("charge_limit_enabled", it) } },
                    modifier = Modifier.testTag("charge_limit_enabled"))
                ThresholdPreference(config.chargeStart..config.chargeStop, 0..100, 5, 5,
                    stringResource(R.string.charge_range), stringResource(R.string.charge_start),
                    stringResource(R.string.charge_stop), "%", enabled = config.chargeLimitEnabled,
                    modifier = Modifier.testTag("charge_range")) {
                    edit { putInt("charge_start", it.first); putInt("charge_stop", it.last) }
                }
            }
            Note(stringResource(R.string.charge_limit_note))
        }
        item(key = "charge_notifications") {
            SettingsGroup(stringResource(R.string.charge_notifications_title)) {
                SwitchPreference(title = stringResource(R.string.charging_notification_enabled),
                    summary = stringResource(R.string.charging_notification_summary),
                    checked = config.chargingNotificationEnabled,
                    onCheckedChange = { edit { putBoolean("charging_notification_enabled", it) } },
                    modifier = Modifier.testTag("charging_notification_enabled"))
                SwitchPreference(title = stringResource(R.string.attached_notification_enabled),
                    summary = stringResource(R.string.attached_notification_summary),
                    checked = config.attachedNotificationEnabled,
                    onCheckedChange = { edit { putBoolean("attached_notification_enabled", it) } },
                    modifier = Modifier.testTag("attached_notification_enabled"))
            }
            Note(stringResource(R.string.charge_notifications_note))
        }
        item(key = "signal") {
            SettingsGroup(stringResource(R.string.signal_title)) {
                SwitchPreference(title = stringResource(R.string.signal_enabled),
                    summary = stringResource(R.string.signal_enabled_summary),
                    checked = config.signalEnabled, enabled = config.batteryEnabled,
                    onCheckedChange = { if (config.batteryEnabled) edit { putBoolean("signal_enabled", it) } },
                    modifier = Modifier.testTag("signal_enabled").semantics(mergeDescendants = true) {
                        if (!config.batteryEnabled) disabled()
                    })
                ThresholdPreference(config.signalOne..config.signalTwo, -120..-20, 5, 5,
                    stringResource(R.string.signal_range), stringResource(R.string.signal_one),
                    stringResource(R.string.signal_two), " dBm", enabled = config.batteryEnabled && config.signalEnabled,
                    modifier = Modifier.testTag("signal_range")) {
                    edit { putInt("signal_one", it.first); putInt("signal_two", it.last) }
                }
            }
            Note(stringResource(R.string.signal_note))
        }
    }
}

@Composable
internal fun KeysScreen(config: EnhanceConfig, onBack: () -> Unit, onOpenApps: () -> Unit, onChange: (Bundle) -> Unit) {
    SettingsPage(stringResource(R.string.overview_keys), "key_settings", onBack) {
        item(key = "pro") {
            SettingsGroup(stringResource(R.string.section_pro)) {
                SwitchPreference(title = stringResource(R.string.pro_enabled), summary = stringResource(R.string.pro_summary),
                    checked = config.proSettings, onCheckedChange = {
                        onChange(config.toBundle().apply { putBoolean("pro_settings", it) })
                    }, modifier = Modifier.testTag("pro_settings"))
            }
            Note(stringResource(R.string.pro_note))
        }
        item(key = "profiles") {
            SettingsGroup(stringResource(R.string.section_app_mappings)) {
                ArrowPreference(title = stringResource(R.string.app_mappings_title),
                    summary = stringResource(R.string.app_mappings_summary, config.appPackages().count(config::appEnabled)),
                    onClick = onOpenApps, modifier = Modifier.testTag("open_app_mappings"))
            }
            Note(stringResource(R.string.section_keys))
            Note(stringResource(R.string.keys_note))
        }
        keyBindings(action = config::action, fullAction = config::fullAction,
            buttonEnabled = config::keyEnabled,
            onButtonEnabledChange = { button, enabled ->
                onChange(config.toBundle().apply { putBoolean(EnhanceConfig.KEY_ENABLED_KEYS[button], enabled) })
            },
            changeFull = { button, action ->
                onChange(config.toBundle().apply { putString(ActionConfig.FULL_KEYS[button], action) })
            }) { binding, action ->
            onChange(config.toBundle().apply { putString(ActionConfig.KEYS[binding], action) })
        }
    }
}

@Composable
internal fun GesturesScreen(config: EnhanceConfig, onBack: () -> Unit, onOpenApps: () -> Unit, onChange: (Bundle) -> Unit) {
    SettingsPage(stringResource(R.string.gesture_title), "gesture_settings", onBack) {
        item(key = "global") {
            SettingsGroup(stringResource(R.string.gesture_title)) {
                GestureSwitches(config.gestureMask(), true) { feature, checked ->
                    onChange(GestureSettingsEdits.global(config, feature, checked))
                }
            }
        }
        item(key = "apps") {
            SettingsGroup(stringResource(R.string.gesture_app_rules)) {
                SwitchPreference(title = stringResource(R.string.gesture_apps_enabled),
                    summary = stringResource(R.string.gesture_apps_enabled_summary), checked = config.gestureApps.enabled,
                    onCheckedChange = { onChange(config.withGestureApps(config.gestureApps.withEnabled(it))) },
                    modifier = Modifier.testTag("gesture_apps_enabled"))
                ArrowPreference(title = stringResource(R.string.gesture_select_apps),
                    summary = stringResource(R.string.gesture_selected_count, config.gestureApps.selectedPackages().size),
                    onClick = onOpenApps, modifier = Modifier.testTag("gesture_select_apps"))
            }
            Note(stringResource(if (config.gestureApps.enabled) R.string.gesture_apps_active else R.string.gesture_apps_inactive))
            Note(stringResource(R.string.gesture_apps_note))
        }
    }
}

@Composable
internal fun GestureSwitches(mask: Int, enabled: Boolean, profile: Boolean = false,
    onChange: (StylusGestureFeature, Boolean) -> Unit) {
    StylusGestureFeature.entries.forEachIndexed { index, item ->
        SwitchPreference(title = stringResource(item.title), summary = if (profile) null else stringResource(item.summary),
            checked = mask and item.bit != 0, enabled = enabled,
            onCheckedChange = { onChange(item, it) },
            modifier = Modifier.testTag(if (profile) "gesture_profile_$index" else item.key))
    }
}

internal fun AppDomain.brushKind(): StylusGestureConfig.Kind = when (this) {
    AppDomain.PINCH -> StylusGestureConfig.Kind.PINCH
    AppDomain.DOUBLE_TAP -> StylusGestureConfig.Kind.DOUBLE_TAP
    else -> error("This application domain has no native brush gesture")
}

internal fun StylusGestureConfig.Kind.tag() = if (this == StylusGestureConfig.Kind.PINCH) "pinch" else "double_tap"

@Composable
internal fun BrushScreen(config: EnhanceConfig, onBack: () -> Unit, onOpenApps: (AppDomain) -> Unit, onChange: (Bundle) -> Unit) {
    SettingsPage(stringResource(R.string.system_brush_title), "brush_settings", onBack) {
        listOf(AppDomain.PINCH, AppDomain.DOUBLE_TAP).forEach { domain ->
            item(key = domain.name) {
                val kind = domain.brushKind()
                val pinch = kind == StylusGestureConfig.Kind.PINCH
                val rule = config.gesture(kind)
                SettingsGroup(stringResource(if (pinch) R.string.global_pinch_action else R.string.global_double_tap_action)) {
                    SwitchPreference(title = stringResource(if (pinch) R.string.takeover_pinch else R.string.takeover_double_tap),
                        summary = stringResource(R.string.brush_takeover_summary), checked = rule.enabled,
                        onCheckedChange = { onChange(config.withStylusGesture(rule.withEnabled(it))) },
                        modifier = Modifier.testTag("${kind.tag()}_takeover"))
                    BrushActionPreference(rule, rule.globalAction, rule.enabled,
                        stringResource(if (pinch) R.string.global_pinch_action else R.string.global_double_tap_action),
                        "${kind.tag()}_global_action") { onChange(config.withStylusGesture(rule.withGlobalAction(it))) }
                    ArrowPreference(title = stringResource(if (pinch) R.string.pinch_apps else R.string.double_tap_apps),
                        summary = stringResource(R.string.brush_selected_count, rule.selectedPackages().size),
                        onClick = { onOpenApps(domain) }, modifier = Modifier.testTag("${kind.tag()}_select_apps"))
                }
            }
        }
        item(key = "help") { Note(stringResource(R.string.system_brush_note)) }
    }
}

@Composable
internal fun BrushActionPreference(rule: StylusGestureConfig, action: Int, enabled: Boolean, title: String, tag: String, onChange: (Int) -> Unit) {
    OverlayDropdownPreference(title = title, items = stringArrayResource(R.array.system_brush_actions).take(rule.kind.maxAction + 1),
        selectedIndex = action, enabled = enabled, onSelectedIndexChange = onChange, modifier = Modifier.testTag(tag))
}
