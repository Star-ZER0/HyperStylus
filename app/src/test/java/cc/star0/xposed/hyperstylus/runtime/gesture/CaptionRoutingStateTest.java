package cc.star0.xposed.hyperstylus.runtime.gesture;

import static org.junit.Assert.*;

import org.junit.Test;

public class CaptionRoutingStateTest {
    @Test public void unpublishedDisplaysUseTheirOwnFallbackUntilTheNativeChangeSucceeds() {
        CaptionRoutingState state = new CaptionRoutingState();
        assertTrue(state.enabled(0, true));
        assertFalse(state.enabled(0, false));
        assertFalse(state.active(0));
        int[] applied = {0};
        assertTrue(state.update(0, true, () -> applied[0]++));
        assertTrue(state.enabled(0, false));
        assertFalse(state.enabled(1, false));
        assertTrue(state.enabled(1, true));
        assertEquals(1, applied[0]);
    }

    @Test public void settingAndFocusChangesCannotAlterRoutingDuringThePress() {
        CaptionRoutingState state = new CaptionRoutingState();
        state.update(0, true, () -> { });
        state.begin(0, 100, 3);
        int[] applied = {0};
        assertFalse(state.update(0, false, () -> applied[0]++));
        assertFalse(state.update(0, true, () -> applied[0]++));
        assertTrue(state.active(0));
        assertTrue(state.enabled(0, false));
        assertEquals(0, applied[0]);
        state.end(0, 100, 3);
        assertTrue(state.update(0, false, () -> applied[0]++));
        assertFalse(state.enabled(0, true));
        assertEquals(1, applied[0]);
    }

    @Test public void allPhysicalDevicesOnADisplayMustFinishBeforeChangingItsRouting() {
        CaptionRoutingState state = new CaptionRoutingState();
        state.update(0, true, () -> { });
        state.begin(0, 100, 3);
        state.begin(0, 110, 4);
        state.end(0, 100, 3);
        assertTrue(state.active(0));
        assertFalse(state.update(0, false, () -> fail("The other device still owns a native stream")));
        state.end(0, 110, 4);
        assertFalse(state.active(0));
        assertTrue(state.update(0, false, () -> { }));
        assertFalse(state.enabled(0, true));
    }

    @Test public void anotherDisplayCanChangeRoutingWhileTheFirstDisplayHasInput() {
        CaptionRoutingState state = new CaptionRoutingState();
        state.update(0, true, () -> { });
        state.update(1, false, () -> { });
        state.begin(0, 100, 3);
        assertTrue(state.update(1, true, () -> { }));
        assertTrue(state.enabled(1, false));
        assertFalse(state.update(0, false, () -> fail("Display zero is still active")));
        state.end(1, 100, 3);
        assertTrue(state.active(0));
        state.end(0, 100, 3);
        assertFalse(state.active(0));
    }

    @Test public void duplicateDownDoesNotNeedExtraUpAndMismatchedUpDoesNotEndThePress() {
        CaptionRoutingState state = new CaptionRoutingState();
        state.begin(0, 100, 3);
        state.begin(0, 100, 3);
        state.end(0, 100, 4);
        state.end(0, 99, 3);
        assertTrue(state.active(0));
        state.end(0, 100, 3);
        assertFalse(state.active(0));
    }

    @Test public void replacedPressIgnoresItsOldUpAndAnOutOfOrderDown() {
        CaptionRoutingState state = new CaptionRoutingState();
        state.begin(0, 100, 3);
        state.begin(0, 200, 3);
        state.begin(0, 150, 3);
        state.end(0, 100, 3);
        state.end(0, 150, 3);
        assertTrue(state.active(0));
        state.end(0, 200, 3);
        assertFalse(state.active(0));
    }

    @Test public void nativeApplyFailurePreservesTheLastPublishedRoutingAndCanRetry() {
        CaptionRoutingState state = new CaptionRoutingState();
        state.update(0, true, () -> { });
        IllegalStateException rejected = new IllegalStateException("Window relayout failed");
        assertSame(rejected, assertThrows(IllegalStateException.class,
                () -> state.update(0, false, () -> { throw rejected; })));
        assertTrue(state.enabled(0, false));
        assertTrue(state.update(0, false, () -> { }));
        assertFalse(state.enabled(0, true));
    }

    @Test public void failedInitialApplyDoesNotReplaceTheFallbackWithAnUncommittedDecision() {
        CaptionRoutingState state = new CaptionRoutingState();
        assertThrows(IllegalArgumentException.class,
                () -> state.update(0, false, () -> { throw new IllegalArgumentException("Unsupported ABI"); }));
        assertTrue(state.enabled(0, true));
        assertFalse(state.enabled(0, false));
        assertTrue(state.update(0, true, () -> { }));
    }

    @Test public void unchangedRoutingDoesNotRelayoutWindowsAgain() {
        CaptionRoutingState state = new CaptionRoutingState();
        int[] applied = {0};
        assertTrue(state.update(0, false, () -> applied[0]++));
        assertFalse(state.update(0, false, () -> applied[0]++));
        assertEquals(1, applied[0]);
    }

    @Test public void clearRemovesPublishedRoutingAndAllDeviceOwnership() {
        CaptionRoutingState state = new CaptionRoutingState();
        state.update(0, true, () -> { });
        state.begin(0, 100, 3);
        state.begin(1, 200, 4);
        state.clear();
        assertFalse(state.active(0));
        assertFalse(state.active(1));
        assertFalse(state.enabled(0, false));
        assertTrue(state.update(0, false, () -> { }));
    }

    @Test public void removedDeviceClearsItsActivityAcrossDisplaysWithoutDiscardingPublishedRouting() {
        CaptionRoutingState state = new CaptionRoutingState();
        state.update(0, true, () -> { });
        state.update(1, false, () -> { });
        state.begin(0, 100, 3);
        state.begin(1, 200, 3);
        state.begin(2, 300, 3);
        state.forgetDevice(3);
        assertFalse(state.active(0));
        assertFalse(state.active(1));
        assertFalse(state.active(2));
        assertTrue(state.enabled(0, false));
        assertFalse(state.enabled(1, true));
        assertTrue(state.enabled(2, true));
        assertFalse(state.enabled(2, false));
        assertTrue(state.update(0, false, () -> { }));
        assertTrue(state.update(1, true, () -> { }));
    }

    @Test public void anotherDevicesActivePressStillDefersRoutingAfterDeviceRemoval() {
        CaptionRoutingState state = new CaptionRoutingState();
        state.update(0, true, () -> { });
        state.update(1, false, () -> { });
        state.begin(0, 100, 3);
        state.begin(0, 110, 4);
        state.begin(1, 120, 3);
        state.forgetDevice(3);
        state.forgetDevice(3);
        state.end(0, 100, 3);
        assertTrue(state.active(0));
        assertFalse(state.update(0, false, () -> fail("Device four still owns the native stream")));
        assertFalse(state.active(1));
        assertTrue(state.update(1, true, () -> { }));
        state.end(0, 110, 4);
        assertTrue(state.update(0, false, () -> { }));
    }
}
