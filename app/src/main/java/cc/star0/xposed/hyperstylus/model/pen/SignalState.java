package cc.star0.xposed.hyperstylus.model.pen;

import cc.star0.xposed.hyperstylus.config.EnhanceConfig;

/** Signal observations expire independently of battery and connection state. */
public final class SignalState {
    public static final int UNKNOWN = Integer.MIN_VALUE;
    public static final long MAX_AGE_MS = 10_000;
    public int rssi = UNKNOWN;
    public long time;
    public static boolean valid(int value) { return value >= -127 && value <= 20; }
    public boolean accept(int value, long sampleTime, long now) {
        if (!fresh(sampleTime, now) || sampleTime < time) return false;
        time = sampleTime;
        rssi = valid(value) ? value : UNKNOWN;
        return true;
    }
    public void clear() { time = 0; rssi = UNKNOWN; }
    public void invalidate(long now) { time = Math.max(time, now); rssi = UNKNOWN; }
    public int bars(EnhanceConfig config, long now) {
        if (!valid(rssi) || !fresh(time, now)) return -1;
        if (rssi >= config.signalTwo) return 2;
        return rssi >= config.signalOne ? 1 : 0;
    }
    private static boolean fresh(long at, long now) { return at > 0 && now >= at && now - at < MAX_AGE_MS; }
    public android.os.Bundle saveState() {
        android.os.Bundle state = new android.os.Bundle(); state.putInt("rssi", rssi); state.putLong("time", time); return state;
    }
    public void restoreState(android.os.Bundle state) {
        if (state != null) { rssi = state.getInt("rssi", UNKNOWN); time = state.getLong("time"); }
    }
}
