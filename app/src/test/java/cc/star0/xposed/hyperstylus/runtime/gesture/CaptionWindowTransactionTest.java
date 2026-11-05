package cc.star0.xposed.hyperstylus.runtime.gesture;

import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class CaptionWindowTransactionTest {
    @Test public void aFailedDisableRetainsItsClaimSoTheNextAttemptActuallyRestoresTheWindow() throws Throwable {
        CaptionWindowTransaction window = new CaptionWindowTransaction();
        int solid = FLAG_NOT_FOCUSABLE;
        int written = window.effective(solid, true);
        List<Integer> writes = new ArrayList<>();
        IllegalStateException rejection = new IllegalStateException("relayout failed");
        assertSame(rejection, assertThrows(IllegalStateException.class, () ->
                window.apply(written, solid, false, flags -> { writes.add(flags); throw rejection; })));
        assertTrue("The module still owns the uncertain NOT_TOUCHABLE write", window.owns(written));
        window.apply(written, solid, false, writes::add);
        assertEquals(List.of(solid, solid), writes);
        assertFalse(window.owns(written));
    }

    @Test public void enablingFailureMustBeCompensatedEvenWhenTheLocalFlagsStillLookNative() throws Throwable {
        CaptionWindowTransaction window = new CaptionWindowTransaction();
        int solid = FLAG_NOT_FOCUSABLE;
        int[] nativeWindow = {solid};
        List<Integer> writes = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> window.apply(solid, solid, true, flags -> {
            writes.add(flags); nativeWindow[0] = flags;
            throw new IllegalStateException("native applied before the reply failed");
        }));
        assertEquals(solid | FLAG_NOT_TOUCHABLE, nativeWindow[0]);
        // The caller reset its LayoutParams to solid after the failing write.
        window.apply(solid, solid, false, flags -> { writes.add(flags); nativeWindow[0] = flags; });
        assertEquals(solid, nativeWindow[0]);
        assertEquals(List.of(solid | FLAG_NOT_TOUCHABLE, solid), writes);
        window.apply(solid, solid, false, writes::add);
        assertEquals("A confirmed compensation needs no additional relayout", 2, writes.size());
    }

    @Test public void anExternalTouchableWindowCannotBeReportedAsPenPassthrough() throws Throwable {
        CaptionWindowTransaction window = new CaptionWindowTransaction();
        int solid = FLAG_NOT_FOCUSABLE;
        List<Integer> writes = new ArrayList<>();
        int externalTouchable = solid | 0x00400000;
        assertThrows(IllegalStateException.class,
                () -> window.apply(externalTouchable, solid, true, writes::add));
        assertTrue(writes.isEmpty());
        int externalPassthrough = externalTouchable | FLAG_NOT_TOUCHABLE;
        window.apply(externalPassthrough, solid, true, writes::add);
        window.apply(externalPassthrough, solid, false, writes::add);
        assertTrue("Native or external routing and its other bits remain untouched", writes.isEmpty());
        assertFalse(window.owns(externalPassthrough));
        int oldWrite = window.effective(solid, true);
        assertTrue(window.owns(oldWrite));
        window.apply(externalPassthrough, solid, true, writes::add);
        assertFalse("An external change ends the old claim even if its earlier value returns later", window.owns(oldWrite));
        window.apply(oldWrite, solid, false, writes::add);
        assertTrue(writes.isEmpty());
    }

    @Test public void aFailedCompensationRemainsRequiredUntilAWriteIsConfirmed() throws Throwable {
        CaptionWindowTransaction window = new CaptionWindowTransaction();
        int solid = FLAG_NOT_FOCUSABLE;
        List<Integer> writes = new ArrayList<>();
        CaptionWindowTransaction.Writer failing = flags -> {
            writes.add(flags); throw new IllegalStateException("reply unavailable");
        };
        assertThrows(IllegalStateException.class, () -> window.apply(solid, solid, true, failing));
        assertThrows(IllegalStateException.class, () -> window.apply(solid, solid, false, failing));
        window.apply(solid, solid, false, writes::add);
        assertEquals(List.of(solid | FLAG_NOT_TOUCHABLE, solid, solid), writes);
        window.apply(solid, solid, false, writes::add);
        assertEquals(3, writes.size());
    }

    @Test public void nativeIdleTransitionReleasesTheClaimWithoutReenablingWindowTouch() throws Throwable {
        CaptionWindowTransaction window = new CaptionWindowTransaction();
        int nativeSolid = FLAG_NOT_FOCUSABLE | 0x80004000;
        int nativeIdle = nativeSolid | FLAG_NOT_TOUCHABLE;
        List<Integer> writes = new ArrayList<>();
        window.apply(nativeSolid, nativeSolid, true, writes::add);
        assertEquals(List.of(nativeIdle), writes);
        assertTrue(window.owns(nativeIdle));
        window.apply(nativeIdle, nativeIdle, false, writes::add);
        assertFalse(window.owns(nativeIdle));
        assertEquals("The idle native window must remain untouchable", List.of(nativeIdle), writes);
    }

    @Test public void uncertainNativeWriteIsReconciledWithTheCurrentExternalFlagsWithoutOverwritingThem() throws Throwable {
        CaptionWindowTransaction window = new CaptionWindowTransaction();
        int solid = FLAG_NOT_FOCUSABLE;
        List<Integer> writes = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> window.apply(solid, solid, true, flags -> {
            writes.add(flags); throw new IllegalStateException("unknown result");
        }));
        int external = solid | FLAG_NOT_TOUCHABLE | 0x20000000;
        window.apply(external, solid, true, writes::add);
        window.apply(external, solid, true, writes::add);
        assertEquals(List.of(solid | FLAG_NOT_TOUCHABLE, external), writes);
        assertFalse(window.owns(external));
    }
}
