package cc.star0.xposed.hyperstylus.runtime.gesture;

import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** The verified ROM CANCEL also snaps a moving divider; stop movement before native cleanup. */
final class SplitDividerCancellation {
    interface Dispatch { void run(View owner, MotionEvent event) throws Throwable; }
    private final Field moving, tracker, layout, committed, resizing;
    private final Method updateBounds;

    SplitDividerCancellation(Class<?> divider, Class<?> splitLayout) throws ReflectiveOperationException {
        moving = field(divider, "mMoving", boolean.class);
        tracker = field(divider, "mVelocityTracker", VelocityTracker.class);
        layout = field(divider, "mSplitLayout", splitLayout);
        committed = field(splitLayout, "mDividerPosition", int.class);
        resizing = field(splitLayout, "mResizingInMinimizedMode", boolean.class);
        updateBounds = splitLayout.getDeclaredMethod("updateDividerBounds", int.class, boolean.class);
        if (updateBounds.getReturnType() != void.class || Modifier.isStatic(updateBounds.getModifiers())
                || Modifier.isAbstract(updateBounds.getModifiers())) throw new NoSuchMethodException("SplitLayout.updateDividerBounds ABI");
        updateBounds.setAccessible(true);
    }

    private static Field field(Class<?> type, String name, Class<?> expected) throws ReflectiveOperationException {
        Field result = type.getDeclaredField(name);
        if (result.getType() != expected || Modifier.isStatic(result.getModifiers()))
            throw new NoSuchFieldException(type.getName() + "." + name + " ABI");
        result.setAccessible(true);
        return result;
    }

    SplitTouchFilter.Cancellation prepare(View owner, MotionEvent cancel, Dispatch original) throws IllegalAccessException {
        Object target = layout.get(owner);
        boolean wasMoving = moving.getBoolean(owner);
        int position = target == null ? 0 : committed.getInt(target);
        return () -> {
            moving.setBoolean(owner, false);
            Throwable failed = null;
            try { original.run(owner, cancel); }
            catch (Throwable failure) { failed = failure; }
            try {
                if (wasMoving && target != null) invoke(updateBounds, target, position, false);
                // Native readiness guards can bypass cleanup. Retain the failed cancellation for retry.
                if (tracker.get(owner) != null || moving.getBoolean(owner)
                        || target != null && resizing.getBoolean(target))
                    throw new IllegalStateException("Native divider cancellation did not release its drag state");
            } catch (Throwable failure) {
                if (failed == null) failed = failure; else failed.addSuppressed(failure);
            }
            if (failed != null) throw failed;
        };
    }

    private static Object invoke(Method method, Object target, Object... args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
