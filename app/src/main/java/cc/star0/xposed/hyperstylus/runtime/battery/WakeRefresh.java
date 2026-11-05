package cc.star0.xposed.hyperstylus.runtime.battery;

import android.os.Handler;

/** Coalesces wake/unlock retries without holding a wake lock or scheduling screen-off polling. */
public final class WakeRefresh {
    private final Handler handler;
    private final Runnable refresh;
    private boolean closed;
    private long revision;

    public WakeRefresh(Handler handler, Runnable refresh) { this.handler = handler; this.refresh = refresh; }

    public void wake() {
        if (closed) return;
        cancel();
        long request = revision;
        refresh.run();
        if (closed || revision != request) return;
        handler.postDelayed(refresh, 3_000);
        handler.postDelayed(refresh, 15_000);
    }

    public void cancel() { revision++; handler.removeCallbacks(refresh); }
    public void close() { closed = true; cancel(); }
}
