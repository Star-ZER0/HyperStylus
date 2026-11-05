package cc.star0.xposed.hyperstylus.runtime.gesture;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import cc.star0.xposed.hyperstylus.config.ConfigBridge;
import cc.star0.xposed.hyperstylus.config.GestureAppConfig;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;
import cc.star0.xposed.hyperstylus.module.ModuleSession;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Make only the verified Shell divider a trusted spy, preserving native window hit testing. */
final class SplitDividerInputHook {
    interface Types { Class<?> resolve(String name) throws ClassNotFoundException; }
    private static final String WINDOWS = "gesture.split.windows";
    private final FocusStylusModule module;
    private final Class<?> dividerType, managerType;
    private final Field managerHost, managerView, dividerHost, features, privateFlags;
    private final Method getManager, getRoot, displayId, inputToken, pilfer, relayout;
    private final SplitTouchFilter filter;
    private final Map<View, Window> windows = new IdentityHashMap<>();
    private final AtomicBoolean subscribed = new AtomicBoolean();
    private ModuleSession.Registration focus;
    private volatile boolean operational, closed;

    private final class Window implements SplitDividerRouting.Host {
        final View view;
        final Object host;
        final Handler handler;
        final InputManager input;
        final SplitDividerRouting routing = new SplitDividerRouting(this);
        WindowManager.LayoutParams params;
        MotionEvent lastNative;
        boolean creating, writing, usable;

        Window(View view, Object host, WindowManager.LayoutParams params, boolean creating) {
            this.view = view; this.host = host; this.params = params; this.creating = creating;
            usable = !creating;
            Handler attached = view.getHandler();
            if (attached == null && Looper.myLooper() == null) throw new IllegalStateException("Divider Looper unavailable");
            handler = attached != null ? attached : new Handler(Looper.myLooper());
            Context context = view.getContext();
            if (context.checkSelfPermission("android.permission.MONITOR_INPUT") != PackageManager.PERMISSION_GRANTED
                    || (input = context.getSystemService(InputManager.class)) == null)
                throw new IllegalStateException("Divider requires native MONITOR_INPUT permission");
        }
        @Override public int features() throws Throwable { return features.getInt(params); }
        @Override public void features(int value) throws Throwable {
            int previous = features.getInt(params);
            features.setInt(params, value);
            if (creating) return;
            writing = true;
            try { invoke(relayout, host, params); }
            catch (Throwable failure) { features.setInt(params, previous); throw failure; }
            finally { writing = false; }
        }
        @Override public void pilfer() throws Throwable {
            Object root = invoke(getRoot, host);
            Object token = root == null ? null : invoke(inputToken, root);
            if (!(token instanceof IBinder)) throw new IllegalStateException("Divider input channel unavailable");
            invoke(pilfer, input, token);
        }
        @Override public void cancel() throws Throwable {
            filter.cancelOwned(view, lastNative);
            recycle();
        }
        void record(MotionEvent event) { recycle(); lastNative = MotionEvent.obtain(event); }
        void recycle() { if (lastNative != null) { lastNative.recycle(); lastNative = null; } }
        void refresh() throws Throwable {
            var config = ConfigBridge.current();
            // The native root already knows its display before View.attach / the first setView.
            int display = (Integer) invoke(displayId, invoke(getRoot, host));
            routing.configure(GestureFocusBridge.enabledOnDisplay(display, GestureAppConfig.SPLIT_DIVIDER,
                    config, config.ignoreStylusSplitDivider));
        }
    }

    SplitDividerInputHook(FocusStylusModule module, Types types, Class<?> divider,
                                  SplitTouchFilter filter) throws Throwable {
        this.module = module; dividerType = divider; this.filter = filter;
        managerType = types.resolve("com.android.wm.shell.common.split.SplitWindowManager");
        Class<?> host = types.resolve("android.view.SurfaceControlViewHost");
        Class<?> root = types.resolve("android.view.ViewRootImpl");
        managerHost = field(managerType, "mViewHost", host);
        managerView = field(managerType, "mDividerView", divider);
        dividerHost = field(divider, "mViewHost", host);
        features = field(WindowManager.LayoutParams.class, "inputFeatures", int.class);
        privateFlags = field(WindowManager.LayoutParams.class, "privateFlags", int.class);
        constant(WindowManager.LayoutParams.class, "INPUT_FEATURE_SPY", SplitDividerRouting.SPY);
        constant(WindowManager.LayoutParams.class, "PRIVATE_FLAG_TRUSTED_OVERLAY", 536870912);
        getManager = method(host, "getWindowlessWM", types.resolve("android.view.WindowlessWindowManager"));
        getRoot = method(host, "getRootSurfaceControl", types.resolve("android.view.AttachedSurfaceControl"));
        displayId = method(root, "getDisplayId", int.class);
        inputToken = method(root, "getInputToken", IBinder.class);
        pilfer = method(InputManager.class, "pilferPointers", void.class, IBinder.class);
        relayout = method(host, "relayout", void.class, WindowManager.LayoutParams.class);
    }

