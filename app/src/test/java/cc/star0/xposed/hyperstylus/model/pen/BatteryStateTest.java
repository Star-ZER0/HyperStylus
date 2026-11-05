package cc.star0.xposed.hyperstylus.model.pen;


import org.junit.Test;
import static org.junit.Assert.*;

public class BatteryStateTest {
    @Test public void staleSnapshotNeverReconnectsOrReplacesNewerSample() {
        BatteryState state = new BatteryState();
        assertTrue(state.battery(75, 4, 1000));
        state.connection(6, 2000);
        assertTrue(state.accept(false, true, 50, 4, 2, 900, 2100, 1500, -1, 0, 1000, 2200));
        assertFalse(state.visible(2200)); assertFalse(state.charging); assertEquals(75, state.displayLevel(2200));
        assertFalse(state.battery(20, 4, 1500));
    }
    @Test public void fullAttachedPenRetainsPhysicalAttachmentWhenChargingEnds() {
        BatteryState state = new BatteryState();
        state.battery(100, 4, 1000); state.attachment(1, 1100); state.charge(2, 1200);
        assertFalse(state.charging); assertTrue(state.attached); assertTrue(state.hallKnown);
        state.attachment(0, 1300); assertFalse(state.attached);
        state.charge(4, 1200); assertFalse(state.charging);
    }
    @Test public void freshnessAndLinkRemainSeparate() {
        BatteryState state = new BatteryState(); state.battery(0, 2, 1000);
        assertEquals(1, BatteryState.bars(0)); assertEquals(5, BatteryState.bars(100));
        assertEquals(-1, state.displayLevel(999));
        assertEquals(-1, state.displayLevel(1000 + BatteryState.MAX_AGE_MS));
        assertTrue(state.visible(1000 + BatteryState.MAX_AGE_MS));
    }
    @Test public void chargingLimitRejectsPreviousSessionAndRequiresCurrentBattery() {
        ChargeLinkState link = new ChargeLinkState();
        assertTrue(link.connection(1, 1000, 900, "first", 1000));
        assertFalse(link.ready()); assertTrue(link.sample(70, 1000, 1000)); assertTrue(link.ready());
        assertTrue(link.connection(0, 1100, 0, "", 1100)); assertFalse(link.ready());
        assertFalse(link.connection(1, 1200, 900, "first", 1200));
        assertTrue(link.connection(1, 1300, 1250, "second", 1300));
        assertFalse(link.sample(70, 1000, 1300)); assertFalse(link.session(900, "first"));
        assertTrue(link.sample(70, 1300, 1300)); assertTrue(link.session(1250, "second"));
    }
    @Test public void chargingSessionRequiresSampleAtMostOneMinuteOld() {
        for (long age : new long[]{59_999, 60_000}) {
            ChargeLinkState link = new ChargeLinkState();
            link.connection(1, 1_000, 1_000, "pen", 1_000);
            assertTrue(link.sample(45, 1_000, 1_000 + age));
            assertTrue(link.ready());
        }
        for (long age : new long[]{-1, 60_001, 90_000}) {
            ChargeLinkState link = new ChargeLinkState();
            link.connection(1, 1_000, 1_000, "pen", 1_000);
            assertFalse(link.sample(45, 2_000, 2_000 + age));
            assertFalse(link.ready());
            // A fresh report in the same session can still confirm telemetry after rejection.
            assertTrue(link.sample(44, 100_000, 100_000));
            assertTrue(link.ready());
        }
    }
}
