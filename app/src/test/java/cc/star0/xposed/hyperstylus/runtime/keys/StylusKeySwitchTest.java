package cc.star0.xposed.hyperstylus.runtime.keys;

import android.os.Bundle;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import cc.star0.xposed.hyperstylus.config.ConfigBridge;
import cc.star0.xposed.hyperstylus.config.EnhanceConfig;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Exercise switch cancellation and real queued hook work without requiring the vendor input service. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class StylusKeySwitchTest {
    private FocusStylusModule module;
    private StylusKeyHook hook;
    private FullKeyDispatcher dispatcher;
    private EnhanceConfig previous;
    private final List<KeyEvent> edges = new ArrayList<>();
    private boolean rejectUp;

    @Before public void setup() throws Exception {
        previous = ConfigBridge.current();
        module = new FocusStylusModule();
        module.attachFramework((io.github.libxposed.api.XposedInterface) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{io.github.libxposed.api.XposedInterface.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("log")) return null;
                    throw new UnsupportedOperationException(method.getName());
                }), () -> { });
        hook = new StylusKeyHook(module, getClass().getClassLoader());
        dispatcher = new FullKeyDispatcher(event -> {
            edges.add(new KeyEvent(event));
            if (rejectUp && event.getAction() == KeyEvent.ACTION_UP) throw new IllegalStateException("UP rejected");
        }, () -> 10_000);
        field(StylusKeyHook.class, "fullDispatcher").set(hook, dispatcher);
    }
    @After public void cleanup() throws Throwable {
        rejectUp = false; hook.close(); module.session().close(2_000);
        field(ConfigBridge.class, "current").set(null, previous);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field value = type.getDeclaredField(name); value.setAccessible(true); return value;
    }
    private static KeyEvent down(int code, long time) {
        return new KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0, 0, 53, 27, 0,
                InputDevice.SOURCE_STYLUS | InputDevice.SOURCE_KEYBOARD);
    }
    @SuppressWarnings("unchecked")
    private Object bind(KeyEvent down, int button) throws Exception {
        Class<?> type = Class.forName(StylusKeyHook.class.getName() + "$Binding");
        var constructor = type.getDeclaredConstructor(long.class, String[].class, int.class, KeyEvent.class, int.class, long.class);
        constructor.setAccessible(true);
        long epoch = ((java.util.concurrent.atomic.AtomicLongArray) field(StylusKeyHook.class, "enabledEpochs").get(hook)).get(button);
        Object binding = constructor.newInstance(down.getDownTime(), new String[]{"none", "none"}, KeyEvent.KEYCODE_SPACE, down, button, epoch);
        ((Map<KeyPressDispatcher.Key, Object>) field(StylusKeyHook.class, "bindings").get(hook))
                .put(new KeyPressDispatcher.Key(down.getDeviceId(), down.getKeyCode()), binding);
        return binding;
    }
    private void configure(boolean focus, boolean upper, boolean lower) throws Exception {
        Bundle bundle = EnhanceConfig.defaults().toBundle();
        bundle.putBoolean(EnhanceConfig.KEY_ENABLED_KEYS[0], focus);
        bundle.putBoolean(EnhanceConfig.KEY_ENABLED_KEYS[1], upper);
        bundle.putBoolean(EnhanceConfig.KEY_ENABLED_KEYS[2], lower);
        EnhanceConfig next = new EnhanceConfig(bundle);
        field(ConfigBridge.class, "current").set(null, next);
        Method method = StylusKeyHook.class.getDeclaredMethod("configure", EnhanceConfig.class);
        method.setAccessible(true); method.invoke(hook, next);
    }
    private void queue(KeyEvent down, Object binding, int button) throws Exception {
        Method method = StylusKeyHook.class.getDeclaredMethod("dispatchBinding", Object.class,
                KeyPressDispatcher.Key.class, int.class, binding.getClass(), KeyEvent.class);
        method.setAccessible(true);
        method.invoke(hook, null, new KeyPressDispatcher.Key(down.getDeviceId(), down.getKeyCode()), button, binding, down);
    }

    @Test public void disablingOneOwnerPreservesTheSharedTargetUntilTheOtherSwitchIsDisabled() throws Throwable {
        KeyEvent focus = down(193, 100), upper = down(KeyEvent.KEYCODE_PAGE_UP, 101);
        bind(focus, 0); bind(upper, 1);
        dispatcher.accept(focus, KeyEvent.KEYCODE_SPACE); dispatcher.accept(upper, KeyEvent.KEYCODE_SPACE);
        configure(false, true, true);
        assertEquals(1, edges.size()); assertTrue(dispatcher.hasHeldKeys());
        configure(false, false, true);
        assertEquals(2, edges.size()); assertEquals(KeyEvent.ACTION_UP, edges.get(1).getAction());
        assertTrue(edges.get(1).isCanceled()); assertFalse(dispatcher.hasHeldKeys());
    }

    @Test public void aCancelledQueuedDownCannotReviveAfterRapidDisableAndReenable() throws Exception {
        KeyEvent original = down(193, 100);
        queue(original, bind(original, 0), 0);
        configure(false, true, true); configure(true, true, true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(edges.isEmpty());
        KeyEvent next = down(193, 200);
        queue(next, bind(next, 0), 0);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, edges.size()); assertEquals(200, edges.get(0).getDownTime());
    }

    @Test public void aFailedUpIsStillHeldAndCannotBeReportedReadyForScopeRemoval() throws Throwable {
        KeyEvent original = down(193, 100);
        bind(original, 0); dispatcher.accept(original, KeyEvent.KEYCODE_SPACE);
        rejectUp = true;
        configure(false, false, false);
        assertTrue(dispatcher.hasHeldKeys());
        List<Boolean> acknowledgements = new ArrayList<>();
        StylusKeyHook.prepareScopeRemoval(module, acknowledgements::add);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));
        assertEquals(List.of(false), acknowledgements);
    }
    @Test public void aBindingPublishedAfterTheDisableTraversalStillCannotReviveAnOldDown() throws Exception {
        KeyEvent original = down(193, 100);
        Object stale = bind(original, 0);
        ((Map<?, ?>) field(StylusKeyHook.class, "bindings").get(hook)).clear();
        configure(false, true, true); configure(true, true, true);
        queue(original, stale, 0);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(edges.isEmpty());
    }
}
