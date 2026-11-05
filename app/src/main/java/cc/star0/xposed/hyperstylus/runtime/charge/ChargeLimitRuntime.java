package cc.star0.xposed.hyperstylus.runtime.charge;

import cc.star0.xposed.hyperstylus.common.Protocol;
import cc.star0.xposed.hyperstylus.config.ConfigBridge;
import cc.star0.xposed.hyperstylus.config.EnhanceConfig;
import cc.star0.xposed.hyperstylus.model.pen.BatteryState;
import cc.star0.xposed.hyperstylus.model.pen.ChargeLinkState;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;
import cc.star0.xposed.hyperstylus.module.ModuleSession;
import android.os.Bundle;
import cc.star0.xposed.hyperstylus.storage.ChargeLimitStore;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;

/** Worker-confined charging policy, telemetry validation, ownership recovery and retries. */
public final class ChargeLimitRuntime {
    private static final long RETRY_MS = 30_000;
    private final FocusStylusModule module;
    private final Handler worker;
    private final WirelessChargeControl control;
    private final ChargeLimitPolicy policy = new ChargeLimitPolicy();
    private final ChargeLinkState link = new ChargeLinkState();
    private final ChargeLimitStore storage = new ChargeLimitStore();
    private BatteryState pen = new BatteryState();
    private Context context;
    private EnhanceConfig config;
    private int tabletLevel = -1, tabletHealth = BatteryManager.BATTERY_HEALTH_UNKNOWN;
    private long retryAt;
    private boolean succeeded;
    private ChargeLimitPolicy.Command previous = ChargeLimitPolicy.Command.NONE;
    private BroadcastReceiver telemetry, tabletEvents;
    private ContentObserver settingObserver;
    private boolean telemetryRegistered, tabletRegistered, observerRegistered, released;
    private volatile boolean closed;
    private boolean restoredOwnedStop;
    private ChargeNotifications notifications;

