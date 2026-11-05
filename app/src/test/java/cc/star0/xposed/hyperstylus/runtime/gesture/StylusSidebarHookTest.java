package cc.star0.xposed.hyperstylus.runtime.gesture;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import cc.star0.xposed.hyperstylus.config.ConfigBridge;
import cc.star0.xposed.hyperstylus.config.EnhanceConfig;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** Exercise actual registration and managed dispatch hooks for the cover and its native line sibling. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class StylusSidebarHookTest {
    private static final int FINGER = MotionEvent.TOOL_TYPE_FINGER, PEN = MotionEvent.TOOL_TYPE_STYLUS,
            ERASER = MotionEvent.TOOL_TYPE_ERASER;

    /** The cover delegates to the line's dispatcher, as the supplied APK does. */
    private static final class NativeCover extends View {
        NativeCover(Context context, Framework framework, NativeLine line) {
            super(context);
            setOnTouchListener((view, event) -> framework.dispatchUnchecked(line, "dispatchTouchEvent", event));
            setOnGenericMotionListener((view, event) -> framework.dispatchUnchecked(line, "dispatchGenericMotionEvent", event));
            setLayoutParams(new NativeParams(2026, 32));
        }
    }

    private static final class NativeLine extends View {
        final Framework framework;
        NativeTouch touch;
        int unassociatedTouches, genericCalls, superTouchCalls;
        NativeLine(Context context, Framework framework) {
            super(context); this.framework = framework;
            setOnTouchListener((view, event) -> {
                if (touch == null) { unassociatedTouches++; return true; }
                return touch.nativeTouch(event);
            });
            setOnGenericMotionListener((view, event) -> { genericCalls++; return true; });
        }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            // The real Region override calls View.super: that globally hooked method must also run.
            superTouchCalls++;
            return framework.superDispatchUnchecked(this, "dispatchTouchEvent", event,
                    (receiver, arguments) -> super.dispatchTouchEvent((MotionEvent) arguments[0]));
        }
        @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
            return framework.superDispatchUnchecked(this, "dispatchGenericMotionEvent", event,
                    (receiver, arguments) -> super.dispatchGenericMotionEvent((MotionEvent) arguments[0]));
        }
    }

    private static final class NativeTouch {
        private boolean n;
        private final View q;
        private final Runnable D;
        private final VelocityTracker g = VelocityTracker.obtain();
        private long h;
        final NativeWrapper wrapper;
        int downs, moves, ups, cancels, delayedRuns, idleCalls, movingCleanup, finishCalls;
        NativeTouch(NativeWrapper wrapper, NativeLine line) {
            this.wrapper = wrapper; q = line; D = () -> delayedRuns++;
        }
        boolean nativeTouch(MotionEvent event) {
            g.addMovement(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    downs++; h = event.getDownTime(); n = false; q.postDelayed(D, 300);
                }
                case MotionEvent.ACTION_MOVE -> { if (!n) moves++; }
                case MotionEvent.ACTION_UP -> {
                    ups++;
                    if (!n) { q.removeCallbacks(D); wrapper.opens++; }
                }
                case MotionEvent.ACTION_CANCEL -> {
                    cancels++;
                    // The APK deliberately shares UP/CANCEL settling, without checking the stream ID.
                    if (!n) { q.removeCallbacks(D); wrapper.opens++; }
                }
                default -> { }
            }
            return true;
        }
        private void v() { movingCleanup++; }
        private void u() { finishCalls++; }
    }

    private static final class NativeWrapper {
        private final NativeTouch u;
        final NativeCover cover;
        final NativeLine line;
        final FrameLayout panel;
        int opens;
        NativeWrapper(Context context, Framework framework) {
            line = new NativeLine(context, framework);
            panel = new FrameLayout(context);
            panel.setLayoutParams(new NativeParams(2003, 32));
            panel.addView(line, new FrameLayout.LayoutParams(40, 100));
            u = new NativeTouch(this, line); line.touch = u;
            cover = new NativeCover(context, framework, line);
        }
        public NativeCover w() { return cover; }
        public NativeLine y() { return line; }
        public FrameLayout B() { return panel; }
        public void P() { u.idleCalls++; }
    }

    private static final class NativeManager { private NativeWrapper e; }
    private static final class NativeService {
        private NativeManager d;
        public void onCreate() { }
    }

    private static final class NativeParams extends WindowManager.LayoutParams {
        public int miuiFlags;
        NativeParams(int type, int flags) { this.type = type; miuiFlags = flags; }
    }

    /** Capture the installed hookers and run their real chains; ORIGIN only skips that method's hook. */
    private static final class Framework {
        interface NativeCall { Object run(Object receiver, Object[] arguments) throws Throwable; }
        final Map<Executable, XposedInterface.Hooker> hooks = new HashMap<>();
        final List<String> errors = new ArrayList<>();
        final List<Method> origins = new ArrayList<>();
        XposedInterface api() {
            return (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "hook" -> builder((Executable) args[0]);
                            case "getInvoker" -> invoker((Method) args[0]);
                            case "log" -> { if (args.length == 4) errors.add((String) args[2]); yield null; }
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }
        private XposedInterface.HookBuilder builder(Executable executable) {
            return (XposedInterface.HookBuilder) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.HookBuilder.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("intercept")) return proxy;
                        assertNull("Each declaration should be installed once", hooks.put(executable, (XposedInterface.Hooker) args[0]));
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookHandle.class},
                                (handle, call, values) -> switch (call.getName()) {
                                    case "getExecutable" -> executable;
                                    case "unhook" -> { hooks.remove(executable); yield null; }
                                    case "getId" -> null;
                                    default -> throw new UnsupportedOperationException(call.getName());
                                });
                    });
        }
        private Object invoker(Method target) {
            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.Invoker.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("setType")) {
                            assertEquals(XposedInterface.Invoker.Type.ORIGIN, args[0]); return proxy;
                        }
                        if (method.getName().equals("invoke")) {
                            origins.add(target);
                            return original(target, args[0], (Object[]) args[1]);
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
        Object call(Method method, Object owner, Object... arguments) throws Throwable {
            return callWithOriginal(method, owner, arguments, (receiver, args) -> original(method, receiver, args));
        }
        Object callWithOriginal(Method method, Object owner, Object[] arguments, NativeCall nativeCall) throws Throwable {
            XposedInterface.Hooker installed = hooks.get(method);
            if (installed == null) return nativeCall.run(owner, arguments);
            return installed.intercept(new XposedInterface.Chain() {
                @Override public Executable getExecutable() { return method; }
                @Override public Object getThisObject() { return owner; }
                @Override public List<Object> getArgs() { return List.of(arguments); }
                @Override public Object getArg(int index) { return arguments[index]; }
                @Override public Object proceed() throws Throwable { return nativeCall.run(owner, arguments); }
                @Override public Object proceed(Object[] args) throws Throwable { return nativeCall.run(owner, args); }
                @Override public Object proceedWith(Object receiver) throws Throwable { return nativeCall.run(receiver, arguments); }
                @Override public Object proceedWith(Object receiver, Object[] args) throws Throwable { return nativeCall.run(receiver, args); }
            });
        }
        boolean dispatch(View view, String name, MotionEvent event) throws Throwable {
            return (Boolean) call(view.getClass().getMethod(name, MotionEvent.class), view, event);
        }
        boolean dispatchUnchecked(View view, String name, MotionEvent event) {
            try { return dispatch(view, name, event); }
            catch (Throwable failure) { throw new AssertionError("Native delegate failed", failure); }
        }
        boolean superDispatchUnchecked(View view, String name, MotionEvent event, NativeCall nativeCall) {
            try {
                return (Boolean) callWithOriginal(View.class.getMethod(name, MotionEvent.class), view,
                        new Object[]{event}, nativeCall);
            } catch (Throwable failure) { throw new AssertionError("Native superclass dispatch failed", failure); }
        }
        private static Object original(Method method, Object owner, Object[] args) throws Throwable {
            method.setAccessible(true);
            try { return method.invoke(owner, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }

    private FocusStylusModule module;
    private StylusSidebarHook hook;
    private Framework framework;
    private NativeWrapper wrapper;
    private EnhanceConfig previousConfig;

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Map<String, Class<?>> schema() {
        return Map.of("com.miui.dock.sidebar.p", NativeWrapper.class,
                "com.miui.dock.sidebar.e", NativeCover.class,
                "com.miui.dock.sidebar.RegionSamplingImageView", NativeLine.class,
                "com.miui.dock.sidebar.k", NativeTouch.class,
                "com.miui.gamebooster.service.DockWindowManagerService", NativeService.class,
                "vb.l0", NativeManager.class);
    }
    private StylusSidebarHook newHook(FocusStylusModule generation) throws Throwable {
        return new StylusSidebarHook(generation, getClass().getClassLoader(), name -> {
            Class<?> type = schema().get(name);
            if (type == null) throw new ClassNotFoundException(name);
            return type;
        });
    }
    private void enabled(boolean enabled) throws ReflectiveOperationException {
        Bundle values = EnhanceConfig.defaults().toBundle(); values.putBoolean("ignore_stylus_sidebar", enabled);
        field(ConfigBridge.class, "current").set(null, new EnhanceConfig(values));
    }
    @Before public void setup() throws Throwable {
        previousConfig = ConfigBridge.current(); enabled(true);
        framework = new Framework(); module = new FocusStylusModule(); module.attachFramework(framework.api(), () -> { });
        hook = newHook(module); hook.install();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        wrapper = new NativeWrapper(activity, framework); activity.setContentView(wrapper.panel);
        assertNotNull(wrapper.line.getHandler());
        // A late-loaded wrapper is discovered by its actual line getter, without replaying its constructor.
        framework.call(NativeWrapper.class.getDeclaredMethod("y"), wrapper);
        assertTrue(framework.errors.isEmpty());
    }
    @After public void cleanup() throws Throwable {
        if (wrapper != null) { wrapper.line.removeCallbacks(wrapper.u.D); wrapper.u.g.recycle(); }
        // Vendor stand-ins use the test/module loader, so session validation properly rejects saving them.
        if (module != null) module.session().close(2_000);
        field(ConfigBridge.class, "current").set(null, previousConfig);
    }
    private static MotionEvent event(int action, long down, int... tools) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[tools.length];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[tools.length];
        for (int i = 0; i < tools.length; i++) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = i; properties[i].toolType = tools[i];
            coordinates[i] = new MotionEvent.PointerCoords(); coordinates[i].x = 10 + i; coordinates[i].y = 10;
            coordinates[i].pressure = 1;
        }
        return MotionEvent.obtain(down, down + 20, action, tools.length, properties, coordinates,
                0, 0, 1, 1, 3, 0, InputDevice.SOURCE_STYLUS | InputDevice.SOURCE_TOUCHSCREEN, 0);
    }
    private boolean send(View owner, int action, long down, int... tools) throws Throwable {
        MotionEvent event = event(action, down, tools);
        try { return framework.dispatch(owner, "dispatchTouchEvent", event); }
        finally { event.recycle(); }
    }
    private boolean generic(View owner, int action, long down, int tool) throws Throwable {
        MotionEvent event = event(action, down, tool);
        try { return framework.dispatch(owner, "dispatchGenericMotionEvent", event); }
        finally { event.recycle(); }
    }

    @Test public void fingerOpeningThroughCoverDoesNotLeaveTheNativeLineAbleToReopenWithPen() throws Throwable {
        assertTrue(send(wrapper.cover, MotionEvent.ACTION_DOWN, 100, FINGER));
        assertTrue(send(wrapper.cover, MotionEvent.ACTION_UP, 100, FINGER));
        assertEquals(1, wrapper.opens); assertEquals(1, wrapper.u.downs);
        for (int tool : new int[]{PEN, ERASER}) {
            long down = tool == PEN ? 200 : 300;
            assertTrue(send(wrapper.line, MotionEvent.ACTION_DOWN, down, tool));
            assertTrue(send(wrapper.line, MotionEvent.ACTION_MOVE, down, tool));
            assertTrue(send(wrapper.line, MotionEvent.ACTION_UP, down, tool));
        }
        assertEquals("The line inside the expanded panel cannot launch another opening", 1, wrapper.opens);
        assertEquals(1, wrapper.u.downs); assertEquals(1, wrapper.u.ups);
        assertEquals(0, wrapper.u.moves); assertEquals(0, wrapper.u.cancels);
        assertTrue(framework.errors.isEmpty());
    }

    @Test public void lineDiscoveryRegistersBothConsumersAndTheirActualDistinctWindows() throws Throwable {
        Map<?, ?> views = (Map<?, ?>) field(StylusSidebarHook.class, "views").get(hook);
        assertEquals(2, views.size());
        assertTrue(views.containsKey(wrapper.cover)); assertTrue(views.containsKey(wrapper.line));
        assertFalse("The full menu parent is not a filtered consumer", views.containsKey(wrapper.panel));
        Object lineBinding = views.get(wrapper.line), coverBinding = views.get(wrapper.cover);
        assertSame(wrapper.panel, ((java.lang.ref.WeakReference<?>) field(lineBinding.getClass(), "window").get(lineBinding)).get());
        assertSame(wrapper.cover, ((java.lang.ref.WeakReference<?>) field(coverBinding.getClass(), "window").get(coverBinding)).get());
        assertTrue(framework.hooks.containsKey(NativeLine.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class)));
        assertTrue(framework.hooks.containsKey(NativeLine.class.getDeclaredMethod("dispatchGenericMotionEvent", MotionEvent.class)));
    }

    @Test public void expandedApplicationMenuSiblingRetainsPenClicksAndMoves() throws Throwable {
        send(wrapper.cover, MotionEvent.ACTION_DOWN, 100, FINGER); send(wrapper.cover, MotionEvent.ACTION_UP, 100, FINGER);
        FrameLayout menu = new FrameLayout(wrapper.panel.getContext());
        Button application = new Button(menu.getContext());
        int[] clicks = {0}, moves = {0};
        application.setOnClickListener(view -> clicks[0]++);
        application.setOnTouchListener((view, event) -> { if (event.getActionMasked() == MotionEvent.ACTION_MOVE) moves[0]++; return false; });
        menu.addView(application, new FrameLayout.LayoutParams(100, 100));
        wrapper.panel.addView(menu, new FrameLayout.LayoutParams(100, 100));
        // The application menu is above the native line, within the same unfiltered panel root.
        wrapper.panel.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
        wrapper.panel.layout(0, 0, 100, 100);
        assertTrue(send(wrapper.panel, MotionEvent.ACTION_DOWN, 200, PEN));
        assertTrue(send(wrapper.panel, MotionEvent.ACTION_MOVE, 200, PEN));
        assertTrue(send(wrapper.panel, MotionEvent.ACTION_UP, 200, PEN));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, clicks[0]); assertEquals(1, moves[0]); assertEquals(1, wrapper.opens);
        // The globally hooked View dispatcher also receives unrelated menu children directly.
        send(application, MotionEvent.ACTION_DOWN, 300, ERASER); send(application, MotionEvent.ACTION_UP, 300, ERASER);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, clicks[0]); assertEquals(1, wrapper.opens);
    }

    @Test public void nativeLineHoverIsFilteredWithoutFilteringUnregisteredInstances() throws Throwable {
        assertTrue(generic(wrapper.line, MotionEvent.ACTION_HOVER_ENTER, 100, PEN));
        assertTrue(generic(wrapper.line, MotionEvent.ACTION_HOVER_MOVE, 100, ERASER));
        assertEquals(0, wrapper.line.genericCalls);
        assertTrue(generic(wrapper.line, MotionEvent.ACTION_SCROLL, 200, MotionEvent.TOOL_TYPE_MOUSE));
        assertEquals(1, wrapper.line.genericCalls);
        NativeLine unrelated = new NativeLine(wrapper.panel.getContext(), framework);
        assertTrue(send(unrelated, MotionEvent.ACTION_DOWN, 300, PEN));
        assertTrue(generic(unrelated, MotionEvent.ACTION_HOVER_MOVE, 300, PEN));
        assertEquals(1, unrelated.unassociatedTouches); assertEquals(1, unrelated.genericCalls);
    }

    @Test public void disablingAllowsNativeLineAndCoverOperationsAndPreservesTheDownPolicy() throws Throwable {
        enabled(false);
        send(wrapper.line, MotionEvent.ACTION_DOWN, 100, PEN);
        enabled(true);
        send(wrapper.line, MotionEvent.ACTION_MOVE, 100, PEN); send(wrapper.line, MotionEvent.ACTION_UP, 100, PEN);
        assertEquals("An allowed native DOWN keeps its policy through UP", 1, wrapper.opens);
        assertEquals(1, wrapper.u.moves);
        send(wrapper.line, MotionEvent.ACTION_DOWN, 200, PEN);
        enabled(false);
        send(wrapper.line, MotionEvent.ACTION_UP, 200, PEN);
        assertEquals("Disabling cannot forward a tail whose DOWN was suppressed", 1, wrapper.opens);
        send(wrapper.cover, MotionEvent.ACTION_DOWN, 300, PEN); send(wrapper.cover, MotionEvent.ACTION_UP, 300, PEN);
        assertEquals(2, wrapper.opens); assertEquals(2, wrapper.u.downs);
    }

    @Test public void redirectIsSubmittedOnlyForTheActualCoverAndNeverForTheLineInThePanel() throws Throwable {
        List<Integer> redirects = new ArrayList<>();
        Field flags = NativeParams.class.getField("miuiFlags"); flags.setAccessible(true);
        field(StylusSidebarHook.class, "redirect").set(hook,
                new SidebarMotionRedirect(event -> 47, flags, redirects::add));
        send(wrapper.line, MotionEvent.ACTION_DOWN, 100, PEN); send(wrapper.line, MotionEvent.ACTION_UP, 100, PEN);
        assertTrue(redirects.isEmpty());
        // Even a later unusual panel parameter must not turn a line event into a cover redirect.
        wrapper.panel.setLayoutParams(new NativeParams(2026, 32));
        send(wrapper.line, MotionEvent.ACTION_DOWN, 200, PEN); send(wrapper.line, MotionEvent.ACTION_UP, 200, PEN);
        assertTrue(redirects.isEmpty());
        send(wrapper.cover, MotionEvent.ACTION_DOWN, 300, PEN);
        send(wrapper.cover, MotionEvent.ACTION_DOWN, 300, PEN);
        send(wrapper.cover, MotionEvent.ACTION_UP, 300, PEN);
        assertEquals(List.of(47), redirects);
        assertEquals(0, wrapper.u.downs); assertEquals(0, wrapper.opens);
    }

    @Test public void mixedCoverStreamCancelsItsDelegatedNativeLineOnceAndCannotReopenIt() throws Throwable {
        send(wrapper.cover, MotionEvent.ACTION_DOWN, 100, FINGER);
        assertEquals(1, wrapper.u.downs);
        send(wrapper.cover, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100, FINGER, PEN);
        send(wrapper.cover, MotionEvent.ACTION_UP, 100, FINGER);
        assertEquals(1, wrapper.u.cancels); assertEquals(1, wrapper.u.idleCalls);
        assertEquals(1, wrapper.u.movingCleanup); assertEquals(1, wrapper.u.finishCalls);
        assertEquals(0, wrapper.opens);
        send(wrapper.line, MotionEvent.ACTION_DOWN, 200, PEN);
        send(wrapper.line, MotionEvent.ACTION_CANCEL, 200, PEN);
        send(wrapper.line, MotionEvent.ACTION_UP, 200, FINGER);
        assertEquals("Redirect or tool-changing tails cannot cancel the completed finger again", 1, wrapper.u.cancels);
        assertEquals(0, wrapper.opens);
        send(wrapper.cover, MotionEvent.ACTION_DOWN, 300, FINGER); send(wrapper.cover, MotionEvent.ACTION_UP, 300, FINGER);
        assertEquals(1, wrapper.opens); assertTrue(framework.errors.isEmpty());
    }

    @Test public void directLinePenJoiningDelegatedFingerWorkDoesNotDoubleCancelThroughCoverTail() throws Throwable {
        send(wrapper.cover, MotionEvent.ACTION_DOWN, 100, FINGER);
        send(wrapper.line, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100, FINGER, PEN);
        send(wrapper.cover, MotionEvent.ACTION_UP, 100, FINGER);
        assertEquals(1, wrapper.u.cancels); assertEquals(1, wrapper.u.idleCalls);
        assertEquals(0, wrapper.opens); assertEquals(0, wrapper.u.ups);
        assertTrue(framework.errors.isEmpty());
    }

    @Test public void lineSuperclassDispatchBypassesTheCoverHookDuringNativeCancellation() throws Throwable {
        assertTrue(framework.hooks.containsKey(View.class.getMethod("dispatchTouchEvent", MotionEvent.class)));
        send(wrapper.line, MotionEvent.ACTION_DOWN, 100, FINGER);
        send(wrapper.line, MotionEvent.ACTION_UP, 100, FINGER);
        assertEquals(2, wrapper.line.superTouchCalls); assertEquals(1, wrapper.opens);
        send(wrapper.line, MotionEvent.ACTION_DOWN, 200, FINGER);
        send(wrapper.line, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200, FINGER, PEN);
        send(wrapper.line, MotionEvent.ACTION_UP, 200, FINGER);
        assertEquals("The override enters super once for each native DOWN, UP or owned CANCEL", 4, wrapper.line.superTouchCalls);
        assertEquals("The superclass hook must not re-enter its pending cancellation", 1, wrapper.u.cancels);
        assertEquals(1, wrapper.u.idleCalls); assertEquals(1, wrapper.u.movingCleanup); assertEquals(1, wrapper.u.finishCalls);
        assertEquals(1, wrapper.opens); assertEquals(2, wrapper.u.downs); assertEquals(1, wrapper.u.ups);
        assertTrue("No recursive cancellation or stack overflow is hidden by the module's error path", framework.errors.isEmpty());
    }
}
