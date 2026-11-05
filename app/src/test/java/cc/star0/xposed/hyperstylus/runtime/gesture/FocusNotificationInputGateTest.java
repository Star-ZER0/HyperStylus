package cc.star0.xposed.hyperstylus.runtime.gesture;

import static org.junit.Assert.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Attribute only native reveal actions; never reserve the island's entire input stream. */
public class FocusNotificationInputGateTest {
    private static final int DOWN = 0, UP = 1, MOVE = 2, CANCEL = 3, POINTER_DOWN = 5, POINTER_UP = 6;

    @Test public void hiddenPenBlocksHorizontalRevealButLeavesClicksAndOtherIslandActionsAlone() {
        FocusNotificationInputGate gate = new FocusNotificationInputGate();
        Object input = new Object(), coordinator = new Object();
        boolean reveal = gate.observe(input, "window", DOWN, 100, 3, true, true, true);
        assertTrue(reveal);
        try (var ignored = gate.enter(coordinator, reveal, true)) {
            assertTrue(gate.blocksSwipe(coordinator));
            assertTrue(gate.blocksEvent(coordinator, true));
            assertFalse("Click, expand, update and controls are not horizontal swipe events",
                    gate.blocksEvent(coordinator, false));
        }
        assertFalse("A later notification update has no input attribution", gate.blocksEvent(coordinator, true));
        assertFalse(gate.blocksSwipe(coordinator));
    }

    @Test public void aVisibleIslandCanBeHiddenEvenIfItBecomesHiddenBeforeTheGestureEnds() {
        FocusNotificationInputGate gate = new FocusNotificationInputGate();
        Object input = new Object(), coordinator = new Object();
        assertFalse(gate.observe(input, "window", DOWN, 100, 3, true, true, false));
        assertFalse(gate.observe(input, "window", MOVE, 100, 3, true, true, true));
        boolean reveal = gate.observe(input, "window", UP, 100, 3, true, true, true);
        try (var ignored = gate.enter(coordinator, reveal, true)) {
            assertFalse(gate.blocksSwipe(coordinator));
            assertFalse(gate.blocksEvent(coordinator, true));
            assertFalse(gate.blocksEvent(coordinator, false));
        }
    }

    @Test public void hiddenPenTapAndVerticalMotionDoNotBlockNativeClickOrVerticalDispatch() {
        FocusNotificationInputGate gate = new FocusNotificationInputGate();
        Object input = new Object(), coordinator = new Object();
        boolean reveal = gate.observe(input, "window", DOWN, 100, 3, true, true, true);
        try (var ignored = gate.enter(coordinator, reveal, false)) {
            assertFalse(gate.blocksSwipe(coordinator));
            assertFalse(gate.blocksEvent(coordinator, false));
        }
    }

    @Test public void fingerOrMouseContactDoesNotAcquirePenAttribution() {
        FocusNotificationInputGate gate = new FocusNotificationInputGate();
        Object input = new Object(), coordinator = new Object();
        assertFalse(gate.observe(input, "window", DOWN, 100, 3, false, true, true));
        assertFalse(gate.observe(input, "window", MOVE, 100, 3, false, true, true));
        boolean reveal = gate.observe(input, "window", UP, 100, 3, false, true, true);
        try (var ignored = gate.enter(coordinator, reveal, true)) {
            assertFalse(gate.blocksSwipe(coordinator));
            assertFalse(gate.blocksEvent(coordinator, true));
        }
    }

    @Test public void hoverDoesNotCreateAContactGestureOrAuthorizeBlocking() {
        FocusNotificationInputGate gate = new FocusNotificationInputGate(); Object input = new Object();
        for (int action : new int[]{9, 7, 10}) {
            assertFalse(gate.observe(input, "window", action, 100, 3, true, true, true));
        }
        assertFalse(gate.observe(input, "window", MOVE, 100, 3, true, true, true));
    }

    @Test public void aMixedGestureKeepsPenAttributionWhenThePenLeavesBeforeTheFinger() {
        FocusNotificationInputGate gate = new FocusNotificationInputGate(); Object input = new Object();
        assertFalse(gate.observe(input, "window", DOWN, 100, 3, false, true, true));
        assertTrue(gate.observe(input, "window", POINTER_DOWN, 100, 3, true, true, true));
        assertTrue(gate.observe(input, "window", POINTER_UP, 100, 3, false, true, true));
        assertTrue(gate.observe(input, "window", MOVE, 100, 3, false, true, true));
        assertTrue(gate.observe(input, "window", UP, 100, 3, false, true, true));
        assertTrue("The same terminal event can traverse intercept and touch",
                gate.observe(input, "window", UP, 100, 3, false, true, true));
    }

    @Test public void policyChangesApplyOnTheNextDownInsteadOfChangingAnExistingGesture() {
        FocusNotificationInputGate gate = new FocusNotificationInputGate(); Object input = new Object();
        assertFalse(gate.observe(input, "window", DOWN, 100, 3, true, false, true));
        assertFalse(gate.observe(input, "window", MOVE, 100, 3, true, true, true));
        assertFalse(gate.observe(input, "window", UP, 100, 3, true, true, true));
        assertTrue(gate.observe(input, "window", DOWN, 200, 3, true, true, true));
        assertTrue(gate.observe(input, "window", MOVE, 200, 3, true, false, true));
        assertTrue(gate.observe(input, "window", CANCEL, 200, 3, true, false, true));
    }

