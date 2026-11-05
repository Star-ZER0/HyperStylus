package cc.star0.xposed.hyperstylus.runtime.keys;

import cc.star0.xposed.hyperstylus.config.ActionConfig;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;

import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.util.HashMap;
import java.util.Map;

/** Main-looper execution of immutable button bindings selected at physical DOWN. */
public final class KeyPressDispatcher {
    public record Key(int device, int code) {}
    private final FocusStylusModule module;
    private final Map<Key, PressGesture> presses = new HashMap<>();
    private final PressGesture.Scheduler scheduler;
    private final Handler handler;
    private boolean closed;
    public KeyPressDispatcher(FocusStylusModule module, Handler handler) {
        this.module = module; this.handler = handler;
        scheduler = new PressGesture.Scheduler() {
            @Override public void post(Runnable task, long delay) { if (closed || !module.session().isActive()) return; handler.postDelayed(() -> {
                if (closed || !module.session().isActive()) return;
                module.safely("Stylus long press", task);
            }, task, delay); }
            @Override public void remove(Runnable task) { handler.removeCallbacksAndMessages(task); }
        };
    }
    public void accept(StylusActions actions, Object manager, Key key, int button, KeyEvent event, String[] binding) {
        if (closed || !module.session().isActive()) return;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            PressGesture previous = presses.remove(key);
            if (previous != null) previous.cancel();
            PressGesture next = new PressGesture(scheduler, new PressGesture.Listener() {
                public boolean nativeHeld;
                public Runnable nativeRelease;
                @Override public void execute(boolean longPress) {
                    String action = binding[longPress ? 1 : 0];
                    module.info("Button " + button + (longPress ? " long: " : " short: ") + action);
                    if (button != 0 || !ActionConfig.DEFAULT.equals(action)) {
                        actions.execute(action, manager, event.getDeviceId(), button, longPress);
                        return;
                    }
                    if (longPress) {
                        nativeRelease = actions.nativeFocusLong(manager, event);
                        if (nativeRelease == null) throw new IllegalStateException("Native focus long press is unavailable");
                        return;
                    }
                    nativeHeld = actions.nativeFocus(manager, event, true);
                    if (!longPress && nativeHeld) {
                        if (!actions.nativeFocus(manager, event, false)) throw new IllegalStateException("Native focus UP failed");
                        nativeHeld = false;
                    }
                }
                @Override public void release() {
                    if (nativeRelease != null) { nativeRelease.run(); nativeRelease = null; }
                    if (nativeHeld) {
                        if (!actions.nativeFocus(manager, event, false)) throw new IllegalStateException("Native focus release failed");
                        nativeHeld = false;
                    }
                }
            }, event.getDownTime(), 380);
            presses.put(key, next);
            next.start(SystemClock.uptimeMillis());
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            PressGesture press = presses.remove(key);
            if (press != null) press.up(event.getDownTime(), event.getEventTime(), event.isCanceled());
        }
    }
    public void forget(int device) {
        presses.entrySet().removeIf(entry -> {
            if (entry.getKey().device != device) return false;
            entry.getValue().cancel();
            return true;
        });
    }
    public void close() throws Throwable {
        closed = true; Throwable failure = null;
        for (PressGesture press : new java.util.ArrayList<>(presses.values())) {
            try { press.cancel(); } catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        presses.clear(); handler.removeCallbacksAndMessages(null);
        if (failure != null) throw failure;
    }
}
