package cc.star0.xposed.hyperstylus.runtime.gesture;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import cc.star0.xposed.hyperstylus.common.Protocol;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** A successful void redirect submission is distinct from delivery of the original touch by the ROM. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SidebarMotionRedirectTest {
    private static final class NativeParams extends WindowManager.LayoutParams {
        public int miuiFlags;
        NativeParams(int type, int flags) { this.type = type; this.miuiFlags = flags; }
    }

    private static Field flags() throws ReflectiveOperationException {
        Field field = NativeParams.class.getField("miuiFlags"); field.setAccessible(true); return field;
    }
    private static View window(int type, int flags) {
        View view = new View(RuntimeEnvironment.getApplication());
        view.setLayoutParams(new NativeParams(type, flags));
        return view;
    }
    private static MotionEvent down() {
        MotionEvent event = MotionEvent.obtain(100, 120, MotionEvent.ACTION_DOWN, 25, 45, 0);
        event.setSource(InputDevice.SOURCE_STYLUS);
        return event;
    }

    @Test public void theNativeEventIdIsForwardedUnchangedIncludingSignedSourceBits() throws Throwable {
        View cover = window(2026, 32), otherCover = window(2026, 32 | 128);
        MotionEvent input = down();
        List<Integer> submitted = new ArrayList<>();
        int[] id = {0};
        SidebarMotionRedirect redirect = new SidebarMotionRedirect(event -> {
            assertSame("The original event, rather than a synthetic click, supplies the native ID", input, event);
            return id[0];
        }, flags(), submitted::add);
        try {
            for (int value : new int[]{0, 47, Integer.MIN_VALUE, -2}) {
                id[0] = value;
                assertTrue(redirect.submit(value == 47 ? otherCover : cover, input));
            }
            assertEquals(List.of(0, 47, Integer.MIN_VALUE, -2), submitted);
            assertEquals(MotionEvent.ACTION_DOWN, input.getActionMasked());
            assertEquals(100, input.getDownTime()); assertEquals(120, input.getEventTime());
            assertEquals(25f, input.getX(), 0f); assertEquals(45f, input.getY(), 0f);
            assertEquals(InputDevice.SOURCE_STYLUS, input.getSource());
        } finally { input.recycle(); }
    }

    @Test public void ineligibleWindowsNeverReadAnEventIdOrSubmitARedirect() throws Throwable {
        SidebarMotionRedirect redirect = new SidebarMotionRedirect(event -> {
            throw new AssertionError("An ineligible window cannot use native event-ID routing");
        }, flags(), id -> { throw new AssertionError("Redirect must not be requested"); });
        MotionEvent input = down();
        try {
            assertFalse(redirect.submit(window(2026, 0), input));
            assertFalse(redirect.submit(window(2003, 128), input));
            assertFalse("Expanded app menus never redirect pen input", redirect.submit(window(2003, 32), input));
            assertFalse(redirect.submit(window(2038, 32), input));
            View unrelated = new View(RuntimeEnvironment.getApplication());
            unrelated.setLayoutParams(new android.view.ViewGroup.LayoutParams(100, 100));
            assertFalse(redirect.submit(unrelated, input));
        } finally { input.recycle(); }
    }

    @Test public void onlyTheNativeMinusOneSentinelIsRejected() throws Throwable {
        int[] requests = {0};
        SidebarMotionRedirect redirect = new SidebarMotionRedirect(event -> -1, flags(), id -> requests[0]++);
        MotionEvent input = down();
        try {
            assertFalse(redirect.submit(window(2026, 32), input));
            assertEquals(0, requests[0]);
        } finally { input.recycle(); }
    }

    @Test public void getterAndRequestFailuresPropagateAndVoidSubmissionDoesNotClaimDelivery() throws Throwable {
        View view = window(2026, 32); MotionEvent input = down();
        IllegalStateException getterFailure = new IllegalStateException("event ID unavailable");
        IllegalArgumentException requestFailure = new IllegalArgumentException("native request failed");
        try {
            SidebarMotionRedirect unreadable = new SidebarMotionRedirect(event -> { throw getterFailure; }, flags(), id -> fail("No ID was obtained"));
            assertSame(getterFailure, assertThrows(IllegalStateException.class, () -> unreadable.submit(view, input)));
            SidebarMotionRedirect rejected = new SidebarMotionRedirect(event -> 99, flags(), id -> { throw requestFailure; });
            assertSame(requestFailure, assertThrows(IllegalArgumentException.class, () -> rejected.submit(view, input)));
            int[] requests = {0};
            SidebarMotionRedirect submitted = new SidebarMotionRedirect(event -> 99, flags(), id -> requests[0]++);
            assertTrue(submitted.submit(view, input));
            assertEquals(1, requests[0]);
        } finally { input.recycle(); }
    }

    @Test public void productionRedirectRequiresTheActualSystemApplicationContext() {
        Context application = RuntimeEnvironment.getApplication();
        assertThrows(IllegalStateException.class, () -> new SidebarMotionRedirect(getClass().getClassLoader(), application));
        Context nonSystemHost = new ContextWrapper(application) {
            @Override public String getPackageName() { return Protocol.SECURITY_CENTER; }
            @Override public ApplicationInfo getApplicationInfo() {
                ApplicationInfo info = new ApplicationInfo(); info.packageName = Protocol.SECURITY_CENTER; info.flags = 0;
                return info;
            }
        };
        assertThrows(IllegalStateException.class, () -> new SidebarMotionRedirect(getClass().getClassLoader(), nonSystemHost));
    }
}
