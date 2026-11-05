package cc.star0.xposed.hyperstylus.module;

import cc.star0.xposed.hyperstylus.common.Protocol;
import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.config.ConfigBridge;
import cc.star0.xposed.hyperstylus.runtime.nativegesture.SystemBrushSettings;
import cc.star0.xposed.hyperstylus.runtime.bluetooth.BluetoothRelay;
import cc.star0.xposed.hyperstylus.runtime.charge.StylusChargeHook;
import cc.star0.xposed.hyperstylus.runtime.gesture.FocusNotificationGestureHook;
import cc.star0.xposed.hyperstylus.runtime.gesture.GestureFocusBridge;
import cc.star0.xposed.hyperstylus.runtime.gesture.StylusBackGestureHook;
import cc.star0.xposed.hyperstylus.runtime.gesture.StylusSystemGestureHook;
import cc.star0.xposed.hyperstylus.runtime.gesture.StylusWindowGestureHook;
import cc.star0.xposed.hyperstylus.runtime.gesture.StylusSidebarHook;
import cc.star0.xposed.hyperstylus.runtime.keys.MappedKeyInjector;
import cc.star0.xposed.hyperstylus.runtime.keys.StylusHover;
import cc.star0.xposed.hyperstylus.runtime.keys.StylusKeyHook;
import cc.star0.xposed.hyperstylus.runtime.prosettings.SecurityCoreHook;
import cc.star0.xposed.hyperstylus.runtime.battery.SystemUiController;
import cc.star0.xposed.hyperstylus.runtime.battery.IconHolderAdapter;

import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** One registry per injected process; optional ROM adapters fail independently. */
public final class ModuleRuntime {
    public interface Installer { public void install() throws Throwable; }
    private final FocusStylusModule module;
    private final HostLifecycle lifecycle;
    private final Set<String> features = ConcurrentHashMap.newKeySet();
    private String process;
    private boolean cachesManaged, systemServer, targetProcess;

    public ModuleRuntime(FocusStylusModule module) {
        this.module = module;
        lifecycle = new HostLifecycle(module);
    }
    public void loaded(String name, boolean systemServer) {
        process = name;
        this.systemServer = systemServer;
        targetProcess = HostProcessPolicy.targetProcess(name, systemServer);
        if (!targetProcess) {
            // Old hooks/resources were retired before onHotReloaded. Do not revive their readers,
            // Application observers or retained host references in an auxiliary process.
            module.session().snapshot().keySet().forEach(key -> module.session().remember(key, null));
        }
        module.session().remember("runtime.process", name);
        module.session().remember("runtime.systemServer", systemServer);
        if (!targetProcess) {
            module.info("Skip feature initialization in auxiliary process: " + name);
            return;
        }
        if (!cachesManaged) {
            cachesManaged = true;
            // Registered first so these host associations clear after every feature cleanup.
            module.session().onClose("reflection caches", Reflect::clearCaches);
            module.session().onClose("SystemUI holder layouts", IconHolderAdapter::clearCaches);
        }
        ConfigBridge.bind(module);
        if (systemServer) ConfigBridge.setScopeRemovalHandler((context, completion) ->
                SystemBrushSettings.prepareScopeRemoval(module, context, prepared -> {
                    if (!Boolean.TRUE.equals(prepared)) completion.accept(false);
                    else StylusKeyHook.prepareScopeRemoval(module, completion);
                }));
    }
    public void resume() {
        if (!targetProcess) return;
        if (systemServer) {
            Object system = module.session().host("runtime.systemLoader");
            if (system instanceof ClassLoader loader) {
                systemServer(loader);
                SystemBrushSettings.resume(module);
            }
            return;
        }
        Object loaded = module.session().host("runtime.packages");
        if (loaded instanceof Map<?, ?> packages) {
            // Snapshot before packageReady updates the registry.
            new LinkedHashMap<>(packages).forEach((pkg, loader) -> {
                if (pkg instanceof String name && loader instanceof ClassLoader host) packageReady(name, host);
            });
        }
    }
    private void install(String id, Installer installer) {
        if (!features.add(id)) return;
        try { installer.install(); }
        catch (Throwable error) { module.error("Initialize " + id, error); }
    }
    public void systemServer(ClassLoader loader) {
        if (!targetProcess || !systemServer) return;
        module.session().remember("runtime.systemLoader", loader);
        install("mapped-key-input", () -> MappedKeyInjector.install(module, loader));
        install("focus", () -> GestureFocusBridge.installServer(module, loader));
        install("charging", () -> new StylusChargeHook(module, loader).install());
        install("keys", () -> new StylusKeyHook(module, loader).install());
        install("hover", () -> StylusHover.install(module, loader));
        install("system-bars", () -> StylusSystemGestureHook.installSystemBars(module, loader));
    }
    public void packageReady(String pkg, ClassLoader loader) {
        if (!targetProcess || systemServer || !HostProcessPolicy.matches(pkg, process)) return;
        module.session().remember("runtime.packages", Map.of(pkg, loader));
        if (Protocol.SYSTEM_UI.equals(pkg)) {
            install("focus-notification-gestures", () -> FocusNotificationGestureHook.install(module, loader));
            install("window-gestures", () -> StylusWindowGestureHook.install(module, loader));
            install("back-gestures", () -> StylusBackGestureHook.install(module, loader));
            install("window-config", () -> lifecycle.add("window-config", app -> {
                GestureFocusBridge.startClient(module, app);
                ConfigBridge.start(app, config -> module.info("Window configuration=" + config.revision));
            }));
            install("status-icons", () -> {
                SystemUiController controller = new SystemUiController(module, loader);
                controller.installHooks();
                lifecycle.add("status-icons", controller::start);
            });
        } else if (Protocol.HOME.equals(pkg)) {
            install("home-gestures", () -> StylusSystemGestureHook.installHome(module, loader));
            install("home-config", () -> lifecycle.add("home-config", app -> {
                GestureFocusBridge.startClient(module, app);
                ConfigBridge.start(app, config -> module.info("Home configuration=" + config.revision));
            }));
        } else if (Protocol.SECURITY_CENTER.equals(pkg)) {
            install("sidebar-gestures", () -> {
                StylusSidebarHook sidebar = new StylusSidebarHook(module, loader);
                sidebar.install();
                lifecycle.add("sidebar-config", sidebar::start);
            });
        } else if (Protocol.SECURITY_CORE.equals(pkg)) {
            install("pro-settings", () -> {
                SecurityCoreHook hook = new SecurityCoreHook(module, loader);
                hook.install(); lifecycle.add("pro-settings", hook::start);
            });
        } else if (Protocol.BLUETOOTH.equals(pkg)) {
            install("bluetooth", () -> {
                BluetoothRelay relay = new BluetoothRelay(module, loader);
                relay.installHooks(); lifecycle.add("bluetooth", relay::start);
            });
        }
    }
}