    public ChargeLimitRuntime(FocusStylusModule module, Handler worker, WirelessChargeControl control) {
        this.module = module; this.worker = worker; this.control = control;
        module.session().onClose("charge.runtime", this::close);
    }
    public void attach(Context candidate) {
        if (closed || context != null) return;
        int saved = 0;
        try { saved = storage.read(); }
        catch (java.io.IOException error) { module.error("Read charge ownership; telemetry remains available", error); }
        policy.blocked = (saved & 1) != 0; policy.ownsStop = (saved & 2) != 0; control.restore(saved);
        context = candidate;
        telemetry = new BroadcastReceiver() {
            @Override public void onReceive(Context sender, Intent event) {
                if (!closed && Protocol.from(sender, getSentFromUid(), Protocol.BLUETOOTH))
                    module.safely("Accept charging telemetry", () -> accept(event));
            }
        };
        candidate.registerReceiver(telemetry, new IntentFilter(Protocol.RELAY), null, worker, Context.RECEIVER_EXPORTED);
        telemetryRegistered = true;
        IntentFilter events = new IntentFilter(Intent.ACTION_BATTERY_CHANGED); events.addAction(Intent.ACTION_SHUTDOWN);
        tabletEvents = new BroadcastReceiver() {
            @Override public void onReceive(Context sender, Intent event) {
                if (closed || !module.session().isActive()) return;
                if (Intent.ACTION_SHUTDOWN.equals(event.getAction())) { control.shutdown(); refreshNotifications(); }
                else { tablet(event); evaluate(); }
            }
        };
        Intent sticky = candidate.registerReceiver(tabletEvents, events, null, worker, Context.RECEIVER_NOT_EXPORTED);
        tabletRegistered = true;
        if (sticky != null) tablet(sticky);
        settingObserver = new ContentObserver(worker) {
            @Override public void onChange(boolean selfChange) { evaluate(); }
        };
        observe(candidate, "OPEN_WIRELESS_CHARGE", settingObserver, 0);
        observe(candidate, "wireless_reverse_charging", settingObserver, 0);
        context = candidate;
        module.session().remember("charge.context", candidate);
        Object savedState = module.session().host("charge.state");
        if (savedState instanceof Bundle state) restoreState(state);
        ConfigBridge.start(module, candidate, value -> {
            if (!closed && ConfigBridge.ready()) worker.post(() -> {
                if (closed || !module.session().isActive()) return;
                config = value; retryAt = 0; evaluate(); requestSnapshot();
            });
        });
        requestSnapshot(); worker.postDelayed(this::requestSnapshot, 3000); worker.postDelayed(this::requestSnapshot, 15000);
        worker.postDelayed(tick, RETRY_MS);
        module.info("Charging policy telemetry attached");
    }
    private void observe(Context context, String setting, ContentObserver observer, int attempt) {
        if (closed || !module.session().isActive()) return;
        try { context.getContentResolver().registerContentObserver(Settings.Global.getUriFor(setting), false, observer); observerRegistered = true; }
        catch (RuntimeException error) {
            if (attempt < 6) worker.postDelayed(() -> observe(context, setting, observer, attempt + 1), 3000);
            else module.error("Optional charge setting observer: " + setting, error);
        }
    }
    private void tablet(Intent event) {
        int level = event.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = event.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        tabletLevel = level >= 0 && scale > 0 ? Math.min(100, level * 100 / scale) : -1;
        tabletHealth = event.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN);
    }
    private void accept(Intent event) {
        String source = event.getStringExtra("source");
        boolean snapshot = "snapshot".equals(source), gatt = "gatt".equals(source);
        if (!snapshot && !gatt && !"hall".equals(source) && !Protocol.BATTERY.equals(source) && !Protocol.SOC.equals(source)
                && !Protocol.ATTACH.equals(source)) return;
        long now = System.currentTimeMillis();
        if (link.connection(event.getIntExtra("pen_connected", -1), event.getLongExtra("pen_connection_time", 0),
                event.getLongExtra("pen_session_time", 0), event.getStringExtra("pen_session_id"), now)) {
            pen = new BatteryState(); policy.resetCycle(); retryAt = 0;
            clearNotifications();
            requestSnapshot();
        }
        if (gatt) { evaluate(); return; }
        boolean matchingSession = !event.hasExtra("pen_session_time")
                || link.session(event.getLongExtra("pen_session_time", 0), event.getStringExtra("pen_session_id"));
        int level = event.getIntExtra("battery", -1), charge = event.getIntExtra("state", -1);
        long sampleTime = event.getLongExtra("time", 0);
        boolean live = Protocol.BATTERY.equals(source) || Protocol.SOC.equals(source) && charge == 4;
        if (snapshot && event.hasExtra("charge_battery") && event.hasExtra("charge_battery_time")) {
            level = event.getIntExtra("charge_battery", -1); sampleTime = event.getLongExtra("charge_battery_time", 0);
        }
        long eventTime = event.getLongExtra("event_time", sampleTime);
        long chargeTime = snapshot ? event.getLongExtra("charge_state_time", 0) : event.getLongExtra("charge_time", eventTime);
        if (snapshot) charge = event.getIntExtra("charge_state", -1);
        if (chargeTime <= 0 || chargeTime > now || now - chargeTime >= BatteryState.MAX_AGE_MS
                || !link.current(chargeTime) || !matchingSession) charge = -1;
        boolean batteryForSession = (live || snapshot) && link.current(sampleTime) && matchingSession;
        if (!batteryForSession) level = -1;
        boolean wasCharging = pen.charging; long lastChargingTime = pen.lastChargeAt;
        boolean wasAttached = pen.attached; long lastAttachmentTime = pen.lastAttachmentAt;
        long attachmentTime = event.getLongExtra("attachment_time", 0);
        int attachment = matchingSession && attachmentTime > 0 && attachmentTime <= now
                && now - attachmentTime < BatteryState.MAX_AGE_MS ? event.getIntExtra("attached", -1) : -1;
        if (!pen.accept(live, snapshot, level, charge, -1, sampleTime, eventTime, eventTime,
                attachment, attachmentTime, chargeTime, now)) return;
        if (pen.hallKnown && !pen.attached && (wasAttached || pen.lastAttachmentAt != lastAttachmentTime)) {
            policy.resetCycle(); retryAt = 0;
            clearNotifications();
        }
        if (batteryForSession) link.sample(level, sampleTime, now);
        if (succeeded && pen.charging && (!wasCharging || pen.lastChargeAt > lastChargingTime)) retryAt = 0;
        evaluate();
    }
    public void nativeCompleted(WirelessChargeControl.NativeRequest request, Object result) {
        if (closed || !request.captured()) return;
        worker.post(() -> {
            if (closed || !module.session().isActive()) return;
            if (result instanceof Integer code && code == 0) {
                if (request.denied()) { policy.ownsStop = true; succeeded = true; }
                else policy.nativeStop();
                persist();
            }
            module.info("Native wireless request enabled=" + request.enabled() + " veto=" + request.denied() + " result=" + result);
            evaluate();
        });
    }
    private boolean mayRestore() {
        if (control.shuttingDown() || control.stoppedByNative() || tabletLevel < 0
                || tabletHealth != BatteryManager.BATTERY_HEALTH_GOOD) return false;
        var resolver = context.getContentResolver();
        return tabletLevel >= requiredTabletLevel()
                && Settings.Global.getInt(resolver, "OPEN_WIRELESS_CHARGE", 1) != 0;
    }
    private int requiredTabletLevel() {
        return Settings.Global.getInt(context.getContentResolver(), "wireless_reverse_charging", 30);
    }
    public void evaluate() {
        if (closed || !module.session().isActive() || context == null || config == null || !control.available() || control.shuttingDown()) return;
        try {
            boolean wasBlocked = policy.blocked;
            ChargeLimitPolicy.Command command = policy.evaluate(config, pen, System.currentTimeMillis(), false);
            boolean ready = chargeReady();
            if (!ready) command = ChargeLimitPolicy.Command.NONE;
            if (restoredOwnedStop && policy.blocked && ready && !control.stoppedByNative()) {
                command = ChargeLimitPolicy.Command.STOP; retryAt = 0;
            }
            restoredOwnedStop = false;
            control.veto(ready && policy.blocked);
            if (policy.ownsStop && (!ready || !policy.blocked) && mayRestore()) command = ChargeLimitPolicy.Command.RESTORE;
            if (command == ChargeLimitPolicy.Command.NONE || command == ChargeLimitPolicy.Command.STOP && control.stoppedByNative()) return;
            if (command != previous || command == ChargeLimitPolicy.Command.STOP && !wasBlocked) retryAt = 0;
            if (SystemClock.uptimeMillis() < retryAt) return;
            previous = command; succeeded = false; retryAt = SystemClock.uptimeMillis() + RETRY_MS;
            long started = SystemClock.elapsedRealtime();
            Object result = control.command(command);
            if (result == WirelessChargeControl.SKIPPED) return;
            succeeded = result instanceof Integer code && code == 0;
            policy.applied(command, succeeded);
            module.info("Charge policy " + command + " result=" + result + " battery=" + pen.displayLevel(System.currentTimeMillis())
                    + " HALms=" + (SystemClock.elapsedRealtime() - started) + " TX=" + control.status());
            worker.postDelayed(() -> { if (!closed && module.session().isActive()) module.info("Charge readback TX=" + control.status()); }, 1500);
        } catch (Throwable error) { module.error("Reconcile stylus charging", error); }
        finally { persist(); refreshNotifications(); }
    }
    private boolean chargeReady() { return link.ready() && (!pen.hallKnown || pen.attached); }

    private void notificationAction(ChargeLimitPolicy.OneShot action) {
        if (closed || !module.session().isActive() || config == null) return;
        // MAIN may have published a newer switch state before its worker listener runs.
        if (ConfigBridge.ready() && ConfigBridge.current().revision != config.revision) {
            config = ConfigBridge.current(); logNotificationAction(action, "configuration-changed"); evaluate(); return;
        }
        ChargeNotifications.Kind kind = ChargeNotifications.kind(config, pen, link.connected() == 1, System.currentTimeMillis());
        ChargeLimitPolicy.OneShot primary = ChargeNotifications.primaryAction(config, kind,
                pen.displayLevel(System.currentTimeMillis()), policy.oneShot());
        boolean allowed = primary != ChargeLimitPolicy.OneShot.NONE && action == primary
                || kind == ChargeNotifications.Kind.CHARGING && action == ChargeLimitPolicy.OneShot.STOP;
        String decision = !allowed ? "state-changed" : control.shuttingDown() ? "shutdown"
                : !chargeReady() ? "session-not-ready"
                : (action == ChargeLimitPolicy.OneShot.START || action == ChargeLimitPolicy.OneShot.FULL)
                    && !notificationBatteryAvailable() ? "battery-unavailable" : "accepted";
        if ("accepted".equals(decision)) {
            policy.request(action); retryAt = 0;
        }
        logNotificationAction(action, decision);
        evaluate(); requestSnapshot();
    }

    private boolean notificationBatteryAvailable() {
        long now = System.currentTimeMillis();
        // Explicit operations can use this session's unchanged percentage. Only automatic
        // lower-threshold admission needs a sample newer than one minute; never re-date it.
        return link.current(pen.updatedAt) && pen.displayLevel(now) >= 0;
    }

    private void logNotificationAction(ChargeLimitPolicy.OneShot action, String decision) {
        long now = System.currentTimeMillis();
        module.info("Charge notification " + action + " decision=" + decision
                + " battery=" + pen.displayLevel(now) + " sampleAgeMs=" + (now - pen.updatedAt)
                + " ownsStop=" + policy.ownsStop + " nativeStopped=" + control.stoppedByNative()
                + " tablet=" + tabletLevel + " health=" + tabletHealth);
    }

    private void clearNotifications() {
        try { if (notifications != null) notifications.clear(); }
        catch (RuntimeException error) { module.error("Clear charging notification", error); }
    }

    private void refreshNotifications() {
        try {
            if (closed || !module.session().isActive() || context == null || config == null
                    || !config.chargeNotificationsEnabled() || !control.available() || control.shuttingDown()) {
                if (notifications != null) notifications.clear();
                return;
            }
            if (notifications == null) notifications = new ChargeNotifications(context,
                    context.createPackageContext(Protocol.MODULE, 0), worker,
                    work -> module.safely("Charging notification action", work), this::notificationAction);
            notifications.update(config, pen, link.connected() == 1, chargeReady() && notificationBatteryAvailable(), policy,
                    mayRestore(), tabletLevel, requiredTabletLevel(), System.currentTimeMillis());
        } catch (Throwable error) { module.error("Update charging notification", error); }
    }
    private void persist() {
        try { storage.write(policy.blocked, policy.ownsStop, control.stoppedByNative()); }
        catch (java.io.IOException | RuntimeException error) { module.error("Save charge ownership", error); }
    }
    private void requestSnapshot() {
        if (!closed && context != null) module.safely("Request charge snapshot", () -> Protocol.send(context,
                new Intent(Protocol.REQUEST).putExtra("reply_to", "android"), Protocol.BLUETOOTH));
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() { if (closed || !module.session().isActive()) return; evaluate(); if (!closed) worker.postDelayed(this, RETRY_MS); }
    };
    private Bundle saveState() {
        Bundle state = new Bundle(); state.putBundle("policy", policy.saveState()); state.putBundle("pen", pen.saveState());
        state.putBundle("link", link.saveState()); state.putBundle("control", control.saveState());
        state.putLong("retryAt", retryAt); state.putBoolean("succeeded", succeeded); state.putString("previous", previous.name());
        return state;
    }
    private void restoreState(Bundle state) {
        policy.restoreState(state.getBundle("policy")); pen.restoreState(state.getBundle("pen")); link.restoreState(state.getBundle("link"));
        control.restoreState(state.getBundle("control")); retryAt = state.getLong("retryAt"); succeeded = state.getBoolean("succeeded");
        String command = state.getString("previous", "NONE"); previous = ChargeLimitPolicy.Command.valueOf(command);
        restoredOwnedStop = policy.ownsStop;
    }
    public void close() throws Throwable {
        if (released) return;
        closed = true;
        ModuleSession.onLooper(worker, () -> {
            if (released) return;
            Throwable failure = null;
            if (context != null) {
                module.session().remember("charge.state", saveState());
                try { storage.write(policy.blocked, policy.ownsStop, control.stoppedByNative()); } catch (Throwable error) { failure = error; }
            }
            worker.removeCallbacksAndMessages(null);
            if (notifications != null) {
                try { notifications.close(); notifications = null; }
                catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
            if (context != null && telemetryRegistered) {
                try { context.unregisterReceiver(telemetry); telemetryRegistered = false; } catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
            if (context != null && tabletRegistered) {
                try { context.unregisterReceiver(tabletEvents); tabletRegistered = false; } catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
            if (context != null && observerRegistered && settingObserver != null) {
                try { context.getContentResolver().unregisterContentObserver(settingObserver); observerRegistered = false; }
                catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
            telemetry = null; tabletEvents = null; settingObserver = null; context = null; config = null;
            control.detach(); released = true;
            if (failure != null) throw failure;
        });
    }
}
