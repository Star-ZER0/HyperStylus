package cc.star0.xposed.hyperstylus.runtime.gesture;

import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.config.ConfigBridge;
import cc.star0.xposed.hyperstylus.config.GestureAppConfig;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;

import android.view.MotionEvent;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shell caption input and fullscreen-corner predicates are filtered before task transitions. */
public final class StylusWindowGestureHook {
    private StylusWindowGestureHook() {}
    public static void install(FocusStylusModule module, ClassLoader loader) {
        Class<?> decoration = Reflect.find(loader,
                "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.MulWinSwitchDecorViewModel");
        Class<?> thermal = Reflect.find(loader, "com.android.wm.shell.multitasking.miuiinfinitymode.MiuiInfinityModeThermalArea");
        int caption = CaptionInputHook.install(module, loader) ? 1 : caption(module, decoration);
        int corner = corners(module, thermal);
        module.info("Window stylus observers: caption=" + caption + " corner=" + corner);
        SplitStylusGestureHook.install(module, loader);
    }
    private static int caption(FocusStylusModule module, Class<?> type) {
        if (type == null) return 0;
        int installed = 0;
        for (Method entry : type.getDeclaredMethods()) {
            if (!MotionGestureFilter.matches(entry, "handleReceivedMotionEvent", MotionEvent.class)
                    || entry.getReturnType() != void.class) continue;
            try {
                MotionGestureFilter.install(module, entry, () -> ConfigBridge.current().ignoreStylusCaption, "window-caption", null);
                installed++;
            } catch (Throwable error) { module.error("Install caption observer " + entry, error); }
        }
        return installed;
    }
    private static int corners(FocusStylusModule module, Class<?> type) {
        if (type == null) return 0;
        int installed = 0;
        for (Method predicate : type.getDeclaredMethods()) {
            if (!MotionGestureFilter.matches(predicate, "fullscreenHotSpotDetection", MotionEvent.class)
                    || predicate.getReturnType() != boolean.class || predicate.getParameterCount() != 1) continue;
            try {
                AtomicBoolean reported = new AtomicBoolean();
                module.managedHook(predicate).intercept(chain -> {
                    Object input = chain.getArg(0);
                    if (input instanceof MotionEvent event && MotionGestureFilter.stylus(event)
                            && GestureFocusBridge.enabled(event, GestureAppConfig.CORNER, ConfigBridge.current().ignoreStylusCornerFreeform)) {
                        if (reported.compareAndSet(false, true)) module.info("Suppressed pen fullscreen-corner hit");
                        return false;
                    }
                    return chain.proceed();
                });
                installed++;
            } catch (Throwable error) { module.error("Install fullscreen-corner predicate " + predicate, error); }
        }
        return installed;
    }
}
