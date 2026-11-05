package cc.star0.xposed.hyperstylus.ui.state

import android.content.Intent
import android.os.Process
import cc.star0.xposed.hyperstylus.config.ConfigBridge
import cc.star0.xposed.hyperstylus.config.EnhanceConfig
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScopeRemovalGuardTest {
    private class Transport : ScopeRemovalGuard.Transport {
        val receivers = linkedMapOf<Int, (Intent, Int) -> Unit>()
        val requests = arrayListOf<Intent>()
        var nextId = 0
        var closed = 0
        var onSend: (Intent) -> Unit = {}
        override fun register(receive: (Intent, Int) -> Unit): AutoCloseable {
            val id = nextId++
            receivers[id] = receive
            return AutoCloseable { receivers.remove(id); closed++ }
        }
        override fun send(request: Intent) {
            check(receivers.isNotEmpty())
            requests += request
            onSend(request)
        }
        fun reply(request: Intent, ready: Boolean = true, sender: Int = Process.SYSTEM_UID,
                  id: String? = request.getStringExtra("request_id"),
                  revision: Long = request.getLongExtra("request_revision", -1),
                  action: String = ConfigBridge.SCOPE_REMOVAL_READY) {
            val event = Intent(action).putExtra("request_id", id).putExtra("request_revision", revision).putExtra("ready", ready)
            receivers.values.toList().forEach { it(event, sender) }
        }
    }
    private fun config() = EnhanceConfig(EnhanceConfig.defaults().toBundle().apply { putLong("revision", 41) })
    private fun guard(transport: Transport) = ScopeRemovalGuard(transport, 30, Dispatchers.Unconfined)

    @Test fun registersBeforeSendingAndCarriesTheDurableSnapshot() = runBlocking {
        val transport = Transport()
        transport.onSend = { transport.reply(it) }
        assertTrue(guard(transport).prepare(config()))
        assertEquals(41, transport.requests.single().getLongExtra("revision", -1))
        assertEquals(ConfigBridge.PREPARE_SCOPE_REMOVAL, transport.requests.single().action)
        assertTrue(transport.requests.single().getBooleanExtra("focus_key_enabled", false))
        assertTrue(transport.receivers.isEmpty())
        assertEquals(1, transport.closed)
    }

    @Test fun rejectsWrongSenderNonceRevisionAndActionBeforeAcceptingTheRealReply() = runBlocking {
        val transport = Transport()
        transport.onSend = {
            transport.reply(it, ready = false, sender = 19071)
            transport.reply(it, ready = false, id = "old-request")
            transport.reply(it, ready = false, revision = 40)
            transport.reply(it, ready = false, action = "unrelated")
            transport.reply(it)
        }
        assertTrue(guard(transport).prepare(config()))
        assertEquals(1, transport.closed)
    }

    @Test fun anExplicitFailureCannotRemoveTheScope() = runBlocking {
        val transport = Transport()
        transport.onSend = { transport.reply(it, ready = false) }
        assertFalse(guard(transport).prepare(config()))
        assertEquals(1, transport.closed)
    }

    @Test fun missingAcknowledgementTimesOutAndUnregisters() = runBlocking {
        val transport = Transport()
        assertFalse(guard(transport).prepare(config()))
        assertTrue(transport.receivers.isEmpty())
        assertEquals(1, transport.closed)
    }

    @Test fun cancellationReleasesTheWaitingReceiver() = runBlocking {
        val transport = Transport()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { guard(transport).prepare(config()) }
        assertEquals(1, transport.receivers.size)
        waiting.cancelAndJoin()
        assertTrue(transport.receivers.isEmpty())
        assertEquals(1, transport.closed)
    }

    @Test fun aSendFailureStillReleasesRegistration() = runBlocking {
        val transport = Transport()
        transport.onSend = { throw IllegalStateException("unavailable") }
        try { guard(transport).prepare(config()); fail("Expected send failure") }
        catch (expected: IllegalStateException) { assertEquals("unavailable", expected.message) }
        assertEquals(1, transport.closed)
    }

    @Test fun parallelFrameworkRequestsDoNotConsumeEachOthersAcknowledgement() = runBlocking {
        val transport = Transport()
        val guard = ScopeRemovalGuard(transport, 1_000, Dispatchers.Unconfined)
        val first = async(start = CoroutineStart.UNDISPATCHED) { guard.prepare(config()) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { guard.prepare(config()) }
        assertNotEquals(transport.requests[0].getStringExtra("request_id"), transport.requests[1].getStringExtra("request_id"))
        transport.reply(transport.requests[1])
        assertTrue(second.await())
        assertFalse(first.isCompleted)
        transport.reply(transport.requests[0], ready = false)
        assertFalse(first.await())
        assertEquals(2, transport.closed)
    }

    @Test fun unusedLateApprovedSystemScopeDoesNotWaitForAnUnloadedProcess() = runBlocking {
        val transport = Transport()
        assertTrue(guard(transport).prepareIfLoaded(config(), listOf("com.android.systemui", "com.miui.home")))
        assertTrue(transport.requests.isEmpty())
        assertTrue(transport.receivers.isEmpty())
    }

    @Test fun aLoadedSystemModuleStillRequiresTheRealReleaseAcknowledgement() = runBlocking {
        val transport = Transport()
        transport.onSend = { transport.reply(it, ready = false) }
        assertFalse(guard(transport).prepareIfLoaded(config(), listOf("system_server")))
        assertEquals(1, transport.requests.size)
        assertTrue(ScopeRemovalGuard.systemModuleLoaded(listOf("android")))
        assertFalse(ScopeRemovalGuard.systemModuleLoaded(listOf("com.android.systemui")))
    }
}
