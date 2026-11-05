package cc.star0.xposed.hyperstylus.runtime.gesture;

import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static org.junit.Assert.*;

import org.junit.Test;

public class CaptionWindowFlagsTest {
    @Test public void solidCaptionBecomesUntouchableAndReturnsToItsNativeStateWhenReleased() {
        CaptionWindowFlags flags = new CaptionWindowFlags();
        int nativeSolid = FLAG_NOT_FOCUSABLE;
        int written = flags.effective(nativeSolid, true);
        assertEquals(24, written);
        assertTrue(flags.owns(written));
        assertEquals(nativeSolid, flags.restore(written, nativeSolid));
        assertFalse(flags.owns(written));
    }

    @Test public void anAlreadyIdleCaptionNeverBecomesModuleOwned() {
        CaptionWindowFlags flags = new CaptionWindowFlags();
        int nativeIdle = FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE;
        assertEquals(nativeIdle, flags.effective(nativeIdle, true));
        assertFalse(flags.owns(nativeIdle));
        assertEquals(nativeIdle, flags.restore(nativeIdle, FLAG_NOT_FOCUSABLE));
    }

    @Test public void nativeIdleTransitionKeepsPassthroughWhenTheModuleReleasesItsClaim() {
        CaptionWindowFlags flags = new CaptionWindowFlags();
        int written = flags.effective(FLAG_NOT_FOCUSABLE, true);
        int nowIdle = FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE;
        assertEquals(nowIdle, flags.restore(written, nowIdle));
        assertFalse(flags.owns(written));
    }

    @Test public void concurrentWindowChangesArePreservedWithoutLaterClaimingTheOldWriteAgain() {
        CaptionWindowFlags flags = new CaptionWindowFlags();
        int written = flags.effective(FLAG_NOT_FOCUSABLE, true);
        int externallyChanged = written | 0x20000000;
        assertFalse(flags.owns(externallyChanged));
        assertEquals(externallyChanged, flags.restore(externallyChanged, FLAG_NOT_FOCUSABLE));
        assertFalse(flags.owns(written));
        assertEquals(written, flags.restore(written, FLAG_NOT_FOCUSABLE));
    }

    @Test public void forcingAndRestoringPreserveUnrelatedAndUnknownNativeBits() {
        CaptionWindowFlags flags = new CaptionWindowFlags();
        int nativeFlags = 0x80400008;
        int written = flags.effective(nativeFlags, true);
        assertEquals(nativeFlags | FLAG_NOT_TOUCHABLE, written);
        assertEquals(nativeFlags, flags.restore(written, nativeFlags));
    }

    @Test public void restorationOnlyRestoresTheOwnedBitAndDoesNotCopyOtherNativeState() {
        CaptionWindowFlags flags = new CaptionWindowFlags();
        int nativeBefore = FLAG_NOT_FOCUSABLE | 0x00400000;
        int written = flags.effective(nativeBefore, true);
        int nativeNow = FLAG_NOT_FOCUSABLE | 0x00800000;
        assertEquals(nativeBefore, flags.restore(written, nativeNow));
    }

    @Test public void disablingAndRepeatedRestorationLeaveTheNativeResultIntact() {
        CaptionWindowFlags flags = new CaptionWindowFlags();
        int written = flags.effective(FLAG_NOT_FOCUSABLE, true);
        int restored = flags.restore(written, FLAG_NOT_FOCUSABLE);
        assertEquals(restored, flags.restore(restored, FLAG_NOT_FOCUSABLE));
        assertEquals(FLAG_NOT_FOCUSABLE, flags.effective(FLAG_NOT_FOCUSABLE, false));
        assertFalse(flags.owns(written));
        assertEquals(FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE,
                flags.effective(FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE, false));
    }

    @Test public void eachNewNativeWriteReplacesThePreviousOwnershipDecision() {
        CaptionWindowFlags flags = new CaptionWindowFlags();
        int forcedSolid = flags.effective(FLAG_NOT_FOCUSABLE, true);
        assertTrue(flags.owns(forcedSolid));
        int nativeIdle = FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE;
        assertEquals(nativeIdle, flags.effective(nativeIdle, true));
        assertFalse(flags.owns(forcedSolid));
        assertEquals(nativeIdle, flags.restore(nativeIdle, FLAG_NOT_FOCUSABLE));
        assertTrue(flags.owns(flags.effective(FLAG_NOT_FOCUSABLE, true)));
    }
}
