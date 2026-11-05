package cc.star0.xposed.hyperstylus.module;

import cc.star0.xposed.hyperstylus.common.Protocol;
import java.util.Map;

/** Exact feature hosts within the framework's package-wide scopes. No prefix or device-name guesses. */
public final class HostProcessPolicy {
    private static final Map<String, String> APP_HOSTS = Map.of(
            Protocol.SYSTEM_UI, Protocol.SYSTEM_UI,
            Protocol.HOME, Protocol.HOME,
            Protocol.SECURITY_CORE, Protocol.SECURITY_CORE,
            Protocol.BLUETOOTH, Protocol.BLUETOOTH,
            Protocol.SECURITY_CENTER, Protocol.SECURITY_CENTER + ":ui");

    private HostProcessPolicy() { }

    public static boolean appProcess(String process) {
        return process != null && APP_HOSTS.containsValue(process);
    }

    public static boolean targetProcess(String process, boolean systemServer) {
        return systemServer || appProcess(process);
    }

    public static boolean matches(String pkg, String process) {
        return pkg != null && process != null && process.equals(APP_HOSTS.get(pkg));
    }

    /** Display classification only. Runtime system-server admission uses the framework's flag. */
    public static boolean expectedProcessName(String process) {
        return "system".equals(process) || "system_server".equals(process) || appProcess(process);
    }
}
