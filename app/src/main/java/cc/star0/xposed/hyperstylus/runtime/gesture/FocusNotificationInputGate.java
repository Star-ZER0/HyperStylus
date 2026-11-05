package cc.star0.xposed.hyperstylus.runtime.gesture;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Attributes native swipe dispatch without consuming input or changing click/hover handling. */
public final class FocusNotificationInputGate {
    private record Channel(String name, int device) { }
    private static final class Touch {
        final long down;
        final boolean enabled, hidden;
        boolean pen, ended;
        Touch(long down, boolean enabled, boolean hidden) {
            this.down = down; this.enabled = enabled; this.hidden = hidden;
        }
    }
    private record Dispatch(Object coordinator, boolean suppressReveal, boolean horizontal) { }
    private final Map<Object, Map<Channel, Touch>> touches = new WeakHashMap<>();
    private final ThreadLocal<Dispatch> dispatch = new ThreadLocal<>();

    public synchronized boolean observe(Object owner, String source, int action, long down, int device,
                                        boolean pen, boolean enabled, boolean hidden) {
        // Hover and orphaned events must never be mistaken for a new hidden-island gesture.
        if (action != 0 && action != 1 && action != 2 && action != 3 && action != 5 && action != 6) return false;
        Channel channel = new Channel(source, device);
        Map<Channel, Touch> channels = touches.get(owner);
        Touch stream = channels == null ? null : channels.get(channel);
        if (action == 0 && (stream == null || down > stream.down)) {
            if (channels == null) {
                channels = new HashMap<>(); touches.put(owner, channels);
            }
            stream = new Touch(down, enabled, hidden);
            channels.put(channel, stream);
        }
        if (stream == null || stream.down != down) return false;
        // Duplicate intercept/touch traversals and late DOWN must retain the original snapshot.
        if (!stream.ended) stream.pen |= pen;
        if (action == 1 || action == 3) stream.ended = true;
        return stream.enabled && stream.hidden && stream.pen;
    }

    public Scope enter(Object coordinator, boolean suppressReveal, boolean horizontal) {
        Dispatch previous = dispatch.get();
        dispatch.set(new Dispatch(coordinator, suppressReveal, horizontal));
        return new Scope(previous);
    }
    public boolean blocksSwipe(Object coordinator) {
        Dispatch current = dispatch.get();
        return current != null && current.coordinator == coordinator && current.suppressReveal && current.horizontal;
    }
    public boolean blocksEvent(Object coordinator, boolean horizontalSwipeEvent) {
        Dispatch current = dispatch.get();
        return horizontalSwipeEvent && current != null && current.coordinator == coordinator && current.suppressReveal;
    }
    public final class Scope implements AutoCloseable {
        private final Dispatch previous;
        private boolean closed;
        private Scope(Dispatch previous) { this.previous = previous; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) dispatch.remove(); else dispatch.set(previous);
        }
    }
}
