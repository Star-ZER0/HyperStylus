package cc.star0.xposed.hyperstylus.runtime.keys;


import android.view.InputDevice;
import java.lang.reflect.Method;

/** Use the InputDevice identity API defined by the supplied HyperOS firmware. */
public final class StylusDevices {
    private static final Method NATIVE = nativeMethod();
    private StylusDevices() {}
    @android.annotation.SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static Method nativeMethod() {
        try {
            Method method = InputDevice.class.getDeclaredMethod("isXiaomiStylus");
            if (method.getReturnType() != int.class) return null;
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }
    public static int version(InputDevice device) {
        if (device == null) return 0;
        try {
            if (NATIVE != null && NATIVE.invoke(device) instanceof Integer version && version > 0) return version;
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return 0;
    }
}
