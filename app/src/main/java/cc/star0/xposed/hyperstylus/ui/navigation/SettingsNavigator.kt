package cc.star0.xposed.hyperstylus.ui.navigation

import android.app.Activity

import cc.star0.xposed.hyperstylus.R
import cc.star0.xposed.hyperstylus.config.EnhanceConfig
import cc.star0.xposed.hyperstylus.ui.apps.AppCatalog
import cc.star0.xposed.hyperstylus.ui.apps.AppCatalogResult
import cc.star0.xposed.hyperstylus.ui.apps.AppEntry
import cc.star0.xposed.hyperstylus.ui.apps.AppListAccess
import cc.star0.xposed.hyperstylus.ui.apps.AppSortOrder
import cc.star0.xposed.hyperstylus.ui.screens.ApplicationProfileScreen
import cc.star0.xposed.hyperstylus.ui.screens.ApplicationSelectionScreen
import cc.star0.xposed.hyperstylus.ui.screens.BatteryScreen
import cc.star0.xposed.hyperstylus.ui.screens.BrushScreen
import cc.star0.xposed.hyperstylus.ui.screens.GesturesScreen
import cc.star0.xposed.hyperstylus.ui.screens.KeysScreen
import cc.star0.xposed.hyperstylus.ui.screens.OverviewScreen
import cc.star0.xposed.hyperstylus.ui.screens.MainDestinationScreen
import cc.star0.xposed.hyperstylus.ui.components.MainNavigationRail
import cc.star0.xposed.hyperstylus.ui.state.ModuleServices
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.clipToBounds
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavController
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.job

