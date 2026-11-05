package cc.star0.xposed.hyperstylus.runtime.gesture;

import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.config.ConfigBridge;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;

import android.view.MotionEvent;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Native back observers share one tested stream gate; foreground input remains untouched. */
public final class StylusBackGestureHook {
    public static final int PASS = 0, DROP = 1, CANCEL_AND_DROP = 2;
    public static final class Gate {
        private final PenMotionStream streams = new PenMotionStream();
        public int decide(Object owner, MotionEvent event, boolean enabled) {
            return streams.decide(owner, event.getActionMasked(), event.getDownTime(), event.getDeviceId(),
                    MotionGestureFilter.stylus(event), enabled);
        }
    }
    private StylusBackGestureHook() {}
    public static void install(FocusStylusModule module, ClassLoader loader) {
        int upstream = installObserver(module, Reflect.find(loader,
                "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler"), "onMotionEvent", "back", true);
        int feedback = upstream == 0 ? 0 : installObserver(module, Reflect.find(loader,
                "com.android.systemui.navigationbar.gestural.BackPanelController"), "onMotionEvent", "back", true);
        module.info("Back observers: upstream=" + upstream + " feedback=" + feedback);
        if (upstream == 0) module.info("Native back input observer unavailable on this ROM");
    }
    public static int installHome(FocusStylusModule module, ClassLoader loader) {
        int count = installObserver(module, Reflect.find(loader, "com.miui.home.recents.GesturesBackController"),
                "onPointerEvent", "home-back", false);
        module.info("Launcher back observers=" + count);
        return count;
    }
    private static int installObserver(FocusStylusModule module, Class<?> type, String entry, String feature, boolean exact) {
        if (type == null) return 0;
        int count = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (!MotionGestureFilter.matches(method, entry, MotionEvent.class)) continue;
            if (exact && (method.getReturnType() != void.class
                    || !Arrays.equals(method.getParameterTypes(), new Class<?>[]{MotionEvent.class}))) continue;
            try {
                MotionGestureFilter.install(module, method, () -> ConfigBridge.current().ignoreStylusBack, feature, null);
                count++;
            } catch (Throwable error) { module.error("Install back observer " + method, error); }
        }
        return count;
    }
}
