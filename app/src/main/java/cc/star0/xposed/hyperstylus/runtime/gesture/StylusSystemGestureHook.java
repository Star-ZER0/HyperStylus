package cc.star0.xposed.hyperstylus.runtime.gesture;

import cc.star0.xposed.hyperstylus.common.Protocol;
import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.config.ConfigBridge;
import cc.star0.xposed.hyperstylus.config.DisplayPolicy;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;

import android.content.Context;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Declarative ROM entry points and scoped terminal guards for pen-only observer suppression. */
public final class StylusSystemGestureHook {
    private record Route(String owner, String method, Class<?> event) { }
    private static final List<String> HOME_HELPERS = List.of(
            "com.miui.home.recents.GestureInputHelper", "com.miui.home.recents.InterceptingGestureInputHelper");
    private static final List<Route> HOME_CONSUMERS = List.of(
            new Route("com.miui.home.recents.GestureTouchEventTracker", "onTouchEvent", MotionEvent.class));

    public static void installSystemBars(FocusStylusModule module, ClassLoader loader) {
        Class<?> listener = Reflect.find(loader, "com.android.server.wm.SystemGesturesPointerEventListener");
        ServerContext context = new ServerContext(module);
        if (listener != null) {
            for (Constructor<?> constructor : listener.getDeclaredConstructors()) {
                try { module.after(constructor, (receiver, args, result) -> context.capture(receiver, args)); }
                catch (Throwable error) { module.error("System-bar context observation", error); }
            }
        }
        Inputs filters = new Inputs(module, "system-bars", () -> ConfigBridge.current().ignoreStylusSystemBars,
                receiver -> context.capture(receiver, new Object[0]));
        filters.observe(listener, "onPointerEvent", MotionEvent.class);
        module.info("System-bar event filters=" + filters.installed.size());
        TransientBars terminals = new TransientBars(module);
        terminals.install(Reflect.find(loader, "com.android.server.wm.DisplayPolicy"), "requestTransientBars");
        terminals.install(Reflect.find(loader, "com.android.server.wm.InsetsPolicy"), "showTransient");
    }

