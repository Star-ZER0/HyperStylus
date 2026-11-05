package cc.star0.xposed.hyperstylus.runtime.nativegesture;

import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.config.ConfigBridge;
import cc.star0.xposed.hyperstylus.config.EnhanceConfig;
import cc.star0.xposed.hyperstylus.config.ScopePolicy;
import cc.star0.xposed.hyperstylus.config.StylusGestureConfig;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;
import cc.star0.xposed.hyperstylus.module.ModuleSession;
import cc.star0.xposed.hyperstylus.storage.SystemBrushJournal;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.function.IntSupplier;
import java.lang.ref.WeakReference;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serial system-server reconciliation of per-user native pinch and double-tap choices. */
public final class SystemBrushSettings {
    private static SystemBrushSettings active;
    private static boolean initializing;
    private static long retryAt;
    private static long startupId;
    private static String pendingFocus;
    private final FocusStylusModule module;
    private final Handler worker;
    private final IntSupplier user;
    private final SystemBrushController controller;
    private final Runnable reconcile = this::apply;
    private volatile EnhanceConfig config;
    private volatile String foreground;
    private ContentResolver resolver;
    private WeakReference<Context> context = new WeakReference<>(null);
    private ContentObserver observer;
    private BroadcastReceiver userReceiver;
    private Consumer<EnhanceConfig> configurationListener;
    private volatile boolean closed;

    public SystemBrushSettings(FocusStylusModule module, Handler worker, IntSupplier currentUser,
                        SystemBrushController.Database database, SystemBrushController.Journal journal) throws Exception {
        this.module = module; this.worker = worker; user = currentUser;
        controller = new SystemBrushController(database, journal);
    }
    /** Restore a stopped generation even when no package or focus callback is replayed. */
    public static void resume(FocusStylusModule module) {
        if (module.session().host("brush.context") instanceof Context context) start(module, context);
    }

    /** Explicit scope removal releases claims; ordinary generation retirement preserves them. */
    public static void prepareScopeRemoval(FocusStylusModule module, Context context, Consumer<Boolean> completion) {
        ModuleSession generation = module.session();
        if (!generation.enter()) { completion.accept(false); return; }
        try {
            EnhanceConfig snapshot = ConfigBridge.current();
            if (context == null || !ConfigBridge.ready() || ScopePolicy.required(snapshot).contains(ScopePolicy.SYSTEM)) {
                completion.accept(false);
                return;
            }
            Preparation request = new Preparation(module, snapshot.revision, completion);
            request.registration = generation.register("native gesture scope removal", () -> request.finish(false));
            if (request.finished.get()) { request.registration.forget(); return; }
            // A failed prior startup must not make an explicit release request wait for the 30s retry window.
            synchronized (SystemBrushSettings.class) { if (active == null && !initializing) retryAt = 0; }
            start(module, context);
            request.main.post(request.poll);
            request.main.postDelayed(() -> request.finish(false), 4_000);
        } catch (Throwable failure) {
            module.error("Prepare native gesture scope removal", failure);
            completion.accept(false);
        } finally { generation.leave(); }
    }

    private static final class Preparation {
        private final FocusStylusModule module;
        private final long revision;
        private final Consumer<Boolean> completion;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final AtomicBoolean finished = new AtomicBoolean();
        private ModuleSession.Registration registration;

