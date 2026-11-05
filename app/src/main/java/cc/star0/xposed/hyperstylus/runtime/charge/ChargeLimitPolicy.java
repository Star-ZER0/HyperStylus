package cc.star0.xposed.hyperstylus.runtime.charge;

import static cc.star0.xposed.hyperstylus.model.pen.ChargeLinkState.FRESH_BATTERY_MS;

import cc.star0.xposed.hyperstylus.config.EnhanceConfig;
import cc.star0.xposed.hyperstylus.model.pen.BatteryState;

/** Hysteresis grants a charging cycle only at/below the start threshold. */
public final class ChargeLimitPolicy {
    public enum Command { NONE, STOP, RESTORE }
    public enum OneShot { NONE, STOP, START, FULL, CANCEL_FULL }
    private enum Cycle { WAITING, ADMITTED }
    public boolean blocked, ownsStop;
    private Cycle cycle = Cycle.WAITING;
    private OneShot oneShot = OneShot.NONE;
    private OneShot beforeFull = OneShot.NONE;
    private Cycle cycleBeforeFull = Cycle.WAITING;

    public OneShot oneShot() { return oneShot; }
    public void request(OneShot value) {
        // CANCEL_FULL is an operation, never a persisted active mode.
        if (value == OneShot.CANCEL_FULL) {
            if (oneShot == OneShot.FULL) {
                oneShot = beforeFull; cycle = cycleBeforeFull; forgetFull();
            }
            return;
        }
        if (value == OneShot.FULL) {
            if (oneShot == OneShot.FULL) return;
            beforeFull = oneShot; cycleBeforeFull = cycle;
        } else forgetFull();
        oneShot = value; cycle = Cycle.WAITING;
    }
    private void forgetFull() { beforeFull = OneShot.NONE; cycleBeforeFull = Cycle.WAITING; }
    public int target(EnhanceConfig config) {
        return oneShot == OneShot.FULL || !config.chargeLimitEnabled ? 100 : config.chargeStop;
    }

    public Command evaluate(EnhanceConfig config, BatteryState pen, long now, boolean mayRestore) {
        int knownLevel = pen.displayLevel(now);
        int freshLevel = now - pen.updatedAt <= FRESH_BATTERY_MS ? knownLevel : -1;
        if (!config.chargeNotificationsEnabled() && oneShot != OneShot.NONE) resetCycle();
        if ((oneShot == OneShot.START || oneShot == OneShot.FULL) && knownLevel >= target(config))
            request(OneShot.STOP);
        if (oneShot != OneShot.NONE) {
            // START must survive the old non-charging sample while the HAL resumes supply.
            blocked = oneShot == OneShot.STOP;
        } else if (!config.chargeLimitEnabled) {
            cycle = Cycle.WAITING;
            blocked = false;
        } else {
            if (freshLevel >= 0 && freshLevel <= config.chargeStart) cycle = Cycle.ADMITTED;
            else if (knownLevel >= config.chargeStop
                    || pen.lastChargeAt > 0 && !pen.charging && pen.level > config.chargeStart) {
                cycle = Cycle.WAITING;
            }
            blocked = cycle == Cycle.WAITING;
        }
        if (ownsStop && !blocked && mayRestore) return Command.RESTORE;
        if (blocked && (pen.charging || !ownsStop && freshLevel >= 0)) return Command.STOP;
        return Command.NONE;
    }
    public void applied(Command command, boolean success) {
        if (!success) return;
        switch (command) { case STOP -> ownsStop = true; case RESTORE -> ownsStop = false; case NONE -> { } }
    }
    public void nativeStop() { ownsStop = false; }
    public void resetCycle() { cycle = Cycle.WAITING; oneShot = OneShot.NONE; forgetFull(); }
    public android.os.Bundle saveState() {
        android.os.Bundle state = new android.os.Bundle(); state.putBoolean("blocked", blocked);
        state.putBoolean("ownsStop", ownsStop); state.putBoolean("admitted", cycle == Cycle.ADMITTED);
        state.putString("oneShot", oneShot.name()); state.putString("beforeFull", beforeFull.name());
        state.putBoolean("beforeFullAdmitted", cycleBeforeFull == Cycle.ADMITTED); return state;
    }
    public void restoreState(android.os.Bundle state) {
        if (state == null) return; blocked = state.getBoolean("blocked"); ownsStop = state.getBoolean("ownsStop");
        cycle = state.getBoolean("admitted") ? Cycle.ADMITTED : Cycle.WAITING;
        oneShot = OneShot.valueOf(state.getString("oneShot", "NONE"));
        beforeFull = OneShot.valueOf(state.getString("beforeFull", "NONE"));
        cycleBeforeFull = state.getBoolean("beforeFullAdmitted") ? Cycle.ADMITTED : Cycle.WAITING;
    }
}
