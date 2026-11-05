package cc.star0.xposed.hyperstylus.runtime.keys;

import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;
import cc.star0.xposed.hyperstylus.module.ModuleSession;
import android.os.Bundle;

import android.content.Context;
import android.graphics.Point;
import android.graphics.PointF;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Share a recent default-display tip position only with the single matching active stylus HID. */
public final class StylusHover {
    public static final long MAX_AGE_MS = 5_000;
    private static final Tracker TRACKER = new Tracker();
    private static final Observers OBSERVERS = new Observers();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile ModuleSession generation;
    private static volatile boolean stopped, released;
    private record DisplayFrame(int width, int height, int rotation) { }
    private record PenIdentity(int device, String descriptor) { }
    private record Position(PenIdentity pen, int pointerDevice, DisplayFrame frame, float x, float y, long time) {
        public boolean fresh(long now) { return time >= 0 && time <= now && now - time <= MAX_AGE_MS; }
    }

    public static void configure(Context context) { TRACKER.configure(context); }
    public static void forget(int device) { TRACKER.position.set(null); }

    public static void install(FocusStylusModule module, ClassLoader loader) {
        generation = module.session(); stopped = false; released = false;
        module.session().onClose("input.hover", StylusHover::close);
        Object savedContext = module.session().host("input.hoverContext");
        if (savedContext instanceof Context context) TRACKER.configure(context);
        Object savedPosition = module.session().host("input.hoverPosition");
        if (savedPosition instanceof Bundle state) TRACKER.restoreState(state);
        Class<?> touchFilm = Reflect.find(loader, "com.miui.server.input.stylus.MiuiStylusTouchFilmManager");
        if (touchFilm == null) return;
        FocusStylusModule.After attach = (receiver, arguments, result) -> MAIN.post(() -> {
            if (!stopped) module.safely("Attach hover observer", () -> ensureMonitoring(module, receiver));
        });
        for (Constructor<?> constructor : touchFilm.getDeclaredConstructors()) {
            try { module.after(constructor, attach); }
            catch (Throwable unsupported) { module.info("Hover constructor adapter unavailable; waiting for native connection"); }
        }
        module.afterMethods(touchFilm, "onStylusConnectionStateChanged", attach);
        Object saved = module.session().host("input.hoverOwners");
        if (saved instanceof Iterable<?> owners) for (Object owner : owners) MAIN.post(() -> {
            if (!stopped) module.safely("Restore hover observer", () -> ensureMonitoring(module, owner));
        });
    }

    public static void ensureMonitoring(FocusStylusModule module, Object film) {
        if (stopped || !module.session().isActive()) return;
        try {
            TRACKER.context(film);
            Object monitor = Reflect.field(film, "mMiuiGestureMonitor");
            if (monitor != null) OBSERVERS.attach(module, film, monitor);
        } catch (Throwable unavailable) { module.error("Attach independent stylus pointer observer", unavailable); }
    }

