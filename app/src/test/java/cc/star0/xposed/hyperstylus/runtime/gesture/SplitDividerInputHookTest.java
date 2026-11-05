package cc.star0.xposed.hyperstylus.runtime.gesture;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
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
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Executes the installed hooks around the OTA's window/manager ABI, not a native InputDispatcher. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SplitDividerInputHookTest {
    static final class Divider extends View {
        Host mViewHost;
        Divider(Context context) { super(context); }
    }
    static final class Manager { Host mViewHost; Divider mDividerView; }
    static final class Root {
        public IBinder getInputToken() { return new Binder(); }
        public int getDisplayId() { return 0; }
    }
    static final class Host {
        Manager manager;
        View view;
        int deliveredFeatures, releases;
        boolean failSet, failLayout;
        public Manager getWindowlessWM() { return manager; }
        public Root getRootSurfaceControl() { return new Root(); }
        public void setView(View view, WindowManager.LayoutParams params) throws Throwable {
            this.view = view; view.setLayoutParams(params); deliveredFeatures = features(params);
            if (failSet) throw new IllegalStateException("setView failed");
        }
        public void relayout(WindowManager.LayoutParams params) throws Throwable {
            deliveredFeatures = features(params);
            if (failLayout) throw new IllegalStateException("relayout failed");
        }
        public void release() { releases++; }
    }
    private final Map<Executable, XposedInterface.Hooker> hooks = new HashMap<>();
    private final List<Host> hosts = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private SplitDividerInputHook hook;
    private EnhanceConfig previous;
    private Context context;
    private static Field field(Class<?> type, String name) throws Exception {
        Field value = type.getDeclaredField(name); value.setAccessible(true); return value;
    }
    private static int features(WindowManager.LayoutParams params) throws Exception {
        return field(WindowManager.LayoutParams.class, "inputFeatures").getInt(params);
    }
    private static WindowManager.LayoutParams params(int feature) throws Exception {
        WindowManager.LayoutParams value = new WindowManager.LayoutParams(); value.type = 2034; value.flags = 545521704;
        field(WindowManager.LayoutParams.class, "privateFlags").setInt(value, 536870976);
        field(WindowManager.LayoutParams.class, "inputFeatures").setInt(value, feature); return value;
    }
    private void enabled(boolean enabled) throws Exception {
        Bundle values = EnhanceConfig.defaults().toBundle(); values.putBoolean("ignore_stylus_split_divider", enabled);
        field(ConfigBridge.class, "current").set(null, new EnhanceConfig(values));
    }
    private XposedInterface framework() {
        return (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> {
            if (method.getName().equals("log")) { if (args.length == 4) errors.add(args[2] + ": " + args[3]); return null; }
            if (!method.getName().equals("hook")) throw new UnsupportedOperationException(method.getName());
            Executable target = (Executable) args[0];
            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookBuilder.class}, (builder, operation, values) -> {
                if (!operation.getName().equals("intercept")) return builder;
                hooks.put(target, (XposedInterface.Hooker) values[0]);
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookHandle.class},
                        (handle, call, ignored) -> call.getName().equals("getExecutable") ? target : null);
            });
        });
    }
    private Object call(Host host, String name, Class<?>[] types, Object... args) throws Throwable {
        Method target = Host.class.getDeclaredMethod(name, types); target.setAccessible(true);
        XposedInterface.Chain chain = new XposedInterface.Chain() {
            public Executable getExecutable() { return target; }
            public Object getThisObject() { return host; }
            public List<Object> getArgs() { return List.of(args); }
            public Object getArg(int index) { return args[index]; }
            public Object proceed() throws Throwable { return proceedWith(host, args); }
            public Object proceed(Object[] values) throws Throwable { return proceedWith(host, values); }
            public Object proceedWith(Object receiver) throws Throwable { return proceedWith(receiver, args); }
            public Object proceedWith(Object receiver, Object[] values) throws Throwable {
                try { return target.invoke(receiver, values); } catch (InvocationTargetException failure) { throw failure.getCause(); }
            }
        };
        return hooks.get(target).intercept(chain);
    }
    private Host window() {
        Host host = new Host(); host.manager = new Manager(); host.manager.mViewHost = host;
        host.manager.mDividerView = new Divider(context); host.manager.mDividerView.mViewHost = host;
        hosts.add(host); return host;
    }
    private void setView(Host host, WindowManager.LayoutParams params) throws Throwable {
        call(host, "setView", new Class<?>[]{View.class, WindowManager.LayoutParams.class}, host.manager.mDividerView, params);
    }
    private void refresh(Host host) throws Throwable {
        Map<?, ?> windows = (Map<?, ?>) field(SplitDividerInputHook.class, "windows").get(hook);
        Object window = windows.get(host.manager.mDividerView);
        Method method = window.getClass().getDeclaredMethod("refresh"); method.setAccessible(true);
        try { method.invoke(window); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    @Before public void setup() throws Throwable {
        previous = ConfigBridge.current(); enabled(true);
        context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public int checkSelfPermission(String permission) { return PackageManager.PERMISSION_GRANTED; }
        };
        FocusStylusModule module = new FocusStylusModule(); module.attachFramework(framework(), () -> { });
        Map<String, Class<?>> schema = Map.of(
                "com.android.wm.shell.common.split.SplitWindowManager", Manager.class,
                "android.view.SurfaceControlViewHost", Host.class,
                "android.view.WindowlessWindowManager", Manager.class,
                "android.view.ViewRootImpl", Root.class,
                "android.view.AttachedSurfaceControl", Root.class);
        hook = new SplitDividerInputHook(module, name -> {
            Class<?> result = schema.get(name); if (result == null) throw new ClassNotFoundException(name); return result;
        }, Divider.class,
                new SplitTouchFilter((view, cancel) -> () -> { }));
        hook.install();
        assertNotNull(errors.toString(), hook);
    }
    @After public void cleanup() throws Throwable {
        if (hook == null) { field(ConfigBridge.class, "current").set(null, previous); return; }
        for (Host host : hosts) { host.failLayout = false; call(host, "release", new Class<?>[0]); }
        Object observer = field(SplitDividerInputHook.class, "focus").get(hook);
        ((cc.star0.xposed.hyperstylus.module.ModuleSession.Registration) observer).forget();
        field(SplitDividerInputHook.class, "closed").setBoolean(hook, true);
        field(ConfigBridge.class, "current").set(null, previous);
    }
    @Test public void exactDividerIsSpyBeforeItsFirstNativeWindowRegistration() throws Throwable {
        Host host = window(); WindowManager.LayoutParams params = params(2); setView(host, params);
        assertEquals(6, host.deliveredFeatures); assertEquals(6, features(params));
        assertEquals(545521704, params.flags); assertTrue(errors.toString(), errors.isEmpty());
    }
    @Test public void unrelatedViewAndUnassociatedDividerKeepTheirNativeInputFeatures() throws Throwable {
        Host host = window(); WindowManager.LayoutParams params = params(2);
        call(host, "setView", new Class<?>[]{View.class, WindowManager.LayoutParams.class}, new View(context), params);
        assertEquals(2, host.deliveredFeatures);
        Divider other = new Divider(context);
        call(host, "setView", new Class<?>[]{View.class, WindowManager.LayoutParams.class}, other, params);
        assertEquals(2, host.deliveredFeatures); assertTrue(errors.toString(), errors.isEmpty());
    }
    @Test public void nativeGeometryRelayoutKeepsSpyAndDisablingRestoresOnlyTheOwnedBit() throws Throwable {
        Host host = window(); setView(host, params(2)); WindowManager.LayoutParams changed = params(8);
        changed.flags &= ~536870912;
        call(host, "relayout", new Class<?>[]{WindowManager.LayoutParams.class}, changed);
        assertEquals(12, host.deliveredFeatures);
        enabled(false); refresh(host);
        assertEquals(8, host.deliveredFeatures); assertEquals(8, features(changed));
        assertEquals(545521704 & ~536870912, changed.flags);
    }
    @Test public void releaseRestoresSpyBeforeLettingTheHostDestroyItsChannel() throws Throwable {
        Host host = window(); WindowManager.LayoutParams params = params(2); setView(host, params);
        call(host, "release", new Class<?>[0]);
        assertEquals(2, host.deliveredFeatures); assertEquals(2, features(params)); assertEquals(1, host.releases);
        assertTrue(((Map<?, ?>) field(SplitDividerInputHook.class, "windows").get(hook)).isEmpty());
    }
    @Test public void failedNativeRelayoutStillRestoresThePreviouslyOwnedWindow() throws Throwable {
        Host host = window(); WindowManager.LayoutParams original = params(2); setView(host, original);
        host.failLayout = true;
        assertThrows(IllegalStateException.class, () -> call(host, "relayout", new Class<?>[]{WindowManager.LayoutParams.class}, params(8)));
        host.failLayout = false; enabled(false); refresh(host);
        assertEquals(2, host.deliveredFeatures); assertEquals(2, features(original));
    }
    @Test public void failedWindowCreationCompensatesTheSpyBitAndDoesNotLeaveAnActiveBinding() throws Throwable {
        Host host = window(); host.failSet = true; WindowManager.LayoutParams params = params(2);
        assertThrows(IllegalStateException.class, () -> setView(host, params));
        assertEquals(2, features(params));
        assertTrue(((Map<?, ?>) field(SplitDividerInputHook.class, "windows").get(hook)).isEmpty());
    }
    @Test public void configChangesDuringPenContactWaitForTheMatchingTerminalEvent() throws Throwable {
        Host host = window(); setView(host, params(0)); View view = host.manager.mDividerView;
        MotionEvent down = SplitDividerRoutingTest.event(MotionEvent.ACTION_DOWN, 100, 3, SplitDividerRoutingTest.PEN);
        MotionEvent up = SplitDividerRoutingTest.event(MotionEvent.ACTION_UP, 100, 3, SplitDividerRoutingTest.PEN);
        try {
            assertFalse(hook.before(view, down)); enabled(false); refresh(host);
            assertTrue(hook.enabled(view, false)); assertEquals(4, host.deliveredFeatures);
            assertFalse(hook.before(view, up)); hook.after(view, up, true, true);
            assertFalse(hook.enabled(view, true)); assertEquals(0, host.deliveredFeatures);
        } finally { down.recycle(); up.recycle(); }
    }
}