    public static void installHome(FocusStylusModule module, ClassLoader loader) {
        StylusBackGestureHook.installHome(module, loader);
        HomeInputs inputs = new HomeInputs(module);
        for (String name : HOME_HELPERS) {
            Class<?> type = Reflect.find(loader, name);
            if (type == null) continue;
            inputs.hierarchy(type);
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                try { module.after(constructor, (receiver, args, result) -> inputs.hierarchy(receiver.getClass())); }
                catch (Throwable error) { module.error("Home runtime consumer discovery", error); }
            }
        }
        for (Route route : HOME_CONSUMERS) inputs.filters.observe(Reflect.find(loader, route.owner()), route.method(), route.event());
        module.info("Home-recents event filters=" + inputs.filters.installed.size());
    }

    private static final class Inputs {
        private final FocusStylusModule module;
        private final String feature;
        private final BooleanSupplier enabled;
        private final Consumer<Object> prepare;
        private final Set<Method> installed = ConcurrentHashMap.newKeySet();

        public Inputs(FocusStylusModule module, String feature, BooleanSupplier enabled, Consumer<Object> prepare) {
            this.module = module; this.feature = feature; this.enabled = enabled; this.prepare = prepare;
        }
        public void observe(Class<?> type, String name, Class<?> input) {
            if (type == null) return;
            for (Method method : type.getDeclaredMethods()) {
                if (!MotionGestureFilter.matches(method, name, input) || !installed.add(method)) continue;
                try { MotionGestureFilter.install(module, method, enabled, feature, prepare); }
                catch (Throwable error) { installed.remove(method); module.error("Observe " + feature, error); }
            }
        }
    }

    private static final class HomeInputs {
        private final FocusStylusModule module;
        private final Inputs filters;
        private final Set<Method> guarded = ConcurrentHashMap.newKeySet();

        public HomeInputs(FocusStylusModule module) {
            this.module = module;
            filters = new Inputs(module, "home-recents", () -> ConfigBridge.current().ignoreStylusHomeRecents, null);
        }
        public void hierarchy(Class<?> runtime) {
            for (Class<?> type = runtime; type != null && type != Object.class; type = type.getSuperclass()) {
                filters.observe(type, "onInputEvent", InputEvent.class);
                for (Method method : type.getDeclaredMethods()) {
                    if (!method.getName().equals("pilferPointers") || method.getParameterCount() != 0
                            || method.getReturnType() != void.class || !concreteInstance(method) || !guarded.add(method)) continue;
                    try {
                        module.managedHook(method).intercept(chain -> {
                            MotionGestureFilter.Origin origin = MotionGestureFilter.origin();
                            return origin != null && origin.blocked() && origin.feature().startsWith("home-")
                                    ? null : chain.proceed();
                        });
                    } catch (Throwable error) { guarded.remove(method); module.error("Home pointer ownership guard", error); }
                }
                if (type.getName().equals(HOME_HELPERS.get(0))) break;
            }
        }
    }

    private static final class TransientBars {
        private final FocusStylusModule module;
        private final Set<String> reported = ConcurrentHashMap.newKeySet();
        public TransientBars(FocusStylusModule module) { this.module = module; }

        public void install(Class<?> type, String name) {
            if (type == null) return;
            for (Method method : type.getDeclaredMethods()) {
                if (!name.equals(method.getName()) || method.getReturnType() != void.class || !concreteInstance(method)) continue;
                try {
                    module.managedHook(method).intercept(chain -> {
                        MotionGestureFilter.Origin origin = MotionGestureFilter.origin();
                        if (origin != null && origin.blocked() && origin.feature().equals("system-bars")) return null;
                        if (ConfigBridge.current().ignoreStylusSystemBars) diagnose(method, origin);
                        return chain.proceed();
                    });
                } catch (Throwable error) { module.error("Transient system-bar endpoint", error); }
            }
        }
        private void diagnose(Method endpoint, MotionGestureFilter.Origin origin) {
            if (reported.size() >= 6) return;
            StringBuilder trace = new StringBuilder();
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                String owner = frame.getClassName();
                if (owner.startsWith(Protocol.MODULE) || owner.startsWith("java.") || owner.startsWith("jdk.")
                        || owner.startsWith("io.github.libxposed.") || owner.startsWith("org.lsposed.")) continue;
                if (trace.length() > 0) trace.append(" <- ");
                trace.append(owner).append('#').append(frame.getMethodName());
                if (trace.length() > 600) break;
            }
            String attribution = origin == null ? "unattributed" : "tool=" + origin.tool() + ", source=0x" + Integer.toHexString(origin.source());
            String detail = endpoint + "; " + attribution + "; " + trace;
            if (reported.add(detail)) module.info("Transient bar route: " + detail);
        }
    }

    private static final class ServerContext {
        private final FocusStylusModule module;
        private boolean attached;
        public ServerContext(FocusStylusModule module) { this.module = module; }

        public synchronized void capture(Object receiver, Object[] arguments) {
            if (attached) return;
            Context host = null;
            for (Object argument : arguments) if (argument instanceof Context value) { host = value; break; }
            if (host == null && receiver != null) {
                try {
                    Object value = Reflect.field(receiver, "mContext");
                    if (value instanceof Context context) host = context;
                } catch (ReflectiveOperationException | RuntimeException unavailable) { return; }
            }
            if (host == null) return;
            attached = true;
            ConfigBridge.start(host, config -> module.info("System-bar configuration=" + config.revision));
        }
    }

    private static boolean concreteInstance(Method method) {
        return !Modifier.isStatic(method.getModifiers()) && !Modifier.isAbstract(method.getModifiers());
    }
    private StylusSystemGestureHook() { }
}
