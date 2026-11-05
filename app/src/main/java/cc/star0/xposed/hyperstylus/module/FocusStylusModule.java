package cc.star0.xposed.hyperstylus.module;

import cc.star0.xposed.hyperstylus.BuildConfig;

import android.util.Log;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface;

/** API 102 entry point. Feature installation and process lifecycle belong to ModuleRuntime. */
public final class FocusStylusModule extends XposedModule {
    private final ModuleSession session = new ModuleSession(this);
    private final ModuleRuntime runtime = new ModuleRuntime(this);

    public ModuleSession session() { return session; }

    @Override public void onModuleLoaded(ModuleLoadedParam parameter) {
        runtime.loaded(parameter.getProcessName(), parameter.isSystemServer());
        info("libxposed 102 / " + BuildConfig.VERSION_NAME + " in " + parameter.getProcessName());
    }
    @Override public void onSystemServerStarting(SystemServerStartingParam parameter) {
        runtime.systemServer(parameter.getClassLoader());
    }
    @Override public void onPackageReady(PackageReadyParam parameter) {
        runtime.packageReady(parameter.getPackageName(), parameter.getClassLoader());
    }

    @Override public boolean onHotReloading(HotReloadingParam parameter) {
        if (!session.close(2_000)) {
            info("Hot reload could not retire all resources; restart this target process");
            return false;
        }
        parameter.setSavedInstanceState(session.snapshot());
        info("Module generation retired for hot reload");
        return true;
    }

    @Override public void onHotReloaded(HotReloadedParam parameter) {
        parameter.getOldHookHandles().forEach(XposedInterface.HookHandle::unhook);
        if (!(parameter.getSavedInstanceState() instanceof Map<?, ?> saved))
            throw new IllegalStateException("Missing HyperStylus generation state");
        session.restore(saved);
        runtime.loaded(parameter.getProcessName(), parameter.isSystemServer());
        runtime.resume();
        info("Hot reloaded " + BuildConfig.VERSION_NAME + " in " + parameter.getProcessName());
    }

    /** Every interception belongs to this generation, including dynamically discovered ROM hooks. */
    public XposedInterface.HookBuilder managedHook(Executable executable) {
        XposedInterface.HookBuilder delegate = hook(executable);
        return new XposedInterface.HookBuilder() {
            @Override public XposedInterface.HookBuilder setPriority(int priority) { delegate.setPriority(priority); return this; }
            @Override public XposedInterface.HookBuilder setExceptionMode(XposedInterface.ExceptionMode mode) { delegate.setExceptionMode(mode); return this; }
            @Override public XposedInterface.HookBuilder setId(String id) { delegate.setId(id); return this; }
            @Override public XposedInterface.HookHandle intercept(XposedInterface.Hooker hooker) {
                XposedInterface.HookHandle handle = delegate.intercept(chain -> {
                    if (!session.enter()) return chain.proceed();
                    try { return hooker.intercept(chain); }
                    finally { session.leave(); }
                });
                return handle;
            }
        };
    }

    public interface After { public void run(Object receiver, Object[] args, Object result) throws Throwable; }
    public void after(Executable executable, After callback) {
        managedHook(executable).intercept(chain -> {
            Object result = chain.proceed();
            try { callback.run(chain.getThisObject(), chain.getArgs().toArray(), result); }
            catch (Throwable error) { error("After hook: " + executable, error); }
            return result;
        });
    }
    public int afterMethods(Class<?> type, String name, After callback) {
        if (type == null) return 0;
        int installed = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (!name.equals(method.getName()) || Modifier.isAbstract(method.getModifiers())) continue;
            try { after(method, callback); installed++; }
            catch (Throwable error) { error("Install " + method, error); }
        }
        return installed;
    }
    public Object allocateIconHolder(Class<?> type) throws Throwable {
        return getInvoker(Object.class.getDeclaredConstructor())
                .setType(XposedInterface.Invoker.Type.ORIGIN).newInstanceSpecial(type);
    }
    public void info(String message) { log(Log.INFO, "HyperStylus", message); }
    public void error(String message, Throwable error) { log(Log.ERROR, "HyperStylus", message, error); }
    public void safely(String operation, Runnable action) {
        if (!session.enter()) return;
        try { action.run(); } catch (Throwable error) { error(operation, error); }
        finally { session.leave(); }
    }
}
