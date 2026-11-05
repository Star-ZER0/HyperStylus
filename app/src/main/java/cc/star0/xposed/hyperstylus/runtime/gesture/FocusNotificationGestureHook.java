package cc.star0.xposed.hyperstylus.runtime.gesture;

import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.config.ConfigBridge;
import cc.star0.xposed.hyperstylus.config.GestureAppConfig;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Blocks only native horizontal swipe reveal when the pen gesture began with the island hidden. */
public final class FocusNotificationGestureHook {
    private static final String WINDOW = "miui.systemui.dynamicisland.window.DynamicIslandWindowView";
    private static final String INTERACTOR = "miui.systemui.dynamicisland.touch.domain.interactor.DynamicIslandTouchInteractor";
    private static final String COORDINATOR = "miui.systemui.dynamicisland.event.DynamicIslandEventCoordinator";
    private static final String EVENT = "miui.systemui.dynamicisland.event.DynamicIslandEvent";
    private static final String CONTENT = "miui.systemui.dynamicisland.window.content.DynamicIslandContentView";
    private final FocusStylusModule module;
    private final FocusNotificationInputGate input = new FocusNotificationInputGate();
    private final Set<ClassLoader> installedPlugins = ConcurrentHashMap.newKeySet();

    private FocusNotificationGestureHook(FocusStylusModule module) { this.module = module; }
    public static void install(FocusStylusModule module, ClassLoader loader) {
        FocusNotificationGestureHook hook = new FocusNotificationGestureHook(module);
        hook.discoverWindow(); hook.pluginLoader(loader);
        if (module.session().host("focus.plugins") instanceof Iterable<?> saved)
            for (Object candidate : saved) if (candidate instanceof ClassLoader plugin) hook.plugin(plugin);
        module.session().onClose("focus notification plugin observers", () ->
                module.session().remember("focus.plugins", new ArrayList<>(hook.installedPlugins)));
    }
    private static boolean selected(MotionEvent event) {
        return GestureFocusBridge.enabled(event, GestureAppConfig.FOCUS_NOTIFICATION,
                ConfigBridge.current().ignoreStylusFocusNotification);
    }

    // Lazy discovery also works when SystemUI loaded the plugin before this generation attached.
    // Every original event still reaches the window and its children unchanged.
    @android.annotation.SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private void discoverWindow() {
        try {
            Method touch = ViewGroup.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
            module.managedHook(touch).intercept(chain -> {
                if (chain.getThisObject() instanceof ViewGroup group) {
                    for (Class<?> type = group.getClass(); type != null && type != View.class; type = type.getSuperclass()) {
                        if (type.getName().equals(WINDOW)) { plugin(type.getClassLoader()); break; }
                    }
                }
                return chain.proceed();
            });
        } catch (Throwable failure) { module.error("Observe island plugin window", failure); }
    }
    private void pluginLoader(ClassLoader loader) {
        Class<?> controller = Reflect.find(loader, "com.android.systemui.statusbar.notification.DynamicIslandPluginController");
        module.afterMethods(controller, "onPluginLoaded", (receiver, args, result) -> {
            if (args.length > 0 && args[0] != null) plugin(args[0].getClass().getClassLoader());
        });
    }
    private void plugin(ClassLoader loader) {
        if (!module.session().isActive() || loader == null || !installedPlugins.add(loader)) return;
        try {
            Class<?> touchType = Class.forName(INTERACTOR, false, loader);
            Class<?> coordinatorType = Class.forName(COORDINATOR, false, loader);
            Class<?> eventType = Class.forName(EVENT, false, loader);
            Class<?> contentType = Class.forName(CONTENT, false, loader);
            Class<?> swipeLeft = Class.forName(EVENT + "$SwipeLeft", false, loader);
            Class<?> swipeRight = Class.forName(EVENT + "$SwipeRight", false, loader);
            Method intercept = touchType.getDeclaredMethod("onInterceptTouchEvent", MotionEvent.class, String.class);
            Method touch = touchType.getDeclaredMethod("onTouchEvent", MotionEvent.class, String.class);
            Method swipe = coordinatorType.getDeclaredMethod("dispatchSwipe", float.class, float.class, boolean.class);
            Method event = coordinatorType.getDeclaredMethod("dispatchEvent", eventType, contentType);

            module.managedHook(swipe).intercept(chain -> {
                if (input.blocksSwipe(chain.getThisObject())) return null;
                return chain.proceed();
            });
            module.managedHook(event).intercept(chain -> {
                Object action = chain.getArg(0);
                if (input.blocksEvent(chain.getThisObject(), swipeLeft.isInstance(action) || swipeRight.isInstance(action))) return null;
                return chain.proceed();
            });
            for (Method method : new Method[]{intercept, touch}) {
                module.managedHook(method).intercept(chain -> {
                    Object coordinator = null;
                    boolean suppressReveal = false, horizontal = false;
                    try {
                        if (chain.getArg(0) instanceof MotionEvent motion && chain.getArg(1) instanceof String source) {
                            Object interactor = chain.getThisObject();
                            Object window = Reflect.field(interactor, "windowView");
                            coordinator = Reflect.call(Reflect.field(interactor, "eventCoordinator"), "get");
                            boolean hidden = isHidden(window);
                            boolean candidate = input.observe(interactor, source, motion.getActionMasked(), motion.getDownTime(),
                                    motion.getDeviceId(), MotionGestureFilter.stylus(motion), selected(motion), hidden);
                            // A notification becoming visible during the gesture must remain operable too.
                            suppressReveal = candidate && hidden;
                            horizontal = suppressReveal && Boolean.TRUE.equals(Reflect.call(Reflect.field(interactor, "direction"), "isHorizontal"));
                        }
                    } catch (Throwable failure) {
                        suppressReveal = false;
                        module.error("Inspect island swipe reveal input", failure);
                    }
                    // Native dispatch is synchronous here, after the plugin's coroutine delivery.
                    // Keep click, press, hover, outside touch, vertical gestures and terminal cleanup intact.
                    try (FocusNotificationInputGate.Scope ignored = input.enter(coordinator, suppressReveal, horizontal)) {
                        return chain.proceed();
                    }
                });
            }
            module.info("Hidden island stylus swipe reveal filter attached");
        } catch (Throwable failure) { module.error("Install island swipe reveal filter", failure); }
    }

    static boolean isHidden(Object window) throws Throwable {
        // A temporary notification is visible even when the normal big/small slots are empty.
        // Unknown ROM access fails open in the caller, never masquerading as a hidden island.
        return Reflect.call(window, "getCurrentBigIslandState") == null
                && Reflect.call(window, "getCurrentSmallIslandState") == null
                && Reflect.call(window, "getCurrentExpandedState") == null
                && Reflect.call(window, "getCurrentTempShowBigIslandState") == null;
    }
}
