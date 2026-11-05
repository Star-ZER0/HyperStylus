package cc.star0.xposed.hyperstylus.runtime.keys;

import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.config.ActionConfig;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;

import android.os.Process;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Native queue: device-preserving Pro gestures and standard virtual keyboard mappings. */
public final class MappedKeyInjector {
    public static final int FILTERED = 0x04000000;
    public static final int DISABLE_REPEAT = 0x08000000;
    public static final int PASS_TO_USER = 0x40000000;
    public static final int FLAGS = FILTERED | DISABLE_REPEAT | PASS_TO_USER;
    // Let beforeQueueing handle keyboard policy. Pen-policy drops must not cancel this keyboard.
    public static final int FULL_FLAGS = DISABLE_REPEAT;
    private static volatile Target active;
    private final Target supplied;
    private record Target(Object receiver, Method inject) {
        public int send(KeyEvent event, int flags) throws Throwable {
            try {
                return (Integer) inject.invoke(receiver, event, false, -1, 0, 0, flags);
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }

    public MappedKeyInjector() { supplied = null; }
    public MappedKeyInjector(Object nativeService) throws ReflectiveOperationException { supplied = resolve(nativeService); }

    public static void install(FocusStylusModule module, ClassLoader loader) {
        Class<?> input = Reflect.find(loader, "com.android.server.input.InputManagerService");
        if (input == null) { module.info("Mapped stylus native input service unavailable"); return; }
        module.session().onClose("input.nativeInjector", () -> active = null);
        FocusStylusModule.After capture = (receiver, arguments, result) -> {
            Object nativeService = Reflect.field(receiver, "mNative");
            active = resolve(nativeService);
            module.session().remember("input.nativeService", nativeService);
            module.info("Mapped stylus device-preserving input attached");
        };
        module.afterMethods(input, "start", capture);
        module.afterMethods(input, "systemRunning", capture);
        Object saved = module.session().host("input.nativeService");
        if (saved != null) try { active = resolve(saved); }
        catch (ReflectiveOperationException failure) { module.error("Restore native pen input service", failure); }
    }

    private static Target resolve(Object receiver) throws ReflectiveOperationException {
        if (receiver == null) throw new IllegalStateException("Native input service unavailable");
        for (Class<?> type = receiver.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals("injectInputEvent") || method.getReturnType() != int.class
                        || Modifier.isStatic(method.getModifiers()) || Modifier.isAbstract(method.getModifiers())) continue;
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != 6 || parameters[0] != InputEvent.class || parameters[1] != boolean.class
                        || parameters[2] != int.class || parameters[3] != int.class
                        || parameters[4] != int.class || parameters[5] != int.class) continue;
                method.setAccessible(true);
                return new Target(receiver, method);
            }
        }
        throw new NoSuchMethodException("Unsupported native input injection ABI: " + receiver.getClass().getName());
    }

    // The local Binder lookup is a late-load fallback; normal capture occurs after native start().
    @android.annotation.SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private Target target() throws Throwable {
        if (supplied != null) return supplied;
        Target ready = active;
        if (ready != null) return ready;
        Object binder = Reflect.call(Class.forName("android.os.ServiceManager"), "getService", "input");
        if (binder == null) throw new IllegalStateException("Input service has not started");
        Object local = Reflect.call(Class.forName("android.hardware.input.IInputManager$Stub"), "asInterface", binder);
        if (local == null || !local.getClass().getName().equals("com.android.server.input.InputManagerService")) {
            throw new IllegalStateException("Device-preserving injection requires the local system-server service");
        }
        ready = resolve(Reflect.field(local, "mNative"));
        active = ready;
        return ready;
    }

    public void deliver(KeyEvent[] pair) throws Throwable {
        if (Process.myUid() != Process.SYSTEM_UID) throw new SecurityException("Mapped pen input is system-server only");
        validate(pair);
        Target queue = target();
        Throwable failure = null;
        boolean accepted = true;
        for (KeyEvent event : pair) {
            // These are final replacements for the physical pen press. Their 194..197 codes
            // overlap BUTTON_7..10: an app that does not handle Pro gestures would otherwise
            // receive an extra DPAD_CENTER from Android's unhandled-key fallback.
            KeyEvent mapped = KeyEvent.changeFlags(event, event.getFlags() | KeyEvent.FLAG_FALLBACK);
            try { accepted &= queue.send(mapped, FLAGS) == 0; }
            catch (Throwable error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
        if (!accepted) throw new IllegalStateException("Native queue rejected mapped pen input");
    }

    /** Repeats come from the complete-mapping dispatcher; native repeats would double them. */
    public void deliverFull(KeyEvent event) throws Throwable {
        if (Process.myUid() != Process.SYSTEM_UID) throw new SecurityException("Mapped pen input is system-server only");
        if (!KeyboardMapping.isEvent(event) || !ActionConfig.validKeyCode(event.getKeyCode())
                || event.getAction() != KeyEvent.ACTION_DOWN && event.getAction() != KeyEvent.ACTION_UP
                || event.getRepeatCount() < 0 || event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() != 0)
            throw new IllegalArgumentException("Not a complete keyboard mapping");
        if (target().send(event, FULL_FLAGS) != 0) throw new IllegalStateException("Native queue rejected complete keyboard mapping");
    }

    private static void validate(KeyEvent[] pair) {
        if (pair == null || pair.length != 2) throw new IllegalArgumentException("One pen DOWN/UP pair is required");
        for (int index = 0; index < pair.length; index++) {
            KeyEvent event = pair[index];
            if (event == null || event.getDeviceId() < 0 || event.getKeyCode() < 194 || event.getKeyCode() > 197
                    || event.getScanCode() != StylusKeyHook.SYNTHETIC_SCAN_CODE
                    || !event.isFromSource(InputDevice.SOURCE_STYLUS)
                    || event.getAction() != (index == 0 ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP)
                    || event.getRepeatCount() != 0) throw new IllegalArgumentException("Not a mapped stylus gesture");
        }
        if (pair[0].getDeviceId() != pair[1].getDeviceId() || pair[0].getKeyCode() != pair[1].getKeyCode()
                || pair[0].getDownTime() != pair[1].getDownTime()) throw new IllegalArgumentException("Mismatched pen key edges");
    }
}
