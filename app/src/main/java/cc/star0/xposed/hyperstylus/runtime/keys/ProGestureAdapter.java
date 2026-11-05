package cc.star0.xposed.hyperstylus.runtime.keys;


import android.view.InputDevice;
import android.view.KeyEvent;

/** Identify module gesture packets; physical Pro routing stays in the ROM. */
public final class ProGestureAdapter {
    private static final int FIRST_APP_GESTURE = 195, LAST_APP_GESTURE = 197;
    public interface Dispatch<T> { public T run() throws Throwable; }

    public static boolean mappedAppGesture(KeyEvent event) {
        if (event == null || event.getScanCode() != StylusKeyHook.SYNTHETIC_SCAN_CODE
                || !event.isFromSource(InputDevice.SOURCE_STYLUS)) return false;
        int code = event.getKeyCode(), action = event.getAction();
        return code >= FIRST_APP_GESTURE && code <= LAST_APP_GESTURE
                && (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP);
    }

    private ProGestureAdapter() { }
}
