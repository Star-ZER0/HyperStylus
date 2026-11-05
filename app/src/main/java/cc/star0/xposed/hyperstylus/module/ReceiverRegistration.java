package cc.star0.xposed.hyperstylus.module;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import cc.star0.xposed.hyperstylus.common.Reflect;
import java.lang.ref.WeakReference;
import java.util.Map;

/** Pairs one receiver with an isolated host context and its actual framework registry. */
public final class ReceiverRegistration implements AutoCloseable {
    interface Registry {
        Context owner(BroadcastReceiver receiver) throws Throwable;
        void unregister(Context owner, BroadcastReceiver receiver) throws Throwable;
    }
    private final WeakReference<Context> context;
    private final BroadcastReceiver receiver;
    private final Registry registry;
    private boolean registered;

    private ReceiverRegistration(Context context, BroadcastReceiver receiver) throws ReflectiveOperationException {
        this(context, receiver, new AndroidRegistry(context));
    }
    ReceiverRegistration(Context context, BroadcastReceiver receiver, Registry registry) {
        this.context = new WeakReference<>(context); this.receiver = receiver; this.registry = registry;
    }

    public static ReceiverRegistration attach(Context source, BroadcastReceiver receiver,
            IntentFilter filter, Handler handler, int flags) throws ReflectiveOperationException {
        Context isolated = source.createAttributionContext(null);
        ReceiverRegistration registration = new ReceiverRegistration(isolated, receiver);
        registration.register(isolated, filter, handler, flags);
        return registration;
    }

    synchronized Intent register(IntentFilter filter, Handler handler, int flags) {
        return register(context.get(), filter, handler, flags);
    }
    private synchronized Intent register(Context host, IntentFilter filter, Handler handler, int flags) {
        if (registered) throw new IllegalStateException("Receiver is already registered");
        if (host == null) throw new IllegalStateException("Receiver context unavailable");
        registered = true;
        try { return host.registerReceiver(receiver, filter, null, handler, flags); }
        catch (RuntimeException failure) {
            try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    public Context context() { return context.get(); }

    @Override public synchronized void close() throws Exception {
        if (!registered) return;
        try {
            Context host = context.get();
            if (host == null) {
                Context actual = registry.owner(receiver);
                if (actual != null) registry.unregister(actual, receiver);
            } else try { host.unregisterReceiver(receiver); }
            catch (IllegalArgumentException missing) {
                String message = missing.getMessage();
                if (message == null || !message.startsWith("Receiver not registered:")
                        && !(message.startsWith("Unregistering Receiver ") && message.contains("already unregistered"))) throw missing;
                // Wrong Context and an already removed receiver share this exception. Verify
                // the full original LoadedApk registry before accepting retirement.
                Context actual = registry.owner(receiver);
                if (actual != null) registry.unregister(actual, receiver);
            }
            registered = false;
            context.clear();
        } catch (Exception failure) { throw failure; }
        catch (Error failure) { throw failure; }
        catch (Throwable failure) { throw new Exception("Could not release host receiver", failure); }
    }

    private static final class AndroidRegistry implements Registry {
        private final Object loadedApk;
        AndroidRegistry(Context context) throws ReflectiveOperationException {
            Context base = context;
            while (base instanceof ContextWrapper wrapper) {
                Context next = wrapper.getBaseContext();
                if (next == null || next == base) throw new IllegalStateException("Invalid receiver context wrapper");
                base = next;
            }
            loadedApk = Reflect.field(base, "mPackageInfo");
            if (loadedApk == null) throw new IllegalStateException("Receiver requires a package host context");
        }
        @Override public Context owner(BroadcastReceiver receiver) throws ReflectiveOperationException {
            Object value = Reflect.field(loadedApk, "mReceivers");
            if (!(value instanceof Map<?, ?> contexts)) throw new IllegalStateException("Unknown host receiver registry");
            synchronized (contexts) {
                for (var entry : contexts.entrySet()) {
                    if (entry.getValue() instanceof Map<?, ?> receivers && receivers.containsKey(receiver)) {
                        if (!(entry.getKey() instanceof Context host)) throw new IllegalStateException("Unknown receiver registry owner");
                        return host;
                    }
                }
                return null;
            }
        }
        @Override public void unregister(Context owner, BroadcastReceiver receiver) throws Throwable {
            Object transport = Reflect.call(loadedApk, "forgetReceiverDispatcher", owner, receiver);
            Object manager = Reflect.call(ActivityManager.class, "getService");
            Reflect.call(manager, "unregisterReceiver", transport);
        }
    }
}
