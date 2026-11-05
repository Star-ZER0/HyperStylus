package cc.star0.xposed.hyperstylus.model.pen;


/** Independently ordered battery, link, charging and Hall channels; no inferred disconnection. */
public final class BatteryState {
    public static final long MAX_AGE_MS = 21_600_000;
    public int level = -1, connectionCode = -1;
    public boolean charging, connectionKnown, attached, attachmentKnown, hallKnown;
    public boolean disconnected = true;
    public long updatedAt, lastEventAt, lastConnectionAt, lastAttachmentAt, lastChargeAt;
    /** The last actual Hall transition, distinct from repeated observations of the same state. */
    public long attachmentChangedAt;

    public boolean accept(boolean batteryReport, boolean snapshot, int value, int charge, int connect,
                   long batteryTime, long eventTime, long now) {
        return accept(batteryReport, snapshot, value, charge, connect, batteryTime, eventTime,
                eventTime, -1, 0, eventTime, now);
    }
    public boolean accept(boolean batteryReport, boolean snapshot, int value, int charge, int connect,
                   long batteryTime, long eventTime, long connectionTime,
                   int attachment, long attachmentTime, long now) {
        return accept(batteryReport, snapshot, value, charge, connect, batteryTime, eventTime,
                connectionTime, attachment, attachmentTime, eventTime, now);
    }
    public boolean accept(boolean batteryReport, boolean snapshot, int value, int charge, int connect,
                   long batteryTime, long eventTime, long connectionTime,
                   int attachment, long attachmentTime, long chargeTime, long now) {
        if (!fresh(eventTime, now) || eventTime < lastEventAt) return false;
        lastEventAt = eventTime;
        boolean staleLink = snapshot && connectionKnown && disconnected && connectedCode(connect)
                && connectionTime <= lastConnectionAt;
        if (!staleLink) connection(connect, connectionTime);
        if ((batteryReport || snapshot) && validLevel(value) && fresh(batteryTime, now) && batteryTime >= updatedAt) {
            level = value;
            updatedAt = batteryTime;
            if (batteryReport && !connectionKnown) connection(2, eventTime);
        }
        if (!isDisconnect(connect) && (!snapshot || !disconnected || chargeTime > lastChargeAt)) {
            charge(charge, chargeTime);
        }
        attachment(attachment, attachmentTime);
        return true;
    }
    public boolean battery(int value, int state, long now) {
        return validLevel(value) && accept(true, false, value, state, -1, now, now, now);
    }
    public void charge(int state) { charge(state, Math.max(1, Math.max(lastEventAt, updatedAt))); }
    public void charge(int state, long time) {
        if (state != 2 && state != 4 || time <= 0 || time < lastChargeAt) return;
        charging = state == 4;
        lastChargeAt = time;
        if (!hallKnown) { attached = charging; attachmentKnown = charging; }
    }
    public void connection(int code, long time) {
        if (!connectedCode(code) && !isDisconnect(code) || time <= 0 || time < lastConnectionAt) return;
        connectionKnown = true;
        connectionCode = code;
        lastConnectionAt = time;
        disconnected = isDisconnect(code);
        if (disconnected) {
            charging = false;
            lastChargeAt = Math.max(lastChargeAt, time);
            if (!hallKnown) { attached = false; attachmentKnown = false; }
        }
    }
    public void attachment(int value, long time) {
        if (value != 0 && value != 1 || time <= 0 || time < lastAttachmentAt) return;
        if (!hallKnown || attached != (value == 1)) attachmentChangedAt = time;
        hallKnown = attachmentKnown = true;
        attached = value == 1;
        lastAttachmentAt = time;
        if (!attached) { charging = false; lastChargeAt = Math.max(lastChargeAt, time); }
    }
    public boolean visible(long now) { return connectionKnown && !disconnected; }
    public int displayLevel(long now) { return validLevel(level) && fresh(updatedAt, now) ? level : -1; }
    public static int bars(int level) { return level < 0 ? 0 : Math.max(1, Math.min(5, (level + 19) / 20)); }
    public static boolean isDisconnect(int code) {
        return switch (code) { case 0, 1, 3, 4, 6, 7 -> true; default -> false; };
    }
    private static boolean connectedCode(int code) { return code == 2 || code == 5; }
    private static boolean validLevel(int value) { return value >= 0 && value <= 100; }
    private static boolean fresh(long time, long now) { return time > 0 && now >= time && now - time < MAX_AGE_MS; }
    public android.os.Bundle saveState() {
        android.os.Bundle state = new android.os.Bundle();
        state.putInt("level", level); state.putInt("connectionCode", connectionCode);
        state.putBoolean("charging", charging); state.putBoolean("connectionKnown", connectionKnown);
        state.putBoolean("attached", attached); state.putBoolean("attachmentKnown", attachmentKnown);
        state.putBoolean("hallKnown", hallKnown); state.putBoolean("disconnected", disconnected);
        state.putLong("updatedAt", updatedAt); state.putLong("lastEventAt", lastEventAt);
        state.putLong("lastConnectionAt", lastConnectionAt); state.putLong("lastAttachmentAt", lastAttachmentAt);
        state.putLong("attachmentChangedAt", attachmentChangedAt);
        state.putLong("lastChargeAt", lastChargeAt); return state;
    }
    public void restoreState(android.os.Bundle state) {
        if (state == null) return;
        level = state.getInt("level", -1); connectionCode = state.getInt("connectionCode", -1);
        charging = state.getBoolean("charging"); connectionKnown = state.getBoolean("connectionKnown");
        attached = state.getBoolean("attached"); attachmentKnown = state.getBoolean("attachmentKnown");
        hallKnown = state.getBoolean("hallKnown"); disconnected = state.getBoolean("disconnected", true);
        updatedAt = state.getLong("updatedAt"); lastEventAt = state.getLong("lastEventAt");
        lastConnectionAt = state.getLong("lastConnectionAt"); lastAttachmentAt = state.getLong("lastAttachmentAt");
        attachmentChangedAt = state.getLong("attachmentChangedAt", lastAttachmentAt);
        lastChargeAt = state.getLong("lastChargeAt");
    }
}
