package cc.star0.xposed.hyperstylus.runtime.keys;

import cc.star0.xposed.hyperstylus.common.Reflect;

import java.lang.ref.WeakReference;

/** Read the current ROM shortcut manager's locked focused-package accessor. */
public final class ForegroundApp {
    private static volatile WeakReference<Object> shortcuts = new WeakReference<>(null);
    public static void registerShortcutManager(Object manager) { shortcuts = new WeakReference<>(manager); }
    public static String currentPackage() { return packageName(shortcuts.get()); }
    public static String packageName(Object manager) {
        if (manager == null) return null;
        try {
            Object value = Reflect.call(manager, "getPackageName");
            return value instanceof String name && !name.isBlank() ? name : null;
        } catch (Throwable unavailable) { return null; }
    }
    private ForegroundApp() {}
}
