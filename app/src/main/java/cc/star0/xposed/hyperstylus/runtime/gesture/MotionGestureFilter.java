package cc.star0.xposed.hyperstylus.runtime.gesture;

import cc.star0.xposed.hyperstylus.config.GestureAppConfig;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;

import android.view.MotionEvent;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Scoped attribution and stream filtering around observer copies of application input. */
public final class MotionGestureFilter {
    public interface InputLifecycle {
        boolean before(Object owner, MotionEvent event, Object inputContext) throws Throwable;
        void after(Object owner, MotionEvent event, Object inputContext, int decision, boolean proceeded) throws Throwable;
    }
    public record Origin(String feature, boolean blocked, int tool, int source) {}
    private static final ThreadLocal<Origin> ORIGIN = new ThreadLocal<>();
    private MotionGestureFilter() {}
    public static Origin origin() { return ORIGIN.get(); }
    public static boolean stylus(MotionEvent event) {
        for (int index = 0; index < event.getPointerCount(); index++) {
            int tool = event.getToolType(index);
            if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) return true;
        }
        return false;
    }
    public static boolean matches(Method method, String name, Class<?> input) {
        int modifiers = method.getModifiers();
        if (!name.equals(method.getName()) || Modifier.isStatic(modifiers) || Modifier.isAbstract(modifiers)
                || method.getParameterCount() == 0) return false;
        Class<?> first = method.getParameterTypes()[0];
        return input.isAssignableFrom(first) && first.isAssignableFrom(MotionEvent.class)
                && (method.getReturnType() == void.class || method.getReturnType() == boolean.class);
    }
    public static void install(FocusStylusModule module, Method method, BooleanSupplier enabled,
                        String feature, Consumer<Object> prepare) {
        installSelected(module, method, event -> GestureFocusBridge.enabled(event,
                GestureAppConfig.feature(feature), enabled.getAsBoolean()), feature, prepare, -1, null);
    }
    /** A window router can publish its effective policy after native window updates have completed. */
    public static void installSelected(FocusStylusModule module, Method method, Predicate<MotionEvent> selected,
                        String feature, Consumer<Object> prepare, int contextIndex, InputLifecycle lifecycle) {
        StylusBackGestureHook.Gate stream = new StylusBackGestureHook.Gate();
        AtomicBoolean reported = new AtomicBoolean();
        module.managedHook(method).intercept(chain -> {
            if (!(chain.getArg(0) instanceof MotionEvent event)) return chain.proceed();
            Object inputContext = lifecycle == null ? null : chain.getArg(contextIndex);
            boolean bypass = false;
            if (lifecycle != null) {
                try { bypass = lifecycle.before(chain.getThisObject(), event, inputContext); }
                catch (Throwable error) { module.error("Prepare input routing " + feature, error); }
            }
            if (prepare != null) {
                try { prepare.accept(chain.getThisObject()); }
                catch (Throwable error) { module.error("Prepare gesture " + feature, error); }
            }
            int decision = bypass ? StylusBackGestureHook.DROP : stream.decide(chain.getThisObject(), event, selected.test(event));
            Origin previous = ORIGIN.get();
            ORIGIN.set(previous != null && previous.blocked ? previous : new Origin(feature,
                    decision != StylusBackGestureHook.PASS,
                    event.getPointerCount() == 0 ? -1 : event.getToolType(0), event.getSource()));
            boolean proceeded = false;
            try {
                if (decision == StylusBackGestureHook.PASS) {
                    Object result = chain.proceed(); proceeded = true; return result;
                }
                if (reported.compareAndSet(false, true)) module.info("Blocked stylus observer stream: " + feature);
                if (decision == StylusBackGestureHook.CANCEL_AND_DROP) {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    Object[] arguments = chain.getArgs().toArray();
                    arguments[0] = cancel;
                    try { chain.proceed(arguments); proceeded = true; }
                    finally { cancel.recycle(); }
                }
                return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
            } finally {
                if (lifecycle != null) {
                    try { lifecycle.after(chain.getThisObject(), event, inputContext, decision, proceeded); }
                    catch (Throwable error) { module.error("Complete input routing " + feature, error); }
                }
                if (previous == null) ORIGIN.remove(); else ORIGIN.set(previous);
            }
        });
        module.info("Gesture observer: " + feature + " -> " + method);
    }
}
