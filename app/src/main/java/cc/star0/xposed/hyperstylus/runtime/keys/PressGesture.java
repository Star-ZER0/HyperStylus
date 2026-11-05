package cc.star0.xposed.hyperstylus.runtime.keys;


/** A press owns its timer and always releases exactly once, including callback failures. */
public final class PressGesture {
    public interface Scheduler { public void post(Runnable task, long delay); public void remove(Runnable task); }
    public interface Listener { public void execute(boolean longPress); public void release(); }
    private enum State { PRESSED, LONG, RELEASED }
    private final Scheduler scheduler;
    private final Listener listener;
    private final long downTime, threshold;
    private final Runnable timeout = () -> executeLong();
    private State state = State.PRESSED;
    private boolean scheduled;

    public PressGesture(Scheduler scheduler, Listener listener, long downTime, long threshold) {
        if (threshold < 0) throw new IllegalArgumentException("Negative press threshold");
        this.scheduler = scheduler; this.listener = listener;
        this.downTime = downTime; this.threshold = threshold;
    }
    public void start(long now) {
        if (scheduled || state != State.PRESSED) return;
        scheduled = true;
        scheduler.post(timeout, Math.max(0, threshold - Math.max(0, now - downTime)));
    }
    private void executeLong() {
        if (state != State.PRESSED) return;
        state = State.LONG;
        listener.execute(true);
    }
    public void up(long matchingDownTime, long now, boolean canceled) {
        if (state == State.RELEASED || matchingDownTime != downTime) return;
        scheduler.remove(timeout);
        try {
            if (!canceled && state == State.PRESSED) {
                if (now - downTime >= threshold) executeLong();
                else listener.execute(false);
            }
        } finally {
            state = State.RELEASED;
            listener.release();
        }
    }
    public void cancel() { up(downTime, downTime, true); }
}
