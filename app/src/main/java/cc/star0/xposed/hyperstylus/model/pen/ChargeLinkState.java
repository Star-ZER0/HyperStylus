package cc.star0.xposed.hyperstylus.model.pen;

/** A charging sample belongs to one officially observed BLE session. */
public final class ChargeLinkState {
    /** Maximum sample age for confirming telemetry and admitting a new charging cycle. */
    public static final long FRESH_BATTERY_MS = 60_000;
    private record Session(long startedAt, String id) {}
    private Session active;
    private int connected = -1;
    private long reportedAt, endedAt;
    private boolean hasBattery;

    public boolean connection(int flag, long report, long session, String id, long now) {
        if (flag != 0 && flag != 1 || report <= 0 || report > now || report < reportedAt) return false;
        Session next = null;
        if (flag == 1) {
            if (session <= 0 || session > report || session < endedAt || id == null || id.isEmpty()
                    || active != null && session < active.startedAt) return false;
            next = new Session(session, id);
        }
        boolean changed = connected != flag || !java.util.Objects.equals(active, next);
        connected = flag;
        reportedAt = report;
        active = next;
        if (flag == 0) endedAt = report;
        if (changed) hasBattery = false;
        return changed;
    }
    public boolean sample(int level, long time, long now) {
        if (!current(time) || level < 0 || level > 100 || time > now
                || now - time > FRESH_BATTERY_MS) return false;
        hasBattery = true;
        return true;
    }
    public boolean current(long time) { return active != null && time >= active.startedAt; }
    public boolean session(long value, String id) { return active != null && active.startedAt == value && active.id.equals(id); }
    public boolean ready() { return active != null && hasBattery; }
    public int connected() { return connected; }
    public android.os.Bundle saveState() {
        android.os.Bundle state = new android.os.Bundle(); state.putInt("connected", connected);
        state.putLong("reportedAt", reportedAt); state.putLong("endedAt", endedAt); state.putBoolean("hasBattery", hasBattery);
        if (active != null) { state.putLong("sessionAt", active.startedAt()); state.putString("sessionId", active.id()); }
        return state;
    }
    public void restoreState(android.os.Bundle state) {
        if (state == null) return;
        connected = state.getInt("connected", -1); reportedAt = state.getLong("reportedAt"); endedAt = state.getLong("endedAt");
        String id = state.getString("sessionId"); long started = state.getLong("sessionAt");
        active = connected == 1 && id != null && !id.isEmpty() && started > 0 ? new Session(started, id) : null;
        hasBattery = active != null && state.getBoolean("hasBattery");
    }
}
