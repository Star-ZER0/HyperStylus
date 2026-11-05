package cc.star0.xposed.hyperstylus.ui.state

import org.junit.Assert.*
import org.junit.Test

class ModuleProcessPresentationTest {
    @Test
    fun repeatedFrameworkBindingReplacesTheWrapperAndIgnoresItsOldDeath() {
        val connections = FrameworkConnections<Any>()
        val old = Any()
        val current = Any()
        assertTrue(connections.bind("LSPosed", old, 1))
        assertTrue(connections.bind("LSPosed", current, 2))
        assertEquals(1, connections.snapshot().size)
        assertSame(current, connections.snapshot().single().second)
        assertFalse(connections.remove(old))
        assertSame(current, connections.snapshot().single().second)
    }

    @Test
    fun lateOlderBindCannotReplaceTheCurrentEndpointAndOtherFrameworksStaySeparate() {
        val connections = FrameworkConnections<Any>()
        val current = Any()
        assertTrue(connections.bind("LSPosed", current, 2))
        assertFalse(connections.bind("LSPosed", Any(), 1))
        assertTrue(connections.bind("Other framework", Any(), 3))
        assertEquals(2, connections.snapshot().size)
        assertSame(current, connections.snapshot().first().second)
        assertTrue(connections.remove(current))
        assertEquals("Other framework", connections.snapshot().single().first)
    }

    private fun unique(rows: List<ModuleTargetStatus>) = distinctModuleProcesses(rows,
        key = ModuleTargetStatus::processKey, state = { it.state }, version = { it.loadedVersionCode })

    @Test
    fun aCurrentGenerationReplacesItsOldDisplayRowEvenAfterADowngrade() {
        val old = ModuleTargetStatus(1, "com.android.systemui", 123, 50, "STALE", "LSPosed", 1000)
        val current = old.copy(token = 2, loadedVersionCode = 49, state = "UP_TO_DATE")
        val rows = unique(listOf(old, current))
        assertEquals(1, rows.size)
        assertSame(current, rows.single())
    }

    @Test
    fun rowsForDifferentUsersProcessesAndFrameworksArePreserved() {
        val row = ModuleTargetStatus(1, "com.miui.home", 123, 49, "UP_TO_DATE", "LSPosed", 10136)
        val rows = listOf(row, row.copy(token = 2, pid = 124, uid = 1010136),
            row.copy(token = 3, frameworkName = "Other framework"))
        assertEquals(3, unique(rows).size)
    }

    @Test
    fun duplicateStaleRowsSelectOneNewestOriginalItemForReload() {
        val old = ModuleTargetStatus(1, "system", 123, 47, "STALE", "LSPosed", 1000)
        val newest = old.copy(token = 2, loadedVersionCode = 48)
        assertSame(newest, unique(listOf(old, newest)).single())
    }

    @Test
    fun reloadResultsMatchOnlyTheirOwnProcessRow() {
        val row = ModuleTargetStatus(1, "com.android.systemui", 123, 49, "UP_TO_DATE", "LSPosed", 1000)
        val result = ModuleReloadOutcome(row.processName, "SUCCEEDED", null, row.frameworkName, row.uid, row.pid)
        assertEquals(row.processKey(), result.processKey())
        assertNotEquals(row.copy(uid = 1001000).processKey(), result.processKey())
        assertNotEquals(row.copy(frameworkName = "Other framework").processKey(), result.processKey())
    }
}
