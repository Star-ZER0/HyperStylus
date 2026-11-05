package cc.star0.xposed.hyperstylus.config;

import cc.star0.xposed.hyperstylus.common.Protocol;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Bundle;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;

/** Manifest endpoints and cross-process authorization share the module's real identity. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ModuleIdentityTest {
    // Deliberate foreign-package fixtures, not production module identities.
    private static final String FOREIGN_PACKAGE = "com.example.untrusted.sender";
    private static final int FOREIGN_UID = 19071;
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        EnhancePreferences.preferences(context).edit().clear().commit();
        shadowOf(RuntimeEnvironment.getApplication()).clearBroadcastIntents();
        PackageInfo foreign = new PackageInfo(); foreign.packageName = FOREIGN_PACKAGE;
        foreign.applicationInfo = new ApplicationInfo(); foreign.applicationInfo.packageName = FOREIGN_PACKAGE;
        foreign.applicationInfo.uid = FOREIGN_UID;
        shadowOf(context.getPackageManager()).installPackage(foreign);
    }

    @After public void resetCaller() { ShadowBinder.reset(); }
    @Test public void nativeScopeRemovalRequestsUseTheSameRealModuleIdentityBoundary() throws Exception {
        int moduleUid = context.getPackageManager().getApplicationInfo(Protocol.MODULE, 0).uid;
        Intent request = new Intent(ConfigBridge.PREPARE_SCOPE_REMOVAL).putExtra("request_id", "example")
                .putExtra("package", Protocol.MODULE).putExtra("uid", moduleUid);
        assertTrue(ConfigBridge.trustedScopeRemoval(context, moduleUid, request));
        assertFalse(ConfigBridge.trustedScopeRemoval(context, FOREIGN_UID, request));
        assertFalse(ConfigBridge.trustedScopeRemoval(context, -1, request));
        assertFalse(ConfigBridge.trustedScopeRemoval(context, moduleUid, new Intent(EnhancePreferences.UPDATE)));
    }

    @Test public void savedConfigurationResolvesThroughTheCurrentManifestProvider() {
        ProviderInfo declared = context.getPackageManager().resolveContentProvider(EnhancePreferences.URI.getAuthority(), 0);
        assertNotNull("Preferences URI must resolve to the actual manifest declaration", declared);
        assertEquals(context.getPackageName(), declared.packageName);
        assertEquals(ConfigProvider.class.getName(), declared.name);
        assertTrue(declared.exported); assertTrue(declared.directBootAware);
        assertNull(context.getPackageManager().resolveContentProvider(FOREIGN_PACKAGE + ".config", 0));
        Bundle changed = EnhanceConfig.defaults().toBundle(); changed.putBoolean("battery_enabled", false);
        EnhanceConfig saved = EnhancePreferences.save(context, changed);
        Bundle read = context.getContentResolver().call(EnhancePreferences.URI, "get_all", null, null);
        assertNotNull(read); assertEquals(saved.revision, read.getLong("revision")); assertFalse(read.getBoolean("battery_enabled"));
        assertEquals(EnhancePreferences.AUTHORITY, Uri.parse(EnhancePreferences.URI.toString()).getAuthority());
    }

    @Test public void foreignUidCannotReadPreferencesOrImpersonateTheirUpdateSender() throws Exception {
        ConfigProvider provider = Robolectric.buildContentProvider(ConfigProvider.class).create().get();
        ShadowBinder.setCallingUid(FOREIGN_UID);
        assertThrows(SecurityException.class, () -> provider.call("get_all", null, null));
        int currentUid = context.getPackageManager().getApplicationInfo(context.getPackageName(), 0).uid;
        Intent forged = new Intent(EnhancePreferences.UPDATE).putExtra("uid", currentUid).putExtra("package", context.getPackageName());
        assertFalse(ConfigBridge.trustedUpdate(context, FOREIGN_UID, forged));
        assertTrue(ConfigBridge.trustedUpdate(context, currentUid, forged));
        assertFalse(ConfigBridge.trustedUpdate(context, currentUid, new Intent(FOREIGN_PACKAGE + ".CONFIG_CHANGED")));
        assertFalse(ConfigBridge.trustedUpdate(context, -1, forged));
    }

    @Test public void durableSavePublishesOneCurrentNamespaceSnapshotToEveryScopedHost() {
        Bundle changed = EnhanceConfig.defaults().toBundle(); changed.putBoolean("battery_enabled", false);
        EnhanceConfig committed = EnhancePreferences.save(context, changed);
        List<Intent> sent = shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents();
        assertEquals(6, sent.size());
        assertEquals(Set.of("android", Protocol.SYSTEM_UI, Protocol.BLUETOOTH, Protocol.SECURITY_CORE, Protocol.HOME, Protocol.SECURITY_CENTER),
                sent.stream().map(Intent::getPackage).collect(java.util.stream.Collectors.toSet()));
        for (Intent event : sent) {
            assertEquals(context.getPackageName() + ".CONFIG_CHANGED", event.getAction());
            assertEquals(committed.revision, event.getLongExtra("revision", -1));
            assertFalse(event.getBooleanExtra("battery_enabled", true));
            assertTrue(BroadcastOptions.fromBundle(shadowOf(RuntimeEnvironment.getApplication()).getBroadcastOptions(event))
                    .isShareIdentityEnabled());
        }
    }

    @Test public void sendingTwoHostMessagesPreservesTheTemplateAndItsSeparateRecipients() {
        Intent template = new Intent(Protocol.REQUEST).putExtra("reply_to", "android");
        Protocol.send(context, template, Protocol.BLUETOOTH);
        Protocol.send(context, template, Protocol.SYSTEM_UI);
        assertNull(template.getPackage());
        List<Intent> sent = shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents();
        assertEquals(2, sent.size());
        assertEquals(Protocol.BLUETOOTH, sent.get(0).getPackage());
        assertEquals(Protocol.SYSTEM_UI, sent.get(1).getPackage());
        assertNotSame(sent.get(0), sent.get(1));
    }
}
