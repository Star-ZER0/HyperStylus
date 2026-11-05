package cc.star0.xposed.hyperstylus.runtime.gesture;

import java.util.HashMap;
import java.util.Map;

/** Publish routing only between native input streams, independently for each display. */
final class CaptionRoutingState {
    private static final class Display {
        Boolean enabled;
        final Map<Integer, Long> presses = new HashMap<>();
    }

    private final Map<Integer, Display> displays = new HashMap<>();

    synchronized boolean enabled(int display, boolean fallback) {
        Display state = displays.get(display);
        return state == null || state.enabled == null ? fallback : state.enabled;
    }

    synchronized boolean active(int display) {
        Display state = displays.get(display);
        return state != null && !state.presses.isEmpty();
    }

    synchronized void begin(int display, long downTime, int device) {
        Display state = displays.computeIfAbsent(display, ignored -> new Display());
        Long previous = state.presses.get(device);
        if (previous == null || downTime > previous) state.presses.put(device, downTime);
    }

    synchronized void end(int display, long downTime, int device) {
        Display state = displays.get(display);
        if (state == null) return;
        Long previous = state.presses.get(device);
        if (previous != null && previous == downTime) state.presses.remove(device);
        if (state.enabled == null && state.presses.isEmpty()) displays.remove(display);
    }

    synchronized void forgetDevice(int device) {
        displays.entrySet().removeIf(entry -> {
            Display state = entry.getValue();
            state.presses.remove(device);
            return state.enabled == null && state.presses.isEmpty();
        });
    }

    synchronized boolean update(int display, boolean desired, Runnable apply) {
        Display state = displays.get(display);
        if (state != null && (!state.presses.isEmpty()
                || state.enabled != null && state.enabled == desired)) return false;
        // Keep the previous published decision if native routing fails to apply.
        apply.run();
        displays.computeIfAbsent(display, ignored -> new Display()).enabled = desired;
        return true;
    }

    synchronized void clear() { displays.clear(); }
}
