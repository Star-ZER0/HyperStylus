package cc.star0.xposed.hyperstylus.runtime.gesture;


import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Process;
import cc.star0.xposed.hyperstylus.common.Protocol;
import cc.star0.xposed.hyperstylus.config.EnhanceConfig;
import cc.star0.xposed.hyperstylus.config.GestureAppConfig;
import cc.star0.xposed.hyperstylus.module.ModuleSession;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class GestureFocusSnapshotTest {
    private static final AtomicLong CLIENT_REVISION = new AtomicLong(10_000);
    private static Bundle snapshot(long revision, String display, String app) {
        Bundle displays = new Bundle(); displays.putString(display, app);
        Bundle message = new Bundle(); message.putLong("revision", revision); message.putBundle("displays", displays);
        return message;
    }

    private static void installedUid(Context context, String pkg, int uid) {
        PackageInfo installed = new PackageInfo(); installed.packageName = pkg;
        installed.applicationInfo = new ApplicationInfo();
        installed.applicationInfo.packageName = pkg; installed.applicationInfo.uid = uid;
        shadowOf(context.getPackageManager()).installPackage(installed);
    }

    @Test public void sidebarFocusRequestsUseTheInstalledHostUidRatherThanAnUnrelatedSender() {
        Context context = RuntimeEnvironment.getApplication();
        int sidebarUid = 23041, homeUid = 23042, foreignUid = 23043;
        installedUid(context, Protocol.SECURITY_CENTER, sidebarUid);
        installedUid(context, Protocol.HOME, homeUid);
        installedUid(context, "com.example.foreign.focus", foreignUid);
        assertTrue(GestureFocusBridge.trustedRequest(context, Process.SYSTEM_UID));
        assertTrue(GestureFocusBridge.trustedRequest(context, sidebarUid));
        assertTrue(GestureFocusBridge.trustedRequest(context, homeUid));
        assertFalse(GestureFocusBridge.trustedRequest(context, foreignUid));
        assertFalse(GestureFocusBridge.trustedRequest(context, -1));
        assertFalse("A host UID must still resolve to an installed host package",
                GestureFocusBridge.trustedRequest(context, sidebarUid + 10));
    }

    @Test public void focusPublicationIncludesTheSidebarHostWithoutAddingOtherPackages() {
        String[] recipients = GestureFocusBridge.clientPackages();
        assertEquals(3, recipients.length);
        assertEquals(Set.of(Protocol.SYSTEM_UI, Protocol.HOME, Protocol.SECURITY_CENTER), Set.of(recipients));
        assertFalse(Set.of(recipients).contains(Protocol.MODULE));
        assertFalse(Set.of(recipients).contains(Protocol.SECURITY_CORE));
        assertFalse(Set.of(recipients).contains(Protocol.BLUETOOTH));
    }

    @Test public void aSidebarHostMayRequestFocusButCannotPublishASystemFocusSnapshot() {
        Context context = RuntimeEnvironment.getApplication();
        int sidebarUid = 23051;
        installedUid(context, Protocol.SECURITY_CENTER, sidebarUid);
        assertTrue(GestureFocusBridge.trustedRequest(context, sidebarUid));
        Bundle forged = snapshot(CLIENT_REVISION.incrementAndGet(), "0", "com.example.editor");
        forged.putInt("uid", Process.SYSTEM_UID); forged.putString("package", "android");
        int[] notifications = {0};
        ModuleSession.Registration observer = GestureFocusBridge.observeChanges(() -> notifications[0]++);
        try {
            assertFalse(GestureFocusBridge.acceptClientSnapshot(forged, sidebarUid));
            assertEquals(0, notifications[0]);
            assertTrue(GestureFocusBridge.acceptClientSnapshot(forged, Process.SYSTEM_UID));
            assertEquals(1, notifications[0]);
        } finally { observer.forget(); }
    }

    @Test public void onlyRealSystemSenderAndNewerRevisionMayReplaceFocus() {
        GestureFocusBridge.Snapshot focus = new GestureFocusBridge.Snapshot();
        assertFalse(focus.accept(snapshot(1, "0", "com.example.forged"), 19001));
        assertNull(focus.get(0));
        assertTrue(focus.accept(snapshot(2, "0", "com.example.editor"), Process.SYSTEM_UID));
        assertFalse(focus.accept(snapshot(1, "0", "com.example.old"), Process.SYSTEM_UID));
        assertEquals("com.example.editor", focus.get(0));
    }

    @Test public void invalidWholeSnapshotRetainsPreviousDisplaySet() {
        GestureFocusBridge.Snapshot focus = new GestureFocusBridge.Snapshot();
        assertTrue(focus.accept(snapshot(1, "0", "com.example.editor"), Process.SYSTEM_UID));
        assertFalse(focus.accept(snapshot(2, "invalid", "com.example.other"), Process.SYSTEM_UID));
        assertFalse(focus.accept(snapshot(3, "-1", "com.example.other"), Process.SYSTEM_UID));
        assertFalse(focus.accept(snapshot(4, "0", "not a package"), Process.SYSTEM_UID));
        assertEquals("com.example.editor", focus.get(0));
    }

    @Test public void emptyFocusAndRemovedDisplayCannotReusePreviousApplication() {
        GestureFocusBridge.Snapshot focus = new GestureFocusBridge.Snapshot();
        Bundle message = snapshot(1, "0", "com.example.editor");
        message.getBundle("displays").putString("2", "com.example.presentation");
        assertTrue(focus.accept(message, Process.SYSTEM_UID));
        assertEquals("com.example.presentation", focus.get(2));
        assertTrue(focus.accept(snapshot(2, "0", ""), Process.SYSTEM_UID));
        assertNull(focus.get(0)); assertNull(focus.get(2));
    }

    @Test public void displayPolicyUsesSelectedZeroProfilesAndFallsBackForUnknownFocus() {
        Bundle values = EnhanceConfig.defaults().toBundle();
        values.putBoolean("ignore_stylus_caption", true);
        EnhanceConfig base = new EnhanceConfig(values);
        GestureAppConfig rules = base.gestureApps.withEnabled(true)
                .withPackages(Set.of("com.example.editor", "com.example.presentation"), true, base.gestureMask())
                .withProfile("com.example.editor", 0)
                .withProfile("com.example.presentation", GestureAppConfig.CAPTION);
        EnhanceConfig config = new EnhanceConfig(base.withGestureApps(rules));
        Bundle message = snapshot(CLIENT_REVISION.incrementAndGet(), "0", "com.example.editor");
        message.getBundle("displays").putString("2", "com.example.presentation");
        message.getBundle("displays").putString("3", "com.example.unselected");
        assertTrue(GestureFocusBridge.acceptClientSnapshot(message, Process.SYSTEM_UID));
        assertFalse(GestureFocusBridge.enabledOnDisplay(0, GestureAppConfig.CAPTION, config, true));
        assertTrue(GestureFocusBridge.enabledOnDisplay(2, GestureAppConfig.CAPTION, config, true));
        assertTrue(GestureFocusBridge.enabledOnDisplay(3, GestureAppConfig.CAPTION, config, true));
        assertTrue(GestureFocusBridge.enabledOnDisplay(4, GestureAppConfig.CAPTION, config, true));
        assertTrue(GestureFocusBridge.enabledOnDisplay(-1, GestureAppConfig.CAPTION, config, true));
        assertTrue(GestureFocusBridge.acceptClientSnapshot(snapshot(CLIENT_REVISION.incrementAndGet(), "0", ""), Process.SYSTEM_UID));
        assertTrue(GestureFocusBridge.enabledOnDisplay(0, GestureAppConfig.CAPTION, config, true));
        assertTrue(GestureFocusBridge.enabledOnDisplay(2, GestureAppConfig.CAPTION, config, true));
        EnhanceConfig disabled = new EnhanceConfig(config.withGestureApps(rules.withEnabled(false)));
        assertFalse(GestureFocusBridge.enabledOnDisplay(0, GestureAppConfig.CAPTION, disabled, false));
    }

    @Test public void focusObserversRunOnlyForAcceptedClientSnapshotsAndCanBeForgotten() {
        int[] notifications = {0};
        ModuleSession.Registration registered = GestureFocusBridge.observeChanges(() -> notifications[0]++);
        try {
            long revision = CLIENT_REVISION.incrementAndGet();
            Bundle accepted = snapshot(revision, "0", "com.example.editor");
            assertFalse(GestureFocusBridge.acceptClientSnapshot(accepted, 19001));
            assertEquals(0, notifications[0]);
            assertTrue(GestureFocusBridge.acceptClientSnapshot(accepted, Process.SYSTEM_UID));
            assertEquals(1, notifications[0]);
            assertFalse(GestureFocusBridge.acceptClientSnapshot(accepted, Process.SYSTEM_UID));
            assertFalse(GestureFocusBridge.acceptClientSnapshot(snapshot(revision - 1, "0", "com.example.old"), Process.SYSTEM_UID));
            assertFalse(GestureFocusBridge.acceptClientSnapshot(snapshot(CLIENT_REVISION.incrementAndGet(), "bad", "com.example.invalid"), Process.SYSTEM_UID));
            assertEquals(1, notifications[0]);
            registered.forget(); registered.forget();
            assertTrue(GestureFocusBridge.acceptClientSnapshot(snapshot(CLIENT_REVISION.incrementAndGet(), "0", "com.example.other"), Process.SYSTEM_UID));
            assertEquals(1, notifications[0]);
        } finally { registered.forget(); }
    }

    @Test public void oneFailingFocusObserverDoesNotPreventOtherWindowsFromUpdating() {
        int[] calls = {0};
        ModuleSession.Registration failed = GestureFocusBridge.observeChanges(() -> { throw new IllegalStateException("window unavailable"); });
        ModuleSession.Registration healthy = GestureFocusBridge.observeChanges(() -> calls[0]++);
        try {
            assertTrue(GestureFocusBridge.acceptClientSnapshot(snapshot(CLIENT_REVISION.incrementAndGet(), "0", "com.example.editor"), Process.SYSTEM_UID));
            assertEquals(1, calls[0]);
        } finally { failed.forget(); healthy.forget(); }
    }

    @Test public void neutralReloadSnapshotUsesTheSameValidatedNotificationPath() {
        GestureFocusBridge.Snapshot previous = new GestureFocusBridge.Snapshot();
        assertTrue(previous.accept(snapshot(CLIENT_REVISION.incrementAndGet(), "7", "com.example.presentation"), Process.SYSTEM_UID));
        int[] calls = {0};
        ModuleSession.Registration observer = GestureFocusBridge.observeChanges(() -> calls[0]++);
        try {
            assertTrue(GestureFocusBridge.acceptClientSnapshot(previous.export(), Process.SYSTEM_UID));
            assertEquals(1, calls[0]);
            assertFalse(GestureFocusBridge.acceptClientSnapshot(previous.export(), Process.SYSTEM_UID));
            assertEquals(1, calls[0]);
        } finally { observer.forget(); }
    }
}
