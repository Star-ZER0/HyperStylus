package cc.star0.xposed.hyperstylus.runtime.gesture;

import static org.junit.Assert.*;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;
import cc.star0.xposed.hyperstylus.module.ModuleSession;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Exercise the actual caption router around the verified vendor schema, without a live Xposed host. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CaptionInputHookTest {
    private static final String PREFIX = "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.";
    private static final int FINGER = MotionEvent.TOOL_TYPE_FINGER, PEN = MotionEvent.TOOL_TYPE_STYLUS,
            ERASER = MotionEvent.TOOL_TYPE_ERASER;

    private static class NativeMonitor {
        int pilfers;
        public void pilferPointers() { pilfers++; }
    }

    private static class NativeViewHost {
        final View view;
        int relayouts, appliedFlags = 24;
        NativeViewHost(View view) { this.view = view; }
        public View getView() { return view; }
        public void relayout(WindowManager.LayoutParams params) { relayouts++; appliedFlags = params.flags; }
    }

    private static class NativeBase {
        private final Context mContext;
        private final ActivityManager.RunningTaskInfo mRunningTaskInfo = new ActivityManager.RunningTaskInfo();
        private final NativeModel mWindowDecorViewModel;
        private boolean mIsDecorationImmersive;
        private final WindowManager.LayoutParams mDecorationLayoutParams = new WindowManager.LayoutParams();
        private final NativeViewHost mViewHost;
        NativeBase(Context context, NativeModel model) throws ReflectiveOperationException {
            this(context, model, new View(context));
        }
        NativeBase(Context context, NativeModel model, View view) throws ReflectiveOperationException {
            mContext = context; mWindowDecorViewModel = model;
            mViewHost = new NativeViewHost(view);
            mDecorationLayoutParams.flags = 24;
            ActivityManager.RunningTaskInfo.class.getField("displayId").setInt(mRunningTaskInfo, 0);
        }
        public int getLayoutParamsFlags(boolean immersive) { return immersive ? 24 : 8; }
        public void releaseViews() { }
    }

    private static class NativeTop extends NativeBase {
        boolean hit = true;
        NativeTop(Context context, NativeModel model) throws ReflectiveOperationException { super(context, model); }
        public boolean isTouchedInCaption(MotionEvent event, boolean split) { return hit; }
    }

    private static class NativeBottomBar extends View {
        int clicks;
        NativeBottomBar(Context context) {
            super(context);
            setOnClickListener(view -> clicks++);
            measure(MeasureSpec.makeMeasureSpec(200, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(40, MeasureSpec.EXACTLY));
            layout(0, 0, 200, 40);
        }
    }

    private static class NativeBottom extends NativeBase {
        boolean splitHit = true;
        NativeBottom(Context context, NativeModel model) throws ReflectiveOperationException {
            super(context, model, new NativeBottomBar(context));
        }
        public boolean isTouchedInSplitBottomCaption(MotionEvent event) { return splitHit; }
    }

    /** The task ID is inherited in the real vendor DTO rather than declared by the concrete class. */
    public static class NativeTaskBase { public int mTaskId = 7; }
    private static class NativeFreeformTask extends NativeTaskBase {
        public float mDestinationScaleX = 1f;
        boolean animating;
        public final Rect getDestinationBounds() { return new Rect(0, 0, 200, 40); }
        public final boolean isInAnimating() { return animating; }
    }
    private static class NativeTouchResult {
        final int mode;
        final NativeFreeformTask info;
        NativeTouchResult(int mode, NativeFreeformTask info) { this.mode = mode; this.info = info; }
        public int getTouchMode() { return mode; }
        public NativeFreeformTask getFreeformModeTaskInfo() { return info; }
    }
    private static class NativeFreeformController {
        int mode = 1;
        final NativeFreeformTask info = new NativeFreeformTask();
        NativeTouchResult last;
        public NativeTouchResult handleTouchEvent(MotionEvent event, NativeMonitor monitor) {
            last = new NativeTouchResult(mode, info); return last;
        }
    }
    private static class NativeTouchStatus {
        boolean dragging;
        public boolean isDragging() { return dragging; }
    }

    private static class NativeDecoration {
        private final NativeTop mTopDecoration;
        private NativeBottom mBottomDecoration;
        NativeDecoration(NativeTop top) { mTopDecoration = top; }
    }

    private static class DecorationWithoutBottom {
        private final NativeTop mTopDecoration;
        DecorationWithoutBottom(NativeTop top) { mTopDecoration = top; }
    }

    private static class NativeReceiver {
        private final NativeMonitor mInputMonitor;
        NativeReceiver(NativeMonitor monitor) { mInputMonitor = monitor; }
    }

    private static class NativeModel {
        private final Context mContext;
        private final Handler mMainHandler = new Handler(Looper.getMainLooper());
        private final SparseArray<NativeDecoration> mWindowDecorByTaskId = new SparseArray<>();
        private final SparseArray<NativeReceiver> mEventReceiversByDisplay = new SparseArray<>();
        private final NativeFreeformController mMiuiFreeformModeController = new NativeFreeformController();
        private final NativeTouchStatus mMultiTaskingTouchStatus = new NativeTouchStatus();
        private boolean mIsHandlingEvent;
        private int mTouchedTaskId = -1, mTouchedFreeformTaskId = -1, mTouchedBottomTaskId = -1;
        private boolean mIsDecorationImmersive;
        boolean startsImmersive, nativeHit = true, failCancel, freeformHit, splitBottomHit;
        int downs, ups, cancels, cancelAttempts, clicks;
        final List<String> order = new ArrayList<>();
        NativeModel(Context context) { mContext = context; }
        public void handleReceivedMotionEvent(MotionEvent event, NativeMonitor monitor, boolean fake) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downs++; order.add("down:" + event.getDownTime());
                mIsHandlingEvent = nativeHit;
                mTouchedTaskId = nativeHit && !freeformHit && !splitBottomHit ? 7 : -1;
                mTouchedFreeformTaskId = nativeHit && freeformHit ? 7 : -1;
                mTouchedBottomTaskId = nativeHit && splitBottomHit ? 7 : -1;
                mIsDecorationImmersive = startsImmersive;
                if (freeformHit) mMiuiFreeformModeController.handleTouchEvent(event, monitor);
            } else if (action == MotionEvent.ACTION_CANCEL) {
                cancelAttempts++;
                if (failCancel) throw new IllegalStateException("Native cancellation failed");
                cancels++; order.add("cancel:" + event.getDownTime());
                mIsHandlingEvent = false;
            } else if (action == MotionEvent.ACTION_UP) {
                ups++; order.add("up:" + event.getDownTime());
                if (mIsHandlingEvent && !mIsDecorationImmersive) clicks++;
                mIsHandlingEvent = false;
            }
        }
    }

    private static class DerivedModel extends NativeModel {
        DerivedModel(Context context) { super(context); }
    }

    private FocusStylusModule module;
    private CaptionInputHook hook;
    private NativeModel model;
    private NativeMonitor monitor;
    private NativeTop top;
    private Object window;
    private Map<String, Class<?>> schema;
    private final StylusBackGestureHook.Gate gate = new StylusBackGestureHook.Gate();

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name); field.setAccessible(true); return field;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object value(Object object, String name) throws ReflectiveOperationException {
        return field(object.getClass(), name).get(object);
    }

    private static Object invoke(Object object, String name, Class<?>[] parameters, Object... args) throws Throwable {
        Method method = object.getClass().getDeclaredMethod(name, parameters); method.setAccessible(true);
        try { return method.invoke(object, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    @Before public void setup() throws Throwable {
        Context context = RuntimeEnvironment.getApplication();
        module = new FocusStylusModule();
        module.attachFramework((io.github.libxposed.api.XposedInterface) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{io.github.libxposed.api.XposedInterface.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("log")) return null;
                    throw new UnsupportedOperationException(method.getName());
                }), () -> { });
        schema = Map.ofEntries(
                Map.entry(PREFIX + "MulWinSwitchDecorViewModel", NativeModel.class),
                Map.entry(PREFIX + "MiuiTopDecoration", NativeTop.class),
                Map.entry(PREFIX + "MiuiBottomDecoration", NativeBottom.class),
                Map.entry(PREFIX + "MiuiBottomBarView", NativeBottomBar.class),
                Map.entry(PREFIX + "MiuiBaseWindowDecoration", NativeBase.class),
                Map.entry(PREFIX + "MiuiWindowDecoration", NativeDecoration.class),
                Map.entry(PREFIX + "MulWinSwitchDecorViewModel$EventReceiver", NativeReceiver.class),
                Map.entry("com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeController", NativeFreeformController.class),
                Map.entry("com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeTouchResult", NativeTouchResult.class),
                Map.entry("com.android.wm.shell.multitasking.taskmanager.MiuiFreeformModeTaskInfo", NativeFreeformTask.class),
                Map.entry("com.android.wm.shell.multitasking.common.MultiTaskingTouchStatus", NativeTouchStatus.class),
                Map.entry("android.view.InputMonitor", NativeMonitor.class),
                Map.entry("android.view.SurfaceControlViewHost", NativeViewHost.class));
        hook = makeHook(schema);
        field(CaptionInputHook.class, "operational").setBoolean(hook, true);
        ((AtomicBoolean) value(hook, "contextStarted")).set(true);
        model = new NativeModel(context); monitor = new NativeMonitor();
        top = new NativeTop(context, model);
        model.mWindowDecorByTaskId.put(7, new NativeDecoration(top));
        model.mEventReceiversByDisplay.put(0, new NativeReceiver(monitor));
        window = invoke(hook, "observeWindow", new Class<?>[]{Object.class}, top);
        Object observedModel = value(window, "model");
        model.mMainHandler.removeCallbacksAndMessages(value(hook, "callbacks"));
        ((AtomicBoolean) value(observedModel, "queued")).set(false);
        ((CaptionRoutingState) value(observedModel, "routing")).update(0, true, () -> { });
        ((CaptionWindowTransaction) value(window, "flags")).effective(8, true);
    }

    private CaptionInputHook makeHook(Map<String, Class<?>> types) throws Throwable {
        return new CaptionInputHook(module, name -> {
            Class<?> type = types.get(name);
            if (type == null) throw new ClassNotFoundException(name);
            return type;
        }, method -> (owner, arguments) -> {
            try { return method.invoke(owner, arguments); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
    }

    @After public void cleanup() throws Throwable {
        if (hook == null) return;
        model.failCancel = false;
        if (!(Boolean) value(hook, "closed")) {
            try { invoke(hook, "close", new Class<?>[]{}); }
            catch (IllegalArgumentException fixtureRejected) {
                // These vendor stand-ins share the module loader, unlike real host vendor classes.
                assertTrue(fixtureRejected.getMessage().contains("Module objects"));
            }
        }
        model.mMainHandler.removeCallbacksAndMessages(value(hook, "callbacks"));
        module.session().close(2_000);
    }

    private static MotionEvent event(int action, long down, int device, int... tools) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[tools.length];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[tools.length];
        for (int index = 0; index < tools.length; index++) {
            properties[index] = new MotionEvent.PointerProperties(); properties[index].id = index;
            properties[index].toolType = tools[index];
            coordinates[index] = new MotionEvent.PointerCoords(); coordinates[index].x = 40 + index;
            coordinates[index].y = 20; coordinates[index].pressure = 1;
        }
        return MotionEvent.obtain(down, down + 10, action, tools.length, properties, coordinates,
                0, 0, 1, 1, device, 0, InputDevice.SOURCE_STYLUS | InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    private boolean selected(MotionEvent event) throws Throwable {
        return (Boolean) invoke(hook, "selected", new Class<?>[]{MotionEvent.class}, event);
    }

    private int dispatch(int action, long down, int device, int... tools) throws Throwable {
        MotionEvent input = event(action, down, device, tools);
        try {
            boolean bypass = hook.before(model, input, monitor);
            int decision = bypass ? StylusBackGestureHook.DROP : gate.decide(model, input, selected(input));
            boolean proceeded = false;
            try {
                if (decision == StylusBackGestureHook.PASS) {
                    model.handleReceivedMotionEvent(input, monitor, false);
                    if (model.freeformHit) invoke(hook, "recordFreeformTouch",
                            new Class<?>[]{Object.class, MotionEvent.class, Object.class, Object.class},
                            model.mMiuiFreeformModeController, input, monitor, model.mMiuiFreeformModeController.last);
                    proceeded = true;
                } else if (decision == StylusBackGestureHook.CANCEL_AND_DROP) {
                    MotionEvent cancel = MotionEvent.obtain(input); cancel.setAction(MotionEvent.ACTION_CANCEL);
                    try { model.handleReceivedMotionEvent(cancel, monitor, false); proceeded = true; }
                    finally { cancel.recycle(); }
                }
            } finally { hook.after(model, input, monitor, decision, proceeded); }
            return decision;
        } finally { input.recycle(); }
    }

    private NativeBottom addBottom(int nativeFlags) throws Throwable {
        assertNotNull("The complete bottom ABI must be available in this fixture", value(hook, "bottom"));
        NativeBottom bottom = new NativeBottom(RuntimeEnvironment.getApplication(), model);
        model.mWindowDecorByTaskId.get(7).mBottomDecoration = bottom;
        Object bottomWindow = invoke(hook, "observeWindow", new Class<?>[]{Object.class}, bottom);
        invoke(hook, "recordNativeFlags", new Class<?>[]{bottomWindow.getClass(), int.class}, bottomWindow, nativeFlags);
        WindowManager.LayoutParams layout = (WindowManager.LayoutParams) value(bottom, "mDecorationLayoutParams");
        layout.flags = ((CaptionWindowTransaction) value(bottomWindow, "flags")).effective(nativeFlags, true);
        model.freeformHit = true;
        model.mMiuiFreeformModeController.mode = 2;
        return bottom;
    }

    private NativeBottomBar bottomBar(NativeBottom bottom) throws ReflectiveOperationException {
        return (NativeBottomBar) ((NativeViewHost) value(bottom, "mViewHost")).view;
    }

    @Test public void bottomPenAndEraserStreamsDoNotStartNativeControlOrProduceWhiteBarClicks() throws Throwable {
        NativeBottom bottom = addBottom(8);
        for (int tool : new int[]{PEN, ERASER}) {
            long down = tool == PEN ? 100 : 200;
            assertEquals(StylusBackGestureHook.DROP, dispatch(MotionEvent.ACTION_DOWN, down, 3, tool));
            assertEquals(StylusBackGestureHook.DROP, dispatch(MotionEvent.ACTION_MOVE, down, 3, tool));
            assertEquals(StylusBackGestureHook.DROP, dispatch(MotionEvent.ACTION_UP, down, 3, tool));
        }
        assertEquals(0, model.downs); assertEquals(0, monitor.pilfers);
        assertEquals(0, bottomBar(bottom).clicks);
        assertEquals(24, ((WindowManager.LayoutParams) value(bottom, "mDecorationLayoutParams")).flags);
    }

    @Test public void bottomFingerTapsSelectTheActualBottomAndPreserveTheNativeThreeClickSequence() throws Throwable {
        NativeBottom bottom = addBottom(8);
        for (long down : new long[]{100, 200, 300}) {
            assertEquals(StylusBackGestureHook.PASS, dispatch(MotionEvent.ACTION_DOWN, down, 3, FINGER));
            Map<?, ?> presses = (Map<?, ?>) value(hook, "presses");
            assertSame("A bottom result must not borrow the top caption's hit predicate", bottom, value(presses.get(model), "window"));
            assertEquals(StylusBackGestureHook.PASS, dispatch(MotionEvent.ACTION_UP, down, 3, FINGER));
        }
        assertEquals(3, monitor.pilfers);
        assertEquals("Forward one native View click per tap, leaving the triple-click listener in charge", 3, bottomBar(bottom).clicks);
        assertEquals(0, model.cancels);
    }

    @Test public void bottomDraggingAndCancelledOrMixedStreamsDoNotProduceAWhiteBarClick() throws Throwable {
        NativeBottom bottom = addBottom(8);
        dispatch(MotionEvent.ACTION_DOWN, 100, 3, FINGER);
        model.mMultiTaskingTouchStatus.dragging = true;
        dispatch(MotionEvent.ACTION_UP, 100, 3, FINGER);
        model.mMultiTaskingTouchStatus.dragging = false;
        dispatch(MotionEvent.ACTION_DOWN, 200, 3, FINGER);
        dispatch(MotionEvent.ACTION_CANCEL, 200, 3, FINGER);
        dispatch(MotionEvent.ACTION_DOWN, 300, 3, FINGER);
        dispatch(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 300, 3, FINGER, PEN);
        dispatch(MotionEvent.ACTION_UP, 300, 3, FINGER);
        assertEquals(0, bottomBar(bottom).clicks);
        assertTrue(((Map<?, ?>) value(hook, "presses")).isEmpty());
    }

    @Test public void idleAndAlreadyNativeCapturedBottomTouchesDoNotAcquireModuleClicksOrStoppingRights() throws Throwable {
        NativeBottom bottom = addBottom(8);
        model.startsImmersive = true;
        dispatch(MotionEvent.ACTION_DOWN, 100, 3, FINGER);
        dispatch(MotionEvent.ACTION_UP, 100, 3, FINGER);
        assertEquals(0, monitor.pilfers); assertEquals(0, bottomBar(bottom).clicks);
        model.startsImmersive = false;
        MotionEvent input = event(MotionEvent.ACTION_DOWN, 200, 3, FINGER);
        try {
            assertFalse(hook.before(model, input, monitor));
            model.handleReceivedMotionEvent(input, monitor, false);
            invoke(hook, "recordFreeformTouch", new Class<?>[]{Object.class, MotionEvent.class, Object.class, Object.class},
                    model.mMiuiFreeformModeController, input, monitor, model.mMiuiFreeformModeController.last);
            monitor.pilferPointers();
            invoke(hook, "recordNativeCapture", new Class<?>[]{Object.class}, monitor);
            hook.after(model, input, monitor, StylusBackGestureHook.PASS, true);
        } finally { input.recycle(); }
        dispatch(MotionEvent.ACTION_UP, 200, 3, FINGER);
        assertEquals(1, monitor.pilfers); assertEquals(0, bottomBar(bottom).clicks);
        assertEquals(0, model.cancels);
    }

    @Test public void aBottomHotAreaResultOutsideTheActualInputWindowDoesNotCaptureTheApplicationTouch() throws Throwable {
        NativeBottom bottom = addBottom(8);
        MotionEvent input = event(MotionEvent.ACTION_DOWN, 100, 3, FINGER);
        input.setLocation(500, 20);
        try {
            assertFalse(hook.before(model, input, monitor));
            model.handleReceivedMotionEvent(input, monitor, false);
            invoke(hook, "recordFreeformTouch", new Class<?>[]{Object.class, MotionEvent.class, Object.class, Object.class},
                    model.mMiuiFreeformModeController, input, monitor, model.mMiuiFreeformModeController.last);
            hook.after(model, input, monitor, StylusBackGestureHook.PASS, true);
        } finally { input.recycle(); }
        assertEquals(0, monitor.pilfers);
        assertTrue(((Map<?, ?>) value(hook, "presses")).isEmpty());
        dispatch(MotionEvent.ACTION_UP, 100, 3, FINGER);
        assertEquals(0, bottomBar(bottom).clicks);
    }

    @Test public void animationAndResizeOrForeignTaskResultsCannotCreateABottomCapture() throws Throwable {
        NativeBottom bottom = addBottom(8);
        model.mMiuiFreeformModeController.info.animating = true;
        dispatch(MotionEvent.ACTION_DOWN, 100, 3, FINGER);
        dispatch(MotionEvent.ACTION_UP, 100, 3, FINGER);
        model.mMiuiFreeformModeController.info.animating = false;
        model.mMiuiFreeformModeController.mode = 3;
        dispatch(MotionEvent.ACTION_DOWN, 200, 3, FINGER);
        dispatch(MotionEvent.ACTION_UP, 200, 3, FINGER);
        model.mMiuiFreeformModeController.mode = 2;
        model.mMiuiFreeformModeController.info.mTaskId = 99;
        dispatch(MotionEvent.ACTION_DOWN, 300, 3, FINGER);
        dispatch(MotionEvent.ACTION_UP, 300, 3, FINGER);
        assertEquals(0, monitor.pilfers); assertEquals(0, bottomBar(bottom).clicks);
        assertTrue(((Map<?, ?>) value(hook, "presses")).isEmpty());
    }

    @Test public void bottomWindowHitUsesItsStableDestinationScale() throws Throwable {
        NativeBottom bottom = addBottom(8);
        model.mMiuiFreeformModeController.info.mDestinationScaleX = 2f;
        MotionEvent input = event(MotionEvent.ACTION_DOWN, 100, 3, FINGER);
        input.setLocation(250, 60); // Inside the scaled window, outside its unscaled 200 x 40 footprint.
        try {
            assertFalse(hook.before(model, input, monitor));
            model.handleReceivedMotionEvent(input, monitor, false);
            invoke(hook, "recordFreeformTouch", new Class<?>[]{Object.class, MotionEvent.class, Object.class, Object.class},
                    model.mMiuiFreeformModeController, input, monitor, model.mMiuiFreeformModeController.last);
            hook.after(model, input, monitor, StylusBackGestureHook.PASS, true);
        } finally { input.recycle(); }
        assertEquals(1, monitor.pilfers);
        assertSame(bottom, value(((Map<?, ?>) value(hook, "presses")).get(model), "window"));
        dispatch(MotionEvent.ACTION_UP, 100, 3, FINGER);
        assertEquals(1, bottomBar(bottom).clicks);
    }

    @Test public void closingBothWindowsRestoresOnlyModuleAddedBitsAndPreservesNativeBottomFlags() throws Throwable {
        NativeBottom bottom = addBottom(24);
        model.freeformHit = false; model.splitBottomHit = true;
        dispatch(MotionEvent.ACTION_DOWN, 100, 3, FINGER);
        dispatch(MotionEvent.ACTION_UP, 100, 3, FINGER);
        assertEquals(0, monitor.pilfers); assertEquals(0, bottomBar(bottom).clicks);
        assertThrows(IllegalArgumentException.class, () -> invoke(hook, "close", new Class<?>[]{}));
        assertEquals(8, ((WindowManager.LayoutParams) value(top, "mDecorationLayoutParams")).flags);
        assertEquals("Split/Home native NOT_TOUCHABLE does not come from immersion alone", 24,
                ((WindowManager.LayoutParams) value(bottom, "mDecorationLayoutParams")).flags);
    }

    @Test public void disablingBottomRoutingAndClosingACapturedBottomPressUseTheNativeCleanupPath() throws Throwable {
        NativeBottom bottom = addBottom(8);
        Object bottomWindow = invoke(hook, "observeWindow", new Class<?>[]{Object.class}, bottom);
        invoke(hook, "apply", new Class<?>[]{bottomWindow.getClass(), boolean.class}, bottomWindow, false);
        assertEquals(8, ((WindowManager.LayoutParams) value(bottom, "mDecorationLayoutParams")).flags);
        assertEquals(8, ((NativeViewHost) value(bottom, "mViewHost")).appliedFlags);
        ((WindowManager.LayoutParams) value(bottom, "mDecorationLayoutParams")).flags =
                ((CaptionWindowTransaction) value(bottomWindow, "flags")).effective(8, true);
        dispatch(MotionEvent.ACTION_DOWN, 100, 3, FINGER);
        assertThrows(IllegalArgumentException.class, () -> invoke(hook, "close", new Class<?>[]{}));
        assertEquals(1, model.cancels); assertEquals(0, bottomBar(bottom).clicks);
        assertEquals(8, ((WindowManager.LayoutParams) value(bottom, "mDecorationLayoutParams")).flags);
        assertTrue(((Map<?, ?>) value(hook, "presses")).isEmpty());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test public void aMissingBottomFieldDoesNotDisableTheVerifiedTopCaptionAdapter() throws Throwable {
        Map<String, Class<?>> older = new java.util.HashMap<>(schema);
        older.put(PREFIX + "MiuiWindowDecoration", DecorationWithoutBottom.class);
        CaptionInputHook legacy = makeHook(older);
        assertNull(value(legacy, "bottom"));
        field(CaptionInputHook.class, "operational").setBoolean(legacy, true);
        ((AtomicBoolean) value(legacy, "contextStarted")).set(true);
        ((SparseArray) model.mWindowDecorByTaskId).put(7, new DecorationWithoutBottom(top));
        Object topWindow = invoke(legacy, "observeWindow", new Class<?>[]{Object.class}, top);
        Object observed = value(topWindow, "model");
        ((AtomicBoolean) value(observed, "queued")).set(false);
        model.mMainHandler.removeCallbacksAndMessages(value(legacy, "callbacks"));
        ((CaptionRoutingState) value(observed, "routing")).update(0, true, () -> { });
        ((CaptionWindowTransaction) value(topWindow, "flags")).effective(8, true);
        MotionEvent input = event(MotionEvent.ACTION_DOWN, 100, 3, PEN);
        try {
            assertFalse(legacy.before(model, input, monitor));
            assertTrue((Boolean) invoke(legacy, "selected", new Class<?>[]{MotionEvent.class}, input));
            legacy.after(model, input, monitor, StylusBackGestureHook.DROP, false);
            assertEquals(0, model.downs); assertEquals(0, monitor.pilfers);
            assertThrows(IllegalArgumentException.class, () -> invoke(legacy, "close", new Class<?>[]{}));
        } finally {
            input.recycle();
            model.mMainHandler.removeCallbacksAndMessages(value(legacy, "callbacks"));
            model.mWindowDecorByTaskId.put(7, new NativeDecoration(top));
        }
    }

    @Test public void penAndEraserDoNotStartNativeCaptionControlOrPilferTheApplicationStream() throws Throwable {
        for (int tool : new int[]{PEN, ERASER}) {
            long down = tool == PEN ? 100 : 200;
            assertEquals(StylusBackGestureHook.DROP, dispatch(MotionEvent.ACTION_DOWN, down, 3, tool));
            assertEquals(StylusBackGestureHook.DROP, dispatch(MotionEvent.ACTION_UP, down, 3, tool));
        }
        assertEquals(0, model.downs); assertEquals(0, model.clicks);
        assertEquals(0, monitor.pilfers);
        assertEquals(24, ((WindowManager.LayoutParams) value(top, "mDecorationLayoutParams")).flags);
    }

    @Test public void solidFingerDownCapturesOnceAndItsNativeControllerClickStillCompletes() throws Throwable {
        assertEquals(StylusBackGestureHook.PASS, dispatch(MotionEvent.ACTION_DOWN, 100, 3, FINGER));
        assertEquals(1, monitor.pilfers);
        assertEquals(StylusBackGestureHook.PASS, dispatch(MotionEvent.ACTION_UP, 100, 3, FINGER));
        assertEquals(1, monitor.pilfers); assertEquals(1, model.clicks);
        assertEquals(0, model.cancels);
    }

    @Test public void aNativeDownCaptureIsNotPilferedAgainOrCancelledAsModuleOwnedWork() throws Throwable {
        MotionEvent input = event(MotionEvent.ACTION_DOWN, 100, 3, FINGER);
        try {
            assertFalse(hook.before(model, input, monitor));
            assertTrue(selected(input));
            model.handleReceivedMotionEvent(input, monitor, false);
            // This is the ROM's own pilfer, recorded by the scoped monitor hook before observer completion.
            monitor.pilferPointers();
            invoke(hook, "recordNativeCapture", new Class<?>[]{Object.class}, monitor);
            hook.after(model, input, monitor, StylusBackGestureHook.PASS, true);
            assertEquals(1, monitor.pilfers);
            assertTrue(((Map<?, ?>) value(hook, "presses")).isEmpty());
            invoke(hook, "releaseWindow", new Class<?>[]{Object.class}, top);
            assertEquals("Native stopping rights must remain native", 0, model.cancels);
            assertEquals(1, monitor.pilfers);
        } finally { input.recycle(); }
    }

    @Test public void theFirstFingerTapOnAnIdleCaptionKeepsItsNativeWakeupBehavior() throws Throwable {
        model.startsImmersive = true;
        dispatch(MotionEvent.ACTION_DOWN, 100, 3, FINGER);
        dispatch(MotionEvent.ACTION_UP, 100, 3, FINGER);
        assertEquals(1, model.downs); assertEquals(1, model.ups);
        assertEquals(0, monitor.pilfers); assertEquals(0, model.clicks);
    }

    @Test public void fingerInputOutsideTheCaptionIsNotCaptured() throws Throwable {
        top.hit = false; model.nativeHit = false;
        dispatch(MotionEvent.ACTION_DOWN, 100, 3, FINGER);
        dispatch(MotionEvent.ACTION_UP, 100, 3, FINGER);
        assertEquals(0, monitor.pilfers); assertEquals(0, model.clicks);
        assertEquals(1, model.downs); assertEquals(1, model.ups);
    }

    @Test public void aNewPressCancelsTheOldCaptureBeforeNativeDownAndItsOldTailCannotClickTheNewControl() throws Throwable {
        dispatch(MotionEvent.ACTION_DOWN, 100, 3, FINGER);
        dispatch(MotionEvent.ACTION_DOWN, 200, 4, FINGER);
        assertEquals(List.of("down:100", "cancel:100", "down:200"), model.order);
        assertEquals(StylusBackGestureHook.DROP, dispatch(MotionEvent.ACTION_UP, 100, 3, FINGER));
        assertEquals(0, model.ups); assertEquals(0, model.clicks);
        assertEquals(StylusBackGestureHook.PASS, dispatch(MotionEvent.ACTION_UP, 200, 4, FINGER));
        assertEquals(1, model.clicks); assertEquals(2, monitor.pilfers);
    }

    @Test public void failedNativeCancelKeepsTheCapturePendingAndALaterEventRetriesWithoutNativePenControl() throws Throwable {
        dispatch(MotionEvent.ACTION_DOWN, 100, 3, FINGER);
        MotionEvent joined = event(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                100, 3, FINGER, PEN);
        try {
            hook.before(model, joined, monitor);
            assertEquals(StylusBackGestureHook.CANCEL_AND_DROP, gate.decide(model, joined, selected(joined)));
            model.failCancel = true;
            assertThrows(IllegalStateException.class,
                    () -> hook.after(model, joined, monitor, StylusBackGestureHook.CANCEL_AND_DROP, false));
            Map<?, ?> captures = (Map<?, ?>) value(hook, "presses");
            assertEquals(1, captures.size());
            assertTrue((Boolean) value(captures.get(model), "pending"));
        } finally { joined.recycle(); }
        model.failCancel = false;
        assertEquals(StylusBackGestureHook.DROP, dispatch(MotionEvent.ACTION_MOVE, 100, 3, PEN));
        assertEquals(1, model.cancels); assertEquals(1, model.downs);
        assertEquals(0, model.clicks);
        assertTrue(((Map<?, ?>) value(hook, "presses")).isEmpty());
        assertEquals(StylusBackGestureHook.DROP, dispatch(MotionEvent.ACTION_UP, 100, 3, PEN));
    }

    @Test public void failedWindowReleaseKeepsItsCaptureAndCanBeRetried() throws Throwable {
        dispatch(MotionEvent.ACTION_DOWN, 100, 3, FINGER);
        model.failCancel = true;
        assertThrows(IllegalStateException.class,
                () -> invoke(hook, "releaseWindow", new Class<?>[]{Object.class}, top));
        assertEquals(1, ((Map<?, ?>) value(hook, "presses")).size());
        assertEquals(1, ((Map<?, ?>) value(hook, "windows")).size());
        model.failCancel = false;
        invoke(hook, "releaseWindow", new Class<?>[]{Object.class}, top);
        assertEquals(1, model.cancels); assertEquals(0, model.clicks);
        assertTrue(((Map<?, ?>) value(hook, "presses")).isEmpty());
        assertTrue(((Map<?, ?>) value(hook, "windows")).isEmpty());
    }

    @Test public void unknownReceiversAndMonitorsDoNotAcquireThePublishedDisplayPolicy() throws Throwable {
        MotionEvent input = event(MotionEvent.ACTION_DOWN, 100, 3, PEN);
        try {
            NativeModel unknown = new DerivedModel(RuntimeEnvironment.getApplication());
            assertFalse(hook.before(unknown, input, monitor));
            assertFalse(selected(input));
            hook.after(unknown, input, monitor, StylusBackGestureHook.PASS, true);
            NativeMonitor unknownMonitor = new NativeMonitor();
            assertFalse(hook.before(model, input, unknownMonitor));
            assertFalse(selected(input));
            hook.after(model, input, unknownMonitor, StylusBackGestureHook.PASS, true);
            Object wrongType = new Object();
            assertFalse(hook.before(model, input, wrongType));
            assertFalse(selected(input));
            hook.after(model, input, wrongType, StylusBackGestureHook.PASS, true);
            assertEquals(0, monitor.pilfers);
        } finally { input.recycle(); }
    }

    @Test public void anUnsupportedNestedCallCannotInheritItsOuterCallsRoutingDecision() throws Throwable {
        MotionEvent input = event(MotionEvent.ACTION_DOWN, 100, 3, PEN);
        try {
            assertFalse(hook.before(model, input, monitor));
            assertTrue(selected(input));
            NativeMonitor unrelated = new NativeMonitor();
            assertFalse(hook.before(model, input, unrelated));
            assertFalse("Reusing the same MotionEvent must not bypass monitor validation", selected(input));
            hook.after(model, input, unrelated, StylusBackGestureHook.PASS, true);
            assertTrue("The outer validated input regains its own decision", selected(input));
            NativeModel unknown = new DerivedModel(RuntimeEnvironment.getApplication());
            assertFalse(hook.before(unknown, input, monitor));
            assertFalse(selected(input));
            hook.after(unknown, input, monitor, StylusBackGestureHook.PASS, true);
            assertTrue(selected(input));
            hook.after(model, input, monitor, StylusBackGestureHook.DROP, false);
            assertFalse("The completed outer call must leave no routing frame", selected(input));
            assertEquals(0, model.downs); assertEquals(0, monitor.pilfers);
        } finally { input.recycle(); }
        dispatch(MotionEvent.ACTION_UP, 100, 3, PEN);
    }

    @Test public void closeCancelsCapturedNativeWorkAndRestoresFlagsBeforeRejectingModuleLoadedVendorFixtures() throws Throwable {
        dispatch(MotionEvent.ACTION_DOWN, 100, 3, FINGER);
        assertSame(ModuleSession.class.getClassLoader(), NativeModel.class.getClassLoader());
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> invoke(hook, "close", new Class<?>[]{}));
        assertTrue(rejected.getMessage().contains("Module objects"));
        assertEquals(1, model.cancels); assertEquals(0, model.clicks);
        assertEquals(8, ((WindowManager.LayoutParams) value(top, "mDecorationLayoutParams")).flags);
        assertEquals(8, ((NativeViewHost) value(top, "mViewHost")).appliedFlags);
        assertTrue(((Map<?, ?>) value(hook, "presses")).isEmpty());
    }
}
