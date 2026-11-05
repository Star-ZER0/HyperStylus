package cc.star0.xposed.hyperstylus.runtime.keys;


import android.graphics.PointF;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class StylusHoverPointTest {
    /** Exact fields and disconnect behavior observed in the OTA TouchFilm manager. */
    private static final class NativePoint {
        private final Object mUpdatePointLock = new Object();
        private final PointF mStylusHoverPoint;
        private boolean mIsTouchFilmStylusConnected;
        public NativePoint(boolean connected, float x, float y) {
            mIsTouchFilmStylusConnected = connected;
            mStylusHoverPoint = new PointF(x, y);
        }
    }
    @Before public void clearPreviousPen() { StylusHover.forget(-1); StylusHover.configure(null); }

    @Test public void connectedNativePointIsCopiedWithoutChangingManagerState() {
        NativePoint manager = new NativePoint(true, 123f, 456f);
        PointF point = StylusHover.pointFor(manager, -1);
        assertEquals(123f, point.x, 0f); assertEquals(456f, point.y, 0f);
        assertNotSame(manager.mStylusHoverPoint, point);
        point.set(1f, 2f);
        assertEquals(123f, manager.mStylusHoverPoint.x, 0f);
        assertEquals(456f, manager.mStylusHoverPoint.y, 0f);
        assertTrue(manager.mIsTouchFilmStylusConnected);
    }

    @Test public void disconnectDoesNotBorrowTheNativeManagersRetainedStalePoint() {
        NativePoint manager = new NativePoint(false, 123f, 456f);
        assertUnknown(StylusHover.pointFor(manager, -1));
        assertFalse(manager.mIsTouchFilmStylusConnected);
        assertEquals(123f, manager.mStylusHoverPoint.x, 0f);
    }

    @Test public void nativeUpMissingLayoutAndInvalidCoordinatesUseTheFirmwareDefaultSentinel() {
        assertUnknown(StylusHover.pointFor(new NativePoint(true, -1f, -1f), -1));
        assertUnknown(StylusHover.pointFor(new NativePoint(true, Float.NaN, 5f), -1));
        assertUnknown(StylusHover.pointFor(new NativePoint(true, 5f, Float.POSITIVE_INFINITY), -1));
        assertUnknown(StylusHover.pointFor(new Object(), -1));
        assertUnknown(StylusHover.pointFor(null, -1));
    }

    private static void assertUnknown(PointF point) {
        assertEquals(-1f, point.x, 0f); assertEquals(-1f, point.y, 0f);
    }
}
