package cc.star0.xposed.hyperstylus.ui.screens

import android.graphics.Bitmap

import cc.star0.xposed.hyperstylus.R
import cc.star0.xposed.hyperstylus.config.EnhanceConfig
import cc.star0.xposed.hyperstylus.ui.apps.AppCatalog
import cc.star0.xposed.hyperstylus.ui.apps.AppCatalogResult
import cc.star0.xposed.hyperstylus.ui.apps.AppEntry
import cc.star0.xposed.hyperstylus.ui.apps.AppListPresentation
import cc.star0.xposed.hyperstylus.ui.apps.AppSortOrder
import cc.star0.xposed.hyperstylus.ui.components.AppListOptionsPopup
import cc.star0.xposed.hyperstylus.ui.components.DropdownGroupDivider
import cc.star0.xposed.hyperstylus.ui.components.Note
import cc.star0.xposed.hyperstylus.ui.components.SettingsGroup
import cc.star0.xposed.hyperstylus.ui.components.SettingsPage
import cc.star0.xposed.hyperstylus.ui.components.keyBindings
import cc.star0.xposed.hyperstylus.ui.navigation.AppDomain
import cc.star0.xposed.hyperstylus.ui.state.GestureSettingsEdits
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.squircle.squircleClip

private fun AppDomain.tag(): String = when (this) {
    AppDomain.KEYS -> "app"
    AppDomain.GESTURES -> "gesture_app"
    AppDomain.PINCH -> "pinch_app"
    AppDomain.DOUBLE_TAP -> "double_tap_app"
}

private fun AppDomain.saved(config: EnhanceConfig): Set<String> = when (this) {
    AppDomain.KEYS -> config.appPackages()
    AppDomain.GESTURES -> config.gestureApps.selectedPackages()
    AppDomain.PINCH, AppDomain.DOUBLE_TAP -> config.gesture(brushKind()).selectedPackages()
}

private fun AppDomain.selected(config: EnhanceConfig): Set<String> = when (this) {
    AppDomain.KEYS -> config.appPackages().filter(config::appEnabled).toSet()
    else -> saved(config)
}

private fun AppDomain.select(config: EnhanceConfig, packages: Set<String>, selected: Boolean): Bundle = when (this) {
    AppDomain.KEYS -> config.withAppsEnabled(packages, selected)
    AppDomain.GESTURES -> config.withGestureAppPackages(packages, selected)
    AppDomain.PINCH, AppDomain.DOUBLE_TAP -> config.withStylusGesture(config.gesture(brushKind()).withPackages(packages, selected))
}

private fun AppDomain.active(config: EnhanceConfig): Boolean = when (this) {
    AppDomain.KEYS -> true
    AppDomain.GESTURES -> config.gestureApps.enabled
    AppDomain.PINCH, AppDomain.DOUBLE_TAP -> config.gesture(brushKind()).enabled
}

private fun AppDomain.title(): Int = when (this) {
    AppDomain.KEYS -> R.string.app_mappings_title
    AppDomain.GESTURES -> R.string.gesture_select_apps
    AppDomain.PINCH -> R.string.pinch_apps
    AppDomain.DOUBLE_TAP -> R.string.double_tap_apps
}

private fun AppDomain.help(): Int = when (this) {
    AppDomain.KEYS -> R.string.app_list_note
    AppDomain.GESTURES -> R.string.gesture_apps_help
    else -> R.string.brush_apps_help
}

@Composable
private fun AppDomain.enableDescription(label: String): String = stringResource(when (this) {
    AppDomain.KEYS -> R.string.app_mapping_checkbox
    AppDomain.GESTURES -> R.string.gesture_app_checkbox
    AppDomain.PINCH -> R.string.pinch_app_checkbox
    AppDomain.DOUBLE_TAP -> R.string.double_tap_app_checkbox
}, label)

