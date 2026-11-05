package cc.star0.xposed.hyperstylus.config;

import cc.star0.xposed.hyperstylus.common.Protocol;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** All enabled consumers contribute to one scope union; a switch never owns a shared host. */
public final class ScopePolicy {
    public static final String SYSTEM = "system";
    public static final Set<String> MANAGED = Set.of(SYSTEM, Protocol.SYSTEM_UI, Protocol.BLUETOOTH,
            Protocol.SECURITY_CORE, Protocol.HOME, Protocol.SECURITY_CENTER);
    private ScopePolicy() { }

    public static int gestureFeatures(EnhanceConfig config) {
        int mask = config.gestureMask();
        if (config.gestureApps.enabled) for (String pkg : config.gestureApps.selectedPackages())
            mask |= config.gestureApps.profileMask(pkg, config.gestureMask());
        return mask;
    }

    public static Set<String> required(EnhanceConfig config) {
        LinkedHashSet<String> scope = new LinkedHashSet<>();
        int gestures = gestureFeatures(config);
        boolean appFocus = config.gestureApps.enabled && !config.gestureApps.selectedPackages().isEmpty()
                && gestures != 0;
        if (config.anyKeyEnabled() || config.chargeLimitEnabled || config.chargeNotificationsEnabled() || config.pinch.enabled || config.doubleTap.enabled
                || (gestures & GestureAppConfig.SYSTEM_BARS) != 0 || appFocus) scope.add(SYSTEM);
        if (config.batteryEnabled
                || (gestures & (GestureAppConfig.BACK | GestureAppConfig.CAPTION | GestureAppConfig.CORNER
                | GestureAppConfig.FOCUS_NOTIFICATION | GestureAppConfig.SPLIT_DIVIDER)) != 0) scope.add(Protocol.SYSTEM_UI);
        if (config.batteryEnabled || config.chargeLimitEnabled || config.chargeNotificationsEnabled()) scope.add(Protocol.BLUETOOTH);
        if (config.proSettings) scope.add(Protocol.SECURITY_CORE);
        if ((gestures & (GestureAppConfig.BACK | GestureAppConfig.HOME_RECENTS)) != 0) scope.add(Protocol.HOME);
        if ((gestures & GestureAppConfig.SIDEBAR) != 0) scope.add(Protocol.SECURITY_CENTER);
        return Collections.unmodifiableSet(scope);
    }
}
