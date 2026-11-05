package cc.star0.xposed.hyperstylus.runtime.gesture;

import cc.star0.xposed.hyperstylus.config.ConfigBridge;
import cc.star0.xposed.hyperstylus.config.GestureAppConfig;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;
import cc.star0.xposed.hyperstylus.module.ModuleSession;
import android.app.ActivityManager;
import android.content.Context;
import android.hardware.input.InputManager;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.libxposed.api.XposedInterface;

/** Non-touchable caption windows route original pen input to the app; the native monitor owns controls. */
final class CaptionInputHook implements MotionGestureFilter.InputLifecycle {
    interface Types { Class<?> resolve(String name) throws ClassNotFoundException; }
    interface Origin { Object invoke(Object owner, Object... arguments) throws Throwable; }
    interface Origins { Origin create(Method method); }
    private static final String MODELS = "gesture.caption.models", CANCELLED = "gesture.caption.cancelled";
    private record Identity(long down, int device) { }
    private interface NativeFlags { int get(Object owner, boolean immersive) throws Throwable; }
    private interface NativeCancel { void run(Object owner, MotionEvent event, Object monitor) throws Throwable; }
    private static final class Model {
        final Object owner;
        final Handler handler;
        final CaptionRoutingState routing = new CaptionRoutingState();
        final AtomicBoolean queued = new AtomicBoolean();
        Model(Object owner, Handler handler) { this.owner = owner; this.handler = handler; }
    }
    private static final class Window {
        final Object owner;
        final Model model;
        final CaptionWindowTransaction flags = new CaptionWindowTransaction();
        Integer bottomNativeFlags;
        Window(Object owner, Model model) { this.owner = owner; this.model = model; }
    }
    private static final class Press {
        final Model model;
        final Object monitor, window;
        final int display;
        final boolean bottomClick;
        MotionEvent last;
        boolean pending, cancelling;
        Press(Model model, Object monitor, Object window, int display, MotionEvent last, boolean bottomClick) {
            this.model = model; this.monitor = monitor; this.window = window; this.display = display; this.last = last;
            this.bottomClick = bottomClick;
        }
        boolean matches(long down, int device) { return last.getDownTime() == down && last.getDeviceId() == device; }
        void recycle() { last.recycle(); }
    }
    private static final class Input {
        final Input previous;
        final Object owner;
        final MotionEvent event;
        final Model model;
        final Object monitor;
        final int display, action, device;
        final long down;
        MotionEvent copy;
        boolean nativeCaptured, moduleCapture;
        int freeformTouchMode, freeformTask = -1;
        Object freeformInfo;
        Input(Input previous, Model model, Object owner, MotionEvent event, Object monitor, int display, boolean copy) {
            this.previous = previous; this.owner = owner; this.event = event; this.display = display;
            this.model = model;
            this.monitor = monitor;
            action = event.getActionMasked(); device = event.getDeviceId(); down = event.getDownTime();
            if (copy) this.copy = MotionEvent.obtain(event);
        }
    }

