package cc.star0.xposed.hyperstylus.runtime.gesture;


import java.util.Map;
import java.util.WeakHashMap;

/** Pin policy to DOWN; a pen joining an already forwarded finger stream requires one CANCEL. */
public final class PenMotionStream {
    private record Identity(long downTime, int device) {}
    private static final class Stream {
        public final Identity identity;
        public final boolean enabled;
        public boolean suppressed, forwarded;
        public Stream(Identity identity, boolean enabled) { this.identity = identity; this.enabled = enabled; }
    }
    private final Map<Object, Stream> active = new WeakHashMap<>();
    public synchronized int decide(Object owner, int action, long downTime, int device, boolean pen, boolean enabled) {
        Identity identity = new Identity(downTime, device);
        Stream stream = active.get(owner);
        if (action == 0) {
            stream = new Stream(identity, enabled);
            active.put(owner, stream);
        } else if (stream != null && !stream.identity.equals(identity)) {
            active.remove(owner);
            stream = null;
        }
        if (stream == null) return enabled && pen ? StylusBackGestureHook.DROP : StylusBackGestureHook.PASS;
        if (action == 3) {
            active.remove(owner);
            return stream.suppressed ? StylusBackGestureHook.DROP : StylusBackGestureHook.PASS;
        }
        int result = StylusBackGestureHook.PASS;
        if (stream.suppressed) result = StylusBackGestureHook.DROP;
        else if (stream.enabled && pen) {
            result = stream.forwarded ? StylusBackGestureHook.CANCEL_AND_DROP : StylusBackGestureHook.DROP;
            stream.suppressed = true;
        } else stream.forwarded = true;
        if (action == 1) active.remove(owner);
        return result;
    }
}
