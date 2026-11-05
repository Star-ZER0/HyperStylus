package cc.star0.xposed.hyperstylus.module;

import cc.star0.xposed.hyperstylus.common.Protocol;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class HostProcessPolicyTest {
    @Test public void eachFeaturePackageHasExactlyItsDeclaredProcess() {
        Map<String, String> hosts = Map.of(Protocol.SYSTEM_UI, Protocol.SYSTEM_UI, Protocol.HOME, Protocol.HOME,
                Protocol.SECURITY_CORE, Protocol.SECURITY_CORE, Protocol.BLUETOOTH, Protocol.BLUETOOTH,
                Protocol.SECURITY_CENTER, Protocol.SECURITY_CENTER + ":ui");
        hosts.forEach((pkg, process) -> {
            assertTrue(HostProcessPolicy.targetProcess(process, false));
            assertTrue(HostProcessPolicy.matches(pkg, process));
            hosts.keySet().forEach(other -> assertEquals(pkg.equals(other), HostProcessPolicy.matches(other, process)));
            assertFalse(HostProcessPolicy.matches(pkg, process + ":remote"));
            assertFalse(HostProcessPolicy.matches(pkg, process + ".remote"));
        });
    }

    @Test public void auxiliaryProcessesInTheScreenshotAndBluetoothUiAreNotFeatureTargets() {
        for (String name : new String[]{"com.miui.securitycenter", "com.miui.securitycenter:cache",
                "com.miui.securitycenter.cache", "com.miui.securitycenter.remote", "com.miui.securitycenter.power",
                "com.miui.securitycore.remote", "com.xiaomi.bluetooth:ui", "com.xiaomi.bluetooth:hello"}) {
            assertFalse(name, HostProcessPolicy.targetProcess(name, false));
            assertFalse(name, HostProcessPolicy.expectedProcessName(name));
        }
        assertFalse(HostProcessPolicy.targetProcess(null, false));
        assertFalse(HostProcessPolicy.matches(null, Protocol.SYSTEM_UI));
        assertFalse(HostProcessPolicy.matches(Protocol.SYSTEM_UI, null));
    }

    @Test public void systemServerAdmissionUsesTheFrameworkFlagInsteadOfAProcessNameGuess() {
        assertTrue(HostProcessPolicy.targetProcess("system", true));
        assertTrue(HostProcessPolicy.targetProcess("system_server", true));
        assertFalse(HostProcessPolicy.targetProcess("system", false));
        assertFalse(HostProcessPolicy.targetProcess("system_server", false));
        assertTrue(HostProcessPolicy.expectedProcessName("system"));
        assertTrue(HostProcessPolicy.expectedProcessName("system_server"));
        assertFalse(HostProcessPolicy.matches("android", "system"));
    }
}
