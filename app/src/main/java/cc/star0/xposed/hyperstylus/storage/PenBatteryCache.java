package cc.star0.xposed.hyperstylus.storage;

import android.content.SharedPreferences;

/** Last successful display sample. Its original timestamp never becomes charging telemetry. */
public final class PenBatteryCache {
    private final SharedPreferences preferences;
    private int level = -1;
    private long sampledAt;
    private boolean dirty;

    public PenBatteryCache(SharedPreferences preferences) {
        this.preferences = preferences;
        int stored = preferences.getInt("level", -1);
        long time = preferences.getLong("time", 0);
        if (valid(stored) && time > 0) { level = stored; sampledAt = time; }
    }

    public boolean accept(int value, long time, long now) {
        if (!valid(value) || time <= 0 || time > now || sampledAt <= now && time < sampledAt) return false;
        if (value == level && time == sampledAt) return false;
        level = value; sampledAt = time; dirty = true;
        return true;
    }

    public int displayLevel(long now) { return valid(level) && sampledAt > 0 && sampledAt <= now ? level : -1; }
    public long sampledAt() { return sampledAt; }

    public void persist() {
        if (!dirty || !valid(level) || sampledAt <= 0) return;
        preferences.edit().putInt("level", level).putLong("time", sampledAt).apply();
        dirty = false;
    }

    private static boolean valid(int level) { return level >= 0 && level <= 100; }
}
