package cc.star0.xposed.hyperstylus.runtime.gesture;

import android.os.Bundle;
import android.view.MotionEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** A spy divider observes pen input; only an isolated native press may take pointer ownership. */
final class SplitDividerRouting {
    static final int SPY = 4;
    interface Host {
        int features() throws Throwable;
        void features(int value) throws Throwable;
        void pilfer() throws Throwable;
        void cancel() throws Throwable;
    }
    private static final class Press {
        final long down;
        final boolean pen;
        boolean denied, captured;
        Press(MotionEvent event) { down = event.getDownTime(); pen = MotionGestureFilter.stylus(event); }
        Press(long down, boolean pen) { this.down = down; this.pen = pen; }
    }
    private final Host host;
    private final Map<Integer, Press> presses = new HashMap<>();
    private final Map<Integer, Long> cancelled = new HashMap<>();
    private boolean owned, desired, enabled, uncertain, configured;

    SplitDividerRouting(Host host) { this.host = host; }
    boolean enabled() { return enabled; }
    boolean enabled(boolean fallback) { return configured ? enabled : fallback; }
    boolean ownsSpy() { return owned; }

    void configure(boolean value) throws Throwable { desired = value; reconcile(); }

    private void reconcile() throws Throwable {
        if (!presses.isEmpty()) return;
        int current = host.features();
        if (!owned && (current & SPY) != 0)
            throw new IllegalStateException("Divider SPY belongs to another owner");
        int next = desired ? current | SPY : owned ? current & ~SPY : current;
        if (next != current || uncertain) {
            // Retain ownership on an uncertain write so retirement can compensate it.
            if (desired) owned = true;
            uncertain = true;
            host.features(next);
            uncertain = false;
        }
        owned = desired;
        enabled = desired;
        configured = true;
    }

    /** Keep the spy bit across native slippery/geometry relayouts, without owning other features. */
    int effective(int features) { return owned ? features | SPY : features; }
    void relayoutFailed() { if (owned) uncertain = true; }

    /** Called before the consumer filter. A denied finger must never start a native action. */
    boolean before(MotionEvent event) throws Throwable {
        for (var entry : presses.entrySet())
            if (entry.getValue().captured && entry.getValue().denied) revoke(entry.getKey(), entry.getValue());
        int device = event.getDeviceId();
        long down = event.getDownTime();
        Long old = cancelled.get(device);
        if (old != null && down <= old) return true;
        Press press = presses.get(device);
        if (press != null && down < press.down) return true;
        if (press == null || press.down != down) {
            if (press != null) revoke(device, press);
            press = new Press(event);
            presses.put(device, press);
            if (owned) {
                if (event.getActionMasked() != MotionEvent.ACTION_DOWN) press.denied = true;
                if (press.pen) {
                    // pilferPointers affects all devices on this channel. Never capture over a pen.
                    for (var entry : presses.entrySet())
                        if (entry.getValue() != press && entry.getValue().captured) revoke(entry.getKey(), entry.getValue());
                } else {
                    press.denied |= presses.values().stream().anyMatch(other -> other != presses.get(device)
                            && (other.pen || other.captured));
                }
            }
        }
        return press.denied;
    }

    void capture(MotionEvent event) throws Throwable {
        if (!owned) return;
        Press press = presses.get(event.getDeviceId());
        if (press == null || press.down != event.getDownTime() || press.denied) return;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !press.captured) {
            // A failure must suppress the rest of this press, not run a second action in the app.
            press.denied = true;
            host.pilfer();
            press.captured = true;
            press.denied = false;
        }
    }

    void after(MotionEvent event) throws Throwable {
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) return;
        Press press = presses.get(event.getDeviceId());
        if (press == null || press.down != event.getDownTime()) return;
        // The real consumer has already handled the terminal event, or it was denied throughout.
        press.captured = false;
        presses.remove(event.getDeviceId());
        reconcile();
    }

    private void revoke(int device, Press press) throws Throwable {
        press.denied = true;
        cancelled.merge(device, press.down, Math::max);
        if (press.captured) { host.cancel(); press.captured = false; }
    }

    void forget(int device) throws Throwable {
        Press press = presses.get(device);
        if (press != null) { revoke(device, press); presses.remove(device); }
        cancelled.remove(device);
        reconcile();
    }

    void close() throws Throwable {
        Throwable failed = null;
        for (var entry : new ArrayList<>(presses.entrySet())) {
            try {
                Press press = entry.getValue();
                if (owned || press.captured || press.denied) {
                    revoke(entry.getKey(), press);
                    presses.remove(entry.getKey());
                }
            }
            catch (Throwable failure) { if (failed == null) failed = failure; else failed.addSuppressed(failure); }
        }
        try { desired = false; reconcile(); }
        catch (Throwable failure) { if (failed == null) failed = failure; else failed.addSuppressed(failure); }
        if (failed != null) throw failed;
    }

    Bundle snapshot() {
        Bundle values = new Bundle();
        Bundle tails = new Bundle(), nativePresses = new Bundle();
        cancelled.forEach((device, down) -> tails.putLong(Integer.toString(device), down));
        presses.forEach((device, press) -> {
            Bundle item = new Bundle(); item.putLong("down", press.down); item.putBoolean("pen", press.pen);
            nativePresses.putBundle(Integer.toString(device), item);
        });
        values.putBundle("cancelled", tails); values.putBundle("native", nativePresses);
        return values;
    }
    void restore(Bundle values) {
        Bundle tails = values.getBundle("cancelled"), nativePresses = values.getBundle("native");
        if (tails != null) for (String key : tails.keySet()) cancelled.put(Integer.parseInt(key), tails.getLong(key));
        if (nativePresses != null) for (String key : nativePresses.keySet()) {
            Bundle item = nativePresses.getBundle(key);
            if (item != null) presses.put(Integer.parseInt(key), new Press(item.getLong("down"), item.getBoolean("pen")));
        }
        if (!presses.isEmpty()) { configured = true; enabled = false; }
    }
}
