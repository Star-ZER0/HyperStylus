package cc.star0.xposed.hyperstylus.runtime.gesture;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.config.ConfigBridge;
import cc.star0.xposed.hyperstylus.config.GestureAppConfig;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;
import cc.star0.xposed.hyperstylus.module.ModuleSession;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.libxposed.api.XposedInterface;

/** Filter both native side-strip consumers; the expanded application menu stays native. */
public final class StylusSidebarHook {
    interface Classes { Class<?> find(String name) throws ClassNotFoundException; }
    private static final String WRAPPERS = "gesture.sidebar.wrappers", STREAMS = "gesture.sidebar.streams";
    private record Identity(long down, int device) { }
    private record Binding(WeakReference<Object> wrapper, WeakReference<View> window, Method touch, Method generic) { }
    private final FocusStylusModule module;
    private final ClassLoader loader;
    private final Class<?> wrapperType, coverType, lineType, serviceType, managerType;
    private final Method cover, line, panel;
    private final Field serviceManager;
    private final SidebarTouchCancellation cancellation;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<View, Binding> views = new WeakHashMap<>();
    private final Map<Object, Boolean> wrappers = new WeakHashMap<>();
    private final Map<Method, Boolean> installed = new HashMap<>();
    private final Map<View, Identity> redirected = new WeakHashMap<>();
    private final AtomicBoolean reported = new AtomicBoolean(), redirectFailure = new AtomicBoolean();
    private final SplitTouchFilter filter;
    private InputManager devices;
    private SidebarMotionRedirect redirect;
    private boolean closed;
    private final InputManager.InputDeviceListener deviceListener = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id) { }
        @Override public void onInputDeviceChanged(int id) { }
        @Override public void onInputDeviceRemoved(int id) {
            module.safely("Forget sidebar input device", () -> {
                try { filter.forget(id); }
                catch (Throwable error) { module.error("Release sidebar input device", error); }
                synchronized (StylusSidebarHook.this) { redirected.entrySet().removeIf(entry -> entry.getValue().device == id); }
            });
        }
    };

    public StylusSidebarHook(FocusStylusModule module, ClassLoader loader) throws Throwable {
        this(module, loader, name -> Class.forName(name, false, loader));
    }
    StylusSidebarHook(FocusStylusModule module, ClassLoader loader, Classes classes) throws Throwable {
        this.module = module; this.loader = loader;
        wrapperType = classes.find("com.miui.dock.sidebar.p");
        coverType = classes.find("com.miui.dock.sidebar.e");
        lineType = classes.find("com.miui.dock.sidebar.RegionSamplingImageView");
        Class<?> touch = classes.find("com.miui.dock.sidebar.k");
        serviceType = classes.find("com.miui.gamebooster.service.DockWindowManagerService");
        managerType = classes.find("vb.l0");
        cover = getter(wrapperType, "w", coverType);
        line = getter(wrapperType, "y", lineType);
        panel = getter(wrapperType, "B", FrameLayout.class);
        serviceManager = serviceType.getDeclaredField("d"); serviceManager.setAccessible(true);
        if (serviceManager.getType() != managerType || Modifier.isStatic(serviceManager.getModifiers())) throw new NoSuchFieldException("Sidebar manager ABI");
        cancellation = new SidebarTouchCancellation(wrapperType, touch);
        filter = new SplitTouchFilter((owner, event) -> {
            Binding binding;
            synchronized (this) { binding = views.get(owner); }
            Object wrapper = binding == null ? null : binding.wrapper.get();
            if (wrapper == null) throw new IllegalStateException("Sidebar consumer association unavailable");
            Method dispatch = owner.getClass().getMethod("dispatchTouchEvent", MotionEvent.class);
            var origin = module.getInvoker(dispatch).setType(XposedInterface.Invoker.Type.ORIGIN);
            return cancellation.prepare(wrapper, owner, event, (view, cancel) -> origin.invoke(view, cancel));
        }, false, true);
        module.session().onClose("sidebar input consumers", this::close);
    }
    private static Method getter(Class<?> type, String name, Class<?> result) throws ReflectiveOperationException {
        Method value = type.getDeclaredMethod(name);
        if (value.getReturnType() != result || Modifier.isStatic(value.getModifiers())) throw new NoSuchMethodException("Sidebar " + name + " ABI");
        value.setAccessible(true); return value;
    }
    public void install() throws Throwable {
        for (var constructor : wrapperType.getDeclaredConstructors()) module.after(constructor, (owner, args, result) -> register(owner));
        module.after(cover, (owner, args, result) -> register(owner));
        module.after(line, (owner, args, result) -> register(owner));
        module.after(serviceType.getDeclaredMethod("onCreate"), (owner, args, result) -> discoverService(owner));
        Object saved = module.session().host(WRAPPERS);
        if (saved instanceof Iterable<?> owners) for (Object owner : owners) if (owner.getClass() == wrapperType) register(owner);
        filter.restore(module.session().host(STREAMS));
        module.session().remember(STREAMS, null);
        module.info("Sidebar consumers installed for SecurityCenter :ui");
    }
    private synchronized void register(Object owner) throws Throwable {
        if (closed || owner.getClass() != wrapperType || wrappers.put(owner, true) != null) return;
        try {
            // ORIGIN avoids invoking this getter's own lifecycle hook again.
            View coverView = (View) module.getInvoker(cover).setType(XposedInterface.Invoker.Type.ORIGIN).invoke(owner);
            if (coverView == null || coverView.getClass() != coverType) throw new IllegalStateException("Sidebar strip unavailable");
            View lineView = (View) module.getInvoker(line).setType(XposedInterface.Invoker.Type.ORIGIN).invoke(owner);
            View panelView = (View) module.getInvoker(panel).setType(XposedInterface.Invoker.Type.ORIGIN).invoke(owner);
            if (lineView == null || lineView.getClass() != lineType || !(panelView instanceof FrameLayout))
                throw new IllegalStateException("Sidebar line unavailable");
            Method coverTouch = coverView.getClass().getMethod("dispatchTouchEvent", MotionEvent.class);
            Method coverGeneric = coverView.getClass().getMethod("dispatchGenericMotionEvent", MotionEvent.class);
            Method lineTouch = lineView.getClass().getMethod("dispatchTouchEvent", MotionEvent.class);
            Method lineGeneric = lineView.getClass().getMethod("dispatchGenericMotionEvent", MotionEvent.class);
            views.put(coverView, new Binding(new WeakReference<>(owner), new WeakReference<>(coverView), coverTouch, coverGeneric));
            // The line remains independently touchable inside the panel after the cover opens it.
            // Its actual window is the panel, never the cover's redirect-eligible 2026 window.
            views.put(lineView, new Binding(new WeakReference<>(owner), new WeakReference<>(panelView), lineTouch, lineGeneric));
            installDispatch(coverTouch, false);
            installDispatch(coverGeneric, true);
            installDispatch(lineTouch, false);
            installDispatch(lineGeneric, true);
        } catch (Throwable failure) {
            views.entrySet().removeIf(entry -> entry.getValue().wrapper.get() == owner);
            wrappers.remove(owner); throw failure;
        }
    }
    private void installDispatch(Method method, boolean generic) throws Throwable {
        if (installed.containsKey(method)) return;
        if (method.getReturnType() != boolean.class || Modifier.isStatic(method.getModifiers()) || Modifier.isAbstract(method.getModifiers()))
            throw new NoSuchMethodException("Sidebar input ABI");
        module.managedHook(method).intercept(chain -> {
            if (!(chain.getThisObject() instanceof View owner) || !(chain.getArg(0) instanceof MotionEvent event)) return chain.proceed();
            if (owner.getClass() != coverType && owner.getClass() != lineType) return chain.proceed();
            Binding binding;
            synchronized (this) { binding = views.get(owner); }
            // Region's override calls View.super; do not filter or retry pending cancellation twice.
            if (binding == null || !(generic ? binding.generic : binding.touch).equals(method)) return chain.proceed();
            boolean enabled = GestureFocusBridge.enabled(event, GestureAppConfig.SIDEBAR, ConfigBridge.current().ignoreStylusSidebar);
            if (generic) {
                if (enabled && MotionGestureFilter.stylus(event)) return true;
                return chain.proceed();
            }
            boolean consumed;
            try { consumed = filter.consume(owner, event, enabled); }
            catch (Throwable failure) { module.error("Sidebar cancellation failed; retaining blocked stream", failure); return true; }
            if (!consumed) return chain.proceed();
            if (reported.compareAndSet(false, true)) module.info("Blocked stylus sidebar consumer");
            if (owner.getClass() == coverType && event.getActionMasked() == MotionEvent.ACTION_DOWN && MotionGestureFilter.stylus(event)) {
                Identity identity = new Identity(event.getDownTime(), event.getDeviceId());
                boolean submit;
                synchronized (this) { submit = !identity.equals(redirected.put(owner, identity)); }
                if (submit && redirect != null) {
                    View window = binding.window.get();
                    try { if (window != null && redirect.submit(window, event)) module.info("Submitted native sidebar motion redirect"); }
                    catch (Throwable failure) { if (redirectFailure.compareAndSet(false, true)) module.error("Sidebar native redirect failed", failure); }
                }
            }
            return true;
        });
        installed.put(method, generic);
    }
    public void start(Context context) {
        if (closed) return;
        ConfigBridge.start(context, config -> { });
        GestureFocusBridge.startClient(module, context);
        module.safely("Start sidebar input support", () -> {
            try { redirect = new SidebarMotionRedirect(loader, context); }
            catch (Throwable failure) { module.error("Sidebar native redirect unavailable; operation filtering remains active", failure); }
            try {
                devices = context.getSystemService(InputManager.class);
                if (devices != null) devices.registerInputDeviceListener(deviceListener, main);
            } catch (Throwable failure) { module.error("Observe sidebar input devices", failure); }
            discoverLoadedServices();
        });
    }
    private void discoverLoadedServices() {
        try {
            Class<?> thread = Reflect.find(loader, "android.app.ActivityThread");
            if (thread == null) throw new ClassNotFoundException("Host ActivityThread unavailable");
            Object current = Reflect.call(thread, "currentActivityThread");
            Object services = current == null ? null : Reflect.field(current, "mServices");
            if (services instanceof Map<?, ?> map) for (Object service : map.values())
                if (service != null && service.getClass() == serviceType) discoverService(service);
        } catch (Throwable failure) { module.error("Discover current sidebar service", failure); }
    }
    private void discoverService(Object service) throws Throwable {
        if (service.getClass() != serviceType) return;
        Object manager = serviceManager.get(service);
        if (manager == null || manager.getClass() != managerType) return;
        for (Field field : managerType.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers()) && field.getType() == wrapperType) {
            field.setAccessible(true); Object owner = field.get(manager);
            if (owner != null) register(owner);
        }
    }
    private void close() throws Throwable {
        closed = true;
        ModuleSession.runAll(
                () -> ModuleSession.onLooper(main, () -> { if (devices != null) devices.unregisterInputDeviceListener(deviceListener); }),
                () -> module.session().remember(STREAMS, filter.snapshot()));
        synchronized (this) {
            module.session().remember(WRAPPERS, new ArrayList<>(wrappers.keySet()));
            redirected.clear(); views.clear(); wrappers.clear();
        }
    }
}
