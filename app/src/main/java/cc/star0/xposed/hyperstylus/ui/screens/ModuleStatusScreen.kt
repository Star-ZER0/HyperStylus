package cc.star0.xposed.hyperstylus.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.star0.xposed.hyperstylus.BuildConfig
import cc.star0.xposed.hyperstylus.R
import cc.star0.xposed.hyperstylus.config.ScopePolicy
import cc.star0.xposed.hyperstylus.module.HostProcessPolicy
import cc.star0.xposed.hyperstylus.ui.components.Note
import cc.star0.xposed.hyperstylus.ui.components.SettingsIcon
import cc.star0.xposed.hyperstylus.ui.components.SettingsPage
import cc.star0.xposed.hyperstylus.ui.state.ModuleReloadOutcome
import cc.star0.xposed.hyperstylus.ui.state.ModuleScopeStatus
import cc.star0.xposed.hyperstylus.ui.state.ModuleStatus
import cc.star0.xposed.hyperstylus.ui.state.ModuleTargetStatus
import cc.star0.xposed.hyperstylus.ui.state.processKey
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Presentation only: all requests still use ModuleServices and its original framework handles. */
@Composable
internal fun ModuleStatusScreen(
    status: ModuleStatus,
    onRefresh: () -> Unit,
    onReload: () -> Unit,
    onScopeRetry: () -> Unit,
) {
    val pending = status.targets.count { it.state == "STALE" || it.state == "FAILED" }
    val outcomes = status.outcomes.associateBy { it.processKey() }
    val displayedProcesses = status.targets.map { it.processKey() }.toSet()
    val remainingOutcomes = status.outcomes.filterNot { it.processKey() in displayedProcesses }
    SettingsPage(stringResource(R.string.main_module_status), "main_status_page", reserveNavigationBarSpace = true) {
        item(key = "overview") { ConnectionOverview(status, pending, onRefresh, onReload) }
        status.message?.let { message ->
            item(key = "diagnostic") {
                Card {
                    BasicComponent(title = stringResource(R.string.status_framework_message), summary = message,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
        item(key = "process_heading") {
            StatusSection(stringResource(R.string.module_targets),
                stringResource(R.string.module_targets_count, status.targets.size, pending))
        }
        if (status.targets.isEmpty()) item(key = "empty_targets") {
            Card {
                BasicComponent(title = stringResource(R.string.status_no_processes),
                    summary = stringResource(if (status.connected) R.string.module_targets_empty else R.string.module_connect_help),
                    startAction = { SettingsIcon(MiuixIcons.Info, Modifier.padding(end = 16.dp)) })
            }
        }
        status.targets.forEach { target ->
            item(key = "target_" + target.token) {
                ProcessCard(target, outcomes[target.processKey()])
            }
        }
        item(key = "process_note") { Note(stringResource(R.string.module_targets_note)) }
        if (remainingOutcomes.isNotEmpty()) {
            item(key = "outcome_heading") { StatusSection(stringResource(R.string.status_reload_results)) }
            remainingOutcomes.forEachIndexed { index, outcome ->
                item(key = "outcome_$index") {
                    Card(insideMargin = PaddingValues(20.dp)) {
                        ProcessIdentity(outcome.processName, outcome.frameworkName, outcome.pid, outcome.uid)
                        ReloadResult(outcome)
                    }
                }
            }
        }
        item(key = "scope_heading") {
            StatusSection(stringResource(R.string.status_scope_title), stringResource(R.string.status_scope_summary))
        }
        if (status.scopeChanges.isEmpty()) item(key = "scope_unavailable") {
            Card {
                BasicComponent(title = stringResource(if (status.connected) R.string.status_scope_loading else R.string.module_not_connected),
                    summary = stringResource(if (status.desiredScope.isEmpty()) R.string.status_scope_not_needed else R.string.scope_required))
                // Before per-framework snapshots arrive, never label the union as an individual grant.
                (status.scope + status.desiredScope).sorted().forEach { packageName ->
                    ScopeRow(packageName, stringResource(R.string.status_scope_unknown), StatusTone.NEUTRAL)
                }
            }
        }
        status.scopeChanges.forEach { scope ->
            item(key = "scope_" + scope.frameworkName) { ScopeCard(scope) }
        }
        if (status.scopeChanges.any { it.rejected.isNotEmpty() || it.message != null && it.pending.isEmpty() }) {
            item(key = "scope_retry") {
                TextButton(text = stringResource(R.string.scope_retry), onClick = onScopeRetry,
                    enabled = status.connected && !status.refreshing && !status.reloading &&
                        status.scopeChanges.none { it.working || it.pending.isNotEmpty() },
                    modifier = Modifier.fillMaxWidth().testTag("scope_retry"))
            }
        }
        item(key = "scope_note") { Note(stringResource(R.string.scope_note)) }
        item(key = "environment") {
            Card {
                BasicComponent(title = stringResource(R.string.module_installed_version),
                    summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                BasicComponent(title = stringResource(R.string.module_framework),
                    summary = if (status.connected) listOfNotNull(status.frameworkName, status.frameworkVersion,
                        status.frameworkApi?.let { "API $it" }).joinToString(" · ")
                    else stringResource(R.string.module_not_connected))
            }
            Note(stringResource(R.string.module_reload_help))
        }
    }
}

@Composable
private fun ConnectionOverview(status: ModuleStatus, pending: Int, onRefresh: () -> Unit, onReload: () -> Unit) {
    val busy = status.refreshing || status.reloading
    val colors = MiuixTheme.colorScheme
    Card(insideMargin = PaddingValues(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingsIcon(if (status.connected) MiuixIcons.Link else MiuixIcons.Info)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(if (status.connected) R.string.module_connected else R.string.module_not_connected),
                    fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                Text(if (status.connected) status.frameworkName.orEmpty() else stringResource(R.string.status_waiting_connection),
                    fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
            }
            if (busy) InfiniteProgressIndicator(color = colors.primary, size = 24.dp,
                modifier = Modifier.testTag("module_progress"))
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(when {
            status.reloading -> R.string.status_reload_in_progress
            status.refreshing -> R.string.status_refresh_in_progress
            !status.connected -> R.string.module_connect_help
            status.frameworkApi != null && status.frameworkApi < 102 -> R.string.status_api_unsupported
            pending > 0 -> R.string.status_updates_available
            status.targets.isEmpty() -> R.string.status_connected_no_processes
            else -> R.string.status_connection_note
        }), fontSize = 14.sp, lineHeight = 21.sp, color = colors.onSurfaceVariantSummary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusMetric(status.targets.size, R.string.status_metric_loaded, Modifier.weight(1f))
            StatusMetric(status.targets.count { it.state == "UP_TO_DATE" }, R.string.status_metric_current, Modifier.weight(1f))
            StatusMetric(pending, R.string.status_metric_pending, Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))
        val refresh: @Composable (Modifier) -> Unit = { modifier ->
            TextButton(text = stringResource(if (status.refreshing) R.string.module_refreshing else R.string.module_refresh),
                enabled = !busy, onClick = onRefresh, modifier = modifier.testTag("module_refresh"))
        }
        val reload: @Composable (Modifier) -> Unit = { modifier ->
            TextButton(text = stringResource(if (status.reloading) R.string.module_reloading else R.string.module_reload),
                enabled = status.connected && !busy && pending > 0, onClick = onReload,
                colors = ButtonDefaults.textButtonColorsPrimary(), modifier = modifier.testTag("module_reload"))
        }
        val fontScale = LocalDensity.current.fontScale
        BoxWithConstraints {
            if (maxWidth / fontScale < 280.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    refresh(Modifier.fillMaxWidth())
                    reload(Modifier.fillMaxWidth())
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                refresh(Modifier.weight(1f))
                reload(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusMetric(value: Int, label: Int, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value.toString(), fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface)
        Text(stringResource(label), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun StatusSection(title: String, summary: String? = null) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SmallTitle(title, insideMargin = PaddingValues(0.dp))
        summary?.let { Text(it, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
    }
}

@Composable
private fun ProcessCard(target: ModuleTargetStatus, outcome: ModuleReloadOutcome?) {
    Card(insideMargin = PaddingValues(20.dp), modifier = Modifier.fillMaxWidth().testTag("module_target_${target.token}")) {
        ProcessIdentity(target.processName, target.frameworkName, target.pid, target.uid)
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusLabel(targetState(target.state), targetTone(target.state))
            if (!HostProcessPolicy.expectedProcessName(target.processName)) {
                StatusLabel(stringResource(R.string.module_process_not_target), StatusTone.NEUTRAL)
            }
        }
        Text(stringResource(R.string.module_loaded_version, target.loadedVersionCode),
            modifier = Modifier.padding(top = 10.dp), fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        outcome?.let { ReloadResult(it) }
    }
}

@Composable
private fun ProcessIdentity(processName: String, framework: String, pid: Int, uid: Int) {
    val title = hostLabel(processName)
    Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    if (title != processName) Text(processName, modifier = Modifier.padding(top = 4.dp),
        fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    Text(listOfNotNull(framework.takeIf { it.isNotBlank() }, pid.takeIf { it >= 0 }?.let { "PID $it" },
        uid.takeIf { it >= 0 }?.let { "UID $it" }).joinToString(" · "),
        modifier = Modifier.padding(top = 6.dp), fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun ReloadResult(outcome: ModuleReloadOutcome) {
    Column(Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.status_reload_result, targetState(outcome.status)), fontSize = 13.sp,
            fontWeight = FontWeight.Medium, color = if (targetTone(outcome.status) == StatusTone.ERROR)
                MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface)
        outcome.message?.let {
            Text(it, fontSize = 13.sp, lineHeight = 20.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun ScopeCard(scope: ModuleScopeStatus) {
    val missing = scope.desired - scope.actual
    Card(modifier = Modifier.fillMaxWidth().testTag("module_scope_${scope.frameworkName}")) {
        BasicComponent(title = scope.frameworkName, summary = stringResource(R.string.status_scope_count,
            (scope.actual intersect scope.desired).size, scope.desired.size), bottomAction = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                StatusLabel(stringResource(when {
                    scope.pending.isNotEmpty() -> R.string.status_scope_pending
                    scope.working -> R.string.scope_syncing
                    missing.isNotEmpty() -> R.string.status_scope_missing
                    scope.message != null -> R.string.status_scope_attention
                    scope.desired.isEmpty() -> R.string.status_scope_not_needed
                    else -> R.string.status_scope_granted
                }), if (missing.isNotEmpty() || scope.message != null) StatusTone.NEUTRAL else StatusTone.ACCENT)
                scope.message?.let { Text(it, fontSize = 13.sp, lineHeight = 20.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            }
        })
        (scope.actual + scope.desired).sorted().forEach { packageName ->
            val label = when {
                packageName in scope.actual && packageName in scope.desired -> R.string.status_scope_enabled
                packageName in scope.actual && packageName !in ScopePolicy.MANAGED -> R.string.status_scope_external
                packageName in scope.actual -> R.string.status_scope_not_required
                packageName in scope.pending -> R.string.status_scope_pending
                packageName in scope.rejected -> R.string.status_scope_rejected
                else -> R.string.status_scope_missing
            }
            ScopeRow(packageName, stringResource(label),
                if (packageName in scope.actual && packageName in scope.desired) StatusTone.ACCENT else StatusTone.NEUTRAL)
        }
    }
}

@Composable
private fun ScopeRow(packageName: String, label: String, tone: StatusTone) {
    BasicComponent(title = hostLabel(packageName), summary = packageName, bottomAction = {
        Box(Modifier.padding(top = 6.dp)) { StatusLabel(label, tone) }
    })
}

private enum class StatusTone { ACCENT, NEUTRAL, ERROR }

@Composable
private fun StatusLabel(label: String, tone: StatusTone) {
    val colors = MiuixTheme.colorScheme
    val background = when (tone) {
        StatusTone.ACCENT -> colors.primary.copy(alpha = .12f)
        StatusTone.ERROR -> colors.errorContainer
        StatusTone.NEUTRAL -> colors.secondaryContainer
    }
    val foreground = when (tone) {
        StatusTone.ACCENT -> colors.onSurface
        StatusTone.ERROR -> colors.onErrorContainer
        StatusTone.NEUTRAL -> colors.onSurface
    }
    Card(cornerRadius = 8.dp, insideMargin = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
        colors = CardDefaults.defaultColors(color = background, contentColor = foreground)) {
        Text(label, fontSize = 12.sp, color = foreground)
    }
}

private fun targetTone(state: String) = when (state) {
    "UP_TO_DATE", "SUCCEEDED" -> StatusTone.ACCENT
    "FAILED", "TIMEOUT" -> StatusTone.ERROR
    else -> StatusTone.NEUTRAL
}

@Composable
private fun targetState(state: String): String = when (state) {
    "UP_TO_DATE" -> stringResource(R.string.module_target_current)
    "SUCCEEDED" -> stringResource(R.string.status_reload_succeeded)
    "STALE" -> stringResource(R.string.module_target_stale)
    "FAILED" -> stringResource(R.string.module_target_failed)
    "UNSUPPORTED" -> stringResource(R.string.module_target_unsupported)
    "PROCESS_DIED" -> stringResource(R.string.module_target_exited)
    "IN_PROGRESS", "RELOADING" -> stringResource(R.string.module_reloading)
    "TIMEOUT" -> stringResource(R.string.module_target_timeout)
    else -> state
}

@Composable
private fun hostLabel(name: String): String = when (name) {
    "system", "system_server" -> stringResource(R.string.status_host_system)
    "com.android.systemui" -> stringResource(R.string.status_host_systemui)
    "com.xiaomi.bluetooth" -> stringResource(R.string.status_host_bluetooth)
    "com.miui.securitycore" -> stringResource(R.string.status_host_securitycore)
    "com.miui.home" -> stringResource(R.string.status_host_home)
    "com.miui.securitycenter", "com.miui.securitycenter:ui" -> stringResource(R.string.status_host_securitycenter)
    else -> name
}
