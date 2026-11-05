package cc.star0.xposed.hyperstylus.runtime.bluetooth;

import cc.star0.xposed.hyperstylus.common.Protocol;
import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.config.ConfigBridge;
import cc.star0.xposed.hyperstylus.model.pen.BatteryState;
import cc.star0.xposed.hyperstylus.model.pen.HallState;
import cc.star0.xposed.hyperstylus.model.pen.SignalState;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;
import cc.star0.xposed.hyperstylus.module.ModuleSession;
import android.os.Bundle;

import android.bluetooth.BluetoothGatt;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.UUID;

/** Handler-confined telemetry aggregation. BLE ownership stays in the ROM service. */
public final class BluetoothRelay {
    private final FocusStylusModule module;
    private final ClassLoader loader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BatteryState battery = new BatteryState(), charging = new BatteryState();
    private final SignalState signal = new SignalState();
    private final HallState hall = new HallState();
    private final PenBleObserver observer;
    private Context context;
    private boolean interactive = true, displayEnabled = true;
    private int linked = -1;
    private long linkTime, sessionTime;
    private String sessionId = "";
    private BluetoothGatt session;
    private BroadcastReceiver requests, screenEvents;
    private boolean requestsRegistered, screenRegistered;
    private volatile boolean closed;

    public BluetoothRelay(FocusStylusModule module, ClassLoader loader) {
        this.module = module; this.loader = loader;
        observer = new PenBleObserver(module, loader, main, new PenBleObserver.Listener() {
            @Override public void ownerChanged() { linked = -1; linkTime = sessionTime = 0; sessionId = ""; session = null; }
            @Override public void connection(int code) { forward(new Intent("gatt").putExtra("connect", code)); }
            @Override public void hall(int three, int four) {
                int attached = hall.update(three, four);
                if (attached >= 0) forward(new Intent("hall").putExtra("attached", attached));
            }
            @Override public void signal(int value) {
                module.safely("Relay pen signal", () -> {
                    long now = System.currentTimeMillis(); signal.accept(value, now, now);
                    if (context != null) Protocol.send(context, new Intent(Protocol.RELAY).putExtra("source", "rssi")
                            .putExtra("rssi", value).putExtra("time", now), Protocol.SYSTEM_UI);
                });
            }
        });
        module.session().onClose("bluetooth.relay", this::close);
    }
    public void installHooks() {
        module.afterMethods(Reflect.find(loader, "android.app.ContextImpl"), "sendBroadcast", (receiver, args, result) -> {
            if (args.length == 0 || !(args[0] instanceof Intent event) || !batteryAction(event.getAction())) return;
            Intent copy = new Intent(event);
            main.post(() -> module.safely("Relay stylus telemetry", () -> forward(copy)));
        });
        try { observer.install(); }
        catch (Throwable error) { module.error("Optional pen BLE observation", error); }
    }
    public void start(Context application) {
        main.post(() -> module.safely("Start stylus relay", () -> {
            if (closed || context != null) return;
            try { context = application.createPackageContext(Protocol.BLUETOOTH, 0); }
            catch (android.content.pm.PackageManager.NameNotFoundException error) {
                module.error("Bluetooth host context", error); return;
            }
            requests = new BroadcastReceiver() {
                @Override public void onReceive(Context sender, Intent event) {
                    if (closed || !module.session().isActive()) return;
                    String destination = snapshotTarget(sender, getSentFromUid(), event);
                    if (destination == null) return;
                    module.safely("Reply stylus snapshot", () -> {
                        observer.bootstrap();
                        Protocol.send(sender, new Intent(Protocol.RELAY).putExtra("source", "ready"), destination);
                        if (battery.lastEventAt > 0) Protocol.send(sender, snapshot(), destination);
                    });
                }
            };
            context.registerReceiver(requests, new IntentFilter(Protocol.REQUEST), null, main, Context.RECEIVER_EXPORTED);
            requestsRegistered = true;
            PowerManager power = context.getSystemService(PowerManager.class);
            interactive = power == null || power.isInteractive();
            IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON); screen.addAction(Intent.ACTION_SCREEN_OFF);
            screenEvents = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent event) {
                    if (closed || !module.session().isActive()) return;
                    interactive = Intent.ACTION_SCREEN_ON.equals(event.getAction()); reconcileSampling();
                }
            };
            context.registerReceiver(screenEvents, screen, null, main, Context.RECEIVER_NOT_EXPORTED);
            screenRegistered = true;
            module.session().remember("bluetooth.context", context);
            Object saved = module.session().host("bluetooth.state");
            if (saved instanceof Bundle state) restoreState(state);
            Object savedGatt = module.session().host("bluetooth.gatt");
            if (savedGatt instanceof BluetoothGatt gatt) session = gatt;
            ConfigBridge.start(module, context, config -> {
                displayEnabled = config.batteryEnabled && config.signalEnabled;
                reconcileSampling();
            });
            observer.start(context); reconcileSampling();
            module.info("Stylus telemetry relay started");
        }));
    }
    public static String snapshotTarget(Context context, int uid, Intent request) {
        if (uid == android.os.Process.SYSTEM_UID && "android".equals(request.getStringExtra("reply_to"))) return "android";
        return Protocol.from(context, uid, Protocol.SYSTEM_UI) ? Protocol.SYSTEM_UI : null;
    }
    private void reconcileSampling() {
        observer.enable(context != null && displayEnabled && interactive && !battery.attached
                && battery.visible(System.currentTimeMillis()));
    }
    private void forward(Intent incoming) {
        if (closed || !module.session().isActive() || context == null) return;
        long now = System.currentTimeMillis();
        String source = incoming.getAction();
        int level = incoming.getIntExtra("battery", -1), charge = incoming.getIntExtra("state", -1);
        int code = incoming.getIntExtra("connect", -1), attached = incoming.getIntExtra("attached", -1);
        if ("gatt".equals(source) && (code == 2 || BatteryState.isDisconnect(code))) {
            int next = code == 2 ? 1 : 0;
            BluetoothGatt current = observer.connection();
            if (next != linked || next == 1 && session != current) {
                sessionTime = next == 1 ? now : 0;
                sessionId = next == 1 ? UUID.randomUUID().toString() : "";
                session = next == 1 ? current : null;
                charging.level = -1; charging.updatedAt = charging.lastChargeAt = 0; charging.charging = false;
            }
            linked = next; linkTime = now;
        }
        boolean liveBattery = Protocol.BATTERY.equals(source);
        battery.accept(liveBattery, false, level, charge, code, now, now, now, attached, now, now);
        charging.accept(liveBattery || Protocol.SOC.equals(source) && charge == 4, false,
                level, batteryAction(source) ? charge : -1, -1, now, now, now, -1, 0, now);
        if (BatteryState.isDisconnect(code)) observer.invalidateConnection();
        reconcileSampling();
        Intent event = identity(new Intent(Protocol.RELAY).putExtra("source", source)).putExtra("battery", level)
                .putExtra("state", charge).putExtra("connect", code).putExtra("attached", attached)
                .putExtra("attachment_time", now).putExtra("connection_time", now).putExtra("charge_time", now)
                .putExtra("time", now).putExtra("event_time", now).putExtra("relay_elapsed_ms", SystemClock.elapsedRealtime());
        Protocol.send(context, event, "android");
        Protocol.send(context, event, Protocol.SYSTEM_UI);
    }
    private Intent identity(Intent event) {
        return event.putExtra("pen_connected", linked).putExtra("pen_connection_time", linkTime)
                .putExtra("pen_session_time", sessionTime).putExtra("pen_session_id", sessionId);
    }
    private Intent snapshot() {
        return identity(new Intent(Protocol.RELAY).putExtra("source", "snapshot"))
                .putExtra("relay_elapsed_ms", SystemClock.elapsedRealtime())
                .putExtra("battery", battery.level).putExtra("time", battery.updatedAt)
                .putExtra("state", battery.charging ? 4 : 2).putExtra("charge_time", battery.lastChargeAt)
                .putExtra("connect", battery.connectionCode).putExtra("connection_time", battery.lastConnectionAt)
                .putExtra("attached", battery.hallKnown ? (battery.attached ? 1 : 0) : -1)
                .putExtra("attachment_time", battery.lastAttachmentAt).putExtra("event_time", battery.lastEventAt)
                .putExtra("rssi", signal.rssi).putExtra("rssi_time", signal.time)
                .putExtra("charge_battery", charging.level).putExtra("charge_battery_time", charging.updatedAt)
                .putExtra("charge_state", charging.lastChargeAt > 0 ? (charging.charging ? 4 : 2) : -1)
                .putExtra("charge_state_time", charging.lastChargeAt);
    }
    private static boolean batteryAction(String action) {
        return Protocol.BATTERY.equals(action) || Protocol.SOC.equals(action) || Protocol.ATTACH.equals(action);
    }
    private Bundle saveState() {
        Bundle state = new Bundle(); state.putBundle("battery", battery.saveState()); state.putBundle("charging", charging.saveState());
        state.putBundle("signal", signal.saveState()); state.putBundle("hall", hall.saveState());
        state.putInt("linked", linked); state.putLong("linkTime", linkTime); state.putLong("sessionTime", sessionTime);
        state.putString("sessionId", sessionId); return state;
    }
    private void restoreState(Bundle state) {
        battery.restoreState(state.getBundle("battery")); charging.restoreState(state.getBundle("charging"));
        signal.restoreState(state.getBundle("signal")); hall.restoreState(state.getBundle("hall"));
        linked = state.getInt("linked", -1); linkTime = state.getLong("linkTime"); sessionTime = state.getLong("sessionTime");
        sessionId = state.getString("sessionId", "");
    }
    public void close() throws Throwable {
        closed = true;
        ModuleSession.onLooper(main, () -> {
            if (context != null) { module.session().remember("bluetooth.state", saveState()); module.session().remember("bluetooth.gatt", session); }
            main.removeCallbacksAndMessages(null);
            Throwable failure = null;
            if (context != null && requestsRegistered) {
                try { context.unregisterReceiver(requests); requestsRegistered = false; } catch (Throwable error) { failure = error; }
            }
            if (context != null && screenRegistered) {
                try { context.unregisterReceiver(screenEvents); screenRegistered = false; } catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
            requests = null; screenEvents = null; context = null; session = null;
            if (failure != null) throw failure;
        });
    }
}
