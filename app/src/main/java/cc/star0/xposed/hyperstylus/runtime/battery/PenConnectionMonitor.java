package cc.star0.xposed.hyperstylus.runtime.battery;

import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.runtime.keys.StylusDevices;
import cc.star0.xposed.hyperstylus.module.ModuleSession;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;
import java.util.HashMap;
import java.util.Map;

/** Merges target-pen HID/ACL events and input-device presence on the SystemUI handler. */
public final class PenConnectionMonitor implements InputManager.InputDeviceListener {
    public interface Listener { public void changed(boolean connected, String source); }
    private static final String HID = "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED";
    private final Context context;
    private final Handler handler;
    private final Listener listener;
    private Map<Integer, String> devices = Map.of();
    private InputManager input;
    private boolean started;
    private boolean receiverRegistered;
    private final BroadcastReceiver bluetooth = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent event) { bluetoothChanged(event); }
    };

    public PenConnectionMonitor(Context context, Handler handler, Listener listener) {
        this.context = context;
        this.handler = handler;
        this.listener = listener;
    }
    public void start() {
        if (started) return;
        started = true;
        input = context.getSystemService(InputManager.class);
        if (input != null) {
            try {
                input.registerInputDeviceListener(this, handler);
                scan(true);
            } catch (Throwable error) { Log.w("StylusBattery", "Pen input monitoring unavailable", error); }
        }
        IntentFilter filter = new IntentFilter(HID);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        try {
            context.registerReceiver(bluetooth, filter, null, handler, Context.RECEIVER_EXPORTED);
            receiverRegistered = true;
        }
        catch (RuntimeException error) { Log.w("StylusBattery", "Pen Bluetooth monitoring unavailable", error); }
    }
    public void close() throws Throwable {
        started = false;
        ModuleSession.runAll(
                () -> { if (input != null) input.unregisterInputDeviceListener(this); },
                () -> { if (receiverRegistered) { context.unregisterReceiver(bluetooth); receiverRegistered = false; } },
                () -> { devices = Map.of(); input = null; });
    }
    private void bluetoothChanged(Intent event) {
        if (!started) return;
        BluetoothDevice device = event.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        if (!matches(device)) return;
        String action = event.getAction();
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) listener.changed(true, "pen ACL connected");
        else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) listener.changed(false, "pen ACL disconnected");
        else if (HID.equals(action)) {
            int state = event.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
            if (state == BluetoothProfile.STATE_CONNECTED) listener.changed(true, "pen HID connected");
            else if (state == BluetoothProfile.STATE_DISCONNECTED || state == BluetoothProfile.STATE_DISCONNECTING)
                listener.changed(false, "pen HID disconnected");
        } else if (event.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1) == BluetoothDevice.BOND_NONE) {
            listener.changed(false, "pen unpaired");
        }
    }
    // Match only the current ROM stored address or a positively identified stylus input device.
    @android.annotation.SuppressLint("MissingPermission")
    private boolean matches(BluetoothDevice device) {
        if (device == null) return false;
        try {
            String address = device.getAddress();
            String configured = Settings.Secure.getString(context.getContentResolver(), "miui_bluetooth_store_pen_address");
            if (configured != null && !configured.isEmpty()) return configured.equalsIgnoreCase(address);
            for (String known : devices.values()) if (known != null && known.equalsIgnoreCase(address)) return true;
            return false;
        } catch (RuntimeException unavailable) { return false; }
    }
    private void scan(boolean initial) throws Throwable {
        Map<Integer, String> found = new HashMap<>();
        for (int id : input.getInputDeviceIds()) {
            InputDevice device = input.getInputDevice(id);
            if (StylusDevices.version(device) <= 0) continue;
            String address = (String) Reflect.call(device, "getBluetoothAddress");
            found.put(id, address);
        }
        boolean beforeAddress = devices.values().stream().anyMatch(address -> address != null);
        boolean afterAddress = found.values().stream().anyMatch(address -> address != null);
        boolean changed = initial || !devices.keySet().equals(found.keySet()) || beforeAddress != afterAddress;
        devices = found;
        if (changed) listener.changed(!found.isEmpty() && (!beforeAddress || afterAddress),
                initial ? "initial pen input scan" : "pen input changed");
    }
    private void inputChanged() {
        if (!started) return;
        try { scan(false); }
        catch (Throwable error) { Log.w("StylusBattery", "Pen input scan failed", error); }
    }
    public void refresh() { inputChanged(); }
    @Override public void onInputDeviceAdded(int id) { inputChanged(); }
    @Override public void onInputDeviceRemoved(int id) { inputChanged(); }
    @Override public void onInputDeviceChanged(int id) { inputChanged(); }
}
