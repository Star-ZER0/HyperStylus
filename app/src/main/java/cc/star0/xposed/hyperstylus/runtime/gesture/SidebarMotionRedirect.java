package cc.star0.xposed.hyperstylus.runtime.gesture;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import cc.star0.xposed.hyperstylus.common.Protocol;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Submit the ROM's own event-ID redirect only for an already eligible, verified sidebar window. */
final class SidebarMotionRedirect {
    interface Request { void run(int id) throws Throwable; }
    interface EventId { int get(MotionEvent event) throws Throwable; }
    private final EventId eventId;
    private final Field miuiFlags;
    private final Request request;
    SidebarMotionRedirect(ClassLoader loader, Context context) throws Throwable {
        if (!Protocol.SECURITY_CENTER.equals(context.getPackageName())
                || (context.getApplicationInfo().flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0)
            throw new IllegalStateException("Sidebar redirect requires the real system application");
        Method idMethod = MotionEvent.class.getMethod("getId");
        if (idMethod.getReturnType() != int.class || Modifier.isStatic(idMethod.getModifiers())) throw new NoSuchMethodException("MotionEvent.getId ABI");
        eventId = event -> (Integer) invoke(idMethod, event);
        miuiFlags = WindowManager.LayoutParams.class.getField("miuiFlags");
        if (miuiFlags.getType() != int.class || Modifier.isStatic(miuiFlags.getModifiers())) throw new NoSuchFieldException("miuiFlags ABI");
        Class<?> type = Class.forName("miui.hardware.input.MiuiInputManager", false, loader);
        Method get = type.getDeclaredMethod("getInstance"), redirect = type.getDeclaredMethod("requestRedirect", int.class);
        if (!Modifier.isStatic(get.getModifiers()) || get.getReturnType() != type || redirect.getReturnType() != void.class
                || Modifier.isStatic(redirect.getModifiers())) throw new NoSuchMethodException("MiuiInputManager redirect ABI");
        get.setAccessible(true); redirect.setAccessible(true);
        Object manager = invoke(get, null);
        Field service = type.getDeclaredField("mService"); service.setAccessible(true);
        if (manager == null || service.get(manager) == null) throw new IllegalStateException("Miui input redirect service unavailable");
        request = id -> invoke(redirect, manager, id);
    }
    SidebarMotionRedirect(EventId eventId, Field flags, Request request) { this.eventId = eventId; this.miuiFlags = flags; this.request = request; }
    boolean submit(View window, MotionEvent event) throws Throwable {
        if (!(window.getLayoutParams() instanceof WindowManager.LayoutParams params)
                || params.type != 2026 || (miuiFlags.getInt(params) & 32) == 0) return false;
        int id = eventId.get(event);
        if (id == -1) return false; // Other signed IDs retain their native source bits.
        request.run(id);
        return true; // Submission has no native delivery acknowledgement.
    }
    private static Object invoke(Method method, Object owner, Object... args) throws Throwable {
        try { return method.invoke(owner, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