/** Application navigation and rule selection use separate touch targets. */
@Composable
internal fun ApplicationSelectionScreen(
    config: EnhanceConfig,
    domain: AppDomain,
    catalog: AppCatalogResult?,
    search: TextFieldState,
    scroll: LazyListState,
    showSystem: Boolean,
    onShowSystem: (Boolean) -> Unit,
    onChange: (Bundle) -> Unit,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onRetry: () -> Unit,
    iconRevision: Int,
    isRefreshing: Boolean,
    sortOrder: AppSortOrder,
    reversed: Boolean,
    onSortChange: (AppSortOrder) -> Unit,
    onReverseChange: (Boolean) -> Unit,
    sortConfiguredFirst: Boolean,
    onSortConfiguredFirstChange: (Boolean) -> Unit,
) {
    var menuVisible by rememberSaveable { mutableStateOf(false) }
    var scaffoldBounds by remember { mutableStateOf(IntRect.Zero) }
    var anchorBounds by remember { mutableStateOf(IntRect.Zero) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val saved = domain.saved(config)
    val selected = domain.selected(config)
    val query = search.text.toString()
    val tag = domain.tag()
    val allApps = remember(catalog, saved) { AppListPresentation.entries(catalog, saved) }
    val visible = remember(allApps, saved, selected, query, showSystem, sortOrder, reversed, sortConfiguredFirst) {
        AppListPresentation.visible(allApps, saved, selected, query, showSystem, sortOrder, reversed, sortConfiguredFirst)
    }
    val systemApps = remember(catalog) { catalog?.apps.orEmpty().filter { it.system }.mapTo(HashSet()) { it.packageName } }
    val bulkAllowed = systemApps.isNotEmpty() && catalog?.accessDenied == false && !catalog.failed
    val scrollBehavior = MiuixScrollBehavior()

    fun hideKeyboard() { focus.clearFocus(); keyboard?.hide() }
    fun scrollToStart() { scope.launch { scroll.scrollToItem(0) } }
    fun closeSearch() {
        hideKeyboard()
        search.edit { replace(0, length, "") }
        searchExpanded = false
    }
    fun openOptions() {
        hideKeyboard()
        menuVisible = !menuVisible
    }
    LaunchedEffect(search) {
        snapshotFlow { search.text.toString() }.drop(1).collect { scroll.scrollToItem(0) }
    }

    Scaffold(modifier = Modifier.onGloballyPositioned { coordinates ->
        val point = coordinates.positionInWindow()
        scaffoldBounds = IntRect(point.x.toInt(), point.y.toInt(),
            point.x.toInt() + coordinates.size.width, point.y.toInt() + coordinates.size.height)
    }, topBar = {
        TopAppBar(title = stringResource(domain.title()), scrollBehavior = scrollBehavior,
            navigationIcon = { AppListBackButton(onBack) },
            actions = {
                Box(Modifier.onGloballyPositioned { coordinates ->
                    val point = coordinates.positionInWindow()
                    anchorBounds = IntRect(point.x.toInt(), point.y.toInt(),
                        point.x.toInt() + coordinates.size.width, point.y.toInt() + coordinates.size.height)
                }) {
                    IconButton(onClick = ::openOptions, minWidth = 48.dp, minHeight = 48.dp,
                        modifier = Modifier.testTag("app_filter")) {
                        Image(MiuixIcons.More, contentDescription = stringResource(R.string.apps_more),
                            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface), modifier = Modifier.size(24.dp))
                    }
                    val sorting = listOf(
                        DropdownItem(text = stringResource(R.string.apps_sort_name), selected = sortOrder == AppSortOrder.NAME,
                            onClick = { onSortChange(AppSortOrder.NAME); scrollToStart() }),
                        DropdownItem(text = stringResource(R.string.apps_sort_package), selected = sortOrder == AppSortOrder.PACKAGE,
                            onClick = { onSortChange(AppSortOrder.PACKAGE); scrollToStart() }),
                        DropdownItem(text = stringResource(R.string.apps_sort_reverse), selected = reversed,
                            icon = { DropdownGroupDivider() },
                            onClick = { onReverseChange(!reversed); scrollToStart() }),
                        DropdownItem(text = stringResource(R.string.apps_sort_configured_first), selected = sortConfiguredFirst,
                            onClick = { onSortConfiguredFirstChange(!sortConfiguredFirst); scrollToStart() }),
                    )
                    val entries = listOf(DropdownEntry(listOf(
                        DropdownItem(text = stringResource(R.string.apps_sort), children = sorting,
                            icon = { modifier -> Image(MiuixIcons.Sort, contentDescription = null, modifier = modifier.size(24.dp),
                                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface)) }),
                        DropdownItem(text = stringResource(R.string.show_system_apps), selected = showSystem,
                            summary = stringResource(R.string.show_system_apps_summary),
                            onClick = { onShowSystem(!showSystem); menuVisible = false; scrollToStart() }),
                        DropdownItem(text = stringResource(R.string.enable_system_apps), enabled = bulkAllowed,
                            selected = systemApps.isNotEmpty() && selected.containsAll(systemApps),
                            summary = stringResource(when (domain) {
                                AppDomain.KEYS -> R.string.apps_bulk_mapping
                                AppDomain.GESTURES -> R.string.gesture_bulk_profiles
                                else -> R.string.brush_bulk_profiles
                            }),
                            onClick = { onChange(domain.select(config, systemApps, !selected.containsAll(systemApps))); menuVisible = false }),
                    )))
                    AppListOptionsPopup(menuVisible, entries, scaffoldBounds, anchorBounds,
                        onDismiss = { menuVisible = false })
                }
            },
            bottomContent = {
                SearchBar(expanded = searchExpanded, onExpandedChange = { if (it) searchExpanded = true else closeSearch() },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("app_search_bar"),
                    inputField = {
                        InputField(query = query, onQueryChange = { value -> search.edit { replace(0, length, value) } },
                            onSearch = { hideKeyboard() }, expanded = searchExpanded,
                            onExpandedChange = { searchExpanded = it }, label = stringResource(R.string.app_search),
                            modifier = Modifier.testTag("app_search"))
                    },
                    outsideEndAction = {
                        TextButton(text = stringResource(R.string.cancel), onClick = ::closeSearch,
                            modifier = Modifier.padding(end = 12.dp).testTag("app_search_cancel"))
                    }) { }
            })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            PullToRefresh(isRefreshing = isRefreshing, onRefresh = onRetry, topAppBarScrollBehavior = scrollBehavior,
                modifier = Modifier.widthIn(max = 800.dp).fillMaxSize(),
                refreshTexts = listOf(stringResource(R.string.apps_refresh_pull), stringResource(R.string.apps_refresh_release),
                    stringResource(R.string.apps_refresh_working), stringResource(R.string.apps_refresh_complete))) {
                LazyColumn(state = scroll,
                    modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection).testTag(tag + "_list"),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "info") {
                        if (catalog != null) Note(stringResource(R.string.apps_result_count, visible.size, selected.size))
                    }
                    if (catalog?.accessDenied == true || catalog?.failed == true) item(key = "read_error") {
                        Card {
                            Note(stringResource(if (catalog.accessDenied) R.string.app_access_denied else R.string.app_load_failed))
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (catalog.accessDenied) TextButton(text = stringResource(R.string.app_request_access),
                                    onClick = onRequestPermission, modifier = Modifier.weight(1f).testTag("request_app_access"))
                                TextButton(text = stringResource(R.string.retry), onClick = onRetry,
                                    modifier = Modifier.weight(1f).testTag("retry_apps"))
                            }
                        }
                    }
                    if (catalog == null && !isRefreshing) item(key = "loading") {
                        Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                        }
                    }
                    if (catalog != null && visible.isEmpty() && !catalog.accessDenied && !catalog.failed) item(key = "empty") {
                        Note(stringResource(R.string.apps_empty))
                    }
                    items(visible, key = { it.packageName }, contentType = { "application" }) { app ->
                        val checked = app.packageName in selected
                        val description = if (checked) stringResource(R.string.app_profile_disable)
                            else domain.enableDescription(app.label)
                        val summary = app.packageName + when {
                            !app.installed -> " · " + stringResource(R.string.apps_uninstalled)
                            app.system -> " · " + stringResource(R.string.apps_system)
                            else -> ""
                        }
                        Card(insideMargin = PaddingValues(4.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(48.dp).testTag(tag + "_enabled_" + app.packageName)
                                    .semantics { contentDescription = description }
                                    .toggleable(checked, role = Role.Checkbox,
                                        onValueChange = { onChange(domain.select(config, setOf(app.packageName), it)) }),
                                    contentAlignment = Alignment.Center) {
                                    Checkbox(state = ToggleableState(checked), onClick = null)
                                }
                                BasicComponent(title = app.label, summary = summary,
                                    startAction = { ApplicationIcon(app, iconRevision, 48.dp) },
                                    endActions = { ForwardArrow() },
                                    insideMargin = PaddingValues(start = 8.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                                    onClick = { onOpenProfile(app.packageName) },
                                    modifier = Modifier.weight(1f).testTag(tag + "_open_" + app.packageName))
                            }
                        }
                    }
                    item(key = "help") { Note(stringResource(domain.help())) }
                }
            }

        }
    }
}

