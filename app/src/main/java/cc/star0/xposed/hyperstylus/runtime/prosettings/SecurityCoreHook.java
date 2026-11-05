package cc.star0.xposed.hyperstylus.runtime.prosettings;

import cc.star0.xposed.hyperstylus.config.ConfigBridge;
import cc.star0.xposed.hyperstylus.config.EnhanceConfig;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;

import android.content.Context;
import android.view.InputDevice;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Adapt native Pro capability queries inside SecurityCore without replacing device state or actions. */
public final class SecurityCoreHook {
    private final FocusStylusModule module;
    private volatile boolean enabled = ConfigBridge.current().proSettings;
    private boolean subscribed;

    public SecurityCoreHook(FocusStylusModule module, ClassLoader loader) { this.module = module; }

    public void install() {
        module.info("Current SecurityCore Pro settings generation adapters: " + hookInputDevice(InputDevice.class));
    }

    public synchronized void start(Context context) {
        if (subscribed) return;
        subscribed = true;
        ConfigBridge.start(context, this::configure);
    }

    public void configure(EnhanceConfig value) { enabled = value.proSettings; }

    public int hookInputDevice(Class<?> type) {
        int installed = 0;
        for (Method query : type.getDeclaredMethods()) {
            if (!deviceGenerationQuery(query)) continue;
            try {
                module.managedHook(query).intercept(chain -> {
                    Object original = chain.proceed();
                    return enabled && original instanceof Integer generation && generation > 0 ? 8 : original;
                });
                installed++;
            } catch (Throwable failure) { module.error("Install SecurityCore generation query " + query, failure); }
        }
        return installed;
    }

    private static boolean deviceGenerationQuery(Method method) {
        if (!method.getName().equals("isXiaomiStylus") || method.getReturnType() != int.class) return false;
        Class<?>[] signature = method.getParameterTypes();
        return signature.length == 0 || Arrays.equals(signature, new Class<?>[]{int.class, int.class});
    }

}
