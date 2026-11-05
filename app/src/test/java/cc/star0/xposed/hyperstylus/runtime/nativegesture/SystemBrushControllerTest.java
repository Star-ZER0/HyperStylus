package cc.star0.xposed.hyperstylus.runtime.nativegesture;

import cc.star0.xposed.hyperstylus.config.StylusGestureConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SystemBrushControllerTest {
    private static final class Database implements SystemBrushController.Database {
        public final Map<SystemBrushController.Key, String> values = new HashMap<>();
        public final List<String> operations;
        public boolean reject;
        public Database(List<String> operations) { this.operations = operations; }
        public String get(SystemBrushController.Key key) { return values.get(key); }
        public boolean put(SystemBrushController.Key key, String value) {
            operations.add("database");
            if (reject) return false;
            if (value == null) values.remove(key); else values.put(key, value);
            return true;
        }
    }
    private static final class Journal implements SystemBrushController.Journal {
        public Map<SystemBrushController.Key, SystemBrushController.Claim> values = Map.of();
        public final List<String> operations;
        public boolean fail;
        public Journal(List<String> operations) { this.operations = operations; }
        public Map<SystemBrushController.Key, SystemBrushController.Claim> read() { return values; }
        public void write(Map<SystemBrushController.Key, SystemBrushController.Claim> next) throws Exception {
            operations.add("journal");
            if (fail) throw new java.io.IOException("journal failed");
            values = Map.copyOf(next);
        }
    }
    private static StylusGestureConfig enabled() {
        return new StylusGestureConfig(StylusGestureConfig.Kind.PINCH, "{}").withEnabled(true).withGlobalAction(2);
    }
    @Test public void journalPrecedesWriteAndDifferentUserRestoresOriginalIncludingAbsentRow() throws Throwable {
        List<String> operations = new ArrayList<>(); Database db = new Database(operations); Journal journal = new Journal(operations);
        var zero = new SystemBrushController.Key(0, StylusGestureConfig.Kind.PINCH);
        var ten = new SystemBrushController.Key(10, StylusGestureConfig.Kind.PINCH);
        db.values.put(zero, "1"); SystemBrushController controller = new SystemBrushController(db, journal);
        controller.apply(enabled(), 0, null);
        assertEquals(List.of("journal", "database"), operations); assertEquals("2", db.get(zero));
        controller.apply(enabled(), 10, null); assertEquals("1", db.get(zero)); assertEquals("2", db.get(ten));
        controller.apply(enabled().withEnabled(false), 10, null); assertNull(db.get(ten)); assertTrue(journal.values.isEmpty());
    }
    @Test public void disablingPreservesExternalEditAfterTakingOwnership() throws Throwable {
        List<String> operations = new ArrayList<>(); Database db = new Database(operations); Journal journal = new Journal(operations);
        var key = new SystemBrushController.Key(0, StylusGestureConfig.Kind.PINCH); db.values.put(key, "1");
        SystemBrushController controller = new SystemBrushController(db, journal); controller.apply(enabled(), 0, null);
        db.values.put(key, "3"); controller.apply(enabled().withEnabled(false), 0, null);
        assertEquals("3", db.get(key)); assertTrue(journal.values.isEmpty());
    }
    @Test public void journalFailurePreventsSettingsMutation() throws Throwable {
        List<String> operations = new ArrayList<>(); Database db = new Database(operations); Journal journal = new Journal(operations);
        journal.fail = true; SystemBrushController controller = new SystemBrushController(db, journal);
        try { controller.apply(enabled(), 0, null); fail("Expected journal failure"); }
        catch (java.io.IOException expected) { assertEquals("journal failed", expected.getMessage()); }
        assertEquals(List.of("journal"), operations); assertTrue(db.values.isEmpty());
    }
    @Test public void failedRestoreRetainsOwnershipForRetryAndRestart() throws Throwable {
        List<String> operations = new ArrayList<>(); Database db = new Database(operations); Journal journal = new Journal(operations);
        var key = new SystemBrushController.Key(0, StylusGestureConfig.Kind.PINCH); db.values.put(key, "1");
        new SystemBrushController(db, journal).apply(enabled(), 0, null);
        db.reject = true; SystemBrushController restarted = new SystemBrushController(db, journal);
        try { restarted.apply(enabled().withEnabled(false), 0, null); fail("Expected failed restore"); }
        catch (IllegalStateException expected) { assertTrue(journal.values.containsKey(key)); }
        db.reject = false; restarted.apply(enabled().withEnabled(false), 0, null);
        assertEquals("1", db.get(key)); assertTrue(journal.values.isEmpty());
    }
}