@Composable
internal fun ApplicationProfileScreen(config: EnhanceConfig, domain: AppDomain, app: AppEntry,
    onChange: (Bundle) -> Unit, onBack: () -> Unit) {
    val selected = app.packageName in domain.selected(config)
    val active = domain.active(config)
    SettingsPage(stringResource(R.string.app_profile_title), domain.tag() + "_profile", onBack) {
        item(key = "identity") {
            Card(insideMargin = PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ApplicationIcon(app, 0, 64.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(app.label, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!app.versionName.isNullOrBlank()) Text(stringResource(R.string.app_profile_version, app.versionName, app.versionCode),
                            fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text(app.packageName, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        app.uid?.let { Text(stringResource(R.string.app_profile_uid, it), fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                    }
                }
            }
        }
        item(key = "enabled") {
            Card {
                SwitchPreference(title = stringResource(if (domain == AppDomain.KEYS) R.string.app_mapping_enabled else R.string.policy_profile_selected),
                    checked = selected, onCheckedChange = { onChange(domain.select(config, setOf(app.packageName), it)) },
                    summary = stringResource(when {
                        selected && !active -> R.string.app_profile_pending
                        selected -> if (domain == AppDomain.KEYS) R.string.app_mapping_active else R.string.app_profile_active
                        else -> if (domain == AppDomain.KEYS) R.string.app_mapping_global else R.string.app_profile_global
                    }), modifier = Modifier.testTag(when (domain) {
                        AppDomain.KEYS -> "profile_enabled"
                        AppDomain.GESTURES -> "gesture_profile_selected"
                        else -> domain.brushKind().tag() + "_profile_selected"
                    }))
            }
        }
        when (domain) {
            AppDomain.KEYS -> keyBindings(action = { config.appAction(app.packageName, it) },
                fullAction = { config.appFullAction(app.packageName, it) }, enabled = selected,
                buttonEnabled = config::keyEnabled,
                changeFull = { button, action -> onChange(config.withAppFullAction(app.packageName, button, action)) }) { binding, action ->
                onChange(config.withAppAction(app.packageName, binding, action))
            }
            AppDomain.GESTURES -> item(key = "gestures") {
                val mask = config.gestureApps.profileMask(app.packageName, config.gestureMask())
                SettingsGroup(stringResource(R.string.gesture_title)) {
                    GestureSwitches(mask, selected, profile = true) { feature, checked ->
                        onChange(GestureSettingsEdits.appProfile(config, app.packageName, feature, checked))
                    }
                }
            }
            AppDomain.PINCH, AppDomain.DOUBLE_TAP -> item(key = "brush") {
                val kind = domain.brushKind()
                val rule = config.gesture(kind)
                SettingsGroup(stringResource(if (domain == AppDomain.PINCH) R.string.app_pinch_action else R.string.app_double_tap_action)) {
                    BrushActionPreference(rule, rule.appAction(app.packageName), selected,
                        stringResource(if (domain == AppDomain.PINCH) R.string.app_pinch_action else R.string.app_double_tap_action),
                        kind.tag() + "_app_action") {
                        onChange(config.withStylusGesture(rule.withAppAction(app.packageName, it)))
                    }
                }
                if (!rule.enabled) Note(stringResource(R.string.brush_takeover_disabled))
            }
        }
        item(key = "help") {
            if (!app.installed) Note(stringResource(R.string.app_not_installed))
            Note(stringResource(when (domain) {
                AppDomain.KEYS -> R.string.app_mapping_note
                AppDomain.GESTURES -> R.string.gesture_profile_note
                else -> R.string.brush_apps_help
            }))
        }
    }
}

@Composable
private fun ForwardArrow(back: Boolean = false) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Image(MiuixIcons.Basic.ArrowRight, contentDescription = null,
        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
        modifier = Modifier.size(10.dp, 16.dp).graphicsLayer { scaleX = if (rtl xor back) -1f else 1f })
}

@Composable
private fun AppListBackButton(onBack: () -> Unit) {
    val description = stringResource(R.string.back)
    IconButton(onClick = onBack, minWidth = 48.dp, minHeight = 48.dp,
        modifier = Modifier.testTag("subpage_back").semantics { contentDescription = description }) {
        ForwardArrow(back = true)
    }
}

@Composable
internal fun ApplicationIcon(app: AppEntry, revision: Int, size: Dp) {
    val context = LocalContext.current
    val mode = LocalConfiguration.current.uiMode
    val bitmap by produceState<Bitmap?>(null, app.packageName, app.installed, mode, revision, size) {
        value = null
        if (app.installed) value = withContext(Dispatchers.IO) { AppCatalog.icon(context, app.packageName, size.value.toInt()) }
    }
    val image = bitmap
    if (image != null) Image(image.asImageBitmap(), contentDescription = null,
        modifier = Modifier.size(size).squircleClip(cornerRadius = size / 4))
    else Box(Modifier.size(size).squircleClip(cornerRadius = size / 4).background(MiuixTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center) {
        Text(app.label.take(1), fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSecondaryContainer)
    }
}