    private final FocusStylusModule module;
    private final Class<?> modelType, topType, monitorType;
    private final Field modelHandler, modelContext, decorations, receivers, receiverMonitor, top, context, task, windowModel,
            immersive, params, host, handling, touchedTask, touchedFreeform, wasImmersive, taskDisplay;
    private final Method flagsMethod, observer, hit, getView, relayout, displayId, contextDisplay, taskMode, pilfer;
    private final NativeFlags nativeFlags;
    private final NativeCancel nativeCancel;
    private final Bottom bottom;
    private boolean bottomActive;
    /** Optional bottom ABI cannot disable the already verified top-caption adapter. */
    private static final class Bottom {
        final Class<?> type, viewType;
        final Field decoration, controller, touchStatus, resultTaskId, touchedSplit, scale;
        final Method controllerTouch, touchMode, resultTask, dragging, splitHit, destinationBounds, animating;
        Bottom(Types types, Class<?> model, Class<?> decorationType, Class<?> monitor) throws Throwable {
            type = CaptionInputHook.type(types, "MiuiBottomDecoration");
            viewType = CaptionInputHook.type(types, "MiuiBottomBarView");
            decoration = field(decorationType, "mBottomDecoration", type);
            Class<?> controlType = types.resolve("com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeController");
            Class<?> resultType = types.resolve("com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeTouchResult");
            Class<?> taskType = types.resolve("com.android.wm.shell.multitasking.taskmanager.MiuiFreeformModeTaskInfo");
            Class<?> statusType = types.resolve("com.android.wm.shell.multitasking.common.MultiTaskingTouchStatus");
            controller = field(model, "mMiuiFreeformModeController", controlType);
            touchStatus = field(model, "mMultiTaskingTouchStatus", statusType);
            touchedSplit = field(model, "mTouchedBottomTaskId", int.class);
            controllerTouch = method(controlType, "handleTouchEvent", resultType, MotionEvent.class, monitor);
            touchMode = method(resultType, "getTouchMode", int.class);
            resultTask = method(resultType, "getFreeformModeTaskInfo", taskType);
            resultTaskId = taskType.getField("mTaskId");
            if (resultTaskId.getType() != int.class || Modifier.isStatic(resultTaskId.getModifiers()))
                throw new NoSuchFieldException("Freeform task mTaskId ABI");
            resultTaskId.setAccessible(true);
            scale = taskType.getField("mDestinationScaleX");
            if (scale.getType() != float.class || Modifier.isStatic(scale.getModifiers()))
                throw new NoSuchFieldException("Freeform task destination scale ABI");
            scale.setAccessible(true);
            destinationBounds = taskType.getMethod("getDestinationBounds");
            if (destinationBounds.getReturnType() != Rect.class || Modifier.isStatic(destinationBounds.getModifiers()))
                throw new NoSuchMethodException("Freeform destination bounds ABI");
            destinationBounds.setAccessible(true);
            animating = taskType.getMethod("isInAnimating");
            if (animating.getReturnType() != boolean.class || Modifier.isStatic(animating.getModifiers()))
                throw new NoSuchMethodException("Freeform animation state ABI");
            animating.setAccessible(true);
            dragging = method(statusType, "isDragging", boolean.class);
            splitHit = method(type, "isTouchedInSplitBottomCaption", boolean.class, MotionEvent.class);
        }
    }
    private final Map<Object, Model> models = new IdentityHashMap<>();
    private final Map<Object, Window> windows = new IdentityHashMap<>();
    private final Map<Object, Press> presses = new IdentityHashMap<>();
    private final Map<Object, ArrayList<Identity>> cancelled = new IdentityHashMap<>();
    private final ThreadLocal<Input> inputs = new ThreadLocal<>();
    private final AtomicBoolean contextStarted = new AtomicBoolean();
    private final Object callbacks = new Object();
    private final Handler deviceHandler = new Handler(Looper.getMainLooper());
    private InputManager devices;
    private final InputManager.InputDeviceListener deviceListener = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id) { }
        @Override public void onInputDeviceChanged(int id) { }
        @Override public void onInputDeviceRemoved(int id) { forgetDevice(id); }
    };
    private ModuleSession.Registration focusRegistration;
    private volatile boolean operational, closed;

    private CaptionInputHook(FocusStylusModule module, ClassLoader loader) throws Throwable {
        this(module, name -> Class.forName(name, false, loader), method -> {
            var invoker = module.getInvoker(method).setType(XposedInterface.Invoker.Type.ORIGIN);
            return (owner, arguments) -> invoker.invoke(owner, arguments);
        });
    }

    CaptionInputHook(FocusStylusModule module, Types types, Origins origins) throws Throwable {
        this.module = module;
        modelType = type(types, "MulWinSwitchDecorViewModel");
        topType = type(types, "MiuiTopDecoration");
        Class<?> base = type(types, "MiuiBaseWindowDecoration"), decoration = type(types, "MiuiWindowDecoration");
        monitorType = types.resolve("android.view.InputMonitor");
        Class<?> viewHost = types.resolve("android.view.SurfaceControlViewHost");
        Class<?> receiver = type(types, "MulWinSwitchDecorViewModel$EventReceiver");
        modelHandler = field(modelType, "mMainHandler", Handler.class);
        modelContext = field(modelType, "mContext", Context.class);
        decorations = field(modelType, "mWindowDecorByTaskId", SparseArray.class);
        receivers = field(modelType, "mEventReceiversByDisplay", SparseArray.class);
        receiverMonitor = field(receiver, "mInputMonitor", monitorType);
        handling = field(modelType, "mIsHandlingEvent", boolean.class);
        touchedTask = field(modelType, "mTouchedTaskId", int.class);
        touchedFreeform = field(modelType, "mTouchedFreeformTaskId", int.class);
        wasImmersive = field(modelType, "mIsDecorationImmersive", boolean.class);
        top = field(decoration, "mTopDecoration", topType);
        context = field(base, "mContext", Context.class);
        task = field(base, "mRunningTaskInfo", ActivityManager.RunningTaskInfo.class);
        windowModel = field(base, "mWindowDecorViewModel", modelType);
        immersive = field(base, "mIsDecorationImmersive", boolean.class);
        params = field(base, "mDecorationLayoutParams", WindowManager.LayoutParams.class);
        host = field(base, "mViewHost", viewHost);
        flagsMethod = method(base, "getLayoutParamsFlags", int.class, boolean.class);
        observer = method(modelType, "handleReceivedMotionEvent", void.class, MotionEvent.class, monitorType, boolean.class);
        hit = method(topType, "isTouchedInCaption", boolean.class, MotionEvent.class, boolean.class);
        getView = method(viewHost, "getView", View.class);
        relayout = method(viewHost, "relayout", void.class, WindowManager.LayoutParams.class);
        displayId = MotionEvent.class.getMethod("getDisplayId");
        contextDisplay = Context.class.getMethod("getDisplayId");
        taskDisplay = ActivityManager.RunningTaskInfo.class.getField("displayId");
        if (taskDisplay.getType() != int.class || Modifier.isStatic(taskDisplay.getModifiers()))
            throw new NoSuchFieldException("TaskInfo.displayId ABI");
        taskMode = ActivityManager.RunningTaskInfo.class.getMethod("getWindowingMode");
        pilfer = method(monitorType, "pilferPointers", void.class);
        Origin flagsOrigin = origins.create(flagsMethod), observerOrigin = origins.create(observer);
        nativeFlags = (owner, value) -> (Integer) flagsOrigin.invoke(owner, value);
        nativeCancel = (owner, event, monitor) -> observerOrigin.invoke(owner, event, monitor, false);
        Bottom optional;
        try { optional = new Bottom(types, modelType, decoration, monitorType); }
        catch (Throwable failure) { optional = null; module.error("Bottom caption input routing ABI unavailable", failure); }
        bottom = optional;
        bottomActive = bottom != null;
    }

    static boolean install(FocusStylusModule module, ClassLoader loader) {
        CaptionInputHook input;
        try { input = new CaptionInputHook(module, loader); }
        catch (Throwable failure) { module.error("Caption input routing ABI unavailable", failure); return false; }
        try { input.install(); }
        catch (Throwable failure) {
            module.error("Install caption input routing", failure);
            // Installed callbacks remain pass-through unless the whole adapter is operational.
            input.operational = false;
            return false;
        }
        return true;
    }

    private void install() throws Throwable {
        module.session().onClose("caption input routing", this::close);
        MotionGestureFilter.installSelected(module, observer, this::selected, "window-caption", null, 1, this);
        module.managedHook(flagsMethod).intercept(chain -> {
            Object result = chain.proceed();
            Object owner = chain.getThisObject();
            if (!operational || !supportedWindow(owner) || !(result instanceof Integer flags)) return result;
            Window window = observeWindow(owner);
            recordNativeFlags(window, flags);
            int display = windowDisplay(owner);
            refreshDisplay(window.model, display);
            synchronized (this) { return window.flags.effective(flags, window.model.routing.enabled(display, false)); }
        });
        if (bottom != null) {
            try { module.after(bottom.controllerTouch, (owner, args, result) ->
                    recordFreeformTouch(owner, (MotionEvent) args[0], args[1], result)); }
            catch (Throwable failure) { bottomActive = false; module.error("Install bottom caption touch attribution", failure); }
        }
        module.managedHook(pilfer).intercept(chain -> {
            Input input = inputs.get();
            MotionGestureFilter.Origin origin = MotionGestureFilter.origin();
            if (input != null && input.monitor == chain.getThisObject() && origin != null && origin.blocked()
                    && "window-caption".equals(origin.feature())) return null;
            Object result = chain.proceed();
            recordNativeCapture(chain.getThisObject());
            return result;
        });
        for (var constructor : modelType.getDeclaredConstructors())
            module.after(constructor, (owner, args, result) -> observeModel(owner));
        Method release = host.getDeclaringClass().getDeclaredMethod("releaseViews");
        if (release.getReturnType() != void.class || Modifier.isStatic(release.getModifiers()))
            throw new NoSuchMethodException("Caption releaseViews ABI");
        module.managedHook(release).intercept(chain -> {
            Object owner = chain.getThisObject();
            if (operational && supportedWindow(owner)) {
                try { releaseWindow(owner); }
                catch (Throwable failure) { module.error("Cancel released caption window", failure); }
            }
            return chain.proceed();
        });
        module.after(method(modelType, "removeEventReceiver", void.class), (owner, args, result) -> {
            if (!operational || closed || owner.getClass() != modelType || ((SparseArray<?>) receivers.get(owner)).size() != 0) return;
            Model model;
            Press press;
            synchronized (this) { model = models.get(owner); press = presses.get(owner); }
            if (press != null && !press.cancelling) {
                try { revoke(press); }
                catch (Throwable failure) { press.pending = true; module.error("Cancel removed caption monitor", failure); }
            }
            if (model != null) { model.routing.clear(); queue(model); }
            synchronized (this) { cancelled.remove(owner); }
        });
        operational = true;
        focusRegistration = GestureFocusBridge.observeChanges(this::refreshAll);
        if (module.session().host(CANCELLED) instanceof Map<?, ?> saved)
            saved.forEach((owner, values) -> {
                if (owner.getClass() != modelType || !(values instanceof Iterable<?> entries)) return;
                ArrayList<Identity> ids = new ArrayList<>();
                for (Object value : entries) if (value instanceof Bundle data)
                    ids.add(new Identity(data.getLong("down"), data.getInt("device")));
                cancelled.put(owner, ids);
            });
        if (module.session().host(MODELS) instanceof Iterable<?> saved)
            for (Object owner : saved) if (modelType.isInstance(owner)) observeModel(owner);
        module.info("Caption routing: native window NOT_TOUCHABLE with receiver-scoped control monitor");
    }

    private static Class<?> type(Types types, String name) throws ClassNotFoundException {
        return types.resolve("com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor." + name);
    }
    private static Field field(Class<?> type, String name, Class<?> expected) throws ReflectiveOperationException {
        Field value = type.getDeclaredField(name);
        if (value.getType() != expected || Modifier.isStatic(value.getModifiers())) throw new NoSuchFieldException(type + "." + name + " ABI");
        value.setAccessible(true); return value;
    }
    private static Method method(Class<?> type, String name, Class<?> result, Class<?>... args) throws ReflectiveOperationException {
        Method value = type.getDeclaredMethod(name, args);
        if (value.getReturnType() != result || Modifier.isStatic(value.getModifiers()) || Modifier.isAbstract(value.getModifiers()))
            throw new NoSuchMethodException(type + "." + name + " ABI");
        value.setAccessible(true); return value;
    }
    private static Object invoke(Method method, Object owner, Object... args) throws Throwable {
        try { return method.invoke(owner, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private synchronized Model observeModel(Object owner) throws Throwable {
        Model model = models.get(owner);
        if (model != null) return model;
        if (owner.getClass() != modelType || !(modelHandler.get(owner) instanceof Handler handler))
            throw new IllegalStateException("Caption model Looper unavailable");
        model = new Model(owner, handler); models.put(owner, model);
        if (contextStarted.compareAndSet(false, true)) {
            // The model's Context is a real SystemUI Context, shared with the existing configuration reader.
            Context source = (Context) modelContext.get(owner);
            ConfigBridge.start(source, config -> {
                if (closed) return;
                if (devices == null) {
                    try {
                        InputManager manager = source.getSystemService(InputManager.class);
                        if (manager != null) { manager.registerInputDeviceListener(deviceListener, deviceHandler); devices = manager; }
                    } catch (Throwable failure) { module.error("Observe caption input devices", failure); }
                }
                refreshAll();
            });
        }
        queue(model);
        return model;
    }

    private synchronized Window observeWindow(Object owner) throws Throwable {
        Window window = windows.get(owner);
        if (window == null) {
            window = new Window(owner, observeModel(windowModel.get(owner)));
            windows.put(owner, window);
        }
        return window;
    }

    private boolean supportedWindow(Object owner) {
        return owner.getClass() == topType || isBottom(owner);
    }
    private boolean isBottom(Object owner) {
        return bottomActive && owner.getClass() == bottom.type;
    }
    private synchronized void recordNativeFlags(Window window, int flags) {
        // Bottom updateViews supplies a compound argument (immersive, split or Home), not just the immersive field.
        if (isBottom(window.owner)) window.bottomNativeFlags = flags;
    }

    private void recordFreeformTouch(Object owner, MotionEvent event, Object monitor, Object result) throws Throwable {
        Input input = inputs.get();
        if (!bottomActive || input == null || input.model == null || input.action != MotionEvent.ACTION_DOWN
                || event.getActionMasked() != MotionEvent.ACTION_DOWN || event.getDownTime() != input.down
                || event.getDeviceId() != input.device || monitor != input.monitor
                || bottom.controller.get(input.owner) != owner || result == null) return;
        Object info = invoke(bottom.resultTask, result);
        input.freeformTouchMode = (Integer) invoke(bottom.touchMode, result);
        input.freeformTask = info == null ? -1 : bottom.resultTaskId.getInt(info);
        input.freeformInfo = info;
    }

    private int windowDisplay(Object owner) throws Throwable {
        ActivityManager.RunningTaskInfo info = (ActivityManager.RunningTaskInfo) task.get(owner);
        return info == null ? (Integer) invoke(contextDisplay, context.get(owner)) : taskDisplay.getInt(info);
    }

    private boolean selected(MotionEvent event) {
        if (!operational || closed) return false;
        Input input = inputs.get();
        if (input == null || input.model == null || input.event != event || input.owner.getClass() != modelType) return false;
        try { return input.model.routing.enabled((Integer) displayId.invoke(event), false); }
        catch (ReflectiveOperationException failure) { return false; }
    }

    private boolean monitorReady(Model model, int display) throws IllegalAccessException {
        Object receiver = ((SparseArray<?>) receivers.get(model.owner)).get(display);
        return receiver != null && monitorType.isInstance(receiverMonitor.get(receiver));
    }

    private void refreshAll() {
        if (!operational || closed) return;
        ArrayList<Model> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(models.values()); }
        snapshot.forEach(this::queue);
    }
    private void queue(Model model) {
        if (closed || !model.queued.compareAndSet(false, true)) return;
        if (!model.handler.postAtTime(() -> {
            model.queued.set(false);
            module.safely("Refresh caption input windows", () -> {
                try {
                    SparseArray<?> current = (SparseArray<?>) decorations.get(model.owner);
                    for (int index = 0; index < current.size(); index++) {
                        Object decoration = current.valueAt(index);
                        Object[] owners = {top.get(decoration), !bottomActive ? null : bottom.decoration.get(decoration)};
                        for (Object owner : owners) if (owner != null) {
                            Window window = observeWindow(owner);
                            int display = windowDisplay(owner);
                            refreshDisplay(model, display);
                            // New or rebuilt windows also need reconciliation when the display policy is unchanged.
                            if (!model.routing.active(display)) apply(window, model.routing.enabled(display, false));
                        }
                    }
                } catch (Throwable failure) { module.error("Refresh caption input windows", failure); }
            });
        }, callbacks, SystemClock.uptimeMillis())) model.queued.set(false);
    }

    private void refreshDisplay(Model model, int display) throws Throwable {
        synchronized (this) {
            for (Press press : presses.values()) if (press.pending && press.display == display) return;
        }
        boolean desired = monitorReady(model, display) && GestureFocusBridge.enabledOnDisplay(display,
                GestureAppConfig.CAPTION, ConfigBridge.current(), ConfigBridge.current().ignoreStylusCaption);
        model.routing.update(display, desired, () -> {
            ArrayList<Window> applied = new ArrayList<>();
            boolean previous = model.routing.enabled(display, false);
            try {
                ArrayList<Window> snapshot;
                synchronized (this) { snapshot = new ArrayList<>(windows.values()); }
                for (Window window : snapshot) if (window.model == model && windowDisplay(window.owner) == display) {
                    applied.add(window); apply(window, desired);
                }
            } catch (Throwable failure) {
                for (Window window : applied) {
                    try { apply(window, previous); } catch (Throwable rollback) { failure.addSuppressed(rollback); }
                }
                throw new IllegalStateException("Could not apply caption input routing", failure);
            }
        });
    }

    private void apply(Window window, boolean enabled) throws Throwable {
        Object viewHost = host.get(window.owner);
        WindowManager.LayoutParams layout = (WindowManager.LayoutParams) params.get(window.owner);
        if (viewHost == null || layout == null) return;
        int current = layout.flags;
        int original;
        if (isBottom(window.owner)) {
            // A late-discovered live bottom window has not yet passed through our flags producer.
            synchronized (this) {
                if (window.bottomNativeFlags == null) window.bottomNativeFlags = current;
                original = window.bottomNativeFlags;
            }
        } else original = nativeFlags.get(window.owner, immersive.getBoolean(window.owner));
        synchronized (this) {
            window.flags.apply(current, original, enabled, next -> {
                layout.flags = next;
                try { invoke(relayout, viewHost, layout); }
                catch (Throwable failure) { layout.flags = current; throw failure; }
            });
        }
    }

    @Override public boolean before(Object owner, MotionEvent event, Object inputContext) throws Throwable {
        if (!operational || closed) return false;
        Input previous = inputs.get();
        // An unsupported nested invocation must not inherit the outer invocation's routing decision.
        inputs.set(new Input(previous, null, owner, event, inputContext, -1, false));
        if (owner.getClass() != modelType || !monitorType.isInstance(inputContext)) return false;
        int display = (Integer) displayId.invoke(event);
        Object receiver = ((SparseArray<?>) receivers.get(owner)).get(display);
        if (receiver == null || receiverMonitor.get(receiver) != inputContext) return false;
        Model model = observeModel(owner);
        Press press;
        synchronized (this) { press = presses.get(owner); }
        boolean bypass = isCancelled(owner, event.getDownTime(), event.getDeviceId());
        if (press != null && press.pending) {
            try {
                revoke(press); press = null;
            } catch (Throwable failure) { bypass = true; }
        }
        if (press != null && !press.pending && event.getActionMasked() == MotionEvent.ACTION_DOWN
                && !press.matches(event.getDownTime(), event.getDeviceId())) {
            if (event.getDownTime() < press.last.getDownTime()) bypass = true;
            else {
                try { revoke(press); press = null; }
                catch (Throwable failure) { press.pending = true; bypass = true; }
            }
        }
        if (press != null && event.getDownTime() < press.last.getDownTime()) bypass = true;
        bypass |= isCancelled(owner, event.getDownTime(), event.getDeviceId());
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !bypass) model.routing.begin(display, event.getDownTime(), event.getDeviceId());
        boolean copy = press != null && press.matches(event.getDownTime(), event.getDeviceId())
                || event.getActionMasked() == MotionEvent.ACTION_DOWN && model.routing.enabled(display, false) && !MotionGestureFilter.stylus(event);
        inputs.set(new Input(previous, model, owner, event, inputContext, display, copy));
        return bypass;
    }

    @Override public void after(Object owner, MotionEvent event, Object inputContext, int decision, boolean proceeded) throws Throwable {
        Input input = inputs.get();
        if (input == null || input.owner != owner || input.event != event || input.monitor != inputContext) return;
        if (input.model == null) {
            if (input.previous == null) inputs.remove(); else inputs.set(input.previous);
            return;
        }
        try {
            Press press;
            synchronized (this) { press = presses.get(owner); }
            if (press != null && !press.pending && press.matches(input.down, input.device)) {
                if (decision != StylusBackGestureHook.PASS && proceeded) {
                    // The shared observer already delivered CANCEL when a pen joined a captured finger stream.
                    handling.setBoolean(owner, false);
                    markCancelled(press);
                    synchronized (this) { presses.remove(owner); }
                    press.recycle();
                } else if (decision != StylusBackGestureHook.PASS || !proceeded) {
                    try { revoke(press); }
                    catch (Throwable failure) { press.pending = true; throw failure; }
                } else if (input.action == MotionEvent.ACTION_UP || input.action == MotionEvent.ACTION_CANCEL) {
                    synchronized (this) { presses.remove(owner); }
                    try {
                        if (input.action == MotionEvent.ACTION_UP && (event.getFlags() & MotionEvent.FLAG_CANCELED) == 0)
                            completeBottomClick(press);
                    } finally { press.recycle(); }
                } else if (input.copy != null) {
                    press.recycle(); press.last = input.copy; input.copy = null;
                }
            }
            if (proceeded && decision == StylusBackGestureHook.PASS && input.action == MotionEvent.ACTION_DOWN && input.copy != null)
                captureControl(owner, inputContext, input);
        } finally {
            if (input.copy != null) input.copy.recycle();
            if (input.action == MotionEvent.ACTION_UP || input.action == MotionEvent.ACTION_CANCEL) {
                input.model.routing.end(input.display, input.down, input.device);
                synchronized (this) {
                    ArrayList<Identity> ids = cancelled.get(owner);
                    if (ids != null) {
                        ids.remove(new Identity(input.down, input.device));
                        if (ids.isEmpty()) cancelled.remove(owner);
                    }
                }
                refreshAll();
            }
            if (input.previous == null) inputs.remove(); else inputs.set(input.previous);
        }
    }

    private void captureControl(Object owner, Object monitor, Input input) throws Throwable {
        if (input.nativeCaptured) return; // Preserve stopping rights the ROM acquired itself.
        if (!monitorType.isInstance(monitor) || !handling.getBoolean(owner) || wasImmersive.getBoolean(owner)) return;
        int id = touchedTask.getInt(owner);
        boolean freeform = id < 0;
        if (freeform) id = touchedFreeform.getInt(owner);
        boolean splitBottom = id < 0 && bottomActive;
        if (splitBottom) id = bottom.touchedSplit.getInt(owner);
        if (id < 0) return;
        Object decoration = ((SparseArray<?>) decorations.get(owner)).get(id);
        boolean bottomHit = bottomActive && (splitBottom || freeform && input.freeformTouchMode == 2 && input.freeformTask == id);
        if (freeform && input.freeformTouchMode != 0 && !bottomHit && input.freeformTouchMode != 1) return;
        Object windowOwner = decoration == null ? null : bottomHit ? bottom.decoration.get(decoration) : top.get(decoration);
        if (windowOwner == null) return;
        Window window = observeWindow(windowOwner);
        WindowManager.LayoutParams layout = (WindowManager.LayoutParams) params.get(windowOwner);
        ActivityManager.RunningTaskInfo info = (ActivityManager.RunningTaskInfo) task.get(windowOwner);
        synchronized (this) { if (layout == null || !window.flags.owns(layout.flags)) return; }
        if (info == null) return;
        if (splitBottom) {
            if (!Boolean.TRUE.equals(invoke(bottom.splitHit, windowOwner, input.copy))) return;
        } else if (bottomHit) {
            if (!insideFreeformBottomWindow(windowOwner, input)) return;
        } else if (!Boolean.TRUE.equals(invoke(hit, windowOwner, input.copy, (Integer) taskMode.invoke(info) == 6))) return;
        Press press = new Press(window.model, monitor, windowOwner, input.display, input.copy, bottomHit && !splitBottom);
        input.moduleCapture = true;
        try { invoke(pilfer, monitor); }
        catch (Throwable failure) {
            // Avoid native controller actions and an application click both completing if capture fails.
            try { cancel(press); markCancelled(press); }
            catch (Throwable secondary) {
                failure.addSuppressed(secondary); press.pending = true;
                synchronized (this) { presses.put(owner, press); }
                input.copy = null;
            }
            throw failure;
        } finally {
            input.moduleCapture = false;
        }
        synchronized (this) {
            Press replaced = presses.put(owner, press);
            if (replaced != null) replaced.recycle();
        }
        input.copy = null;
    }

    private boolean insideFreeformBottomWindow(Object owner, Input input) throws Throwable {
        if (input.freeformInfo == null) return false;
        Object viewHost = host.get(owner);
        Object view = viewHost == null ? null : invoke(getView, viewHost);
        if (!(view instanceof View bar) || bar.getClass() != bottom.viewType || bar.getWidth() <= 0 || bar.getHeight() <= 0) return false;
        if (Boolean.TRUE.equals(invoke(bottom.animating, input.freeformInfo))) return false;
        float scale = bottom.scale.getFloat(input.freeformInfo);
        Rect bounds = (Rect) invoke(bottom.destinationBounds, input.freeformInfo);
        if (!Float.isFinite(scale) || scale <= 0 || bounds == null || bounds.isEmpty()) return false;
        // The native freeform bottom window is centered in the task and flush with its bottom edge.
        // Its gesture hot region additionally includes space outside this actual input window.
        float halfWidth = bar.getWidth() * scale / 2;
        float center = bounds.left + bounds.width() * scale / 2;
        float bottomEdge = bounds.top + bounds.height() * scale;
        float x = input.copy.getX(), y = input.copy.getY();
        return x >= center - halfWidth && x < center + halfWidth
                && y >= bottomEdge - bar.getHeight() * scale && y < bottomEdge;
    }

    private void completeBottomClick(Press press) throws Throwable {
        if (!press.bottomClick || !isBottom(press.window)) return;
        if (Boolean.TRUE.equals(invoke(bottom.dragging, bottom.touchStatus.get(press.model.owner)))) return;
        Object viewHost = host.get(press.window);
        if (viewHost == null) return;
        Object view = invoke(getView, viewHost);
        if (view instanceof View bar && bar.getClass() == bottom.viewType && bar.hasOnClickListeners())
            bar.performClick(); // Keep the host's existing triple-click listener; do not replace its state.
    }

    private void recordNativeCapture(Object monitor) {
        Input input = inputs.get();
        if (input != null && input.monitor == monitor && !input.moduleCapture) input.nativeCaptured = true;
    }

    private void cancel(Press press) throws Throwable {
        MotionEvent event = MotionEvent.obtain(press.last); event.setAction(MotionEvent.ACTION_CANCEL);
        press.cancelling = true;
        try {
            nativeCancel.run(press.model.owner, event, press.monitor);
            handling.setBoolean(press.model.owner, false);
        } finally { press.cancelling = false; event.recycle(); }
    }

    private synchronized boolean isCancelled(Object owner, long down, int device) {
        ArrayList<Identity> ids = cancelled.get(owner);
        return ids != null && ids.contains(new Identity(down, device));
    }
    private synchronized void markCancelled(Press press) {
        Identity id = new Identity(press.last.getDownTime(), press.last.getDeviceId());
        ArrayList<Identity> ids = cancelled.computeIfAbsent(press.model.owner, ignored -> new ArrayList<>());
        if (!ids.contains(id)) ids.add(id);
    }
    private void revoke(Press press) throws Throwable {
        cancel(press); markCancelled(press);
        synchronized (this) { presses.remove(press.model.owner); }
        press.recycle();
    }

    private void releaseWindow(Object owner) throws Throwable {
        Press press = null;
        synchronized (this) {
            for (Press value : presses.values()) if (value.window == owner) { press = value; break; }
        }
        if (press != null && !press.cancelling) {
            try { revoke(press); }
            catch (Throwable failure) { press.pending = true; throw failure; }
        }
        synchronized (this) { windows.remove(owner); }
    }

    private void forgetDevice(int device) {
        if (closed) return;
        ArrayList<Model> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(models.values()); }
        for (Model model : snapshot) model.handler.postAtTime(() -> module.safely("Forget caption input device", () -> {
            Press press;
            synchronized (this) { press = presses.get(model.owner); }
            if (press != null && press.last.getDeviceId() == device) {
                try { revoke(press); }
                catch (Throwable failure) { press.pending = true; module.error("Cancel removed caption device", failure); }
            }
            model.routing.forgetDevice(device);
            synchronized (this) {
                ArrayList<Identity> ids = cancelled.get(model.owner);
                if (ids != null) {
                    ids.removeIf(id -> id.device == device);
                    if (ids.isEmpty()) cancelled.remove(model.owner);
                }
            }
            queue(model);
        }), callbacks, SystemClock.uptimeMillis());
    }

    private void close() throws Throwable {
        closed = true; operational = false;
        if (focusRegistration != null) focusRegistration.forget();
        ArrayList<Model> saved;
        synchronized (this) { saved = new ArrayList<>(models.values()); }
        ArrayList<ModuleSession.Cleanup> cleanups = new ArrayList<>();
        cleanups.add(() -> ModuleSession.onLooper(deviceHandler, () -> {
            if (devices != null) { devices.unregisterInputDeviceListener(deviceListener); devices = null; }
        }));
        for (Model model : saved) cleanups.add(() -> ModuleSession.onLooper(model.handler, () -> {
            model.handler.removeCallbacksAndMessages(callbacks);
            ArrayList<ModuleSession.Cleanup> releases = new ArrayList<>();
            Press press;
            synchronized (this) { press = presses.get(model.owner); }
            if (press != null) releases.add(() -> {
                revoke(press);
            });
            ArrayList<Window> snapshot;
            synchronized (this) { snapshot = new ArrayList<>(windows.values()); }
            for (Window window : snapshot) if (window.model == model) releases.add(() -> apply(window, false));
            ModuleSession.runAll(releases.toArray(ModuleSession.Cleanup[]::new));
        }));
        ModuleSession.runAll(cleanups.toArray(ModuleSession.Cleanup[]::new));
        module.session().remember(MODELS, saved.stream().map(value -> value.owner).toList());
        Map<Object, ArrayList<Bundle>> cancelledState = new IdentityHashMap<>();
        synchronized (this) {
            cancelled.forEach((owner, ids) -> {
                ArrayList<Bundle> values = new ArrayList<>();
                for (Identity id : ids) {
                    Bundle value = new Bundle(); value.putLong("down", id.down); value.putInt("device", id.device); values.add(value);
                }
                cancelledState.put(owner, values);
            });
        }
        module.session().remember(CANCELLED, cancelledState);
        saved.forEach(model -> model.routing.clear());
        synchronized (this) { windows.clear(); models.clear(); }
    }
}
