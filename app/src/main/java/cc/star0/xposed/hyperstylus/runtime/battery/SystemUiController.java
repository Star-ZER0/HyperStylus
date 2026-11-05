package cc.star0.xposed.hyperstylus.runtime.battery;

import cc.star0.xposed.hyperstylus.common.Protocol;
import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.config.ConfigBridge;
import cc.star0.xposed.hyperstylus.config.EnhanceConfig;
import cc.star0.xposed.hyperstylus.model.pen.BatteryState;
import cc.star0.xposed.hyperstylus.model.pen.SignalState;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;
import cc.star0.xposed.hyperstylus.module.ModuleSession;
import cc.star0.xposed.hyperstylus.storage.PenBatteryCache;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Bundle;
import android.os.Looper;
import java.lang.reflect.Constructor;
import java.util.Map;

/** SystemUI host coordinator; telemetry, model presentation and view painting stay separate. */
public final class SystemUiController {
    public static final String SLOT = "bluetooth_stylus_battery";
    private final FocusStylusModule module;
    private final ClassLoader loader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BatteryState battery = new BatteryState();
    private final SignalState signal = new SignalState();
    private final IconVisibility visibility;
    private final PenStatusPresenter presenter;
    private final PenStatusViews views;
    private final Runnable expireSignal = this::render;
    private final Runnable controllerLookup = this::findController;
    private Context context;
    private PenBatteryCache cache;
    private final WakeRefresh wakeRefresh;
    private int lookupAttempts;
    private boolean refreshPending;
    private PenConnectionMonitor connectionMonitor;
    private BroadcastReceiver telemetryReceiver, adapterReceiver, wakeReceiver;
    private boolean restored;

