package cc.star0.xposed.hyperstylus.module;

import android.os.Bundle;
import cc.star0.xposed.hyperstylus.common.Protocol;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
// A module generation has its own ConfigBridge statics. Use a distinct Robolectric sandbox from
// the reader-retirement tests, which intentionally shut that generation's singleton down.
@Config(sdk = 35, instrumentedPackages = "cc.star0.xposed.hyperstylus.config")
public class ModuleRuntimeProcessTest {
    private final AtomicInteger hooks = new AtomicInteger();

    private FocusStylusModule module() {
        FocusStylusModule module = new FocusStylusModule();
        module.attachFramework((XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> {
                    if (method.getName().equals("log")) return null;
                    if (method.getName().equals("hook")) hooks.incrementAndGet();
                    throw new AssertionError("Unexpected framework call: " + method.getName());
                }), () -> { });
        return module;
    }

    private static Field field(String name) throws Exception {
        Field field = ModuleRuntime.class.getDeclaredField(name); field.setAccessible(true); return field;
    }

    private static final class UnusedLoader extends ClassLoader {
        int reads;
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            reads++; throw new ClassNotFoundException(name);
        }
    }

    @Test public void auxiliaryCallbacksNeverBindConfigurationInstallHooksOrRetainPackageLoaders() {
        for (String process : new String[]{"com.miui.securitycenter:cache", "com.miui.securitycenter.cache",
                "com.miui.securitycenter.remote", "com.miui.securitycenter.power", "com.miui.securitycenter",
                "com.miui.securitycore.remote", "com.xiaomi.bluetooth:ui", "system"}) {
            FocusStylusModule module = module(); ModuleRuntime runtime = new ModuleRuntime(module);
            UnusedLoader loader = new UnusedLoader(); runtime.loaded(process, false);
            for (String pkg : new String[]{Protocol.SECURITY_CORE, Protocol.SECURITY_CENTER, Protocol.BLUETOOTH, Protocol.SYSTEM_UI, Protocol.HOME})
                runtime.packageReady(pkg, loader);
            runtime.systemServer(loader); runtime.resume();
            assertEquals(0, hooks.get()); assertEquals(0, loader.reads);
            assertFalse(module.session().hasResource("configuration bridge"));
            assertFalse(module.session().hasResource("reflection caches"));
            assertEquals(Set.of("runtime.process", "runtime.systemServer"), module.session().snapshot().keySet());
            assertTrue(module.session().close(0));
        }
    }

    @Test public void mismatchedPackagesInAnAdmittedProcessAreRejectedBeforeClassLoading() throws Exception {
        FocusStylusModule module = module(); ModuleRuntime runtime = new ModuleRuntime(module);
        // Represents a process already admitted by loaded(); unrelated packages must not install work.
        field("process").set(runtime, Protocol.SECURITY_CORE); field("targetProcess").setBoolean(runtime, true);
        UnusedLoader loader = new UnusedLoader();
        for (String pkg : new String[]{Protocol.BLUETOOTH, Protocol.SYSTEM_UI, Protocol.HOME, Protocol.SECURITY_CENTER, "android"})
            runtime.packageReady(pkg, loader);
        runtime.systemServer(loader);
        assertEquals(0, hooks.get()); assertEquals(0, loader.reads);
        assertTrue(module.session().snapshot().isEmpty());
        assertTrue(module.session().close(0));
    }

    @Test public void reloadingAnAuxiliaryProcessUnhooksOldGenerationWithoutRestoringItsClients() {
        FocusStylusModule module = module(); AtomicInteger removed = new AtomicInteger();
        XposedInterface.HookHandle handle = (XposedInterface.HookHandle) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.HookHandle.class}, (proxy, method, args) -> {
                    if (method.getName().equals("unhook")) { removed.incrementAndGet(); return null; }
                    if (method.getName().equals("hashCode")) return 1;
                    if (method.getName().equals("equals")) return proxy == args[0];
                    throw new AssertionError(method.getName());
                });
        ClassLoader host = ClassLoader.getSystemClassLoader();
        Map<String, Object> saved = Map.of("runtime.packages", Map.of(Protocol.SECURITY_CORE, host),
                "runtime.systemLoader", host, "config.snapshot", new Bundle(), "config.ready", true,
                "config.context", new Object(), "host.application", new Object());
        XposedModuleInterface.HotReloadedParam param = (XposedModuleInterface.HotReloadedParam) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{XposedModuleInterface.HotReloadedParam.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getOldHookHandles" -> List.of(handle);
                    case "getSavedInstanceState" -> saved;
                    case "getProcessName" -> Protocol.SECURITY_CORE + ".remote";
                    case "isSystemServer" -> false;
                    default -> throw new AssertionError(method.getName());
                });
        module.onHotReloaded(param);
        assertEquals(1, removed.get()); assertEquals(0, hooks.get());
        assertEquals(Set.of("runtime.process", "runtime.systemServer"), module.session().snapshot().keySet());
        assertFalse(module.session().hasResource("configuration bridge"));
        assertTrue(module.session().close(0));
    }

    @Test public void supportedHostBindsConfigurationWhileRejectingUnrelatedPackages() {
        FocusStylusModule module = module(); ModuleRuntime runtime = new ModuleRuntime(module);
        runtime.loaded(Protocol.SECURITY_CORE, false);
        assertTrue(module.session().hasResource("configuration bridge"));
        assertTrue(module.session().hasResource("reflection caches"));
        UnusedLoader loader = new UnusedLoader(); runtime.packageReady(Protocol.SECURITY_CENTER, loader);
        runtime.packageReady(Protocol.BLUETOOTH, loader);
        assertEquals(0, hooks.get()); assertEquals(0, loader.reads);
        assertNull(module.session().host("runtime.packages"));
        assertTrue(module.session().close(2_000));
    }
}
