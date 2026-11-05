package cc.star0.xposed.hyperstylus.runtime.gesture;

import static org.junit.Assert.*;

import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SplitDividerCancellationTest {
    /** Exact fields and method types used by the verified Shell cancellation adapter. */
    private static final class Layout {
        private int mDividerPosition = 240;
        private boolean mResizingInMinimizedMode = true;
        private int visualPosition = 700, rollbacks, commits, snaps;
        private boolean failUpdate;
        public void updateDividerBounds(int position, boolean resizing) {
            if (failUpdate) throw new IllegalStateException("bounds unavailable");
            assertFalse("Cancellation must roll back the preview, not keep resizing", resizing);
            visualPosition = position; rollbacks++;
        }
    }

    private static final class Divider extends View {
        private boolean mMoving = true;
        private VelocityTracker mVelocityTracker = VelocityTracker.obtain();
        private Layout mSplitLayout = new Layout();
        private int cancellations;
        private boolean ready = true, sawMoving;
        Divider() { super(RuntimeEnvironment.getApplication()); }
        void dispatchNativeCancel(View owner, MotionEvent event) {
            assertSame(this, owner);
            assertEquals(MotionEvent.ACTION_CANCEL, event.getActionMasked());
            if (!ready) return; // Mirrors the ROM readiness guard before its terminal cleanup.
            cancellations++;
            sawMoving = mMoving;
            if (mMoving) { mSplitLayout.snaps++; mSplitLayout.commits++; }
            if (mVelocityTracker != null) mVelocityTracker.recycle();
            mVelocityTracker = null;
            mMoving = false;
            mSplitLayout.mResizingInMinimizedMode = false;
        }
        void dispose() {
            if (mVelocityTracker != null) { mVelocityTracker.recycle(); mVelocityTracker = null; }
        }
    }

    private static MotionEvent cancel() {
        return MotionEvent.obtain(100, 150, MotionEvent.ACTION_CANCEL, 900, 20, 0);
    }

    @Test public void movingDividerIsStoppedBeforeNativeCancelAndItsPreviewRollsBackWithoutASnap() throws Throwable {
        Divider owner = new Divider();
        MotionEvent input = cancel();
        try {
            SplitDividerCancellation adapter = new SplitDividerCancellation(Divider.class, Layout.class);
            adapter.prepare(owner, input, owner::dispatchNativeCancel).run();
            assertEquals(1, owner.cancellations);
            assertFalse(owner.sawMoving);
            assertFalse(owner.mMoving);
            assertNull(owner.mVelocityTracker);
            assertFalse(owner.mSplitLayout.mResizingInMinimizedMode);
            assertEquals(240, owner.mSplitLayout.visualPosition);
            assertEquals(240, owner.mSplitLayout.mDividerPosition);
            assertEquals(1, owner.mSplitLayout.rollbacks);
            assertEquals(0, owner.mSplitLayout.snaps);
            assertEquals(0, owner.mSplitLayout.commits);
        } finally { input.recycle(); owner.dispose(); }
    }

    @Test public void anUnmovedPressNeedsNativeCleanupWithoutAnUnnecessaryLayoutRollback() throws Throwable {
        Divider owner = new Divider();
        owner.mMoving = false;
        MotionEvent input = cancel();
        try {
            SplitDividerCancellation adapter = new SplitDividerCancellation(Divider.class, Layout.class);
            adapter.prepare(owner, input, owner::dispatchNativeCancel).run();
            assertEquals(1, owner.cancellations);
            assertNull(owner.mVelocityTracker);
            assertEquals(0, owner.mSplitLayout.rollbacks);
            assertEquals(0, owner.mSplitLayout.snaps);
        } finally { input.recycle(); owner.dispose(); }
    }

    @Test public void nativeReadinessGuardCannotBeReportedAsAReleasedPressAndTheSameCancellationCanRetry() throws Throwable {
        Divider owner = new Divider();
        owner.ready = false;
        MotionEvent input = cancel();
        try {
            SplitDividerCancellation adapter = new SplitDividerCancellation(Divider.class, Layout.class);
            SplitTouchFilter.Cancellation pending = adapter.prepare(owner, input, owner::dispatchNativeCancel);
            assertThrows(IllegalStateException.class, pending::run);
            assertNotNull(owner.mVelocityTracker);
            assertEquals(0, owner.cancellations);
            owner.ready = true;
            pending.run();
            assertNull(owner.mVelocityTracker);
            assertFalse(owner.mSplitLayout.mResizingInMinimizedMode);
            assertEquals(1, owner.cancellations);
            assertEquals(240, owner.mSplitLayout.visualPosition);
            assertEquals(0, owner.mSplitLayout.snaps);
        } finally { input.recycle(); owner.dispose(); }
    }

    @Test public void nativeFailureStillAttemptsRollbackAndAggregatesRollbackFailure() throws Throwable {
        Divider owner = new Divider();
        owner.mSplitLayout.failUpdate = true;
        MotionEvent input = cancel();
        try {
            SplitDividerCancellation adapter = new SplitDividerCancellation(Divider.class, Layout.class);
            IllegalArgumentException nativeFailure = new IllegalArgumentException("native cancel rejected");
            SplitTouchFilter.Cancellation pending = adapter.prepare(owner, input, (view, event) -> {
                owner.dispatchNativeCancel(view, event);
                throw nativeFailure;
            });
            Throwable failed = assertThrows(IllegalArgumentException.class, pending::run);
            assertSame(nativeFailure, failed);
            assertEquals(1, failed.getSuppressed().length);
            assertEquals("bounds unavailable", failed.getSuppressed()[0].getMessage());
            assertNull(owner.mVelocityTracker);
            assertEquals(0, owner.mSplitLayout.snaps);
        } finally { input.recycle(); owner.dispose(); }
    }

    private static final class WrongMoving extends View {
        private int mMoving;
        WrongMoving() { super(RuntimeEnvironment.getApplication()); }
    }
    private static final class StaticMoving extends View {
        private static boolean mMoving;
        StaticMoving() { super(RuntimeEnvironment.getApplication()); }
    }
    private static final class WrongLayout {
        private int mDividerPosition;
        private boolean mResizingInMinimizedMode;
        public boolean updateDividerBounds(int position, boolean resizing) { return true; }
    }
    private static final class WrongLayoutDivider extends View {
        private boolean mMoving;
        private VelocityTracker mVelocityTracker;
        private WrongLayout mSplitLayout;
        WrongLayoutDivider() { super(RuntimeEnvironment.getApplication()); }
    }

    @Test public void mismatchedOrStaticFieldsAndWrongReturnAbiAreRejectedBeforeInstallingTheAdapter() {
        assertThrows(NoSuchFieldException.class,
                () -> new SplitDividerCancellation(WrongMoving.class, Layout.class));
        assertThrows(NoSuchFieldException.class,
                () -> new SplitDividerCancellation(StaticMoving.class, Layout.class));
        assertThrows(NoSuchMethodException.class,
                () -> new SplitDividerCancellation(WrongLayoutDivider.class, WrongLayout.class));
    }
}
