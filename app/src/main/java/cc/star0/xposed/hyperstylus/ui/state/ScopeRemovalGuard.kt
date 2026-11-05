package cc.star0.xposed.hyperstylus.ui.state

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.Process
import cc.star0.xposed.hyperstylus.common.Protocol
import cc.star0.xposed.hyperstylus.config.ConfigBridge
import cc.star0.xposed.hyperstylus.config.EnhanceConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Removing system scope waits for the current system_server to release owned native settings and keys. */
internal class ScopeRemovalGuard(
    private val transport: Transport,
    private val timeoutMillis: Long = 7_000,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    interface Transport {
        fun register(receive: (Intent, Int) -> Unit): AutoCloseable
        fun send(request: Intent)
    }

    constructor(context: Context) : this(AndroidTransport(context.applicationContext ?: context))

    companion object {
        fun systemModuleLoaded(processes: Collection<String>): Boolean =
            processes.any { it == "system_server" || it == "system" || it == "android" }
    }

    /** A cancelled late approval has no native state to release if the module never entered system_server. */
    suspend fun prepareIfLoaded(config: EnhanceConfig, processes: Collection<String>): Boolean =
        !systemModuleLoaded(processes) || prepare(config)

    suspend fun prepare(config: EnhanceConfig): Boolean = withContext(dispatcher) {
        val requestId = UUID.randomUUID().toString()
        val result = CompletableDeferred<Boolean>()
        val registration = transport.register { event, sender ->
            try {
                if (sender == Process.SYSTEM_UID && event.action == ConfigBridge.SCOPE_REMOVAL_READY
                    && event.getStringExtra("request_id") == requestId
                    && event.getLongExtra("request_revision", -1) == config.revision) {
                    result.complete(event.getBooleanExtra("ready", false))
                }
            } catch (_: RuntimeException) { /* A malformed or unrelated reply cannot release a scope. */ }
        }
        try {
            transport.send(Intent(ConfigBridge.PREPARE_SCOPE_REMOVAL).putExtras(config.toBundle())
                .putExtra("request_id", requestId).putExtra("request_revision", config.revision))
            withTimeoutOrNull(timeoutMillis) { result.await() } ?: false
        } finally {
            registration.close()
        }
    }

    private class AndroidTransport(private val context: Context) : Transport {
        override fun register(receive: (Intent, Int) -> Unit): AutoCloseable {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { receive(intent, sentFromUid) }
            }
            context.registerReceiver(receiver, IntentFilter(ConfigBridge.SCOPE_REMOVAL_READY), null,
                Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED)
            return AutoCloseable { context.unregisterReceiver(receiver) }
        }
        override fun send(request: Intent) { Protocol.send(context, request, "android") }
    }
}
