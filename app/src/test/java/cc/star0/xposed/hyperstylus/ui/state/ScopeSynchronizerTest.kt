package cc.star0.xposed.hyperstylus.ui.state

import cc.star0.xposed.hyperstylus.common.Protocol
import cc.star0.xposed.hyperstylus.config.ScopePolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext

class ScopeSynchronizerTest {
    private companion object {
        const val SYSTEM = "system"
        const val UI = "com.android.systemui"
        const val BLE = "com.xiaomi.bluetooth"
        const val UNRELATED = "user.other.app"
        val MANAGED = setOf(SYSTEM, UI, BLE)
    }

    /** A deterministic main/IO queue exercises real coroutines without an additional test dependency. */
    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun drain() {
            var count = 0
            while (queue.isNotEmpty()) {
                check(++count <= 1_000) { "Scope synchronization did not become idle" }
                queue.removeFirst().run()
            }
        }
    }

    private class Backend(initial: Set<String> = emptySet()) : ScopeSynchronizer.Remote {
        data class Request(val packages: List<String>, val callback: ScopeSynchronizer.Callback)
        val actual = initial.toMutableSet()
        val requests = arrayListOf<Request>()
        val removals = arrayListOf<List<String>>()
        var reads = 0
        var readGate: CompletableDeferred<Unit>? = null
        var removeFailure: String? = null
        var ignoreRemoval = false
        var immediateApproval = false
        override suspend fun getScope(): Set<String> {
            reads++
            readGate?.await()
            return actual.toSet()
        }
        override suspend fun removeScope(packages: List<String>) {
            removals += packages.toList()
            removeFailure?.let { throw IllegalStateException(it) }
            if (!ignoreRemoval) actual.removeAll(packages.toSet())
        }
        override suspend fun requestScope(packages: List<String>, callback: ScopeSynchronizer.Callback) {
            requests += Request(packages.toList(), callback)
            if (immediateApproval) {
                actual.addAll(packages)
                callback.approved(packages)
            }
        }
        fun approve(index: Int = requests.lastIndex, packages: Set<String> = requests[index].packages.toSet()) {
            actual.addAll(packages)
            requests[index].callback.approved(packages.toList())
        }
        fun reject(index: Int = requests.lastIndex) { requests[index].callback.failed("用户拒绝作用域申请") }
    }

    private class Rig(val backend: Backend = Backend(), managed: Set<String> = MANAGED) : AutoCloseable {
        val dispatcher = QueueDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val changes = arrayListOf<ScopeSynchronizer.State>()
        val sync = ScopeSynchronizer(backend, managed, scope, dispatcher) { changes += it }
        val state get() = changes.last()
        fun drain() = dispatcher.drain()
        override fun close() {
            sync.close()
            scope.cancel()
            drain()
        }
    }

    @Test
    fun aSharedScopeRemainsUntilTheLastFeatureStopsNeedingIt() {
        Rig(Backend(setOf(BLE))).use { rig ->
            // Both display and charging resolve to the same package union.
            rig.sync.update(setOf(BLE)); rig.drain()
            rig.sync.update(setOf(BLE)); rig.drain()
            assertTrue(rig.backend.removals.isEmpty())
            rig.sync.update(emptySet()); rig.drain()
            assertEquals(listOf(listOf(BLE)), rig.backend.removals)
            assertTrue(rig.state.actual.isEmpty())
        }
    }

    @Test
    fun theActualManagedSetRevokesLateSidebarApprovalWithoutRemovingSharedOrUnknownScopes() {
        Rig(Backend(setOf(UI, UNRELATED)), ScopePolicy.MANAGED).use { rig ->
            rig.sync.update(setOf(UI, Protocol.SECURITY_CENTER)); rig.drain()
            assertEquals(listOf(Protocol.SECURITY_CENTER), rig.backend.requests.single().packages)
            rig.sync.update(setOf(UI)); rig.drain()
            rig.backend.approve(); rig.drain()
            assertEquals(listOf(listOf(Protocol.SECURITY_CENTER)), rig.backend.removals)
            assertEquals(setOf(UI, UNRELATED), rig.state.actual)
            assertEquals(1, rig.backend.requests.size)
        }
    }

    @Test
    fun theActualSixManagedHostsCanBeRemovedWhileUnknownFrameworkGrantsRemain() {
        Rig(Backend(ScopePolicy.MANAGED + UNRELATED), ScopePolicy.MANAGED).use { rig ->
            rig.sync.update(setOf(Protocol.SECURITY_CENTER)); rig.drain()
            assertEquals(ScopePolicy.MANAGED - Protocol.SECURITY_CENTER, rig.backend.removals.single().toSet())
            assertEquals(setOf(Protocol.SECURITY_CENTER, UNRELATED), rig.state.actual)
            assertTrue(rig.backend.requests.isEmpty())
        }
    }

    @Test
    fun onlyUnusedManagedPackagesAreRemovedAndUnknownScopeIsPreserved() {
        Rig(Backend(setOf(SYSTEM, UI, BLE, UNRELATED))).use { rig ->
            rig.sync.update(setOf(UI)); rig.drain()
            assertEquals(listOf(listOf(BLE, SYSTEM)), rig.backend.removals)
            assertEquals(setOf(UI, UNRELATED), rig.state.actual)
            assertTrue(rig.backend.requests.isEmpty())
        }
    }

    @Test
    fun approvalIsVerifiedAgainstRealScopeAndKeepsPreviouslyGrantedPackages() {
        Rig(Backend(setOf(UI))).use { rig ->
            rig.sync.update(setOf(UI, BLE)); rig.drain()
            assertEquals(listOf(BLE), rig.backend.requests.single().packages)
            assertEquals(setOf(BLE), rig.state.pending)
            assertEquals(setOf(UI), rig.state.actual)
            rig.backend.approve(); rig.drain()
            assertEquals(setOf(UI, BLE), rig.state.actual)
            assertTrue(rig.state.pending.isEmpty())
            assertTrue(rig.state.rejected.isEmpty())
            assertNull(rig.state.message)
            assertEquals(1, rig.backend.requests.size)
        }
    }

    @Test
    fun partialApprovalDoesNotPromptAgainOnRefreshOrAnUnrelatedConfigurationSave() {
        Rig().use { rig ->
            rig.sync.update(setOf(UI, BLE)); rig.drain()
            rig.backend.approve(packages = setOf(UI)); rig.drain()
            assertEquals(setOf(UI), rig.state.actual)
            assertEquals(setOf(BLE), rig.state.rejected)
            rig.sync.refresh(); rig.sync.update(setOf(UI, BLE)); rig.drain()
            assertEquals(1, rig.backend.requests.size)
            assertEquals(setOf(BLE), rig.state.rejected)
        }
    }

    @Test
    fun rejectionRemainsVisibleAndExplicitRetryAllowsANewRequest() {
        Rig().use { rig ->
            rig.sync.update(setOf(UI)); rig.drain()
            rig.backend.reject(); rig.drain()
            assertEquals("用户拒绝作用域申请", rig.state.message)
            assertEquals(setOf(UI), rig.state.rejected)
            rig.sync.refresh(); rig.drain()
            assertEquals(1, rig.backend.requests.size)
            rig.sync.retry(); rig.drain()
            assertEquals(2, rig.backend.requests.size)
            assertEquals(setOf(UI), rig.state.pending)
        }
    }

    @Test
    fun disablingThenReenablingAScopeClearsItsPriorRejection() {
        Rig().use { rig ->
            rig.sync.update(setOf(UI)); rig.drain()
            rig.backend.reject(); rig.drain()
            rig.sync.update(emptySet()); rig.drain()
            assertTrue(rig.state.rejected.isEmpty())
            rig.sync.update(setOf(UI)); rig.drain()
            assertEquals(2, rig.backend.requests.size)
        }
    }

    @Test
    fun lateApprovalAfterDisableIsRemovedWithoutAnotherPrompt() {
        Rig().use { rig ->
            rig.sync.update(setOf(UI)); rig.drain()
            rig.sync.update(emptySet()); rig.sync.refresh(); rig.drain()
            assertEquals(1, rig.backend.requests.size)
            assertEquals(setOf(UI), rig.state.pending)
            rig.backend.approve(); rig.drain()
            assertEquals(listOf(listOf(UI)), rig.backend.removals)
            assertTrue(rig.state.actual.isEmpty())
            assertTrue(rig.state.pending.isEmpty())
            assertEquals(1, rig.backend.requests.size)
        }
    }

    @Test
    fun anotherRequiredPackageWaitsForTheOutstandingRequestAndUsesTheLatestUnion() {
        Rig().use { rig ->
            rig.sync.update(setOf(UI)); rig.drain()
            rig.sync.update(setOf(BLE)); rig.drain()
            assertEquals(1, rig.backend.requests.size)
            rig.backend.approve(); rig.drain()
            assertEquals(listOf(listOf(UI)), rig.backend.removals)
            assertEquals(listOf(BLE), rig.backend.requests[1].packages)
            assertEquals(setOf(BLE), rig.state.desired)
        }
    }

    @Test
    fun aConfigurationChangeDuringScopeReadPreventsAnObsoleteRemoval() {
        Rig(Backend(setOf(UI))).use { rig ->
            val gate = CompletableDeferred<Unit>()
            rig.backend.readGate = gate
            rig.sync.update(emptySet()); rig.drain()
            rig.sync.update(setOf(UI)); rig.drain()
            gate.complete(Unit); rig.drain()
            assertTrue(rig.backend.removals.isEmpty())
            assertTrue(rig.backend.requests.isEmpty())
            assertEquals(setOf(UI), rig.state.actual)
        }
    }

    @Test
    fun anApprovalCallbackCannotPretendThatTheFrameworkActuallyGrantedScope() {
        Rig().use { rig ->
            rig.sync.update(setOf(UI)); rig.drain()
            // Callback says approved, but the authoritative getter still has no package.
            rig.backend.requests.single().callback.approved(listOf(UI)); rig.drain()
            assertTrue(rig.state.actual.isEmpty())
            assertEquals(setOf(UI), rig.state.rejected)
            rig.sync.refresh(); rig.drain()
            assertEquals(1, rig.backend.requests.size)
            rig.backend.actual.add(UI)
            rig.sync.refresh(); rig.drain()
            assertEquals(setOf(UI), rig.state.actual)
            assertTrue(rig.state.rejected.isEmpty())
            assertNull(rig.state.message)
        }
    }

    @Test
    fun removalFailureAndAFrameworkThatKeepsScopeAreNotReportedAsSuccess() {
        Rig(Backend(setOf(UI))).use { rig ->
            rig.backend.removeFailure = "框架服务已断开"
            rig.sync.update(emptySet()); rig.drain()
            assertEquals(setOf(UI), rig.state.actual)
            assertEquals("框架服务已断开", rig.state.message)
            rig.backend.removeFailure = null
            rig.backend.ignoreRemoval = true
            rig.sync.refresh(); rig.drain()
            assertEquals(setOf(UI), rig.state.actual)
            assertTrue(rig.state.message!!.contains("未移除"))
            rig.backend.ignoreRemoval = false
            rig.sync.refresh(); rig.drain()
            assertTrue(rig.state.actual.isEmpty())
            assertNull(rig.state.message)
        }
    }

    @Test
    fun synchronousApprovalDuringSubmissionDoesNotStartDuplicateWorkersOrRequests() {
        Rig().use { rig ->
            rig.backend.immediateApproval = true
            rig.sync.update(setOf(UI)); rig.drain()
            assertEquals(1, rig.backend.requests.size)
            assertEquals(setOf(UI), rig.state.actual)
            assertTrue(rig.state.pending.isEmpty())
        }
    }

    @Test
    fun twoFrameworkConnectionsKeepIndependentRequestsAndRejections() {
        Rig().use { first ->
            Rig().use { second ->
                first.sync.update(setOf(UI)); second.sync.update(setOf(UI))
                first.drain(); second.drain()
                first.backend.reject(); second.backend.approve()
                first.drain(); second.drain()
                assertEquals(setOf(UI), first.state.rejected)
                assertTrue(first.state.actual.isEmpty())
                assertTrue(second.state.rejected.isEmpty())
                assertEquals(setOf(UI), second.state.actual)
            }
        }
    }

    @Test
    fun closingAnOldConnectionIgnoresItsLateCallbacksAndQueuedUpdates() {
        Rig().use { old ->
            old.sync.update(setOf(UI)); old.drain()
            val count = old.changes.size
            old.sync.update(setOf(BLE))
            old.sync.close()
            old.backend.approve(); old.drain()
            assertEquals(count, old.changes.size)
            assertTrue(old.backend.removals.isEmpty())
            assertEquals(1, old.backend.requests.size)
        }
    }

    @Test
    fun anUnreleasedOldScopeDoesNotBlockAnotherEnabledFeatureFromRequestingItsHost() {
        Rig(Backend(setOf(SYSTEM))).use { rig ->
            rig.backend.removeFailure = "Native state not released"
            rig.sync.update(setOf(UI, BLE)); rig.drain()
            assertEquals(listOf(BLE, UI).sorted(), rig.backend.requests.single().packages)
            assertEquals(setOf(SYSTEM), rig.state.actual)
            rig.backend.approve(); rig.drain()
            assertEquals(setOf(SYSTEM, UI, BLE), rig.state.actual)
            assertEquals("Native state not released", rig.state.message)
            assertEquals(1, rig.backend.requests.size)
        }
    }
}
