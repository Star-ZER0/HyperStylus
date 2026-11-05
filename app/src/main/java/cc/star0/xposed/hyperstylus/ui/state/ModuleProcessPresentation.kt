package cc.star0.xposed.hyperstylus.ui.state

/** Metadata groups display rows only; the selected item still carries the service's opaque handle. */
internal data class ModuleProcessKey(val framework: String, val uid: Int, val pid: Int, val name: String)

internal fun ModuleTargetStatus.processKey() = ModuleProcessKey(frameworkName, uid, pid, processName)
internal fun ModuleReloadOutcome.processKey() = ModuleProcessKey(frameworkName, uid, pid, processName)

internal fun <T> distinctModuleProcesses(
    values: List<T>,
    key: (T) -> ModuleProcessKey,
    state: (T) -> String,
    version: (T) -> Long,
): List<T> {
    fun rank(value: T): Int = when (state(value)) {
        "UP_TO_DATE" -> 3
        "RELOADING" -> 2
        "FAILED" -> 1
        else -> 0
    }
    val chosen = linkedMapOf<ModuleProcessKey, T>()
    values.forEach { candidate ->
        val identity = key(candidate)
        val previous = chosen[identity]
        if (previous == null || rank(candidate) > rank(previous)
            || rank(candidate) == rank(previous) && version(candidate) >= version(previous)) chosen[identity] = candidate
    }
    return chosen.values.toList()
}