    public SystemUiController(FocusStylusModule module, ClassLoader loader) {
        this.module = module; this.loader = loader;
        wakeRefresh = new WakeRefresh(main, () -> module.safely("Refresh pen status after wake", this::refreshAfterWake));
        visibility = new IconVisibility(module, loader);
        presenter = new PenStatusPresenter(module, loader);
        views = new PenStatusViews(module, loader, visibility, this::appearance);
        if (module.session().host("systemui.telemetry") instanceof Bundle saved) {
            restoreTelemetry(saved); restored = true;
        }
        Object controller = module.session().host("systemui.controller");
        if (controller != null) presenter.capture(controller);
        if (module.session().host("systemui.views") instanceof Map<?, ?> saved) views.restore(saved);
        module.session().onClose("SystemUI stylus coordinator", () -> ModuleSession.onLooper(main, () -> {
            main.removeCallbacksAndMessages(null);
            wakeRefresh.close();
            ModuleSession.runAll(
                    () -> { if (connectionMonitor != null) connectionMonitor.close(); },
                    () -> { if (telemetryReceiver != null) context.unregisterReceiver(telemetryReceiver); },
                    () -> { if (adapterReceiver != null) context.unregisterReceiver(adapterReceiver); },
                    () -> { if (wakeReceiver != null) context.unregisterReceiver(wakeReceiver); },
                    () -> {
                        persist();
                        module.session().remember("systemui.telemetry", saveTelemetry());
                        module.session().remember("systemui.controller", presenter.controller());
                        module.session().remember("systemui.views", views.detach());
                        context = null; cache = null;
                    });
        }));
    }
    public void installHooks() {
        IconSlotOrder.install(module, loader);
        Class<?> controller = Reflect.find(loader, "com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl");
        if (controller != null) for (Constructor<?> constructor : controller.getDeclaredConstructors()) {
            try { module.after(constructor, (receiver, args, result) -> main.post(() -> capture(receiver))); }
            catch (Throwable error) { module.error("Observe icon controller", error); }
        }
        views.install(); visibility.install();
    }
    public void start(Context application) {
        main.post(() -> module.safely("Start SystemUI stylus coordinator", () -> {
            if (context != null) return;
            context = application;
            cache = new PenBatteryCache(context.getSharedPreferences("HyperStylusBattery", Context.MODE_PRIVATE));
            if (!restored) {
                battery.level = cache.displayLevel(System.currentTimeMillis()); battery.updatedAt = cache.sampledAt();
            } else cache.accept(battery.level, battery.updatedAt, System.currentTimeMillis());
            ConfigBridge.start(context, this::configure);
            module.safely("Register pen input monitoring", () -> {
                connectionMonitor = new PenConnectionMonitor(context, main, this::localConnection);
                connectionMonitor.start();
            });
            module.safely("Read Bluetooth startup state", this::initialBluetoothState);
            BroadcastReceiver telemetry = new BroadcastReceiver() {
                @Override public void onReceive(Context sender, Intent event) {
                    module.safely("Receive stylus telemetry", () -> {
                        if (Protocol.from(sender, getSentFromUid(), Protocol.BLUETOOTH)) accept(event);
                    });
                }
            };
            context.registerReceiver(telemetry, new IntentFilter(Protocol.RELAY), null, main, Context.RECEIVER_EXPORTED);
            telemetryReceiver = telemetry;
            BroadcastReceiver adapter = new BroadcastReceiver() {
                @Override public void onReceive(Context sender, Intent event) {
                    int state = event.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                    if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) bluetoothOff();
                    else if (state == BluetoothAdapter.STATE_ON) requestSnapshot();
                }
            };
            context.registerReceiver(adapter, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), null, main, Context.RECEIVER_EXPORTED);
            adapterReceiver = adapter;
            IntentFilter wake = new IntentFilter(Intent.ACTION_SCREEN_ON);
            wake.addAction(Intent.ACTION_USER_PRESENT); wake.addAction(Intent.ACTION_SCREEN_OFF);
            BroadcastReceiver resumed = new BroadcastReceiver() {
                @Override public void onReceive(Context sender, Intent event) {
                    module.safely("Pen status screen lifecycle", () -> {
                        if (Intent.ACTION_SCREEN_OFF.equals(event.getAction())) wakeRefresh.cancel();
                        else wakeRefresh.wake();
                    });
                }
            };
            context.registerReceiver(resumed, wake, null, main, Context.RECEIVER_NOT_EXPORTED);
            wakeReceiver = resumed;
            findController(); requestSnapshot();
            configure(ConfigBridge.current()); views.refresh();
            main.postDelayed(this::requestSnapshot, 3000); main.postDelayed(this::requestSnapshot, 15000); main.post(tick);
        }));
    }
    private void configure(EnhanceConfig config) {
        if (!config.batteryEnabled || !config.signalEnabled) {
            signal.invalidate(System.currentTimeMillis()); main.removeCallbacks(expireSignal);
        }
        visibility.setEnabled(config.batteryEnabled, presenter.controller());
        visibility.setMode(config.displayMode, presenter.controller());
        render();
    }
    @android.annotation.SuppressLint("MissingPermission")
    private void initialBluetoothState() {
        if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        if (manager != null && manager.getAdapter() != null && !manager.getAdapter().isEnabled()) bluetoothOff();
    }
    private void capture(Object controller) {
        if (!module.session().isActive()) return;
        if (presenter.capture(controller)) {
            main.removeCallbacks(controllerLookup);
            configure(ConfigBridge.current());
        }
    }
    private void findController() {
        main.removeCallbacks(controllerLookup);
        if (!module.session().isActive()) return;
        if (presenter.controller() != null) { render(); return; }
        try {
            Class<?> dependency = Reflect.find(loader, "com.android.systemui.Dependency");
            Class<?> contract = Reflect.find(loader, "com.android.systemui.statusbar.phone.ui.StatusBarIconController");
            if (dependency != null && contract != null) capture(Reflect.call(dependency, "get", contract));
        } catch (Throwable ignored) { }
        if (presenter.controller() == null && lookupAttempts++ < 120) main.postDelayed(controllerLookup, 1000);
        else if (presenter.controller() == null) module.info("SystemUI icon controller unavailable on this ROM");
    }
    private void accept(Intent event) {
        String source = event.getStringExtra("source");
        if ("ready".equals(source)) return;
        long now = System.currentTimeMillis();
        if ("rssi".equals(source)) {
            if (!ConfigBridge.current().signalEnabled || !ConfigBridge.current().batteryEnabled) return;
            int previous = signal.bars(ConfigBridge.current(), now);
            if (battery.visible(now) && !battery.attached) signal.accept(event.getIntExtra("rssi", SignalState.UNKNOWN), event.getLongExtra("time", 0), now);
            else signal.invalidate(now);
            expireSignal(now);
            if (previous != signal.bars(ConfigBridge.current(), now)) render();
            return;
        }
        long sampleTime = event.getLongExtra("time", 0), eventTime = event.getLongExtra("event_time", sampleTime);
        boolean displaySample = Protocol.BATTERY.equals(source) || "snapshot".equals(source)
                || Protocol.SOC.equals(source) && event.getIntExtra("state", -1) == 4;
        boolean cached = displaySample && cache != null
                && cache.accept(event.getIntExtra("battery", -1), sampleTime, now);
        if (cache != null) cache.persist();
        if (!battery.accept(Protocol.BATTERY.equals(source), "snapshot".equals(source), event.getIntExtra("battery", -1),
                event.getIntExtra("state", -1), event.getIntExtra("connect", -1), sampleTime, eventTime,
                event.getLongExtra("connection_time", eventTime), event.getIntExtra("attached", -1),
                event.getLongExtra("attachment_time", 0), event.getLongExtra("charge_time", eventTime), now)) {
            if (cached) render();
            return;
        }
        if (!battery.visible(now) || battery.attached) signal.invalidate(now);
        else if ("snapshot".equals(source) && ConfigBridge.current().signalEnabled && ConfigBridge.current().batteryEnabled)
            signal.accept(event.getIntExtra("rssi", SignalState.UNKNOWN), event.getLongExtra("rssi_time", 0), now);
        expireSignal(now); persist(); render();
    }
    private void expireSignal(long now) {
        main.removeCallbacks(expireSignal);
        if (SignalState.valid(signal.rssi) && signal.time > 0)
            main.postDelayed(expireSignal, Math.max(1, SignalState.MAX_AGE_MS - (now - signal.time)));
    }
    private void bluetoothOff() {
        module.safely("Bluetooth adapter turned off", () -> {
            long now = System.currentTimeMillis();
            battery.connection(0, now); battery.lastEventAt = now; signal.invalidate(now);
            persist(); render();
        });
    }
    private void localConnection(boolean connected, String source) {
        module.safely("Update local pen connection", () -> {
            long now = System.currentTimeMillis(); battery.connection(connected ? 2 : 6, now);
            if (!connected) signal.invalidate(now);
            persist(); render(); module.info(source + " connected=" + connected);
        });
    }
    private void persist() {
        if (cache != null) {
            cache.accept(battery.level, battery.updatedAt, System.currentTimeMillis());
            cache.persist();
        }
    }
    private PenStatusPresenter.Appearance appearance() {
        long now = System.currentTimeMillis();
        return new PenStatusPresenter.Appearance(cache == null ? -1 : cache.displayLevel(now),
                battery.charging, battery.attached, signal.bars(ConfigBridge.current(), now), ConfigBridge.current().signalEnabled);
    }
    private void render() {
        if (!module.session().isActive()) return;
        if (context == null || presenter.controller() == null) return;
        try {
            presenter.show(context, appearance(), visibility.enabled() && battery.visible(System.currentTimeMillis()));
            if (presenter.visible() && !refreshPending) {
                refreshPending = true;
                main.post(() -> { refreshPending = false; module.safely("Refresh stylus icon views", views::refresh); });
            }
        } catch (Throwable error) { module.error("Present stylus status icon", error); }
    }
    private void requestSnapshot() {
        if (context != null) module.safely("Request stylus snapshot", () -> Protocol.send(context, new Intent(Protocol.REQUEST), Protocol.BLUETOOTH));
    }
    private void refreshAfterWake() {
        if (!module.session().isActive() || context == null) return;
        if (connectionMonitor != null) connectionMonitor.refresh();
        if (presenter.controller() == null) lookupAttempts = 0;
        findController(); render(); views.refresh(); requestSnapshot();
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!module.session().isActive()) return;
            if (presenter.visible()) render(); main.postDelayed(this, 60_000);
        }
    };

    private Bundle saveTelemetry() {
        Bundle state = new Bundle();
        state.putBundle("battery", battery.saveState());
        state.putBundle("signal", signal.saveState());
        return state;
    }
    private void restoreTelemetry(Bundle state) {
        battery.restoreState(state.getBundle("battery"));
        signal.restoreState(state.getBundle("signal"));
    }
}
