package cc.star0.xposed.hyperstylus.runtime.gesture;

import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

/** Keep bit ownership and compensate uncertain native writes before reporting a completed update. */
final class CaptionWindowTransaction {
    interface Writer { void write(int flags) throws Throwable; }
    private final CaptionWindowFlags lease = new CaptionWindowFlags();
    private boolean failed;

    int effective(int nativeFlags, boolean enabled) { return lease.effective(nativeFlags, enabled); }
    boolean owns(int currentFlags) { return lease.owns(currentFlags); }

    void apply(int current, int original, boolean enabled, Writer writer) throws Throwable {
        boolean owned = lease.owns(current);
        if (current != original && !owned) {
            lease.restore(current, original); // Once another owner has changed flags, do not reacquire an old write by value.
            if (enabled && (current & FLAG_NOT_TOUCHABLE) == 0)
                throw new IllegalStateException("Caption input flags changed outside this owner");
            if (failed) {
                writer.write(current);
                failed = false;
            }
            return;
        }
        int next = enabled ? lease.effective(original, true) : lease.restore(current, original);
        if (next != current || failed) {
            try {
                writer.write(next);
                failed = false;
            } catch (Throwable failure) {
                failed = true;
                if (owned) lease.effective(current & ~FLAG_NOT_TOUCHABLE, true);
                throw failure;
            }
        }
    }
}