/** Route, domain and selected package survive recreation; each list retains its own query and scroll. */
@Composable
internal fun SettingsNavigator(config: EnhanceConfig, catalogRevision: Int, onChange: (Bundle) -> Unit) {
    val backStack = rememberSaveable(saver = MainRouteSaver) { navBackStackOf(MainRoot) }
    var mainPageName by rememberSaveable { mutableStateOf(MainPage.MODULE_STATUS.name) }
    val rootPage = MainPage.valueOf(mainPageName)
    val navigator = remember(backStack) { NavController(backStack) }
    val pager = rememberPagerState(initialPage = rootPage.ordinal,
        pageCount = { MainPage.entries.size })
    val navigationScope = rememberCoroutineScope()
    val railState = rememberNavigationRailState()
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    val selectedRoute = backStack.lastOrNull() as? SettingsRoute
    val page = selectedRoute?.page ?: FeaturePage.HOME
    val moduleStatus by ModuleServices.status.collectAsState()
    val queries = listOf(rememberTextFieldState(), rememberTextFieldState(), rememberTextFieldState(), rememberTextFieldState())
    val lists = listOf(rememberLazyListState(), rememberLazyListState(), rememberLazyListState(), rememberLazyListState())
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var sortName by rememberSaveable { mutableStateOf(AppSortOrder.NAME.name) }
    var reverseSort by rememberSaveable { mutableStateOf(false) }
    var sortConfiguredFirst by rememberSaveable { mutableStateOf(true) }
    val sortOrder = AppSortOrder.valueOf(sortName)
    var retries by remember { mutableIntStateOf(0) }
    var catalogLoading by remember { mutableStateOf(false) }
    var attemptedAutomaticRequest by rememberSaveable { mutableStateOf(false) }
    var requestedRuntimePermission by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { retries++ }
    val needsApps = page == FeaturePage.APPS || page == FeaturePage.PROFILE
    val catalog by produceState<AppCatalogResult?>(null, needsApps, catalogRevision, retries) {
        if (needsApps) {
            catalogLoading = true
            try {
                value = withContext(Dispatchers.IO) { AppCatalog.read(context) }
            } finally {
                catalogLoading = false
            }
        }
    }

    fun showPermissionSettings() {
        if (!AppListAccess.openSettings(context)) {
            Toast.makeText(context, R.string.app_access_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }
    fun requestPermission(manual: Boolean) {
        val missing = AppListAccess.missingRuntimePermission(context)
        val rationale = missing != null && (context as? Activity)?.shouldShowRequestPermissionRationale(missing) == true
        if (missing != null && (!manual || !requestedRuntimePermission || rationale)) {
            requestedRuntimePermission = true
            try { permissions.launch(missing) } catch (_: RuntimeException) { showPermissionSettings() }
        } else showPermissionSettings()
    }
    LaunchedEffect(needsApps, catalog?.accessDenied) {
        if (!needsApps) attemptedAutomaticRequest = false
        if (needsApps && catalog?.accessDenied == true && !attemptedAutomaticRequest) {
            attemptedAutomaticRequest = true
            requestPermission(false)
        }
    }
    fun navigate(route: SettingsRoute) {
        if (backStack.lastOrNull() == route) return
        if (route in backStack) navigator.popUntil { it == route } else navigator.push(route)
    }
    fun openApps(value: AppDomain) { navigate(SettingsRoute(FeaturePage.APPS, value)) }
    fun back() { navigator.pop() }
    fun selectRoot(destination: MainPage) {
        navigationJob?.cancel()
        navigationJob = null
        val leavingFeature = backStack.size > 1
        if (leavingFeature) navigator.popUntil { it == MainRoot }
        mainPageName = destination.name
        if (leavingFeature) pager.requestScrollToPage(destination.ordinal)
        else if (pager.currentPage != destination.ordinal || pager.isScrollInProgress) {
            navigationJob = navigationScope.launch {
                val currentJob = coroutineContext.job
                try {
                    pager.animateScrollToPage(destination.ordinal)
                } finally {
                    if (navigationJob === currentJob) navigationJob = null
                }
            }
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().drop(1).collect { index ->
            if (backStack.size == 1 && navigationJob?.isActive != true) mainPageName = MainPage.entries[index].name
        }
    }
    BackHandler(backStack.size == 1 && rootPage != MainPage.MODULE_STATUS) {
        selectRoot(MainPage.MODULE_STATUS)
    }
    val displayedPage = if (selectedRoute != null || navigationJob != null) rootPage
        else MainPage.entries[pager.currentPage]
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val direction = LocalLayoutDirection.current
        Row(Modifier.fillMaxSize()) {
            if (wide && direction == LayoutDirection.Ltr) MainNavigationRail(displayedPage, railState, ::selectRoot)
            Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                NavDisplay(navController = navigator, modifier = Modifier.fillMaxSize(), onBack = ::back,
                    effects = NavDisplayEffects(blockInputDuringTransition = true)) {
                    entry<MainRoot> {
                        MainDestinationScreen(displayedPage, moduleStatus,
                            onSelect = { if (backStack.size == 1) selectRoot(it) },
                            onOpenFeature = { navigate(SettingsRoute(it)) },
                            onRefresh = ModuleServices::refresh, onReload = ModuleServices::reloadPending,
                            showBottomNavigation = !wide, pager = pager, onScopeRetry = ModuleServices::retryScope)
                    }
                    entry<SettingsRoute> { route ->
                        val domain = route.domain
                        when (route.page) {
                            FeaturePage.BATTERY -> BatteryScreen(config, ::back, onChange)
                            FeaturePage.KEYS -> KeysScreen(config, ::back, { openApps(AppDomain.KEYS) }, onChange)
                            FeaturePage.GESTURES -> GesturesScreen(config, ::back, { openApps(AppDomain.GESTURES) }, onChange)
                            FeaturePage.BRUSH -> BrushScreen(config, ::back, ::openApps, onChange)
                            FeaturePage.APPS -> ApplicationSelectionScreen(config, domain, catalog, queries[domain.ordinal], lists[domain.ordinal],
                                showSystem, { showSystem = it }, onChange, ::back,
                                onOpenProfile = { navigate(SettingsRoute(FeaturePage.PROFILE, domain, it)) },
                                onRequestPermission = { requestPermission(true) }, onRetry = { retries++ }, iconRevision = catalogRevision + retries,
                                isRefreshing = catalogLoading, sortOrder = sortOrder, reversed = reverseSort,
                                onSortChange = { sortName = it.name }, onReverseChange = { reverseSort = it },
                                sortConfiguredFirst = sortConfiguredFirst, onSortConfiguredFirstChange = { sortConfiguredFirst = it })
                            FeaturePage.PROFILE -> {
                                val unknownInstallation = catalog == null || catalog?.accessDenied == true || catalog?.failed == true
                                val app = catalog?.apps?.firstOrNull { it.packageName == route.packageName }
                                    ?: AppEntry(route.packageName, route.packageName, installed = unknownInstallation)
                                ApplicationProfileScreen(config, domain, app, onChange, ::back)
                            }
                            FeaturePage.HOME -> OverviewScreen { navigate(SettingsRoute(it)) }
                        }
                    }
                }
            }
            if (wide && direction == LayoutDirection.Rtl) MainNavigationRail(displayedPage, railState, ::selectRoot)
        }
    }
}
