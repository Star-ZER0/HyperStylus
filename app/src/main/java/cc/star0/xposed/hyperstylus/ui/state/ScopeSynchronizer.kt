package cc.star0.xposed.hyperstylus.ui.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Serializes scope changes for one concrete framework connection, using the latest feature union. */
internal class ScopeSynchronizer(
    private val remote: Remote,
    managed: Set<String>,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val onChange: (State) -> Unit,
) : AutoCloseable {
    interface Remote {
        suspend fun getScope(): Set<String>
        suspend fun removeScope(packages: List<String>)
        suspend fun requestScope(packages: List<String>, callback: Callback)
    }

    interface Callback {
        fun approved(packages: List<String>)
        fun failed(message: String)
    }

    data class State(
        val actual: Set<String> = emptySet(),
        val desired: Set<String> = emptySet(),
        val pending: Set<String> = emptySet(),
        val rejected: Set<String> = emptySet(),
        val working: Boolean = false,
        val message: String? = null,
    )

    private data class Request(val ticket: Long, val packages: Set<String>)

    private val managed = managed.toSet()
    private val owner = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private var actual = emptySet<String>()
    private var desired = emptySet<String>()
    private val rejected = linkedSetOf<String>()
    private var pending: Request? = null
    private var nextTicket = 0L
    private var dirty = false
    private var running = false
    private var message: String? = null
    @Volatile private var closed = false

    /** Public commands are dispatched to the supplied scope, normally the app's main scope. */
    fun update(packages: Set<String>) {
        val next = packages.intersect(managed)
        owner.launch {
            if (closed) return@launch
            // A disabled scope may be requested again when a feature needs it later.
            rejected.removeAll(desired - next)
            desired = next
            publish()
            wake()
        }
    }

    /** Re-read real framework state without repeatedly prompting for a rejected request. */
    fun refresh() {
        owner.launch {
            if (!closed) wake()
        }
    }

    /** Explicit user retry also permits a previously rejected scope to be requested again. */
    fun retry() {
        owner.launch {
            if (closed) return@launch
            rejected.clear()
            message = null
            wake()
        }
    }

    private fun publish() {
        if (!closed) onChange(State(actual.toSet(), desired.toSet(), pending?.packages?.toSet() ?: emptySet(),
            rejected.toSet(), running, message))
    }

    private fun wake() {
        dirty = true
        if (running) return
        // Set before launch: an immediate dispatcher or synchronous callback must not start a second worker.
        running = true
        owner.launch {
            try {
                while (dirty && !closed) {
                    dirty = false
                    publish()
                    reconcile()
                }
            } finally {
                running = false
                publish()
            }
        }
    }

    private suspend fun readActual() {
        actual = withContext(io) { remote.getScope().toSet() }
        rejected.retainAll(desired - actual)
        if (pending == null && desired.all { it in actual } && actual.intersect(managed) == desired) {
            message = null
        }
        publish()
    }

    private suspend fun reconcile() {
        try {
            readActual()
            // update() may have run while getScope suspended; compute removals from the current union.
            val unwanted = actual.intersect(managed) - desired
            if (unwanted.isNotEmpty()) {
                try {
                    withContext(io) { remote.removeScope(unwanted.sorted()) }
                    readActual()
                    val remaining = actual.intersect(managed) - desired
                    if (remaining.isNotEmpty()) {
                        message = "框架未移除作用域：${remaining.sorted().joinToString()}"
                        publish()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: RuntimeException) {
                    // An unsafe or failed removal must not prevent another enabled feature requesting its host.
                    message = failure.message ?: "移除作用域失败"
                    publish()
                }
            }

            val missing = desired - actual - rejected
            if (pending != null || missing.isEmpty() || closed) return
            val request = Request(++nextTicket, missing.toSet())
            pending = request
            publish()
            try {
                withContext(io) {
                    remote.requestScope(request.packages.sorted(), object : Callback {
                        override fun approved(packages: List<String>) {
                            complete(request, packages.toSet(), null)
                        }
                        override fun failed(message: String) {
                            complete(request, emptySet(), message)
                        }
                    })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RuntimeException) {
                settle(request, emptySet(), failure.message ?: "申请作用域失败")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            message = failure.message ?: "同步框架作用域失败"
            publish()
        }
    }

    /** Scope callbacks can arrive from Binder threads, after the switch or connection has changed. */
    private fun complete(request: Request, approved: Set<String>, failure: String?) {
        owner.launch {
            settle(request, approved, failure)
        }
    }

    private fun settle(request: Request, approved: Set<String>, failure: String?) {
        if (closed || pending?.ticket != request.ticket) return
        pending = null
        val required = request.packages.intersect(desired)
        // Verify every completion against getScope. An approved-but-still-missing scope must not
        // immediately cause another prompt, and partial approval must preserve previous scopes.
        rejected.addAll(required)
        message = when {
            required.isEmpty() -> null
            failure != null -> failure
            (required - approved).isNotEmpty() -> "部分作用域未获批准：${(required - approved).sorted().joinToString()}"
            else -> "正在核对框架批准的作用域"
        }
        publish()
        wake()
    }

    /** Removing a connection cannot cancel its remote approval UI; all its late callbacks are ignored. */
    override fun close() {
        closed = true
        owner.cancel()
    }
}
