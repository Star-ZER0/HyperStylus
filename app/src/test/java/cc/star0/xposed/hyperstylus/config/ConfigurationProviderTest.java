package cc.star0.xposed.hyperstylus.config;

import cc.star0.xposed.hyperstylus.common.Protocol;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Process;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ConfigurationProviderTest {
    private Context context;
    private ConfigProvider provider;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        EnhancePreferences.preferences(context).edit().clear().commit();
        provider = Robolectric.buildContentProvider(ConfigProvider.class).create().get();
    }

    @After public void clearCaller() { ShadowBinder.reset(); }

    @Test public void unscopedCallerCannotReadOrSpoofItsUidInExtras() {
        ShadowBinder.setCallingUid(19001);
        Bundle forged = new Bundle(); forged.putInt("uid", Process.SYSTEM_UID); forged.putString("package", Protocol.SYSTEM_UI);
        assertThrows(SecurityException.class, () -> provider.call("get_all", null, forged));
    }

    @Test public void scopedPackageUidCanReadCommittedConfigurationOnly() {
        PackageInfo host = new PackageInfo(); host.packageName = Protocol.SYSTEM_UI;
        host.applicationInfo = new ApplicationInfo(); host.applicationInfo.packageName = host.packageName; host.applicationInfo.uid = 19002;
        shadowOf(context.getPackageManager()).installPackage(host);
        Bundle changed = EnhanceConfig.defaults().toBundle(); changed.putBoolean("battery_enabled", false);
        EnhanceConfig saved = EnhancePreferences.save(context, changed);
        ShadowBinder.setCallingUid(19002);
        Bundle result = provider.call("get_all", null, null);
        assertNotNull(result); assertFalse(result.getBoolean("battery_enabled")); assertEquals(saved.revision, result.getLong("revision"));
        assertNull(provider.call("set_all", null, changed));
        assertFalse(EnhancePreferences.load(context).batteryEnabled);
    }

    @Test public void theSidebarHostsActualUidCanReadButClaimedPackageOrUidExtrasCannotAuthorizeAnotherCaller() {
        int sidebarUid = 19125;
        PackageInfo host = new PackageInfo(); host.packageName = Protocol.SECURITY_CENTER;
        host.applicationInfo = new ApplicationInfo(); host.applicationInfo.packageName = host.packageName;
        host.applicationInfo.uid = sidebarUid;
        shadowOf(context.getPackageManager()).installPackage(host);
        Bundle changed = EnhanceConfig.defaults().toBundle(); changed.putBoolean("ignore_stylus_sidebar", true);
        EnhanceConfig saved = EnhancePreferences.save(context, changed);
        ShadowBinder.setCallingUid(sidebarUid);
        Bundle read = provider.call("get_all", null, null);
        assertTrue(read.getBoolean("ignore_stylus_sidebar"));
        assertEquals(saved.revision, read.getLong("revision"));
        assertTrue(ConfigProvider.allowed(context, sidebarUid));
        ShadowBinder.setCallingUid(19126);
        Bundle extras = new Bundle(); extras.putInt("uid", sidebarUid); extras.putString("package", Protocol.SECURITY_CENTER);
        assertThrows(SecurityException.class, () -> provider.call("get_all", null, extras));
        assertFalse(ConfigProvider.allowed(context, 19126));
    }

    @Test public void exportedCrudNeverAllowsWrites() {
        assertNull(provider.query(EnhancePreferences.URI, null, null, null, null));
        assertThrows(UnsupportedOperationException.class, () -> provider.insert(EnhancePreferences.URI, new ContentValues()));
        assertThrows(UnsupportedOperationException.class, () -> provider.update(EnhancePreferences.URI, new ContentValues(), null, null));
        assertThrows(UnsupportedOperationException.class, () -> provider.delete(EnhancePreferences.URI, null, null));
    }

    @Test public void savesUseCurrentDeviceProtectedFileAndStrictlyIncreasingRevisions() {
        Bundle first = EnhanceConfig.defaults().toBundle(); first.putLong("revision", Long.MAX_VALUE); first.putBoolean("battery_enabled", false);
        EnhanceConfig saved = EnhancePreferences.save(context, first);
        assertNotEquals(Long.MAX_VALUE, saved.revision);
        Bundle stale = EnhanceConfig.defaults().toBundle(); stale.putLong("revision", 0);
        EnhanceConfig later = EnhancePreferences.save(context, stale);
        assertTrue(later.revision > saved.revision);
        assertEquals(later.revision, context.createDeviceProtectedStorageContext().getSharedPreferences("hyperstylus", Context.MODE_PRIVATE).getLong("revision", -1));
        assertTrue(EnhancePreferences.load(context).batteryEnabled);
    }

    @Test public void reopeningSettingsCanPublishWithoutChangingTheDurableRevision() {
        Bundle changed = EnhanceConfig.defaults().toBundle(); changed.putBoolean("battery_enabled", false);
        EnhanceConfig saved = EnhancePreferences.save(context, changed);
        EnhancePreferences.publish(context, saved);
        EnhanceConfig restored = EnhancePreferences.load(context);
        assertEquals(saved.revision, restored.revision);
        assertFalse(restored.batteryEnabled);
        assertTrue(shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents().stream()
                .anyMatch(event -> EnhancePreferences.UPDATE.equals(event.getAction()) && Protocol.SYSTEM_UI.equals(event.getPackage())
                        && event.getLongExtra("revision", -1) == saved.revision));
    }

    @Test public void simultaneousEditsCommitDistinctOrderedRevisions() throws Exception {
        var writers = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = writers.submit(() -> {
                start.await();
                return EnhancePreferences.save(context, EnhanceConfig.defaults().toBundle());
            });
            var second = writers.submit(() -> {
                start.await();
                Bundle changed = EnhanceConfig.defaults().toBundle(); changed.putBoolean("battery_enabled", false);
                return EnhancePreferences.save(context, changed);
            });
            start.countDown();
            EnhanceConfig a = first.get(10, TimeUnit.SECONDS), b = second.get(10, TimeUnit.SECONDS);
            assertNotEquals(a.revision, b.revision);
            EnhanceConfig latest = a.revision > b.revision ? a : b;
            EnhanceConfig committed = EnhancePreferences.load(context);
            assertEquals(latest.revision, committed.revision);
            assertEquals(latest.batteryEnabled, committed.batteryEnabled);
        } finally { writers.shutdownNow(); }
    }
}
