package cc.star0.xposed.hyperstylus.runtime.keys;

import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.config.ActionConfig;
import cc.star0.xposed.hyperstylus.config.ConfigBridge;
import cc.star0.xposed.hyperstylus.config.EnhanceConfig;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;
import cc.star0.xposed.hyperstylus.module.ModuleSession;
import android.os.Bundle;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongArray;

/** Own the whole custom press so the ROM cannot run competing short/long timers. */
public final class StylusKeyHook {
    public static final int SYNTHETIC_SCAN_CODE = 0x5354;
    private record Binding(long downTime, String[] actions, int fullKeyCode, KeyEvent down, int button, long epoch) {}
    private static volatile StylusKeyHook active;
    private final FocusStylusModule module;
    private final ClassLoader loader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final KeyPressDispatcher dispatcher;
    private final FullKeyDispatcher fullDispatcher;
    private final Map<KeyPressDispatcher.Key, Binding> bindings = new ConcurrentHashMap<>();
    private final AtomicBoolean initializing = new AtomicBoolean();
    private volatile StylusActions actions;
    private volatile boolean closed;
    private volatile boolean releaseFailed;
    private final AtomicLongArray enabledEpochs = new AtomicLongArray(3);
    private int enabledMask = 7;
    private InputManager watchedInput;
    private InputManager.InputDeviceListener deviceListener;
    private record Cancelled(int device, int code, long down) {}
    private final java.util.Set<Cancelled> cancelled = ConcurrentHashMap.newKeySet();

