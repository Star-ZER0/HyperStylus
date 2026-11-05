package cc.star0.xposed.hyperstylus.ui.state

import android.os.Bundle
import android.content.Context
import cc.star0.xposed.hyperstylus.BuildConfig
import cc.star0.xposed.hyperstylus.config.EnhanceConfig
import cc.star0.xposed.hyperstylus.config.ScopePolicy
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

data class ModuleTargetStatus(
    val token: Long,
    val processName: String,
    val pid: Int,
    val loadedVersionCode: Long,
    val state: String,
    val frameworkName: String = "",
    val uid: Int = -1,
)

data class ModuleReloadOutcome(
    val processName: String, val status: String, val message: String?,
    val frameworkName: String = "", val uid: Int = -1, val pid: Int = -1,
)

data class ModuleScopeStatus(
    val frameworkName: String,
    val actual: Set<String>,
    val desired: Set<String>,
    val pending: Set<String>,
    val rejected: Set<String>,
    val working: Boolean,
    val message: String?,
)

data class ModuleStatus(
    val connected: Boolean = false,
    val frameworkName: String? = null,
    val frameworkVersion: String? = null,
    val frameworkApi: Int? = null,
    val scope: Set<String> = emptySet(),
    val targets: List<ModuleTargetStatus> = emptyList(),
    val refreshing: Boolean = false,
    val reloading: Boolean = false,
    val message: String? = null,
    val outcomes: List<ModuleReloadOutcome> = emptyList(),
    val desiredScope: Set<String> = emptySet(),
    val scopeChanges: List<ModuleScopeStatus> = emptyList(),
)

/** App-process service connection. Target handles are never reconstructed from a PID or process name. */
object ModuleServices {
    private data class Target(val framework: String, val service: XposedService, val handle: HookedTarget, val token: Long)
    private data class Snapshot(val state: ModuleStatus, val targets: List<Target>)
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableStatus = MutableStateFlow(ModuleStatus())
    val status: StateFlow<ModuleStatus> = mutableStatus.asStateFlow()
    private val services = FrameworkConnections<XposedService>()
    private var targets = emptyList<Target>()
    private var initialized = false
    private val nextToken = AtomicLong()
    private val nextBinding = AtomicLong()
    private var revision = 0L
    private var pending = 0
    private val pendingTargets = linkedMapOf<Long, Target>()
    private data class ScopeConnection(val service: XposedService, val sync: ScopeSynchronizer,
        var state: ScopeSynchronizer.State = ScopeSynchronizer.State())
    private val scopeConnections = linkedMapOf<String, ScopeConnection>()
    @Volatile private var desiredScope = ScopePolicy.required(EnhanceConfig.defaults())
    @Volatile private var configuration = EnhanceConfig.defaults()
    private var removalGuard: ScopeRemovalGuard? = null

    /** Called after durable save and publication to injected hosts, independently of hot reload. */
    fun configure(config: EnhanceConfig) {
        val required = ScopePolicy.required(config)
        main.launch {
            configuration = config
            desiredScope = required
            scopeConnections.values.forEach { it.sync.update(required) }
            publishScopes()
        }
    }

    fun retryScope() {
        main.launch { scopeConnections.values.forEach { it.sync.retry() } }
    }

    private fun scopeRows() = scopeConnections.map { (name, connection) ->
        val state = connection.state
        ModuleScopeStatus(name, state.actual, desiredScope, state.pending, state.rejected, state.working, state.message)
    }

    private fun publishScopes() {
        mutableStatus.value = mutableStatus.value.copy(desiredScope = desiredScope, scopeChanges = scopeRows())
    }