        Preparation(FocusStylusModule module, long revision, Consumer<Boolean> completion) {
            this.module = module; this.revision = revision; this.completion = completion;
        }
        private boolean current() {
            return !finished.get() && module.session().isActive() && ConfigBridge.ready()
                    && ConfigBridge.current().revision == revision
                    && !ScopePolicy.required(ConfigBridge.current()).contains(ScopePolicy.SYSTEM);
        }
        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!current()) { finish(false); return; }
                SystemBrushSettings owner;
                synchronized (SystemBrushSettings.class) { owner = active; }
                if (owner == null || owner.closed) { main.postDelayed(this, 50); return; }
                if (!owner.worker.post(() -> release(owner))) finish(false);
            }
        };
        private void release(SystemBrushSettings owner) {
            boolean entered = module.session().enter();
            boolean released = false;
            try {
                if (!entered || !current() || owner.closed) return;
                EnhanceConfig snapshot = ConfigBridge.current();
                int user = owner.user.getAsInt();
                if (user < 0 && owner.controller.hasClaims()) return;
                if (user >= 0) ModuleSession.runAll(
                        () -> owner.controller.apply(snapshot.pinch.withEnabled(false), user, null),
                        () -> owner.controller.apply(snapshot.doubleTap.withEnabled(false), user, null));
                released = !owner.controller.hasClaims() && current();
            } catch (Throwable failure) { module.error("Release native gesture claims before scope removal", failure); }
            finally {
                if (entered) module.session().leave();
                finish(released);
            }
        }
        private void finish(boolean success) {
            if (!finished.compareAndSet(false, true)) return;
            main.removeCallbacksAndMessages(null);
            if (registration != null) registration.forget();
            try { completion.accept(success); }
            catch (RuntimeException failure) { module.error("Complete native gesture scope removal", failure); }
        }
    }

    public static void start(FocusStylusModule module, Context context) {
        ModuleSession generation = module.session();
        if (!generation.enter()) return;
        WorkerLifetime lifetime = null;
        HandlerThread thread = null;
        boolean claimedStartup = false;
        long ticket = 0;
        try {
            synchronized (SystemBrushSettings.class) {
                if (active != null || initializing || SystemClock.uptimeMillis() < retryAt) return;
                initializing = true;
                claimedStartup = true;
                ticket = ++startupId;
                if (generation.host("brush.foreground") instanceof String saved) pendingFocus = saved;
            }
            Context application = context.getApplicationContext();
            Context processContext = application == null ? context : application;
            generation.remember("brush.context", processContext);
            thread = new HandlerThread("HyperStylus-native-gestures");
            HandlerThread createdThread = thread;
            AtomicBoolean retiredThread = new AtomicBoolean();
            // Track the raw thread before Handler construction can fail. A late cleanup
            // also fences start if retirement was rejected and the old generation resumes.
            generation.onClose("native gesture thread", () -> {
                retiredThread.set(true);
                stopThread(createdThread);
            });
            if (retiredThread.get() || !generation.isActive()) {
                clearInitializing(ticket);
                return;
            }
            thread.start();
            lifetime = new WorkerLifetime(module, thread, new Handler(thread.getLooper()), ticket);
            WorkerLifetime worker = lifetime;
            // Register outside the class monitor: an already-closing generation runs
            // late cleanups immediately and may need this worker to acquire that monitor.
            generation.onClose("native gesture settings", worker::close);
            if (worker.closing || !generation.isActive()) {
                worker.close();
                clearInitializing(ticket);
                return;
            }
            if (!worker.handler.post(() -> initialize(module, processContext, worker))) {
                worker.close();
                clearInitializing(ticket);
            }
        } catch (Throwable error) {
            if (lifetime != null) {
                try { lifetime.close(); }
                catch (Throwable cleanupFailure) { if (cleanupFailure != error) error.addSuppressed(cleanupFailure); }
            } else if (thread != null) {
                try { stopThread(thread); }
                catch (Throwable cleanupFailure) { if (cleanupFailure != error) error.addSuppressed(cleanupFailure); }
            }
            if (claimedStartup) clearInitializing(ticket);
            module.error("Start native gesture worker", error);
        } finally { generation.leave(); }
    }

    private static void initialize(FocusStylusModule module, Context context, WorkerLifetime lifetime) {
        boolean entered = module.session().enter();
        try {
            if (!entered || lifetime.closing) {
                lifetime.abortOnWorker();
                return;
            }
            SystemBrushSettings session = new SystemBrushSettings(module, lifetime.handler, SystemBrushSettings::currentUser,
                    new FrameworkDatabase(context), new SystemBrushJournal());
            lifetime.owner = session;
            synchronized (SystemBrushSettings.class) { active = session; session.foreground = pendingFocus; }
            session.subscribe(context);
            session.schedule();
        } catch (Throwable error) {
            synchronized (SystemBrushSettings.class) { retryAt = SystemClock.uptimeMillis() + 30_000; }
            try { lifetime.abortOnWorker(); }
            catch (Throwable cleanupFailure) { if (cleanupFailure != error) error.addSuppressed(cleanupFailure); }
            module.error("Initialize native gesture ownership", error);
        } finally {
            clearInitializing(lifetime.ticket);
            if (entered) module.session().leave();
        }
    }

    private static synchronized void clearInitializing(long ticket) {
        if (startupId == ticket) initializing = false;
    }

    private static void stopThread(HandlerThread thread) throws Throwable {
        ModuleSession.runAll(
                () -> thread.quitSafely(),
                () -> {
                    if (Thread.currentThread() != thread) {
                        try { thread.join(2_000); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw interrupted;
                        }
                    }
                    if (thread.isAlive()) throw new IllegalStateException("Native gesture worker still running");
                });
    }

    private static final class WorkerLifetime {
        private final FocusStylusModule module;
        private final HandlerThread thread;
        private final Handler handler;
        private final long ticket;
        private volatile SystemBrushSettings owner;
        private volatile boolean closing, released;

        WorkerLifetime(FocusStylusModule module, HandlerThread thread, Handler handler, long ticket) {
            this.module = module; this.thread = thread; this.handler = handler; this.ticket = ticket;
        }

        private void releaseCallbacks() throws Throwable {
            handler.removeCallbacksAndMessages(null);
            SystemBrushSettings previous = owner;
            synchronized (SystemBrushSettings.class) {
                if (active == previous) active = null;
                if (startupId == ticket) initializing = false;
            }
            if (previous != null) {
                previous.closed = true;
                ModuleSession.runAll(
                        () -> module.session().remember("brush.foreground", previous.foreground),
                        previous::unsubscribe);
            }
            released = true;
        }

        void abortOnWorker() throws Throwable {
            closing = true;
            try { releaseCallbacks(); }
            finally { thread.quitSafely(); }
        }

        void close() throws Throwable {
            boolean alreadyClosing = closing;
            closing = true;
            // A failed startup may already have stopped its Looper. Callback unregistration
            // uses thread-safe framework APIs and can still be retried without that Looper.
            ModuleSession.runAll(
                    () -> {
                        if (released) return;
                        if (alreadyClosing || !thread.isAlive()) releaseCallbacks();
                        else {
                            try { ModuleSession.onLooper(handler, this::releaseCallbacks); }
                            catch (Throwable failure) {
                                // An initializer may have released everything and quit between
                                // the liveness check and posting. The join below still verifies exit.
                                if (!released) throw failure;
                            }
                        }
                    },
                    () -> stopThread(thread));
        }
    }
    public static synchronized void focus(String pkg) {
        pendingFocus = normalize(pkg);
        if (active != null) active.onFocus(pendingFocus);
    }
    public void onFocus(String pkg) { if (!closed) { foreground = normalize(pkg); schedule(); } }
    public void configure(EnhanceConfig value) { if (!closed) { config = value; schedule(); } }
    private static String normalize(String pkg) { return EnhanceConfig.validPackage(pkg) ? pkg : null; }
    private void schedule() {
        if (closed || !module.session().isActive()) return;
        worker.removeCallbacks(reconcile); worker.post(reconcile);
    }
    private void subscribe(Context context) {
        this.context = new WeakReference<>(context);
        resolver = context.getContentResolver();
        observer = new ContentObserver(worker) {
            @Override public void onChange(boolean selfChange) { schedule(); }
        };
        for (StylusGestureConfig.Kind kind : StylusGestureConfig.Kind.values()) {
            try { Reflect.call(context.getContentResolver(), "registerContentObserver", Settings.System.getUriFor(kind.key), false, observer, -1); }
            catch (Throwable error) { module.error("Observe native stylus setting " + kind.key, error); }
        }
        try {
            BroadcastReceiver updates = new BroadcastReceiver() {
                @Override public void onReceive(Context sender, Intent event) {
                    if (getSentFromUid() == android.os.Process.SYSTEM_UID) onFocus(null);
                }
            };
            context.registerReceiver(updates, new IntentFilter("android.intent.action.USER_SWITCHED"), null, worker, Context.RECEIVER_EXPORTED);
            userReceiver = updates;
        } catch (RuntimeException error) { module.error("Observe active Android user", error); }
        configurationListener = value -> { if (ConfigBridge.ready()) configure(value); };
        ConfigBridge.start(module, context, configurationListener);
        worker.postDelayed(tick, 30_000);
    }

    private void unsubscribe() throws Throwable {
        ModuleSession.runAll(
                () -> {
                    ConfigBridge.removeListener(configurationListener);
                    configurationListener = null;
                },
                () -> {
                    if (observer != null) { resolver.unregisterContentObserver(observer); observer = null; }
                },
                () -> {
                    if (userReceiver != null) {
                        Context host = context.get();
                        if (host == null) throw new IllegalStateException("Native gesture receiver context unavailable");
                        host.unregisterReceiver(userReceiver); userReceiver = null;
                    }
                });
        context.clear();
        resolver = null;
    }
    private void apply() {
        if (closed || !module.session().isActive()) return;
        EnhanceConfig snapshot = config;
        if (snapshot == null) return;
        int current;
        try { current = user.getAsInt(); }
        catch (RuntimeException error) { module.error("Resolve active Android user", error); return; }
        String pkg = foreground;
        for (StylusGestureConfig.Kind kind : StylusGestureConfig.Kind.values()) {
            try { controller.apply(snapshot.gesture(kind), current, pkg); }
            catch (Throwable error) { module.error("Reconcile native " + kind.key + " user=" + current, error); }
        }
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed || !module.session().isActive()) return;
            schedule(); worker.postDelayed(this, 30_000);
        }
    };
    private static int currentUser() {
        try { return Reflect.call(ActivityManager.class, "getCurrentUser") instanceof Integer current ? current : -1; }
        catch (Throwable ignored) { return -1; }
    }
    public static final class FrameworkDatabase implements SystemBrushController.Database {
        private final ContentResolver resolver;
        public FrameworkDatabase(Context context) { resolver = context.getContentResolver(); }
        @Override public String get(SystemBrushController.Key key) throws Throwable {
            long identity = Binder.clearCallingIdentity();
            try { return (String) Reflect.call(Settings.System.class, "getStringForUser", resolver, key.kind().key, key.user()); }
            finally { Binder.restoreCallingIdentity(identity); }
        }
        @Override public boolean put(SystemBrushController.Key key, String value) throws Throwable {
            long identity = Binder.clearCallingIdentity();
            try { return Boolean.TRUE.equals(Reflect.call(Settings.System.class, "putStringForUser", resolver, key.kind().key, value, key.user())); }
            finally { Binder.restoreCallingIdentity(identity); }
        }
    }
}
