package cc.star0.xposed.hyperstylus.runtime.keys;


import android.content.Context;
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.os.PowerManager;
import android.view.InputEvent;
import android.view.KeyEvent;
import java.lang.reflect.Method;

/** Every submitted DOWN gets an attempted UP, including rejected and exceptional input injection. */
public final class StylusKeyInjector {
    private final Context context;
    private Method injection;
    private final MappedKeyInjector penInput = new MappedKeyInjector();
    public StylusKeyInjector(Context context) { this.context = context; }

    public static KeyEvent[] packet(int code, int device, int source) {
        long time = SystemClock.uptimeMillis();
        KeyEvent[] edges = new KeyEvent[2];
        for (int edge = 0; edge < edges.length; edge++) {
            edges[edge] = new KeyEvent(time, time, edge == 0 ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                    code, 0, 0, device, StylusKeyHook.SYNTHETIC_SCAN_CODE, 0, source);
        }
        return edges;
    }
    public void deliver(int code, int device, int source) throws Throwable { deliver(packet(code, device, source)); }

    public void deliverMappedPro(int code, int device) throws Throwable {
        PowerManager power = context.getSystemService(PowerManager.class);
        if (power != null && !power.isInteractive()) throw new IllegalStateException("Mapped pen actions require an interactive screen");
        penInput.deliver(packet(code, device, android.view.InputDevice.SOURCE_STYLUS | android.view.InputDevice.SOURCE_KEYBOARD));
    }

    // This module calls the host's input queue through a ROM framework API after libxposed injection.
    public @android.annotation.SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    void deliver(KeyEvent[] edges) throws Throwable {
        InputManager input = context.getSystemService(InputManager.class);
        if (input == null) throw new IllegalStateException("InputManager unavailable");
        if (injection == null) {
            injection = InputManager.class.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
            injection.setAccessible(true);
        }
        Throwable failure = null;
        boolean accepted = true;
        for (KeyEvent edge : edges) {
            try { accepted &= Boolean.TRUE.equals(injection.invoke(input, edge, 0)); }
            catch (Throwable error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
        if (!accepted) throw new IllegalStateException("Input queue rejected one or more key edges");
    }
}
