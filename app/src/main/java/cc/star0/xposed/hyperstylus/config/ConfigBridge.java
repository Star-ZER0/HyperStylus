package cc.star0.xposed.hyperstylus.config;

import cc.star0.xposed.hyperstylus.common.Protocol;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;
import cc.star0.xposed.hyperstylus.module.ModuleSession;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.lang.ref.WeakReference;

/** Main-thread publication with one serialized background reader per injected process. */
public final class ConfigBridge {
    private static final String TAG = "HyperStylus";
    public static final String PREPARE_SCOPE_REMOVAL = Protocol.MODULE + ".PREPARE_SCOPE_REMOVAL";
    public static final String SCOPE_REMOVAL_READY = Protocol.MODULE + ".SCOPE_REMOVAL_READY";
    @FunctionalInterface public interface ScopeRemovalHandler {
        void prepare(Context context, Consumer<Boolean> completion) throws Throwable;
    }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService READER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "HyperStylus-config"); thread.setDaemon(true); return thread;
    });
    private static final List<Consumer<EnhanceConfig>> LISTENERS = new ArrayList<>();
    private static volatile EnhanceConfig current = EnhanceConfig.defaults();
    private static volatile boolean ready;
    private static Session session;
    private static ModuleSession owner;
    private static volatile boolean closing;
    private static boolean bound;
    private static volatile ScopeRemovalHandler scopeRemovalHandler;
    private static final Object SUBMISSIONS = new Object();

    public static synchronized void bind(FocusStylusModule module) {
        if (bound) return;
        bound = true;
        owner = module.session();
        if (module.session().host("config.snapshot") instanceof Bundle saved) {
            current = new EnhanceConfig(saved);
            ready = Boolean.TRUE.equals(module.session().host("config.ready"));
        }
        module.session().onClose("configuration bridge", () -> retire(module.session()));
        if (module.session().host("config.context") instanceof Context context) start(context, ignored -> { });
    }

    private static void retire(ModuleSession generation) throws Throwable {
        synchronized (SUBMISSIONS) {
            closing = true;
            scopeRemovalHandler = null;
            READER.shutdownNow();
        }
        Throwable failure = null;
        AtomicBoolean mainCleanupRan = new AtomicBoolean();
        try {
            ModuleSession.onLooper(MAIN, () -> {
                MAIN.removeCallbacksAndMessages(null);
                Throwable unsubscribeFailure = null;
                Session previous = session;
                try {
                    if (previous != null) {
                        try { generation.remember("config.context", previous.context.get()); }
                        catch (Throwable error) { unsubscribeFailure = error; }
                        try { previous.unsubscribe(); }
                        catch (Throwable error) { unsubscribeFailure = combine(unsubscribeFailure, error); }
                    }
                } finally {
                    LISTENERS.clear();
                    session = null;
                    mainCleanupRan.set(true);
                }
                if (unsubscribeFailure != null) throw unsubscribeFailure;
            });
        } catch (Throwable error) { failure = error; }
        try {
            if (!READER.awaitTermination(2, TimeUnit.SECONDS))
                throw new IllegalStateException("Configuration reader still running");
        } catch (Throwable error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            failure = combine(failure, error);
        }
        // Preserve a delayed cleanup if the host main Looper timed out: it must still
        // be allowed to unregister callbacks after this generation rejects retirement.
        if (mainCleanupRan.get()) MAIN.removeCallbacksAndMessages(null);
        try {
            generation.remember("config.snapshot", current.toBundle());
            generation.remember("config.ready", ready);
        } catch (Throwable error) { failure = combine(failure, error); }
        if (failure != null) throw failure;
    }

    private static Throwable combine(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    /** Closing and submission share one gate, so shutdown cannot reject a live callback. */
    static boolean submitRead(Runnable action) {
        synchronized (SUBMISSIONS) {
            if (closing) return false;
            try { READER.execute(action); return true; }
            catch (RejectedExecutionException error) {
                if (!closing) Log.w(TAG, "Configuration reader rejected work", error);
                return false;
            }
        }
    }

    private static void post(Runnable action) {
        synchronized (SUBMISSIONS) { if (!closing) MAIN.post(action); }
    }

    public static EnhanceConfig current() { return current; }
    public static boolean ready() { return ready; }

    /** Only the actual system_server generation may promise that native setting claims are released. */
    public static synchronized void setScopeRemovalHandler(ScopeRemovalHandler handler) {
        if (handler != null && (owner == null || !Boolean.TRUE.equals(owner.host("runtime.systemServer"))))
            throw new IllegalStateException("Scope removal preparation requires system_server");
        scopeRemovalHandler = handler;
    }

    public static boolean trustedScopeRemoval(Context context, int sender, Intent event) {
        return event != null && PREPARE_SCOPE_REMOVAL.equals(event.getAction())
                && Protocol.from(context, sender, Protocol.MODULE);
    }

    private static boolean canRemoveSystem(long revision) {
        return !closing && ready && current.revision == revision
                && !ScopePolicy.required(current).contains(ScopePolicy.SYSTEM);
    }

    private static void prepareScopeRemoval(Context context, Intent event) {
        if (owner == null || !Boolean.TRUE.equals(owner.host("runtime.systemServer"))) return;
        String request = event.getStringExtra("request_id");
        long revision = event.getLongExtra("request_revision", -1);
        if (request == null || request.isBlank() || request.length() > 128 || revision < 0) return;
        Bundle values = event.getExtras();
        if (values != null) accept(new EnhanceConfig(values));
        AtomicBoolean answered = new AtomicBoolean();
        Consumer<Boolean> reply = prepared -> post(() -> {
            if (!answered.compareAndSet(false, true)) return;
            boolean released = Boolean.TRUE.equals(prepared) && canRemoveSystem(revision);
            try {
                Protocol.send(context, new Intent(SCOPE_REMOVAL_READY).putExtra("request_id", request)
                        .putExtra("request_revision", revision).putExtra("ready", released), Protocol.MODULE);
            } catch (RuntimeException failure) { Log.w(TAG, "Scope removal acknowledgement failed", failure); }
        });
        ScopeRemovalHandler handler = scopeRemovalHandler;
        if (!canRemoveSystem(revision) || handler == null) { reply.accept(false); return; }
        try { handler.prepare(context, reply); }
        catch (Throwable failure) {
            Log.w(TAG, "Native setting release before scope removal failed", failure);
            reply.accept(false);
        }
    }

    /** Both message namespace and actual sender UID must belong to this installed module. */
    public static boolean trustedUpdate(Context context, int sender, Intent event) {
        return event != null && EnhancePreferences.UPDATE.equals(event.getAction())
                && Protocol.from(context, sender, Protocol.MODULE);
    }

    public static void start(Context context, Consumer<EnhanceConfig> listener) {
        if (closing) return;
        post(() -> {
            if (closing) return;
            if (!LISTENERS.contains(listener)) LISTENERS.add(listener);
            deliver(listener, current);
            if (session != null) return;
            Context application = context.getApplicationContext();
            Context processContext = application == null ? context : application;
            if (owner != null) owner.remember("config.context", processContext);
            // system_server supplies a process Context rather than an Application. Retain
            // only its resolver: neither the static bridge nor its session owns an Activity.
            session = new Session(processContext.getContentResolver());
            session.subscribe(processContext);
            session.refresh();
        });
    }

    public static void start(FocusStylusModule module, Context context, Consumer<EnhanceConfig> listener) {
        bind(module);
        start(context, listener);
    }

    public static void removeListener(Consumer<EnhanceConfig> listener) {
        if (listener != null) post(() -> LISTENERS.remove(listener));
    }

    private static final class Session {
        private final ContentResolver resolver;
        private WeakReference<Context> context = new WeakReference<>(null);
        private BroadcastReceiver receiver;
        private ContentObserver observer;
        // Accessed on MAIN; at most one provider operation can enter READER at a time.
        private boolean reading, dirty;
        private int generation;

        public Session(ContentResolver resolver) { this.resolver = resolver; }

        private void subscribe(Context processContext) {
            context = new WeakReference<>(processContext);
            IntentFilter filter = new IntentFilter(EnhancePreferences.UPDATE);
            filter.addAction(PREPARE_SCOPE_REMOVAL);
            filter.addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED); filter.addAction(Intent.ACTION_BOOT_COMPLETED);
            try {
                BroadcastReceiver updates = new BroadcastReceiver() {
                    @Override public void onReceive(Context context, Intent event) {
                        if (closing) return;
                        int sender = getSentFromUid();
                        if (EnhancePreferences.UPDATE.equals(event.getAction())) {
                            if (!trustedUpdate(context, sender, event)) return;
                            Bundle values = event.getExtras();
                            if (values != null) accept(new EnhanceConfig(values));
                        } else if (PREPARE_SCOPE_REMOVAL.equals(event.getAction())) {
                            if (trustedScopeRemoval(context, sender, event)) prepareScopeRemoval(context, event);
                        } else if (sender == Process.SYSTEM_UID) refresh();
                    }
                };
                processContext.registerReceiver(updates, filter, null, MAIN, Context.RECEIVER_EXPORTED);
                receiver = updates;
            } catch (RuntimeException error) { Log.w(TAG, "Configuration broadcast subscription failed", error); }
            try {
                ContentObserver updates = new ContentObserver(MAIN) {
                    @Override public void onChange(boolean selfChange) { refresh(); }
                };
                resolver.registerContentObserver(EnhancePreferences.URI, false, updates);
                observer = updates;
            } catch (RuntimeException error) { Log.w(TAG, "Configuration observer subscription failed", error); }
        }

        private void unsubscribe() throws Throwable {
            Throwable failure = null;
            try {
                if (receiver != null) {
                    Context host = context.get();
                    if (host == null) throw new IllegalStateException("Configuration receiver context unavailable");
                    host.unregisterReceiver(receiver);
                }
            }
            catch (Throwable error) { failure = error; }
            finally { receiver = null; }
            try { if (observer != null) resolver.unregisterContentObserver(observer); }
            catch (Throwable error) { failure = combine(failure, error); }
            finally { observer = null; context.clear(); }
            if (failure != null) throw failure;
        }

        private void refresh() {
            if (closing) return;
            generation++;
            if (reading) { dirty = true; return; }
            read(0, generation);
        }

        private void read(int attempt, int request) {
            if (closing) return;
            reading = true;
            if (!submitRead(() -> {
                EnhanceConfig snapshot = null;
                RuntimeException failure = null;
                try {
                    Bundle values = resolver.call(EnhancePreferences.URI, "get_all", null, null);
                    if (values == null) throw new IllegalStateException("Empty configuration response");
                    snapshot = new EnhanceConfig(values);
                } catch (RuntimeException error) { failure = error; }
                EnhanceConfig result = snapshot;
                RuntimeException error = failure;
                if (closing) return;
                post(() -> {
                    if (closing) return;
                    reading = false;
                    if (result != null) accept(result);
                    if (dirty) { dirty = false; read(0, generation); return; }
                    if (error == null) return;
                    if (attempt >= 5) { Log.w(TAG, "Configuration provider unavailable; waiting for boot or app update", error); return; }
                    MAIN.postDelayed(() -> {
                        if (generation == request && !reading) read(attempt + 1, request);
                    }, 3_000);
                });
            })) reading = false;
        }
    }

    private static void accept(EnhanceConfig value) {
        if (closing) return;
        if (ready && value.revision <= current.revision) return;
        current = value; ready = true;
        for (Consumer<EnhanceConfig> listener : List.copyOf(LISTENERS)) deliver(listener, value);
    }

    private static void deliver(Consumer<EnhanceConfig> listener, EnhanceConfig value) {
        try { listener.accept(value); }
        catch (RuntimeException error) { Log.e(TAG, "Configuration listener failed", error); }
    }
    private ConfigBridge() { }
}