    /** A mapped pinch copies one point; it never rewrites the ROM manager or consumes a key pair. */
    public static PointF pointFor(Object film, int deviceId) {
        Position recent = TRACKER.position.get();
        if (recent != null && deviceId >= 0) {
            if (TRACKER.matches(recent, film, deviceId)) return new PointF(recent.x, recent.y);
            TRACKER.position.compareAndSet(recent, null);
        }
        try {
            // The OTA updates this point only while a real TouchFilm pen is connected. A
            // disconnect does not clear the field, so connection state must also be checked.
            if (!Boolean.TRUE.equals(Reflect.field(film, "mIsTouchFilmStylusConnected"))) return unknownPoint();
            Object lock = Reflect.field(film, "mUpdatePointLock");
            if (lock == null) return unknownPoint();
            synchronized (lock) {
                Object value = Reflect.field(film, "mStylusHoverPoint");
                if (value instanceof PointF point && validPoint(point)) return new PointF(point);
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) { }
        return unknownPoint();
    }

    private static boolean validPoint(PointF point) {
        return Float.isFinite(point.x) && Float.isFinite(point.y) && point.x >= 0 && point.y >= 0;
    }
    private static PointF unknownPoint() { return new PointF(-1f, -1f); }

    private static final class Tracker {
        private volatile WeakReference<Context> configured = new WeakReference<>(null);
        private final AtomicReference<Position> position = new AtomicReference<>();
        public void configure(Context value) {
            Context application = value == null ? null : value.getApplicationContext();
            configured = new WeakReference<>(application == null ? value : application);
        }

        public Context context(Object owner) throws ReflectiveOperationException {
            Context known = configured.get();
            if (known != null || owner == null) return known;
            if (Reflect.field(owner, "mContext") instanceof Context discovered) {
                configure(discovered);
                return discovered;
            }
            return null;
        }

        Bundle saveState() {
            Bundle state = new Bundle(); Position value = position.get();
            if (value != null) {
                state.putInt("device", value.pen().device()); state.putString("descriptor", value.pen().descriptor());
                state.putInt("pointer", value.pointerDevice()); state.putInt("width", value.frame().width());
                state.putInt("height", value.frame().height()); state.putInt("rotation", value.frame().rotation());
                state.putFloat("x", value.x()); state.putFloat("y", value.y()); state.putLong("time", value.time());
            }
            return state;
        }
        void restoreState(Bundle state) {
            String descriptor = state.getString("descriptor");
            if (descriptor == null || descriptor.isBlank()) return;
            Position restored = new Position(new PenIdentity(state.getInt("device", -1), descriptor), state.getInt("pointer", -1),
                    new DisplayFrame(state.getInt("width"), state.getInt("height"), state.getInt("rotation")),
                    state.getFloat("x"), state.getFloat("y"), state.getLong("time"));
            if (restored.fresh(SystemClock.uptimeMillis())) position.set(restored);
        }

        public boolean matches(Position point, Object owner, int deviceId) {
            try {
                Context context = context(owner);
                return point.fresh(SystemClock.uptimeMillis()) && point.pen.device == deviceId
                        && point.pen.equals(activePen(context)) && point.frame.equals(displayFrame(context));
            } catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
        }

        public void observe(Object owner, MotionEvent event) throws Throwable {
            if (event == null || event.getDeviceId() < 0) return;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_CANCEL) { position.set(null); return; }
            if (!samplingAction(action)) return;
            int pointer = penPointer(event);
            if (pointer < 0 || !Integer.valueOf(Display.DEFAULT_DISPLAY).equals(Reflect.call(event, "getDisplayId"))) return;
            Context context = context(owner);
            DisplayFrame frame = displayFrame(context);
            PenIdentity pen = activePen(context);
            if (frame == null || pen == null) { position.set(null); return; }
            float x = event.getX(pointer), y = event.getY(pointer);
            if (!Float.isFinite(x) || !Float.isFinite(y) || x < 0 || y < 0 || x >= frame.width || y >= frame.height) return;
            Position candidate = new Position(pen, event.getDeviceId(), frame, x, y, event.getEventTime());
            if (candidate.fresh(SystemClock.uptimeMillis())) position.set(candidate);
        }
    }

    public static void close() throws Throwable {
        if (released) return;
        stopped = true;
        ModuleSession.onLooper(MAIN, () -> {
            if (generation != null) {
                generation.remember("input.hoverOwners", OBSERVERS.saveOwners());
                generation.remember("input.hoverContext", TRACKER.configured.get());
                generation.remember("input.hoverPosition", TRACKER.saveState());
            }
            MAIN.removeCallbacksAndMessages(null);
            try { OBSERVERS.close(); }
            finally { TRACKER.configure(null); TRACKER.position.set(null); released = true; }
        });
    }

