package cc.star0.xposed.hyperstylus.runtime.charge;

import cc.star0.xposed.hyperstylus.common.Reflect;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Serializes module and native HAL requests; native OFF remains authoritative. */
public final class WirelessChargeControl {
    public record NativeRequest(boolean captured, boolean enabled, boolean denied) {}
    public static final Object SKIPPED = new Object();
    public final Object lock = new Object();
    private final ThreadLocal<Boolean> moduleCommand = ThreadLocal.withInitial(() -> false);
    private final Map<Object, Boolean> nativeIntents = Collections.synchronizedMap(new WeakHashMap<>());
    private volatile Object hardware;
    private volatile boolean blocked, nativeStopped, shutdown;

    public boolean ownsInvocation() { return moduleCommand.get(); }
    public boolean available() { return hardware != null; }
    public boolean stoppedByNative() { return nativeStopped; }
    public boolean shuttingDown() { return shutdown; }
    public void shutdown() { shutdown = true; blocked = false; }
    public void veto(boolean value) { blocked = value && !shutdown; }
    public boolean capture(Object value) {
        synchronized (lock) {
            if (hardware == value) return false;
            hardware = value;
            nativeStopped = Boolean.FALSE.equals(nativeIntents.get(value));
            return true;
        }
    }
    public NativeRequest nativeRequest(Object receiver, boolean enabled) {
        nativeIntents.put(receiver, enabled);
        boolean captured = receiver == hardware;
        if (captured) nativeStopped = !enabled;
        return new NativeRequest(captured, enabled, captured && enabled && blocked);
    }
    public void restore(int flags) {
        synchronized (lock) {
            Boolean latest = nativeIntents.get(hardware);
            nativeStopped = latest == null ? (flags & 4) != 0 : !latest;
        }
    }
    public Object command(ChargeLimitPolicy.Command command) throws Throwable {
        synchronized (lock) {
            if (hardware == null || shutdown
                    || command == ChargeLimitPolicy.Command.RESTORE && nativeStopped) return SKIPPED;
            moduleCommand.set(true);
            try { return Reflect.call(hardware, "setWirelessChargingEnabled", command == ChargeLimitPolicy.Command.RESTORE); }
            finally { moduleCommand.remove(); }
        }
    }
    public int status() {
        try { return Reflect.call(hardware, "getWirelessChargingStatus") instanceof Integer value ? value : -1; }
        catch (Throwable ignored) { return -1; }
    }
    public android.os.Bundle saveState() {
        synchronized (lock) {
            android.os.Bundle state = new android.os.Bundle(); state.putBoolean("blocked", blocked);
            state.putBoolean("nativeStopped", nativeStopped); state.putBoolean("shutdown", shutdown); return state;
        }
    }
    public void restoreState(android.os.Bundle state) {
        if (state != null) synchronized (lock) {
            blocked = state.getBoolean("blocked"); nativeStopped = state.getBoolean("nativeStopped"); shutdown = state.getBoolean("shutdown");
        }
    }
    public void detach() {
        synchronized (lock) { hardware = null; nativeIntents.clear(); moduleCommand.remove(); }
    }
}
