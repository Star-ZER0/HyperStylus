package cc.star0.xposed.hyperstylus.runtime.nativegesture;

import static org.junit.Assert.*;

import cc.star0.xposed.hyperstylus.module.FocusStylusModule;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SystemBrushRetirementTest {
    @Test public void retiredGenerationDoesNotAllocateOrInitializeANativeWorker() {
        FocusStylusModule module = new FocusStylusModule();
        assertTrue(module.session().close(2_000));
        Set<Thread> before = brushThreads();
        // A retired generation must return before touching the context or registering
        // a late cleanup that would block waiting for its own class monitor.
        SystemBrushSettings.start(module, null);
        Set<Thread> after = brushThreads(); after.removeAll(before);
        assertTrue("Retired module code cannot create a new HandlerThread", after.isEmpty());
    }

    private static Set<Thread> brushThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().equals("HyperStylus-native-gestures"))
                .collect(Collectors.toSet());
    }
}
