package cc.star0.xposed.hyperstylus.runtime.gesture;

import cc.star0.xposed.hyperstylus.common.Protocol;
import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.config.ConfigBridge;
import cc.star0.xposed.hyperstylus.config.EnhanceConfig;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;
import cc.star0.xposed.hyperstylus.module.ModuleSession;
import cc.star0.xposed.hyperstylus.module.ReceiverRegistration;
import cc.star0.xposed.hyperstylus.runtime.nativegesture.SystemBrushSettings;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.MotionEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Captures WMS focus under its lock; publishes immutable snapshots after returning to the main loop. */
public final class GestureFocusBridge {
    public static final String UPDATE = Protocol.MODULE + ".GESTURE_FOCUS";
    public static final String REQUEST = Protocol.MODULE + ".GESTURE_FOCUS_REQUEST";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean SUBSCRIBED = new AtomicBoolean();
    private static final Snapshot LOCAL = new Snapshot();
    private static final AtomicBoolean CLIENT_MANAGED = new AtomicBoolean();
    private static final CopyOnWriteArrayList<Runnable> CHANGES = new CopyOnWriteArrayList<>();
    private static ReceiverRegistration client;
    private static final Method DISPLAY_ID = findDisplayMethod();

    private static Method findDisplayMethod() {
        try { return MotionEvent.class.getMethod("getDisplayId"); }
        catch (ReflectiveOperationException unsupported) { return null; }
    }

    public static final class Snapshot {
        private record State(long revision, Map<Integer, String> displays) { }
        private volatile State state = new State(-1, Map.of());

        public synchronized boolean accept(Bundle message, int sender) {
            if (sender != Process.SYSTEM_UID || message == null) return false;
            try {
                long revision = message.getLong("revision", -1);
                Bundle encoded = message.getBundle("displays");
                if (revision < 0 || revision <= state.revision() || encoded == null) return false;
                Map<Integer, String> next = new HashMap<>();
                for (String key : encoded.keySet()) {
                    int display = Integer.parseInt(key);
                    String app = encoded.getString(key);
                    if (display < 0 || app == null || !app.isEmpty() && !EnhanceConfig.validPackage(app)) return false;
                    next.put(display, app);
                }
                state = new State(revision, Map.copyOf(next));
                return true;
            } catch (RuntimeException malformed) { return false; }
        }

        public String get(int display) {
            String value = state.displays().get(display);
            return value == null || value.isEmpty() ? null : value;
        }
        public Bundle export() {
            State snapshot = state;
            Bundle displays = new Bundle();
            snapshot.displays().forEach((id, pkg) -> displays.putString(Integer.toString(id), pkg));
            Bundle encoded = new Bundle();
            encoded.putLong("revision", snapshot.revision()); encoded.putBundle("displays", displays);
            return encoded;
        }
    }

    public static boolean enabled(MotionEvent event, int feature, boolean global) {
        EnhanceConfig config = ConfigBridge.current();
        if (!config.gestureApps.enabled || feature == 0 || DISPLAY_ID == null || event == null) return global;
        try {
            Object value = DISPLAY_ID.invoke(event);
            return value instanceof Integer display ? enabledOnDisplay(display, feature, config, global) : global;
        } catch (ReflectiveOperationException | RuntimeException unsupported) { return global; }
    }

    /** Window input policy uses the same focus snapshot as event filtering, without Binder reads. */
    static boolean enabledOnDisplay(int display, int feature, EnhanceConfig config, boolean global) {
        if (config == null || !config.gestureApps.enabled || feature == 0 || display < 0) return global;
        String app = LOCAL.get(display);
        if (app == null) return global;
        return (config.gestureApps.mask(app, config.gestureMask()) & feature) != 0;
    }

    /** The owner must forget its registration when its module generation retires. */
    static ModuleSession.Registration observeChanges(Runnable listener) {
        Objects.requireNonNull(listener);
        Runnable registered = listener::run;
        CHANGES.add(registered);
        AtomicBoolean forgotten = new AtomicBoolean();
        return () -> { if (forgotten.compareAndSet(false, true)) CHANGES.remove(registered); };
    }

