package cc.star0.xposed.hyperstylus.runtime.gesture;

import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.config.ConfigBridge;
import cc.star0.xposed.hyperstylus.config.GestureAppConfig;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;
import cc.star0.xposed.hyperstylus.module.ModuleSession;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.libxposed.api.XposedInterface;

/** Exact Shell divider ABI and a receiver-scoped menu dispatch filter; no host listeners are replaced. */
public final class SplitStylusGestureHook {
    private static final String DIVIDER_STATE = "gesture.split.divider", MENU_STATE = "gesture.split.menu";
    private final FocusStylusModule module;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean deviceStart = new AtomicBoolean();
    private InputManager devices;
    private SplitTouchFilter divider, menu;
    private SplitDividerInputHook routing;
    private final InputManager.InputDeviceListener listener = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int device) { }
        @Override public void onInputDeviceChanged(int device) { }
        @Override public void onInputDeviceRemoved(int device) {
            module.safely("Forget split input device", () -> {
                try { ModuleSession.runAll(() -> { if (routing != null) routing.forget(device); },
                        () -> { if (divider != null) divider.forget(device); },
                        () -> { if (menu != null) menu.forget(device); }); }
                catch (Throwable failure) { module.error("Release split input device", failure); }
            });
        }
    };

    private SplitStylusGestureHook(FocusStylusModule module) {
        this.module = module;
        module.session().onClose("split input consumers", () -> ModuleSession.runAll(
                () -> ModuleSession.onLooper(main, () -> {
                    main.removeCallbacksAndMessages(null);
                    if (devices != null) { devices.unregisterInputDeviceListener(listener); devices = null; }
                }),
                () -> { if (divider != null) module.session().remember(DIVIDER_STATE, divider.snapshot()); },
                () -> { if (menu != null) module.session().remember(MENU_STATE, menu.snapshot()); }));
    }

    public static void install(FocusStylusModule module, ClassLoader loader) {
        SplitStylusGestureHook input = new SplitStylusGestureHook(module);
        input.installDivider(loader);
        input.installMenu(loader);
    }

    private void installDivider(ClassLoader loader) {
        try {
            Class<?> type = Reflect.find(loader, "com.android.wm.shell.common.split.DividerView");
            Class<?> layout = Reflect.find(loader, "com.android.wm.shell.common.split.SplitLayout");
            if (type == null || layout == null) throw new ClassNotFoundException("Shell split divider");
            Method touch = type.getDeclaredMethod("onTouch", View.class, MotionEvent.class);
            requireInput(touch);
            SplitDividerCancellation cleanup = new SplitDividerCancellation(type, layout);
            var original = module.getInvoker(touch).setType(XposedInterface.Invoker.Type.ORIGIN);
            SplitTouchFilter.Canceller canceller = (owner, event) -> {
                SplitTouchFilter.Cancellation cancel = cleanup.prepare(owner, event,
                        (view, input) -> original.invoke(view, view, input));
                return onOwner(owner, cancel);
            };
            divider = new SplitTouchFilter(canceller);
            restore(divider, DIVIDER_STATE);
            AtomicBoolean reported = new AtomicBoolean(), failed = new AtomicBoolean();
            AtomicBoolean routingFailed = new AtomicBoolean();
            module.managedHook(touch).intercept(chain -> {
                if (chain.getThisObject().getClass() != type || !(chain.getThisObject() instanceof View owner)
                        || chain.getArg(0) != owner || !(chain.getArg(1) instanceof MotionEvent event)) return chain.proceed();
                boolean consumed;
                try {
                    consumed = routing != null && routing.before(owner, event);
                    if (!consumed) consumed = consume(divider, owner, event, reported, failed);
                    if (!consumed && routing != null) routing.capture(owner, event);
                    routingFailed.set(false);
                } catch (Throwable failure) {
                    if (routingFailed.compareAndSet(false, true))
                        module.error("Split divider pointer routing failed; retaining blocked press", failure);
                    return true;
                }
                boolean success = false;
                try {
                    Object result = consumed ? true : chain.proceed();
                    success = true;
                    return result;
                } finally {
                    if (routing != null) {
                        try { routing.after(owner, event, consumed, success); }
                        catch (Throwable failure) { module.error("Finish divider input routing", failure); }
                    }
                }
            });
            routing = SplitDividerInputHook.install(module, loader, type, divider);
            module.info("Split divider consumer: " + touch);
        } catch (Throwable failure) { module.error("Split divider input unavailable", failure); }
    }

    private void installMenu(ClassLoader loader) {
        try {
            Class<?> type = Reflect.find(loader, "com.android.wm.shell.common.split.MiuiSplitMenuView");
            if (type == null || !View.class.isAssignableFrom(type)) throw new ClassNotFoundException("Shell split menu");
            Method dispatch = type.getMethod("dispatchTouchEvent", MotionEvent.class);
            requireInput(dispatch);
            var original = module.getInvoker(dispatch).setType(XposedInterface.Invoker.Type.ORIGIN);
            menu = new SplitTouchFilter((owner, event) -> onOwner(owner, () -> original.invoke(owner, event)));
            restore(menu, MENU_STATE);
            AtomicBoolean reported = new AtomicBoolean(), failed = new AtomicBoolean();
            module.managedHook(dispatch).intercept(chain -> {
                // This inherited framework entry also serves other views; reject them before reading config.
                if (chain.getThisObject().getClass() != type || !(chain.getThisObject() instanceof View owner)
                        || !(chain.getArg(0) instanceof MotionEvent event)) return chain.proceed();
                if (consume(menu, owner, event, reported, failed)) return true;
                return chain.proceed();
            });
            module.info("Split menu consumer: " + dispatch + " receiver=" + type.getName());
        } catch (Throwable failure) { module.error("Split menu input unavailable", failure); }
    }

    private static void requireInput(Method method) throws NoSuchMethodException {
        if (method.getReturnType() != boolean.class || Modifier.isStatic(method.getModifiers())
                || Modifier.isAbstract(method.getModifiers())) throw new NoSuchMethodException("Split input ABI: " + method);
    }

    private static SplitTouchFilter.Cancellation onOwner(View owner, SplitTouchFilter.Cancellation task) {
        Handler handler = owner.getHandler();
        Looper looper = Looper.myLooper();
        if (handler == null && looper != null) handler = new Handler(looper);
        Handler target = handler;
        return () -> {
            if (target == null) throw new IllegalStateException("Split consumer Looper unavailable");
            ModuleSession.onLooper(target, task::run);
        };
    }

    private void restore(SplitTouchFilter filter, String key) {
        filter.restore(module.session().host(key));
        module.session().remember(key, null);
    }

    private boolean consume(SplitTouchFilter filter, View owner, MotionEvent event,
                            AtomicBoolean reported, AtomicBoolean failed) throws Throwable {
        ensureDevices(owner);
        boolean enabled = GestureFocusBridge.enabled(event, GestureAppConfig.SPLIT_DIVIDER,
                ConfigBridge.current().ignoreStylusSplitDivider);
        if (filter == divider && routing != null) enabled = routing.enabled(owner, enabled);
        try {
            boolean consumed = filter.consume(owner, event, enabled);
            failed.set(false);
            if (consumed && reported.compareAndSet(false, true)) module.info("Blocked stylus split consumer: " + owner.getClass().getName());
            return consumed;
        } catch (Throwable failure) {
            if (failed.compareAndSet(false, true)) module.error("Split cancellation failed; retaining blocked stream", failure);
            if (filter == divider && routing != null) throw failure;
            return true;
        }
    }

    private void ensureDevices(View owner) {
        if (!deviceStart.compareAndSet(false, true)) return;
        Context context = owner.getContext();
        if (!main.post(() -> module.safely("Observe split input devices", () -> {
            try {
                InputManager manager = context.getSystemService(InputManager.class);
                if (manager == null) { deviceStart.set(false); return; }
                manager.registerInputDeviceListener(listener, main);
                devices = manager;
            } catch (Throwable failure) {
                deviceStart.set(false); module.error("Observe split input devices", failure);
            }
        }))) deviceStart.set(false);
    }
}