    private fun bindScope(name: String, service: XposedService) {
        scopeConnections[name]?.takeIf { it.service === service }?.let {
            it.sync.update(desiredScope); return
        }
        scopeConnections.remove(name)?.sync?.close()
        val sync = ScopeSynchronizer(object : ScopeSynchronizer.Remote {
            override suspend fun getScope() = service.scope.toSet()
            override suspend fun removeScope(packages: List<String>) {
                var removable = packages.filter { it !in desiredScope }
                if (ScopePolicy.SYSTEM in removable) {
                    val loadedProcesses = service.runningTargets.map { it.processName }
                    val ready = !ScopeRemovalGuard.systemModuleLoaded(loadedProcesses)
                        || removalGuard?.prepareIfLoaded(configuration, loadedProcesses) == true
                    removable = packages.filter { it !in desiredScope }
                    if (ScopePolicy.SYSTEM in removable && !ready)
                        throw IllegalStateException("系统进程尚未确认按键释放和原生设置恢复，暂时保留 system 作用域；请重启手机加载新版模块后重试")
                }
                if (removable.isNotEmpty()) service.removeScope(removable)
            }
            override suspend fun requestScope(packages: List<String>, callback: ScopeSynchronizer.Callback) {
                service.requestScope(packages, object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(approved: List<String>) { callback.approved(approved) }
                    override fun onScopeRequestFailed(message: String) { callback.failed(message) }
                })
            }
        }, ScopePolicy.MANAGED, main) { state ->
            val connection = scopeConnections[name]
            if (connection != null && connection.service === service) {
                val changed = connection.state.actual != state.actual
                connection.state = state
                publishScopes()
                if (changed) refresh()
            }
        }
        scopeConnections[name] = ScopeConnection(service, sync)
        sync.update(desiredScope)
    }

    @Synchronized
    fun initialize(context: Context? = null) {
        if (context != null) removalGuard = ScopeRemovalGuard(context)
        if (initialized) return
        initialized = true
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                val sequence = nextBinding.incrementAndGet()
                main.launch {
                    try {
                        val name = withContext(Dispatchers.IO) { service.frameworkName }
                        if (services.bind(name, service, sequence)) { bindScope(name, service); refresh() }
                    } catch (failure: RuntimeException) {
                        mutableStatus.value = mutableStatus.value.copy(message = failure.message ?: "框架连接失败")
                    }
                }
            }
            override fun onServiceDied(service: XposedService) {
                main.launch {
                    val removed = services.remove(service)
                    scopeConnections.entries.removeAll { (_, connection) ->
                        if (connection.service === service) { connection.sync.close(); true } else false
                    }
                    publishScopes()
                    pendingTargets.values.filter { it.service === service }.toList().forEach {
                        finish(it, HotReloadResult.Status.PROCESS_DIED.name, "框架服务连接已断开")
                    }
                    targets = targets.filterNot { it.service === service }
                    if (removed) refresh()
                }
            }
        })
    }

    fun refresh() {
        main.launch {
            scopeConnections.values.forEach { it.sync.refresh() }
            if (pending != 0) return@launch
            val request = ++revision
            val connected = services.snapshot()
            if (connected.isEmpty()) {
                targets = emptyList()
                mutableStatus.value = ModuleStatus(outcomes = mutableStatus.value.outcomes,
                    desiredScope = desiredScope, scopeChanges = scopeRows())
                return@launch
            }
            mutableStatus.value = mutableStatus.value.copy(refreshing = true)
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    val found = arrayListOf<Target>()
                    val names = arrayListOf<String>()
                    val versions = arrayListOf<String>()
                    val allowed = linkedSetOf<String>()
                    var api = Int.MAX_VALUE
                    for ((name, service) in connected) {
                        names += name
                        versions += service.frameworkVersion
                        api = minOf(api, service.apiVersion)
                        allowed += service.scope
                        // API 102 is the required contract. An unsupported service is shown as such.
                        if (service.apiVersion < XposedService.API_102) continue
                        for (target in service.runningTargets) found += Target(name, service, target, nextToken.incrementAndGet())
                    }
                    val unique = distinctModuleProcesses(found,
                        key = { ModuleProcessKey(it.framework, it.handle.uid, it.handle.pid, it.handle.processName) },
                        state = { it.handle.state.name }, version = { it.handle.loadedVersionCode })
                    val rows = unique.map {
                        ModuleTargetStatus(it.token, it.handle.processName, it.handle.pid,
                            it.handle.loadedVersionCode, it.handle.state.name, it.framework, it.handle.uid)
                    }
                    Snapshot(ModuleStatus(true, names.joinToString(" · "), versions.joinToString(" · "), api,
                        allowed, rows, message = if (api < XposedService.API_102) "框架服务需要支持 libxposed API 102" else null), unique)
                }
                if (request != revision) return@launch
                targets = snapshot.targets
                mutableStatus.value = snapshot.state.copy(outcomes = mutableStatus.value.outcomes,
                    desiredScope = desiredScope, scopeChanges = scopeRows())
            } catch (failure: RuntimeException) {
                if (request == revision) mutableStatus.value = mutableStatus.value.copy(
                    refreshing = false, message = failure.message ?: "读取框架状态失败")
            }
        }
    }

    fun reloadPending() {
        main.launch {
            if (pending != 0 || mutableStatus.value.refreshing) return@launch
            val requests = targets.filter { it.handle.state == HookedTarget.State.STALE || it.handle.state == HookedTarget.State.FAILED }
            if (requests.isEmpty()) return@launch
            ++revision
            pending = requests.size
            pendingTargets.putAll(requests.associateBy { it.token })
            mutableStatus.value = mutableStatus.value.copy(reloading = true, message = null, outcomes = emptyList())
            for (target in requests) {
                try {
                    withContext(Dispatchers.IO) {
                        target.service.hotReloadModule(target.handle, Bundle().apply {
                            putString("request", "module-status")
                            putLong("installedVersionCode", BuildConfig.VERSION_CODE.toLong())
                        }) { _, result ->
                            main.launch { finish(target, result.status().name, result.message()) }
                        }
                    }
                } catch (failure: RuntimeException) {
                    finish(target, HotReloadResult.Status.FAILED.name, failure.message)
                }
            }
            main.launch {
                delay(30_000)
                requests.filter { it.token in pendingTargets }.forEach {
                    finish(it, "TIMEOUT", "暂未收到框架结果，请刷新进程状态")
                }
            }
        }
    }

    private fun finish(target: Target, result: String, message: String?) {
        if (pendingTargets.remove(target.token) == null) return
        val detail = message ?: when (result) {
            "FAILED" -> "旧模块拒绝热重载，请重启目标进程"
            "UNSUPPORTED" -> "框架或目标进程不支持本次热重载"
            "IN_PROGRESS" -> "目标进程已有热重载请求"
            "PROCESS_DIED" -> "目标进程已退出"
            else -> null
        }
        val outcome = ModuleReloadOutcome(target.handle.processName, result, detail,
            target.framework, target.handle.uid, target.handle.pid)
        mutableStatus.value = mutableStatus.value.copy(outcomes =
            mutableStatus.value.outcomes.filterNot { it.processKey() == outcome.processKey() } + outcome)
        pending--
        if (pending == 0) {
            mutableStatus.value = mutableStatus.value.copy(reloading = false)
            refresh()
        }
    }
}