    /** Both broadcasts and neutral reload restoration use this validated publication path. */
    static boolean acceptClientSnapshot(Bundle message, int sender) {
        if (!LOCAL.accept(message, sender)) return false;
        for (Runnable listener : CHANGES) {
            try { listener.run(); }
            catch (Throwable failure) { android.util.Log.w("HyperStylus", "Focus change listener failed", failure); }
        }
        return true;
    }

    private static void subscribeClient(FocusStylusModule module, Context context) {
        if (!SUBSCRIBED.compareAndSet(false, true)) return;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent event) {
                    if (!module.session().enter()) return;
                    try { acceptClientSnapshot(event.getExtras(), getSentFromUid()); }
                    finally { module.session().leave(); }
                }
            };
            client = ReceiverRegistration.attach(context, receiver, new IntentFilter(UPDATE), MAIN, Context.RECEIVER_EXPORTED);
            module.session().remember("focus.client.context", client.context());
        } catch (ReflectiveOperationException | RuntimeException error) {
            SUBSCRIBED.set(false);
            android.util.Log.w("HyperStylus", "Focus subscription unavailable", error);
            return;
        }
        try { Protocol.send(context, new Intent(REQUEST), "android"); }
        catch (RuntimeException error) { android.util.Log.w("HyperStylus", "Focus handshake unavailable", error); }
    }

    public static void startClient(FocusStylusModule module, Context context) {
        if (!module.session().enter()) return;
        try {
            if (CLIENT_MANAGED.compareAndSet(false, true)) {
                if (module.session().host("focus.client.snapshot") instanceof Bundle saved) acceptClientSnapshot(saved, Process.SYSTEM_UID);
                module.session().onClose("focus snapshot client", () -> ModuleSession.onLooper(MAIN, () -> {
                    MAIN.removeCallbacksAndMessages(null);
                    if (client != null) client.close();
                    module.session().remember("focus.client.snapshot", LOCAL.export());
                    client = null; SUBSCRIBED.set(false);
                }));
            }
            if (Looper.myLooper() == MAIN.getLooper()) subscribeClient(module, context);
            else MAIN.post(() -> module.safely("Subscribe focus snapshot client", () -> subscribeClient(module, context)));
        } finally { module.session().leave(); }
    }

    public static void installServer(FocusStylusModule module, ClassLoader loader) {
        Class<?> type = Reflect.find(loader, "com.android.server.wm.DisplayContent");
        if (type == null) { module.info("Display focus source unavailable"); return; }
        Server server = new Server(module);
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            try { module.after(constructor, (display, args, result) -> server.observe(display, false)); }
            catch (Throwable error) { module.error("Display constructor observer", error); }
        }
        int installed = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || Modifier.isAbstract(method.getModifiers())) continue;
            boolean removed = method.getName().equals("removeImmediately") && method.getParameterCount() == 0;
            boolean updated = method.getName().equals("updateFocusedWindowLocked") && method.getReturnType() == boolean.class;
            if (!removed && !updated) continue;
            try {
                module.after(method, (display, args, result) -> server.observe(display, removed));
                installed++;
            } catch (Throwable error) { module.error("Display focus observer", error); }
        }
        module.info("Per-display focus observers=" + installed);
    }

    public static final class Server {
        private final FocusStylusModule module;
        private final Map<Integer, String> displays = new HashMap<>();
        private Context context;
        private String brushApp;
        private long revision;
        private boolean queued;
        private BroadcastReceiver requests;

        public Server(FocusStylusModule module) {
            this.module = module;
            if (module.session().host("focus.server.snapshot") instanceof Bundle saved) {
                LOCAL.accept(saved, Process.SYSTEM_UID);
                revision = saved.getLong("revision", 0);
                Bundle encoded = saved.getBundle("displays");
                if (encoded != null) for (String key : encoded.keySet()) displays.put(Integer.parseInt(key), encoded.getString(key, ""));
            }
            if (module.session().host("focus.server.brush") instanceof String saved) brushApp = saved;
            if (module.session().host("focus.server.context") instanceof Context saved) { context = saved; MAIN.post(this::subscribe); }
            module.session().onClose("focus snapshot server", () -> ModuleSession.onLooper(MAIN, () -> {
                MAIN.removeCallbacksAndMessages(null);
                if (requests != null) context.unregisterReceiver(requests);
                module.session().remember("focus.server.snapshot", message());
                module.session().remember("focus.server.context", context);
                module.session().remember("focus.server.brush", brushApp);
                requests = null; context = null;
            }));
        }

        public synchronized void observe(Object display, boolean removed) throws Throwable {
            Object rawId = Reflect.call(display, "getDisplayId");
            if (!(rawId instanceof Integer id) || id < 0) return;
            captureContext(display);
            String nextBrush = focusedBrushApp(display, removed);
            boolean brushChanged = !Objects.equals(brushApp, nextBrush);
            brushApp = nextBrush;
            String app = removed ? null : focusedWindow(display);
            String encoded = app == null ? "" : app;
            boolean changed = removed ? displays.remove(id) != null : !encoded.equals(displays.put(id, encoded));
            if (!changed && !brushChanged) return;
            revision++;
            LOCAL.accept(message(), Process.SYSTEM_UID);
            if (!queued) { queued = true; MAIN.post(this::publish); }
        }

        private void captureContext(Object display) {
            if (context != null) return;
            try {
                Object wm = Reflect.field(display, "mWmService");
                Object value = Reflect.field(wm, "mContext");
                if (value instanceof Context host) { context = host; MAIN.post(this::subscribe); }
            } catch (Throwable unavailable) { /* A later display update can supply the host context. */ }
        }

        private String focusedBrushApp(Object display, boolean removed) {
            try {
                Object wm = Reflect.field(display, "mWmService");
                Object active = Reflect.call(Reflect.field(wm, "mRoot"), "getTopFocusedDisplayContent");
                return active == null || removed && active == display ? null : focusedWindow(active);
            } catch (Throwable unavailable) { return null; }
        }

        private void subscribe() {
            if (!module.session().isActive() || requests != null) return;
            Context host;
            synchronized (this) { host = context; }
            if (host == null) return;
            try {
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override public void onReceive(Context context, Intent request) {
                        int sender = getSentFromUid();
                        if (trustedRequest(context, sender)) publish();
                    }
                };
                host.registerReceiver(receiver, new IntentFilter(REQUEST), null, MAIN, Context.RECEIVER_EXPORTED);
                requests = receiver;
                ConfigBridge.start(host, ignored -> { });
                publish();
            } catch (RuntimeException error) { module.error("Focus server subscription", error); }
        }

        private Bundle message() {
            Bundle apps = new Bundle();
            displays.forEach((display, app) -> apps.putString(Integer.toString(display), app));
            Bundle value = new Bundle();
            value.putLong("revision", revision);
            value.putBundle("displays", apps);
            return value;
        }

        private void publish() {
            if (!module.session().isActive()) return;
            Context host;
            Bundle snapshot;
            String brush;
            synchronized (this) { queued = false; host = context; snapshot = message(); brush = brushApp; }
            if (host == null) return;
            try { SystemBrushSettings.start(module, host); SystemBrushSettings.focus(brush); }
            catch (Throwable error) { module.error("Brush focus synchronization", error); }
            for (String target : clientPackages()) {
                try { Protocol.send(host, new Intent(UPDATE).putExtras(snapshot), target); }
                catch (RuntimeException error) { module.error("Focus snapshot publication", error); }
            }
        }
    }

    static boolean trustedRequest(Context context, int sender) {
        return sender == Process.SYSTEM_UID || Protocol.from(context, sender, Protocol.HOME)
                || Protocol.from(context, sender, Protocol.SECURITY_CENTER);
    }

    static String[] clientPackages() { return new String[]{Protocol.SYSTEM_UI, Protocol.HOME, Protocol.SECURITY_CENTER}; }

    private static String focusedWindow(Object display) {
        try {
            Object window = Reflect.field(display, "mCurrentFocus");
            if (window == null) return null;
            Object app = Reflect.call(window, "getOwningPackage");
            return app instanceof String value && EnhanceConfig.validPackage(value) ? value : null;
        } catch (Throwable unavailable) { return null; }
    }

    private GestureFocusBridge() { }
}
