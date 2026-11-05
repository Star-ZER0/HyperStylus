package cc.star0.xposed.hyperstylus.runtime.bluetooth;

import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;
import cc.star0.xposed.hyperstylus.module.ModuleSession;

import android.Manifest;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

/** Observes the ROM-owned pen service and callbacks, without owning a BLE connection. */
public final class PenBleObserver {
    public interface Listener {
        public void ownerChanged();
        public void connection(int code);
        public void hall(int three, int four);
        public void signal(int value);
    }
    private final FocusStylusModule module;
    private final ClassLoader loader;
    private final Handler handler;
    private final Listener listener;
    private final RssiPoller poller;
    private final Set<Class<?>> discovered = new HashSet<>();
    private final Set<Object> gattCallbacks = weakSet(), hallCallbacks = weakSet();
    private WeakReference<Object> owner = new WeakReference<>(null);
    private Class<?> serviceType, gattType, hallType, ueventType;
    private Context context;
    private Field connectionField;
    private BluetoothGatt active;
    private String readStatus;
    private volatile boolean closed;

    public PenBleObserver(FocusStylusModule module, ClassLoader loader, Handler handler, Listener listener) {
        this.module = module; this.loader = loader; this.handler = handler; this.listener = listener;
        poller = new RssiPoller(handler, this::readSignal, listener::signal, this::diagnostic);
        module.session().onClose("bluetooth.observer", this::close);
    }
    private void post(String operation, Runnable task) {
        if (!closed && module.session().isActive()) handler.post(() -> {
            if (!closed) module.safely(operation, task);
        });
    }
    private static Set<Object> weakSet() {
        return Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    }
    public void install() {
        gattType = Reflect.find(loader, "com.android.bluetooth.ble.app.oobhelper.a");
        hallType = Reflect.find(loader, "com.android.bluetooth.ble.app.oobhelper.f");
        ueventType = Reflect.find(loader, "android.os.UEventObserver");
        serviceType = Reflect.find(loader, "com.android.bluetooth.ble.app.oobhelper.MiuiBleOobHelperService");
        observeRssi(BluetoothGattCallback.class);
        discoverType(gattType, true, false); discoverType(hallType, false, true);
        if (serviceType == null) return;
        discoverFields(null);
        for (Constructor<?> constructor : serviceType.getDeclaredConstructors()) {
            try { module.after(constructor, (receiver, args, result) -> capture(receiver)); }
            catch (Throwable error) { module.error("Observe pen service construction", error); }
        }
        module.afterMethods(serviceType, "onCreate", (receiver, args, result) -> capture(receiver));
        module.afterMethods(serviceType, "onDestroy", (receiver, args, result) -> post("Pen service teardown", () -> {
            if (owner.get() != receiver) return;
            owner.clear(); connectionField = null; active = null; poller.bind(null);
            listener.connection(6);
        }));
    }
    public void start(Context context) {
        if (closed) return;
        this.context = context;
        Object saved = module.session().host("bluetooth.service");
        if (saved != null && serviceType != null && serviceType.isInstance(saved)) {
            discoverFields(saved); bind(saved, true);
        } else bootstrap();
    }
    public void enable(boolean value) { poller.enable(value); }
    public void invalidateConnection() { active = null; poller.bind(null); }
    public BluetoothGatt connection() { return active != null ? active : official(); }
    private BluetoothGatt official() { return PenGattSource.read(owner.get(), connectionField); }
    private void capture(Object value) {
        discoverFields(value);
        post("Capture pen service", () -> bind(value));
    }
    private void bind(Object value) { bind(value, false); }
    private void bind(Object value, boolean restored) {
        if (closed) return;
        if (owner.get() != value) {
            owner = new WeakReference<>(value);
            connectionField = PenGattSource.resolve(value.getClass());
            active = null; poller.bind(null); if (!restored) listener.ownerChanged();
            module.session().remember("bluetooth.service", value);
            module.info("Captured pen service GATT: " + (connectionField == null ? "ambiguous" : connectionField.getName()));
        }
        bootstrap();
    }
    private void discoverFields(Object value) {
        if (serviceType == null) return;
        for (Class<?> type = serviceType; type != null && type != android.app.Service.class && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                boolean gatt = field.getName().equals("X0") && field.getType() == BluetoothGattCallback.class;
                boolean hall = field.getName().equals("r") && ueventType != null && field.getType() == ueventType;
                if (!gatt && !hall) continue;
                discoverType(field.getType(), gatt, hall);
                if (value == null || Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true); Object callback = field.get(value);
                    if (callback == null) continue;
                    if (gatt && callback instanceof BluetoothGattCallback) gattCallbacks.add(callback);
                    if (hall && ueventType != null && ueventType.isInstance(callback)) hallCallbacks.add(callback);
                    discoverType(callback.getClass(), gatt, hall);
                } catch (ReflectiveOperationException | RuntimeException ignored) { }
            }
        }
    }
    private synchronized void discoverType(Class<?> type, boolean gatt, boolean hall) {
        if (type == null || discovered.contains(type)) return;
        boolean isGatt = gatt && type != BluetoothGattCallback.class && BluetoothGattCallback.class.isAssignableFrom(type);
        boolean isHall = hall && ueventType != null && type != ueventType && ueventType.isAssignableFrom(type);
        if (!isGatt && !isHall) return;
        discovered.add(type);
        if (isHall) module.afterMethods(type, "onUEvent", (receiver, args, result) -> {
            if (args.length == 0 || args[0] == null || !ownsHall(receiver)) return;
            int three = sensor(Reflect.call(args[0], "get", "POWER_SUPPLY_PEN_HALL3"));
            int four = sensor(Reflect.call(args[0], "get", "POWER_SUPPLY_PEN_HALL4"));
            if (three >= 0 || four >= 0) post("Pen Hall callback", () -> listener.hall(three, four));
        });
        if (isGatt) {
            observeRssi(type);
            module.afterMethods(type, "onConnectionStateChange", (receiver, args, result) -> {
                if (!ownsGatt(receiver) || args.length < 3 || !(args[1] instanceof Integer status)
                        || !(args[2] instanceof Integer state)) return;
                int code = status != 0 || state == BluetoothProfile.STATE_DISCONNECTED ? 6
                        : state == BluetoothProfile.STATE_CONNECTED ? 2 : -1;
                if (code < 0) return;
                Object service = enclosingService(receiver);
                post("Pen connection callback", () -> connectionChanged(code, args[0], service));
            });
        }
        module.info("Observed pen callbacks: " + type.getName());
    }
    private void connectionChanged(int code, Object candidate, Object service) {
        if (owner.get() == null && service != null) { discoverFields(service); bind(service); }
        if (candidate instanceof BluetoothGatt gatt) {
            BluetoothGatt official = official();
            if (code == 6 && active != null && active != gatt || code == 2 && official != null && official != gatt) return;
            if (code == 2 && owner.get() != null && (official == null || official == gatt)) {
                active = gatt; poller.bind(gatt);
            } else if (code == 6) { active = null; poller.bind(null); }
        }
        listener.connection(code);
    }
    private boolean ownsGatt(Object value) { return gattCallbacks.contains(value) || gattType != null && gattType.isInstance(value); }
    private boolean ownsHall(Object value) { return hallCallbacks.contains(value) || hallType != null && hallType.isInstance(value); }
    private Object enclosingService(Object callback) {
        if (serviceType == null) return null;
        try {
            Object service = Reflect.field(callback, "a");
            return serviceType.isInstance(service) ? service : null;
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
    }
    private void observeRssi(Class<?> type) {
        module.afterMethods(type, "onReadRemoteRssi", (receiver, args, result) -> {
            if (!ownsGatt(receiver) || args.length != 3 || !(args[0] instanceof BluetoothGatt gatt)
                    || !(args[1] instanceof Integer value) || !(args[2] instanceof Integer status)) return;
            post("Pen RSSI callback", () -> { if (gatt == active && gatt == official()) poller.received(gatt, value, status); });
        });
    }
    private boolean permitted() {
        return context != null && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }
    public @android.annotation.SuppressLint("MissingPermission")
    void bootstrap() {
        if (active != null || !permitted()) return;
        BluetoothGatt gatt = official();
        if (gatt == null) {
            if (owner.get() != null && connectionField != null) listener.connection(6);
            return;
        }
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            if (manager == null) return;
            boolean connected = manager.getConnectionState(gatt.getDevice(), BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED;
            if (connected) { active = gatt; poller.bind(gatt); }
            listener.connection(connected ? 2 : 6);
        } catch (RuntimeException ignored) { }
    }
    @android.annotation.SuppressLint("MissingPermission")
    private boolean readSignal(Object value) {
        if (closed || !module.session().isActive()) return false;
        if (value != active || value != official()) { diagnostic("Official pen GATT replaced"); return false; }
        if (!permitted()) { diagnostic("BLE permission unavailable"); return false; }
        try {
            boolean accepted = ((BluetoothGatt) value).readRemoteRssi();
            diagnostic(accepted ? "RSSI request accepted" : "RSSI request deferred");
            return accepted;
        } catch (RuntimeException error) { diagnostic("RSSI failed: " + error.getClass().getSimpleName()); return false; }
    }
    private void diagnostic(String message) {
        if (message.equals(readStatus)) return;
        readStatus = message; module.info(message);
    }
    private static int sensor(Object value) {
        try { return value == null ? -1 : Integer.parseInt(value.toString().trim()); }
        catch (NumberFormatException ignored) { return -1; }
    }
    public void close() throws Throwable {
        closed = true;
        ModuleSession.onLooper(handler, () -> {
            poller.close(); handler.removeCallbacksAndMessages(null);
            owner.clear(); active = null; connectionField = null; context = null;
            gattCallbacks.clear(); hallCallbacks.clear(); discovered.clear();
        });
    }
}
