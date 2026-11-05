package cc.star0.xposed.hyperstylus.module;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ModuleSessionTest {
    private static final class OldGenerationObject { }
    private static ModuleSession session() { return new ModuleSession((operation, error) -> { }); }

    @Test public void inflightTimeoutPreservesResourcesUntilACallbackFinishes() {
        ModuleSession scope = session();
        AtomicInteger closed = new AtomicInteger();
        scope.onClose("listener", closed::incrementAndGet);
        assertTrue(scope.enter());
        assertFalse(scope.close(0));
        assertTrue(scope.isActive());
        assertEquals(0, closed.get());
        scope.leave();
        assertTrue(scope.close(0));
        assertEquals(1, closed.get());
        assertFalse(scope.enter());
    }

    @Test public void teardownRunsInReverseOwnershipOrderAndLateResourcesCloseImmediately() {
        ModuleSession scope = session();
        List<String> closed = new ArrayList<>();
        scope.onClose("reader", () -> closed.add("reader"));
        scope.onClose("worker", () -> closed.add("worker"));
        assertTrue(scope.close(0));
        scope.onClose("late listener", () -> closed.add("late"));
        assertTrue(scope.close(0));
        assertEquals(List.of("worker", "reader", "late"), closed);
    }

    @Test public void failedCleanupCannotBeAcceptedByASecondReloadRequest() {
        ModuleSession scope = session();
        AtomicInteger remaining = new AtomicInteger();
        scope.onClose("other", remaining::incrementAndGet);
        scope.onClose("blocked reader", () -> { throw new IllegalStateException("reader did not terminate"); });
        assertFalse(scope.close(0));
        assertFalse(scope.isActive());
        assertEquals(1, remaining.get());
        assertFalse(scope.close(0));
    }

    @Test public void nestedOldCodeIsRejectedBeforeStateTransfer() {
        ModuleSession scope = session();
        for (Object state : List.of(
                Map.of("listener", List.of(new OldGenerationObject())),
                Map.of("type", OldGenerationObject.class),
                new Object[]{OldGenerationObject.class.getClassLoader()})) {
            assertThrows(IllegalArgumentException.class, () -> scope.remember("unsafe", state));
        }
    }

    @Test public void snapshotChecksContainersAgainAfterTheyWereMutated() {
        ModuleSession scope = session();
        Map<String, Object> mutable = new LinkedHashMap<>();
        mutable.put("foreground", "com.example.app");
        scope.remember("state", mutable);
        mutable.put("callback", new OldGenerationObject());
        assertThrows(IllegalArgumentException.class, scope::snapshot);
    }

    @Test public void primitiveChargeStateAndHostObjectsCanBeRestoredWithoutOldModels() {
        ModuleSession old = session();
        Bundle charge = new Bundle();
        charge.putBoolean("admitted", true);
        charge.putInt("battery", 60);
        Object hostService = new Object();
        old.remember("charge", charge);
        old.remember("host", hostService);
        ModuleSession next = session();
        next.restore(old.snapshot());
        assertSame(hostService, next.host("host"));
        assertTrue(((Bundle) next.host("charge")).getBoolean("admitted"));
        assertEquals(60, ((Bundle) next.host("charge")).getInt("battery"));
    }

    @Test public void cleanupOnTheHostLooperNeverStopsThatLooper() throws Throwable {
        AtomicInteger closed = new AtomicInteger();
        Handler host = new Handler(Looper.getMainLooper());
        ModuleSession.onLooper(host, closed::incrementAndGet);
        assertEquals(1, closed.get());
        assertTrue(host.post(closed::incrementAndGet));
    }

    @Test public void aFailedUnregistrationDoesNotSkipOtherReleases() {
        AtomicInteger released = new AtomicInteger();
        Throwable failure = assertThrows(IllegalStateException.class, () -> ModuleSession.runAll(
                () -> { throw new IllegalStateException("receiver"); },
                released::incrementAndGet,
                () -> { throw new IllegalArgumentException("observer"); }));
        assertEquals(1, released.get());
        assertEquals(1, failure.getSuppressed().length);
    }
    @Test public void aFinishedPressCanReleaseItsSavedRetirementCallback() {
        ModuleSession scope = session();
        AtomicInteger released = new AtomicInteger();
        ModuleSession.Registration completed = scope.register("native press", released::incrementAndGet);
        completed.forget(); completed.forget();
        assertTrue(scope.close(0));
        assertEquals(0, released.get());
    }
    @Test public void scopeRemovalCanWaitUntilTheLastNativePressRegistrationFinishes() {
        ModuleSession scope = session();
        var first = scope.register("input.nativeLaserPress", () -> { });
        var second = scope.register("input.nativeLaserPress", () -> { });
        assertTrue(scope.hasResource("input.nativeLaserPress"));
        first.forget(); assertTrue(scope.hasResource("input.nativeLaserPress"));
        second.forget(); assertFalse(scope.hasResource("input.nativeLaserPress"));
    }
}
