package cc.star0.xposed.hyperstylus.runtime.gesture;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import cc.star0.xposed.hyperstylus.module.ModuleSession;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** This APK shares UP/CANCEL settling; cancel only its press and moving-line work. */
final class SidebarTouchCancellation {
    interface Dispatch { void run(View owner, MotionEvent event) throws Throwable; }
    private final Field listener, cancelled, queue, delayed, tracker, down;
    private final Method idle, movingCleanup, finish;
    SidebarTouchCancellation(Class<?> wrapper, Class<?> touch) throws ReflectiveOperationException {
        listener = field(wrapper, "u", touch);
        cancelled = field(touch, "n", boolean.class);
        queue = field(touch, "q", View.class);
        delayed = field(touch, "D", Runnable.class);
        tracker = field(touch, "g", VelocityTracker.class);
        down = field(touch, "h", long.class);
        idle = method(wrapper, "P"); movingCleanup = method(touch, "v"); finish = method(touch, "u");
    }
    private static Field field(Class<?> type, String name, Class<?> expected) throws ReflectiveOperationException {
        Field result = type.getDeclaredField(name);
        if (result.getType() != expected || Modifier.isStatic(result.getModifiers())) throw new NoSuchFieldException(name + " ABI");
        result.setAccessible(true); return result;
    }
    private static Method method(Class<?> type, String name) throws ReflectiveOperationException {
        Method result = type.getDeclaredMethod(name);
        if (result.getReturnType() != void.class || Modifier.isStatic(result.getModifiers()) || Modifier.isAbstract(result.getModifiers()))
            throw new NoSuchMethodException(name + " ABI");
        result.setAccessible(true); return result;
    }
    private static void invoke(Method method, Object owner) throws Throwable {
        try { method.invoke(owner); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    SplitTouchFilter.Cancellation prepare(Object wrapper, View owner, MotionEvent event, Dispatch original) throws Throwable {
        Object touch = listener.get(wrapper);
        if (touch == null) throw new IllegalStateException("Sidebar touch listener unavailable");
        View view = (View) queue.get(touch);
        Handler handler = view.getHandler();
        if (handler == null && Looper.myLooper() != null) handler = new Handler(Looper.myLooper());
        Handler target = handler;
        return () -> {
            if (target == null) throw new IllegalStateException("Sidebar input Looper unavailable");
            ModuleSession.onLooper(target, () -> {
                if (down.getLong(touch) != event.getDownTime()) return;
                cancelled.setBoolean(touch, true);
                ModuleSession.runAll(
                        () -> view.removeCallbacks((Runnable) delayed.get(touch)),
                        () -> original.run(owner, event),
                        () -> invoke(idle, wrapper),
                        () -> invoke(movingCleanup, touch),
                        () -> invoke(finish, touch),
                        () -> { if (tracker.get(touch) instanceof VelocityTracker value) value.clear(); });
            });
        };
    }
}
