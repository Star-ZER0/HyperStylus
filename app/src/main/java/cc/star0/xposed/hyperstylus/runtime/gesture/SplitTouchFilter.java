package cc.star0.xposed.hyperstylus.runtime.gesture;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/** Consumer streams retain DOWN policy and cancel native work before consuming a joining pen. */
final class SplitTouchFilter {
    interface Cancellation { void run() throws Throwable; }
    interface Canceller { Cancellation prepare(View owner, MotionEvent cancel) throws Throwable; }
    private static final class Stream {
        long downTime;
        int device;
        boolean enabled, suppressed;
        MotionEvent last, cancel;
        Cancellation pending;
        Stream(long downTime, int device, boolean enabled) {
            this.downTime = downTime; this.device = device; this.enabled = enabled;
        }
        void recycle() {
            if (last != null) { last.recycle(); last = null; }
            if (cancel != null) { cancel.recycle(); cancel = null; }
        }
    }
    private final Map<View, Stream> streams = new IdentityHashMap<>();
    private final Canceller canceller;
    private final boolean cancelUnobserved, retainSuppressedCancel;

    SplitTouchFilter(Canceller canceller) { this(canceller, true, false); }
    SplitTouchFilter(Canceller canceller, boolean cancelUnobserved, boolean retainSuppressedCancel) {
        this.canceller = canceller;
        this.cancelUnobserved = cancelUnobserved;
        this.retainSuppressedCancel = retainSuppressedCancel;
    }

    /** Returns true only when the caller must consume the event instead of dispatching it. */
    synchronized boolean consume(View owner, MotionEvent event, boolean enabled) throws Throwable {
        Stream stream = streams.get(owner);
        if (stream != null && stream.pending != null) finishCancellation(stream);
        // A late terminal from an older press must not cancel or replace the current native press.
        if (stream != null && event.getDownTime() < stream.downTime) return true;
        boolean same = stream != null && stream.downTime == event.getDownTime() && stream.device == event.getDeviceId();
        int action = event.getActionMasked();
        if (!same) {
            // A new pen DOWN must also stop an old, unterminated native press on this consumer.
            if (stream != null && stream.last != null && enabled && MotionGestureFilter.stylus(event)) {
                cancel(owner, stream, stream.last);
            }
            if (stream != null) stream.recycle();
            stream = new Stream(event.getDownTime(), event.getDeviceId(), enabled);
            streams.put(owner, stream);
            if (cancelUnobserved && action != MotionEvent.ACTION_DOWN && enabled && MotionGestureFilter.stylus(event)) {
                // Late injection may first observe MOVE/UP after the host already received DOWN.
                cancel(owner, stream, event);
            }
        }
        boolean terminal = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        if (!stream.suppressed && stream.enabled && MotionGestureFilter.stylus(event)) {
            stream.suppressed = true;
            if (stream.last != null) cancel(owner, stream, stream.last);
        }
        boolean consumed = stream.suppressed;
        if (terminal) {
            stream.recycle();
            // Native redirect can cancel the overlay while the physical press still has queued tails.
            if (!(retainSuppressedCancel && consumed && action == MotionEvent.ACTION_CANCEL)) streams.remove(owner);
        }
        else if (!consumed) {
            if (stream.last != null) stream.last.recycle();
            // Copy before the native divider changes event coordinates.
            stream.last = MotionEvent.obtain(event);
        }
        return consumed;
    }

    private void cancel(View owner, Stream stream, MotionEvent source) throws Throwable {
        stream.suppressed = true;
        stream.cancel = MotionEvent.obtain(source);
        stream.cancel.setAction(MotionEvent.ACTION_CANCEL);
        try { stream.pending = canceller.prepare(owner, stream.cancel); }
        catch (Throwable failure) {
            // Preserve the event so prepare can be retried without forwarding pen input.
            stream.pending = () -> canceller.prepare(owner, stream.cancel).run();
            throw failure;
        }
        finishCancellation(stream);
    }

    private static void finishCancellation(Stream stream) throws Throwable {
        stream.pending.run();
        stream.pending = null;
        stream.recycle();
    }

    /** Only the divider routing owner calls this after it has pilfered a native press. */
    synchronized void cancelOwned(View owner, MotionEvent lastNative) throws Throwable {
        if (lastNative == null) return;
        Stream stream = streams.get(owner);
        if (stream == null) {
            stream = new Stream(lastNative.getDownTime(), lastNative.getDeviceId(), true);
            streams.put(owner, stream);
        }
        if (stream.pending != null) finishCancellation(stream);
        else if (!stream.suppressed) cancel(owner, stream, lastNative);
    }

    /** A failed pilfer never reached the native consumer, so do not cancel an unrelated old press. */
    synchronized void denyUnstarted(View owner) {
        Stream stream = streams.get(owner);
        if (stream != null && stream.pending == null) { stream.suppressed = true; stream.recycle(); }
    }

    synchronized void forget(int device) throws Throwable {
        Throwable failed = null;
        for (var entry : new ArrayList<>(streams.entrySet())) {
            Stream stream = entry.getValue();
            if (stream.device != device) continue;
            try {
                if (stream.pending != null) finishCancellation(stream);
                streams.remove(entry.getKey()); stream.recycle();
            } catch (Throwable failure) {
                if (failed == null) failed = failure; else failed.addSuppressed(failure);
            }
        }
        if (failed != null) throw failed;
    }

    /** Native finger work stays native; its policy and blocked tails survive generation replacement. */
    synchronized Map<View, Bundle> snapshot() throws Throwable {
        Map<View, Bundle> saved = new IdentityHashMap<>();
        Throwable failed = null;
        for (var entry : streams.entrySet()) {
            Stream stream = entry.getValue();
            try { if (stream.pending != null) finishCancellation(stream); }
            catch (Throwable failure) {
                if (failed == null) failed = failure; else failed.addSuppressed(failure);
            }
        }
        if (failed != null) throw failed;
        for (var entry : streams.entrySet()) {
            Stream stream = entry.getValue();
            Bundle values = new Bundle();
            values.putLong("down", stream.downTime); values.putInt("device", stream.device);
            values.putBoolean("enabled", stream.enabled); values.putBoolean("suppressed", stream.suppressed);
            if (stream.last != null) values.putParcelable("last", MotionEvent.obtain(stream.last));
            saved.put(entry.getKey(), values);
            stream.recycle();
        }
        streams.clear();
        return saved;
    }

    synchronized void restore(Object saved) {
        if (!(saved instanceof Map<?, ?> entries)) return;
        entries.forEach((owner, state) -> {
            if (!(owner instanceof View view) || !(state instanceof Bundle values)) return;
            Stream stream = new Stream(values.getLong("down"), values.getInt("device"), values.getBoolean("enabled"));
            stream.suppressed = values.getBoolean("suppressed");
            MotionEvent last = values.getParcelable("last", MotionEvent.class);
            if (last != null) { stream.last = MotionEvent.obtain(last); last.recycle(); values.remove("last"); }
            streams.put(view, stream);
        });
    }
}