    static SplitDividerInputHook install(FocusStylusModule module, ClassLoader loader, Class<?> divider,
                                         SplitTouchFilter filter) {
        SplitDividerInputHook hook = null;
        try {
            hook = new SplitDividerInputHook(module, name -> Class.forName(name, false, loader), divider, filter);
            hook.install();
            return hook;
        } catch (Throwable failure) {
            if (hook != null) {
                hook.operational = false;
                try { hook.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            }
            module.error("Split divider passthrough unavailable", failure); return null;
        }
    }

    void install() throws Throwable {
        module.session().onClose("split divider window routing", this::close);
        Class<?> hostType = managerHost.getType();
        Method setView = method(hostType, "setView", void.class, View.class, WindowManager.LayoutParams.class);
        module.managedHook(setView).intercept(chain -> {
            if (!operational || closed || !(chain.getArg(0) instanceof View view) || view.getClass() != dividerType
                    || !(chain.getArg(1) instanceof WindowManager.LayoutParams params)) return chain.proceed();
            Window window;
            try { window = discover(view, chain.getThisObject(), params, true); }
            catch (Throwable failure) { module.error("Unsupported divider input window", failure); return chain.proceed(); }
            if (window == null) return chain.proceed();
            try { window.refresh(); }
            catch (Throwable failure) { module.error("Prepare divider spy window", failure); }
            try {
                Object result = chain.proceed();
                window.creating = false; window.usable = true;
                subscribe(view.getContext());
                return result;
            } catch (Throwable failure) {
                window.creating = false;
                try { window.routing.close(); synchronized (windows) { windows.remove(view); } }
                catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        });
        module.managedHook(relayout).intercept(chain -> {
            Window window = forHost(chain.getThisObject());
            WindowManager.LayoutParams previous = window == null ? null : window.params;
            if (operational && !closed && window != null && !window.writing
                    && chain.getArg(0) instanceof WindowManager.LayoutParams params) {
                if (params.type != 2034 || (privateFlags.getInt(params) & 536870912) == 0) {
                    boolean owned = window.routing.ownsSpy();
                    try { window.routing.close(); }
                    catch (Throwable failure) { module.error("Restore changed divider window", failure); }
                    if (owned) features.setInt(params, features.getInt(params) & ~SplitDividerRouting.SPY);
                    window.usable = false;
                    return chain.proceed();
                }
                features.setInt(params, window.routing.effective(features.getInt(params)));
                window.params = params;
                if (window.view.getLayoutParams() != params) window.view.setLayoutParams(params);
            }
            try { return chain.proceed(); }
            catch (Throwable failure) {
                if (window != null && !window.writing) {
                    window.routing.relayoutFailed(); window.params = previous;
                    if (window.view.getLayoutParams() != previous) window.view.setLayoutParams(previous);
                }
                throw failure;
            }
        });
        module.managedHook(method(hostType, "release", void.class)).intercept(chain -> {
            Window window = forHost(chain.getThisObject());
            if (window != null) {
                try { ModuleSession.onLooper(window.handler, window.routing::close); }
                catch (Throwable failure) { module.error("Cancel released divider window", failure); }
            }
            Object result = chain.proceed();
            if (window != null) synchronized (windows) { windows.remove(window.view); window.recycle(); }
            return result;
        });
        operational = true;
        focus = GestureFocusBridge.observeChanges(this::refreshAll);
        if (module.session().host(WINDOWS) instanceof Map<?, ?> saved) {
            for (var entry : saved.entrySet()) {
                if (!(entry.getKey() instanceof View view) || view.getClass() != dividerType
                        || !(entry.getValue() instanceof Bundle state)) continue;
                try {
                    Object host = dividerHost.get(view);
                    if (host == null || !(view.getLayoutParams() instanceof WindowManager.LayoutParams params)) continue;
                    Window window = discover(view, host, params, false);
                    if (window != null) { window.routing.restore(state); subscribe(view.getContext()); queue(window); }
                } catch (Throwable failure) { module.error("Restore divider input window", failure); }
            }
        }
        module.session().remember(WINDOWS, null);
        module.info("Split divider routing: trusted SPY window; isolated native press pilfers its own channel");
    }

    private Window discover(View view, Object host, WindowManager.LayoutParams params, boolean creating) throws Throwable {
        synchronized (windows) {
            Window existing = windows.get(view);
            if (existing != null) return existing.host == host ? existing : null;
        }
        Object manager = invoke(getManager, host);
        if (manager == null || manager.getClass() != managerType || managerHost.get(manager) != host
                || managerView.get(manager) != view || params.type != 2034
                || (privateFlags.getInt(params) & 536870912) == 0) return null;
        if ((features.getInt(params) & SplitDividerRouting.SPY) != 0)
            throw new IllegalStateException("Divider already has a native SPY owner");
        Window window = new Window(view, host, params, creating);
        synchronized (windows) { windows.put(view, window); }
        return window;
    }

    private Window forHost(Object host) {
        synchronized (windows) { for (Window window : windows.values()) if (window.host == host) return window; }
        return null;
    }
    private Window forView(View view) { synchronized (windows) { return windows.get(view); } }

    /** Late injection discovers an already attached divider without pretending its current DOWN was rerouted. */
    void observe(View view) {
        if (closed || !operational || forView(view) != null) return;
        try {
            Object host = dividerHost.get(view);
            if (host == null || !(view.getLayoutParams() instanceof WindowManager.LayoutParams params)) return;
            Window window = discover(view, host, params, false);
            if (window != null) { subscribe(view.getContext()); queue(window); }
        } catch (Throwable failure) { module.error("Discover divider input window", failure); }
    }

    boolean before(View owner, MotionEvent event) throws Throwable {
        observe(owner);
        Window window = forView(owner);
        return window != null && window.usable && window.routing.before(event);
    }
    boolean enabled(View owner, boolean fallback) {
        Window window = forView(owner);
        return window == null || !window.usable ? fallback : window.routing.enabled(fallback);
    }
    void capture(View owner, MotionEvent event) throws Throwable {
        Window window = forView(owner);
        if (window != null && window.usable) {
            try { window.routing.capture(event); }
            catch (Throwable failure) { filter.denyUnstarted(owner); throw failure; }
            window.record(event);
        }
    }
    void after(View owner, MotionEvent event, boolean consumed, boolean success) throws Throwable {
        Window window = forView(owner);
        if (window == null || !window.usable) return;
        boolean same = window.lastNative != null && window.lastNative.getDeviceId() == event.getDeviceId()
                && window.lastNative.getDownTime() == event.getDownTime();
        if (consumed && same) window.recycle(); // The filter has completed any mixed-stream cancellation.
        int action = event.getActionMasked();
        if (success) {
            if (same && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) window.recycle();
            window.routing.after(event);
        }
    }

    void forget(int device) throws Throwable {
        var tasks = new ArrayList<ModuleSession.Cleanup>();
        synchronized (windows) { for (Window window : windows.values())
            tasks.add(() -> ModuleSession.onLooper(window.handler, () -> window.routing.forget(device))); }
        ModuleSession.runAll(tasks.toArray(ModuleSession.Cleanup[]::new));
    }
    private void subscribe(Context context) {
        if (subscribed.compareAndSet(false, true)) ConfigBridge.start(context, ignored -> { if (!closed) refreshAll(); });
    }
    private void refreshAll() { synchronized (windows) { for (Window window : windows.values()) queue(window); } }
    private void queue(Window window) {
        window.handler.post(() -> module.safely("Refresh divider input routing", () -> {
            if (closed || !window.usable || forView(window.view) != window) return;
            try { window.refresh(); } catch (Throwable failure) { module.error("Refresh divider spy window", failure); }
        }));
    }
    private void close() throws Throwable {
        closed = true;
        if (focus != null) focus.forget();
        Map<View, Bundle> saved = new IdentityHashMap<>();
        var tasks = new ArrayList<ModuleSession.Cleanup>();
        synchronized (windows) { for (Window window : windows.values()) tasks.add(() -> ModuleSession.onLooper(window.handler, () -> {
            window.routing.close(); window.recycle(); saved.put(window.view, window.routing.snapshot());
        })); }
        ModuleSession.runAll(tasks.toArray(ModuleSession.Cleanup[]::new));
        module.session().remember(WINDOWS, saved);
    }

    private static Field field(Class<?> type, String name, Class<?> expected) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        if (field.getType() != expected || Modifier.isStatic(field.getModifiers())) throw new NoSuchFieldException(name + " ABI");
        field.setAccessible(true); return field;
    }
    private static void constant(Class<?> type, String name, int expected) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        if (field.getType() != int.class || !Modifier.isStatic(field.getModifiers()) || field.getInt(null) != expected)
            throw new NoSuchFieldException(name + " ABI");
    }
    private static Method method(Class<?> type, String name, Class<?> result, Class<?>... args) throws ReflectiveOperationException {
        Method method = type.getDeclaredMethod(name, args);
        if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers()) || Modifier.isAbstract(method.getModifiers()))
            throw new NoSuchMethodException(name + " ABI");
        method.setAccessible(true); return method;
    }
    private static Object invoke(Method method, Object owner, Object... args) throws Throwable {
        try { return method.invoke(owner, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
