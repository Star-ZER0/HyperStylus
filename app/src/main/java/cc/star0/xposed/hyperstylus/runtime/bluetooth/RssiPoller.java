package cc.star0.xposed.hyperstylus.runtime.bluetooth;

import cc.star0.xposed.hyperstylus.model.pen.SignalState;

import android.os.Handler;
import android.os.SystemClock;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/** Handler-owned sampler. At most one request is outstanding on a service-owned connection. */
public final class RssiPoller {
    public static final long INTERVAL_MS = 1_500;
    public static final long TIMEOUT_MS = 5_000;
    public interface Reader { public boolean read(Object connection); }
    private record Request(Object connection, long startedAt) { }
    private final Handler handler;
    private final Reader reader;
    private final IntConsumer result;
    private final Consumer<String> diagnostic;
    private final LongSupplier clock;
    private final Runnable timer = this::sample;
    private Object connection;
    private Request outstanding;
    private boolean enabled, closed;

    public RssiPoller(Handler handler, Reader reader, IntConsumer result) {
        this(handler, reader, result, message -> { });
    }
    public RssiPoller(Handler handler, Reader reader, IntConsumer result, Consumer<String> diagnostic) {
        this(handler, reader, result, diagnostic, SystemClock::uptimeMillis);
    }
    public RssiPoller(Handler handler, Reader reader, IntConsumer result, Consumer<String> diagnostic, LongSupplier clock) {
        this.handler = handler;
        this.reader = reader;
        this.result = result;
        this.diagnostic = diagnostic;
        this.clock = clock;
    }
    public void bind(Object next) {
        if (closed) return;
        if (next == connection) return;
        connection = next;
        outstanding = null;
        invalidate();
    }
    public void enable(boolean next) {
        if (closed) return;
        if (enabled == next) return;
        enabled = next;
        // Disabling does not cancel the Bluetooth controller's outstanding operation.
        invalidate();
    }
    public void received(Object owner, int value, int status) {
        if (closed) return;
        Request request = outstanding;
        if (request == null || owner != connection || owner != request.connection()) return;
        outstanding = null;
        if (!enabled) return;
        long age = clock.getAsLong() - request.startedAt();
        result.accept(age >= 0 && age < TIMEOUT_MS && status == 0 && SignalState.valid(value)
                ? value : SignalState.UNKNOWN);
        schedule(INTERVAL_MS);
    }
    private void invalidate() {
        handler.removeCallbacks(timer);
        result.accept(SignalState.UNKNOWN);
        schedule(0);
    }
    private void schedule(long delay) {
        handler.removeCallbacks(timer);
        if (!closed && enabled && connection != null) handler.postDelayed(timer, delay);
    }
    private void sample() {
        if (closed) return;
        if (!enabled || connection == null) return;
        if (outstanding != null) {
            long remaining = TIMEOUT_MS - (clock.getAsLong() - outstanding.startedAt());
            if (remaining > 0) schedule(remaining);
            else {
                result.accept(SignalState.UNKNOWN);
                diagnostic.accept("callback timed out; waiting for outstanding request");
            }
            return;
        }
        Request request = new Request(connection, clock.getAsLong());
        outstanding = request;
        boolean accepted;
        try { accepted = reader.read(request.connection()); }
        catch (RuntimeException unavailable) { accepted = false; }
        if (!accepted && outstanding == request) {
            outstanding = null;
            result.accept(SignalState.UNKNOWN);
        }
        schedule(outstanding == null ? INTERVAL_MS : TIMEOUT_MS);
    }
    public void close() {
        closed = true; enabled = false; handler.removeCallbacks(timer); outstanding = null; connection = null;
    }
}