    private static boolean samplingAction(int action) {
        return switch (action) {
            case MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> true;
            default -> false;
        };
    }

    private static int penPointer(MotionEvent event) {
        for (int index = 0; index < event.getPointerCount(); index++) {
            int tool = event.getToolType(index);
            if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) return index;
        }
        return -1;
    }

    @SuppressWarnings("deprecation")
    private static DisplayFrame displayFrame(Context context) {
        if (context == null) return null;
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        Display display = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) return null;
        Point bounds = new Point(); display.getRealSize(bounds);
        return bounds.x > 0 && bounds.y > 0 ? new DisplayFrame(bounds.x, bounds.y, display.getRotation()) : null;
    }

    private static PenIdentity activePen(Context context) {
        InputManager input = context == null ? null : context.getSystemService(InputManager.class);
        if (input == null) return null;
        PenIdentity selected = null;
        for (int id : input.getInputDeviceIds()) {
            InputDevice device = input.getInputDevice(id);
            if (device == null || StylusDevices.version(device) <= 0) continue;
            String descriptor = device.getDescriptor();
            if (descriptor == null || descriptor.isBlank()) continue;
            if (selected != null) return null;
            selected = new PenIdentity(id, descriptor);
        }
        return selected;
    }

    private static final class Observers {
        private final Map<Object, Object> registered = new WeakHashMap<>();
        private final Map<Object, WeakReference<Object>> owners = new WeakHashMap<>();

        public synchronized void attach(FocusStylusModule module, Object owner, Object monitor) throws Throwable {
            if (registered.containsKey(monitor)) return;
            Class<?> listener = Class.forName("com.miui.server.input.gesture.MiuiGestureListener", false, monitor.getClass().getClassLoader());
            Method callback = listener.getDeclaredMethod("onPointerEvent", MotionEvent.class);
            if (!listener.isInterface() || callback.getReturnType() != void.class) throw new IllegalStateException("Unexpected pointer listener contract");
            Method registration = monitor.getClass().getMethod("registerPointerEventListener", listener);
            if (registration.getReturnType() != void.class) throw new IllegalStateException("Unexpected pointer registration contract");
            Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, new PointerObserver(owner));
            registration.invoke(monitor, proxy);
            registered.put(monitor, proxy); owners.put(monitor, new WeakReference<>(owner));
            module.info("Independent stylus hover tracking attached");
        }
        synchronized java.util.List<Object> saveOwners() {
            java.util.List<Object> saved = new java.util.ArrayList<>();
            for (WeakReference<Object> value : owners.values()) { Object owner = value.get(); if (owner != null) saved.add(owner); }
            return saved;
        }
        synchronized void close() throws Throwable {
            Throwable failure = null;
            for (var entry : new java.util.ArrayList<>(registered.entrySet())) {
                try {
                    Object proxy = entry.getValue(); Class<?> listener = proxy.getClass().getInterfaces()[0];
                    entry.getKey().getClass().getMethod("unregisterPointerEventListener", listener).invoke(entry.getKey(), proxy);
                } catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
            registered.clear(); owners.clear();
            if (failure != null) throw failure;
        }
    }

    private static final class PointerObserver implements InvocationHandler {
        private final WeakReference<Object> owner;
        public PointerObserver(Object owner) { this.owner = new WeakReference<>(owner); }

        @Override public Object invoke(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> arguments != null && arguments.length == 1 && proxy == arguments[0];
                case "toString" -> "HyperStylusPointerObserver";
                case "onPointerEvent" -> {
                    ModuleSession current = generation;
                    if (stopped || current != null && !current.enter()) yield null;
                    try { TRACKER.observe(owner.get(), (MotionEvent) arguments[0]); }
                    catch (Throwable ignored) { /* Independent observation must never fail native dispatch. */ }
                    finally { if (current != null) current.leave(); }
                    yield null;
                }
                default -> null;
            };
        }
    }
    private StylusHover() { }
}
