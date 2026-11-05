package cc.star0.xposed.hyperstylus.config;

import cc.star0.xposed.hyperstylus.common.Protocol;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/** Exported for injection hosts, but never exposes a write operation or trusts caller extras. */
public final class ConfigProvider extends ContentProvider {
    private static final String[] SCOPED_HOSTS = {Protocol.SECURITY_CORE, Protocol.SYSTEM_UI, Protocol.BLUETOOTH, Protocol.HOME, Protocol.SECURITY_CENTER};

    @Override public boolean onCreate() { return true; }

    public static boolean allowed(Context context, int uid) {
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID) return true;
        for (String host : SCOPED_HOSTS) if (Protocol.from(context, uid, host)) return true;
        return false;
    }

    @Override public Bundle call(String method, String argument, Bundle extras) {
        Context context = requireContext();
        if (!allowed(context, Binder.getCallingUid())) throw new SecurityException("Stylus configuration is restricted to scoped hosts");
        return "get_all".equals(method) ? EnhancePreferences.load(context).toBundle() : null;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd." + EnhancePreferences.AUTHORITY; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw readOnly(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) { throw readOnly(); }
    @Override public int delete(Uri uri, String selection, String[] arguments) { throw readOnly(); }
    private static UnsupportedOperationException readOnly() { return new UnsupportedOperationException("Read only"); }
}
