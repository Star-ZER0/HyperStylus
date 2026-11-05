package cc.star0.xposed.hyperstylus.runtime.keys;

import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowProcess;
import static org.junit.Assert.*;

/** Exercise the production Handler/session scheduler, not the dispatcher's manual test timer. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class StylusKeyRepeatTest {
    private int uid;
    private Object previousTarget;
    private FocusStylusModule module;
    private StylusKeyHook hook;
    private Object binding;
    private KeyEvent down;
    private final CompleteKeyInjectionTest.NativeQueue queue = new CompleteKeyInjectionTest.NativeQueue();
    private final List<Throwable> failures = new ArrayList<>();

    @Before public void setup() throws Exception {
        uid = Process.myUid(); ShadowProcess.setUid(Process.SYSTEM_UID);
        previousTarget = field(MappedKeyInjector.class, "active").get(null);
        Method resolve = MappedKeyInjector.class.getDeclaredMethod("resolve", Object.class);
        resolve.setAccessible(true);
        field(MappedKeyInjector.class, "active").set(null, resolve.invoke(null, queue));
        module = new FocusStylusModule();
        module.attachFramework((io.github.libxposed.api.XposedInterface) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{io.github.libxposed.api.XposedInterface.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("log")) {
                        for (Object arg : args) if (arg instanceof Throwable failure) failures.add(failure);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }), () -> { });
        hook = new StylusKeyHook(module, getClass().getClassLoader());
        long time = SystemClock.uptimeMillis();
        down = new KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_UP, 0,
                0, 53, 104, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_STYLUS);
        Class<?> type = Class.forName(StylusKeyHook.class.getName() + "$Binding");
        var constructor = type.getDeclaredConstructor(long.class, String[].class, int.class,
                KeyEvent.class, int.class, long.class);
        constructor.setAccessible(true);
        binding = constructor.newInstance(time, new String[]{"none", "none"}, KeyEvent.KEYCODE_5, down, 1, 0L);
    }

    @After public void cleanup() {
        try { if (module != null) assertTrue(module.session().close(2_000)); }
        finally {
            try { field(MappedKeyInjector.class, "active").set(null, previousTarget); }
            catch (Exception failure) { throw new AssertionError(failure); }
            ShadowProcess.setUid(uid);
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field value = type.getDeclaredField(name); value.setAccessible(true); return value;
    }

    private void dispatch(KeyEvent event) throws Exception {
        Method method = StylusKeyHook.class.getDeclaredMethod("dispatchBinding", Object.class,
                KeyPressDispatcher.Key.class, int.class, binding.getClass(), KeyEvent.class);
        method.setAccessible(true);
        method.invoke(hook, null, new KeyPressDispatcher.Key(53, KeyEvent.KEYCODE_PAGE_UP), 1, binding, event);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void advance(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    @Test public void holdingFiveRepeatsThroughTheProductionSchedulerUntilPhysicalRelease() throws Exception {
        dispatch(down);
        assertEquals(1, queue.events.size());
        advance(ViewConfiguration.getKeyRepeatTimeout());
        advance(2L * ViewConfiguration.getKeyRepeatDelay());
        assertEquals(List.of(0, 1, 2, 3), queue.events.stream().map(KeyEvent::getRepeatCount).toList());
        assertTrue(queue.events.get(1).isLongPress());
        assertFalse(queue.events.get(2).isLongPress());
        dispatch(new KeyEvent(down.getDownTime(), SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
                down.getKeyCode(), 0, 0, 53, 104, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_STYLUS));
        advance(1_000);
        assertEquals(5, queue.events.size());
        assertEquals(KeyEvent.ACTION_UP, queue.events.get(4).getAction());
        assertTrue(queue.events.stream().allMatch(event -> event.getKeyCode() == KeyEvent.KEYCODE_5
                && event.getDownTime() == down.getDownTime()));
        assertTrue(failures.toString(), failures.isEmpty());
    }

    @Test public void retiringTheModuleReleasesTheHeldKeyAndCancelsProductionRepeats() throws Exception {
        dispatch(down);
        advance(ViewConfiguration.getKeyRepeatTimeout());
        assertEquals(2, queue.events.size());
        assertTrue(module.session().close(2_000));
        advance(1_000);
        assertEquals(3, queue.events.size());
        assertEquals(KeyEvent.ACTION_UP, queue.events.get(2).getAction());
        assertTrue(queue.events.get(2).isCanceled());
        assertTrue(failures.toString(), failures.isEmpty());
    }
}
