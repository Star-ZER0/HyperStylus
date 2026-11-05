package cc.star0.xposed.hyperstylus.runtime.gesture;

import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

/** Owns only the NOT_TOUCHABLE bit added to a native caption window. */
final class CaptionWindowFlags {
    private boolean added;
    private int writtenFlags;

    int effective(int nativeFlags, boolean enabled) {
        added = enabled && (nativeFlags & FLAG_NOT_TOUCHABLE) == 0;
        writtenFlags = enabled ? nativeFlags | FLAG_NOT_TOUCHABLE : nativeFlags;
        return writtenFlags;
    }

    boolean owns(int currentFlags) { return added && currentFlags == writtenFlags; }

    int restore(int currentFlags, int nativeFlags) {
        int restored = owns(currentFlags)
                ? (currentFlags & ~FLAG_NOT_TOUCHABLE) | (nativeFlags & FLAG_NOT_TOUCHABLE)
                : currentFlags;
        added = false;
        return restored;
    }
}
