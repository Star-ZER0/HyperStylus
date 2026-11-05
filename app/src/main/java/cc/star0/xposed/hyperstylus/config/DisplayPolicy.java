package cc.star0.xposed.hyperstylus.config;


/** Stable saved values for the two SystemUI icon regions. */
public final class DisplayPolicy {
    public static final int STATUS_BAR = 0, CONTROL_CENTER = 1, BOTH = 2;
    private DisplayPolicy() {}
    public static boolean valid(int mode) { return mode == STATUS_BAR || mode == CONTROL_CENTER || mode == BOTH; }
    public static boolean allows(int mode, boolean controlCenter) {
        return switch (mode) { case STATUS_BAR -> !controlCenter; case CONTROL_CENTER -> controlCenter; case BOTH -> true; default -> false; };
    }
}