    @Test public void repeatedDownAndTerminalTraversalsKeepTheirOriginalVisibilitySnapshot() {
        FocusNotificationInputGate gate = new FocusNotificationInputGate(); Object input = new Object();
        assertTrue(gate.observe(input, "window", DOWN, 100, 3, true, true, true));
        assertTrue(gate.observe(input, "window", DOWN, 100, 3, true, false, false));
        assertTrue(gate.observe(input, "window", CANCEL, 100, 3, true, false, false));
        assertTrue(gate.observe(input, "window", DOWN, 100, 3, true, false, false));
        assertTrue(gate.observe(input, "window", CANCEL, 100, 3, true, false, false));
        assertFalse(gate.observe(input, "window", DOWN, 200, 3, true, true, false));
        assertFalse(gate.observe(input, "window", DOWN, 200, 3, true, true, true));
    }

    @Test public void missingDownAndLateEventsDoNotBorrowOrDestroyAnotherGesturesPolicy() {
        FocusNotificationInputGate gate = new FocusNotificationInputGate(); Object input = new Object();
        assertFalse(gate.observe(input, "window", MOVE, 100, 3, true, true, true));
        assertFalse(gate.observe(input, "window", UP, 100, 3, true, true, true));
        assertTrue(gate.observe(input, "window", DOWN, 200, 3, true, true, true));
        assertFalse(gate.observe(input, "window", DOWN, 100, 3, false, false, false));
        assertFalse(gate.observe(input, "window", MOVE, 100, 3, true, true, true));
        assertFalse(gate.observe(input, "window", UP, 100, 3, true, true, true));
        assertTrue(gate.observe(input, "window", MOVE, 200, 3, true, true, true));
    }

    @Test public void ownersChannelsAndDevicesCannotBorrowEachOthersPenGesture() {
        FocusNotificationInputGate gate = new FocusNotificationInputGate(); Object first = new Object(), second = new Object();
        assertTrue(gate.observe(first, "status_bar", DOWN, 100, 3, true, true, true));
        assertFalse(gate.observe(first, "notif_shade", MOVE, 100, 3, true, true, true));
        assertFalse(gate.observe(second, "status_bar", MOVE, 100, 3, true, true, true));
        assertFalse(gate.observe(first, "status_bar", MOVE, 100, 4, true, true, true));
        assertFalse(gate.observe(first, "status_bar", DOWN, 100, 4, false, true, true));
        assertTrue(gate.observe(first, "status_bar", MOVE, 100, 3, true, true, true));
        assertFalse(gate.observe(first, "status_bar", MOVE, 100, 4, false, true, true));
        assertFalse(gate.observe(first, "notif_shade", DOWN, 100, 4, false, true, true));
        assertTrue(gate.observe(first, "status_bar", UP, 100, 3, true, true, true));
    }

    @Test public void aNewFingerGestureDoesNotInheritThePreviousPenSuppression() {
        FocusNotificationInputGate gate = new FocusNotificationInputGate(); Object input = new Object();
        assertTrue(gate.observe(input, "window", DOWN, 100, 3, true, true, true));
        assertTrue(gate.observe(input, "window", UP, 100, 3, true, true, true));
        assertFalse(gate.observe(input, "window", DOWN, 200, 3, false, true, true));
        assertFalse(gate.observe(input, "window", MOVE, 200, 3, false, true, true));
        assertFalse(gate.observe(input, "window", UP, 200, 3, false, true, true));
        assertFalse("A recycled or late duplicate cannot turn a completed finger gesture into pen input",
                gate.observe(input, "window", DOWN, 200, 3, true, true, true));
        assertFalse(gate.observe(input, "window", UP, 200, 3, true, true, true));
    }

    @Test public void nestedNativeDispatchRestoresItsParentAndNeverBlocksOtherCoordinators() {
        FocusNotificationInputGate gate = new FocusNotificationInputGate(); Object first = new Object(), second = new Object();
        try (var outer = gate.enter(first, true, true)) {
            assertTrue(gate.blocksSwipe(first));
            assertFalse(gate.blocksSwipe(second));
            assertFalse(gate.blocksEvent(second, true));
            try (var inner = gate.enter(second, false, true)) {
                assertFalse(gate.blocksSwipe(first));
                assertFalse(gate.blocksSwipe(second));
                assertFalse(gate.blocksEvent(first, true));
            }
            assertTrue(gate.blocksSwipe(first));
            assertTrue(gate.blocksEvent(first, true));
        }
        assertFalse(gate.blocksSwipe(first));
        assertFalse(gate.blocksEvent(first, true));
    }

    @Test public void aNativeExceptionCannotLeaveTheThreadMarkedAsPenInput() {
        FocusNotificationInputGate gate = new FocusNotificationInputGate(); Object coordinator = new Object();
        assertThrows(IllegalStateException.class, () -> {
            try (var ignored = gate.enter(coordinator, true, true)) {
                assertTrue(gate.blocksSwipe(coordinator));
                throw new IllegalStateException("Native handler failed");
            }
        });
        assertFalse(gate.blocksSwipe(coordinator));
        assertFalse(gate.blocksEvent(coordinator, true));
    }

    @Test public void unrelatedWorkOnAnotherThreadCannotInheritTheCurrentGesture() throws Exception {
        FocusNotificationInputGate gate = new FocusNotificationInputGate(); Object coordinator = new Object();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (var ignored = gate.enter(coordinator, true, true)) {
            assertFalse(worker.submit(() -> gate.blocksSwipe(coordinator)).get(5, TimeUnit.SECONDS));
            assertFalse(worker.submit(() -> gate.blocksEvent(coordinator, true)).get(5, TimeUnit.SECONDS));
            assertTrue(gate.blocksSwipe(coordinator));
        } finally {
            worker.shutdownNow();
        }
    }
}