    public StylusKeyHook(FocusStylusModule module, ClassLoader loader) {
        this.module = module;
        this.loader = loader;
        active = this;
        dispatcher = new KeyPressDispatcher(module, main);
        fullDispatcher = new FullKeyDispatcher(new MappedKeyInjector()::deliverFull, SystemClock::uptimeMillis,
                new FullKeyDispatcher.Scheduler() {
                    @Override public void post(Runnable task, long delay) {
                        main.postDelayed(() -> module.safely("Complete mapping repeat", task), task, delay);
                    }
                    @Override public void remove(Runnable task) { main.removeCallbacksAndMessages(task); }
                }, ViewConfiguration.getKeyRepeatTimeout(), ViewConfiguration.getKeyRepeatDelay(),
                failure -> module.error("Complete mapping repeat", failure));
        Object saved = module.session().host("input.cancelledPresses");
        if (saved instanceof Bundle state) {
            int[] devices = state.getIntArray("devices"), codes = state.getIntArray("codes"); long[] times = state.getLongArray("times");
            if (devices != null && codes != null && times != null && devices.length == codes.length && codes.length == times.length)
                for (int i = 0; i < devices.length; i++) cancelled.add(new Cancelled(devices[i], codes[i], times[i]));
        }
        module.session().onClose("input.keys", this::close);
    }
    public void install() {
        Class<?> manager = Reflect.find(loader, "com.miui.server.input.stylus.MiuiStylusShortcutManager");
        if (manager == null) { module.info("Native stylus shortcut service unavailable"); return; }
        for (Constructor<?> constructor : manager.getDeclaredConstructors()) {
            try { module.after(constructor, (receiver, args, result) -> start(receiver)); }
            catch (Throwable error) { module.error("Observe stylus service constructor", error); }
        }
        module.afterMethods(manager, "onSystemBooted", (receiver, args, result) -> start(receiver));
        try {
            Method entry = manager.getDeclaredMethod("shouldInterceptKey", KeyEvent.class);
            module.managedHook(entry).intercept(chain -> {
                KeyEvent event = (KeyEvent) chain.getArg(0);
                if (isCancelled(event)) return true;
                if (softwareGesture(event)) return false;
                int button = button(event);
                if (button < 0) return chain.proceed();
                Object service = chain.getThisObject();
                if (actions == null) {
                    start(service);
                    if (actions == null) return chain.proceed();
                }
                KeyPressDispatcher.Key key = key(event);
                Binding selected = bindings.get(key);
                if (event.getAction() == KeyEvent.ACTION_DOWN && selected != null
                        && selected.downTime == event.getDownTime()) return true;
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    if (selected != null) retireBinding(service, key, button, selected, event.getEventTime());
                    long epoch = enabledEpochs.get(button);
                    var config = ConfigBridge.current();
                    String pkg = ForegroundApp.packageName(service);
                    String[] pair = config.actionsFor(pkg, button);
                    int fullCode = config.fullKeyCodeFor(pkg, button);
                    if (fullCode == 0 && ActionConfig.DEFAULT.equals(pair[0]) && ActionConfig.DEFAULT.equals(pair[1])) {
                        bindings.remove(key);
                        return chain.proceed();
                    }
                    selected = new Binding(event.getDownTime(), pair, fullCode, new KeyEvent(event), button, epoch);
                    bindings.put(key, selected);
                }
                if (selected == null || selected.downTime != event.getDownTime()) return chain.proceed();
                dispatchBinding(service, key, button, selected, event);
                return true;
            });
            module.info("Physical button interceptor installed");
        } catch (Throwable error) { module.error("Physical button interceptor", error); }
        try {
            Method delay = manager.getDeclaredMethod("getDelayTime", KeyEvent.class);
            module.managedHook(delay).intercept(chain -> {
                KeyEvent event = (KeyEvent) chain.getArg(0);
                if (isCancelled(event)) return -1L;
                if (softwareGesture(event)) return 0L;
                if (button(event) >= 0) {
                    Binding pair = bindings.get(key(event));
                    if (pair != null && pair.downTime == event.getDownTime()) return -1L;
                }
                return chain.proceed();
            });
        } catch (Throwable error) { module.error("Optional stylus dispatch delay", error); }
        Object saved = module.session().host("input.shortcutManager");
        if (saved != null && manager.isInstance(saved)) start(saved);
    }
    private void dispatchBinding(Object service, KeyPressDispatcher.Key key, int button, Binding binding, KeyEvent event) {
        KeyEvent copy = new KeyEvent(event);
        main.post(() -> module.safely("Dispatch stylus press", () -> {
            // A queued DOWN must not revive a press already cancelled by a switch change.
            if (!copy.isCanceled() && (isCancelled(copy) || !ConfigBridge.current().keyEnabled(button)
                    || binding.epoch != enabledEpochs.get(button))) {
                cancelled.add(new Cancelled(key.device(), key.code(), binding.downTime));
                return;
            }
            try { executeBinding(service, key, button, binding, copy); }
            catch (Throwable failure) { throw new IllegalStateException("Keyboard mapping failed", failure); }
        }));
    }
    private void executeBinding(Object service, KeyPressDispatcher.Key key, int button, Binding binding, KeyEvent event) throws Throwable {
        if (binding.fullKeyCode > 0) fullDispatcher.accept(event, binding.fullKeyCode);
        else dispatcher.accept(actions, service, key, button, event, binding.actions);
    }
    private void configure(EnhanceConfig config) {
        int nextMask = 0;
        for (int button = 0; button < 3; button++) {
            if (config.keyEnabled(button)) nextMask |= 1 << button;
            else if ((enabledMask & (1 << button)) != 0) enabledEpochs.incrementAndGet(button);
        }
        enabledMask = nextMask;
        bindings.forEach((key, binding) -> {
            if (config.keyEnabled(binding.button) || !bindings.remove(key, binding)) return;
            cancelled.add(new Cancelled(key.device(), key.code(), binding.downTime));
            KeyEvent down = binding.down;
            KeyEvent up = new KeyEvent(binding.downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
                    down.getKeyCode(), 0, down.getMetaState(), down.getDeviceId(), down.getScanCode(),
                    down.getFlags() | KeyEvent.FLAG_CANCELED, down.getSource());
            try { executeBinding(module.session().host("input.shortcutManager"), key, binding.button, binding, up); }
            catch (Throwable failure) { releaseFailed = true; module.error("Release disabled stylus mapping", failure); }
        });
        module.info("Button configuration=" + config.revision);
    }
    /** Confirm generated keyboard edges and asynchronous native laser releases before shrinking system scope. */
    public static void prepareScopeRemoval(FocusStylusModule module, java.util.function.Consumer<Boolean> completion) {
        StylusKeyHook owner = active;
        if (owner == null) { completion.accept(true); return; }
        owner.main.post(new Runnable() {
            private int attempts;
            @Override public void run() {
                if (owner.closed || !module.session().isActive() || ConfigBridge.current().anyKeyEnabled()) {
                    completion.accept(false); return;
                }
                owner.configure(ConfigBridge.current());
                boolean released = !owner.releaseFailed && !owner.fullDispatcher.hasHeldKeys()
                        && !module.session().hasResource("input.nativeLaserPress");
                if (released) completion.accept(true);
                else if (++attempts < 20) owner.main.postDelayed(this, 100);
                else completion.accept(false);
            }
        });
    }
    private void retireBinding(Object service, KeyPressDispatcher.Key key, int button, Binding binding, long time) {
        KeyEvent down = binding.down;
        KeyEvent cancel = new KeyEvent(binding.downTime, time, KeyEvent.ACTION_UP, down.getKeyCode(), 0,
                down.getMetaState(), down.getDeviceId(), down.getScanCode(), down.getFlags() | KeyEvent.FLAG_CANCELED, down.getSource());
        dispatchBinding(service, key, button, binding, cancel);
    }
    private void start(Object manager) {
        if (closed || !module.session().isActive()) return;
        module.session().remember("input.shortcutManager", manager);
        ForegroundApp.registerShortcutManager(manager);
        if (actions != null || !initializing.compareAndSet(false, true)) return;
        Context context = systemContext(manager);
        if (context == null) { initializing.set(false); return; }
        Runnable start = () -> {
            if (closed || !module.session().isActive()) { initializing.set(false); return; }
            try {
                StylusActions executor = new StylusActions(module, loader, context);
                ConfigBridge.start(module, context, this::configure);
                watchDevices(context);
                actions = executor;
            } catch (Throwable error) {
                actions = null;
                module.error("Initialize physical stylus actions", error);
            } finally { initializing.set(false); }
        };
        if (Looper.myLooper() == main.getLooper()) start.run();
        else if (!main.post(start)) initializing.set(false);
    }
    private void watchDevices(Context context) {
        try {
            InputManager input = context.getSystemService(InputManager.class);
            if (input == null) return;
            watchedInput = input;
            deviceListener = new InputManager.InputDeviceListener() {
                @Override public void onInputDeviceAdded(int id) { if (!closed && module.session().isActive()) StylusHover.forget(id); }
                @Override public void onInputDeviceChanged(int id) { if (!closed && module.session().isActive()) StylusHover.forget(id); }
                @Override public void onInputDeviceRemoved(int id) {
                    if (closed || !module.session().isActive()) return;
                    cancelled.removeIf(press -> press.device() == id);
                    StylusHover.forget(id);
                    module.safely("Cancel removed stylus actions", () -> dispatcher.forget(id));
                    try { fullDispatcher.forget(id); }
                    catch (Throwable failure) { module.error("Release removed complete mappings", failure); }
                    bindings.keySet().removeIf(key -> key.device() == id);
                }
            };
            input.registerInputDeviceListener(deviceListener, main);
        } catch (RuntimeException error) { module.error("Optional stylus device listener", error); }
    }
    @android.annotation.SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private Context systemContext(Object manager) {
        try { if (Reflect.field(manager, "mContext") instanceof Context context) return context; }
        catch (ReflectiveOperationException ignored) { }
        try {
            Object thread = Reflect.call(Class.forName("android.app.ActivityThread"), "currentActivityThread");
            if (thread != null && Reflect.call(thread, "getSystemContext") instanceof Context context) return context;
        } catch (Throwable ignored) { }
        return null;
    }
    private static KeyPressDispatcher.Key key(KeyEvent event) {
        return new KeyPressDispatcher.Key(event.getDeviceId(), event.getKeyCode());
    }
    private static boolean softwareGesture(KeyEvent event) {
        return ProGestureAdapter.mappedAppGesture(event) || event != null
                && event.getKeyCode() == 194 && event.getScanCode() == SYNTHETIC_SCAN_CODE
                && event.isFromSource(InputDevice.SOURCE_STYLUS)
                && (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP);
    }
    public static int button(KeyEvent event) {
        if (event == null || event.getDeviceId() < 0 || event.getScanCode() == SYNTHETIC_SCAN_CODE) return -1;
        return buttonFor(event.getKeyCode(), StylusDevices.version(event.getDevice()));
    }
    public static int buttonFor(int code, int version) {
        if (version <= 0) return -1;
        if (code == 193 || code == 194 && version == 3) return 0;
        return switch (code) { case KeyEvent.KEYCODE_PAGE_UP -> 1; case KeyEvent.KEYCODE_PAGE_DOWN -> 2; default -> -1; };
    }
    private boolean isCancelled(KeyEvent event) {
        return event != null && cancelled.contains(new Cancelled(event.getDeviceId(), event.getKeyCode(), event.getDownTime()));
    }
    public void close() throws Throwable {
        closed = true;
        ModuleSession.onLooper(main, () -> {
            bindings.forEach((key, binding) -> cancelled.add(new Cancelled(key.device(), key.code(), binding.downTime())));
            Bundle state = new Bundle(); int[] devices = new int[cancelled.size()], codes = new int[cancelled.size()]; long[] times = new long[cancelled.size()];
            int i = 0; for (Cancelled press : cancelled) { devices[i] = press.device(); codes[i] = press.code(); times[i++] = press.down(); }
            state.putIntArray("devices", devices); state.putIntArray("codes", codes); state.putLongArray("times", times);
            module.session().remember("input.cancelledPresses", state);
            Throwable failure = null;
            try { dispatcher.close(); } catch (Throwable error) { failure = error; }
            try { fullDispatcher.close(); }
            catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            main.removeCallbacksAndMessages(null);
            if (watchedInput != null && deviceListener != null) {
                try { watchedInput.unregisterInputDeviceListener(deviceListener); }
                catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
            bindings.clear(); cancelled.clear(); deviceListener = null; watchedInput = null; actions = null;
            ForegroundApp.registerShortcutManager(null);
            if (active == this) active = null;
            if (failure != null) throw failure;
        });
    }
}
