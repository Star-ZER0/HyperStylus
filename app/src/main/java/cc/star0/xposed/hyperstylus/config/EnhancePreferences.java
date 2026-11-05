package cc.star0.xposed.hyperstylus.config;

import cc.star0.xposed.hyperstylus.common.Protocol;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/** One durable transaction precedes all notifications to scoped host processes. */
public final class EnhancePreferences {
    public static final String AUTHORITY = Protocol.MODULE + ".config";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/settings");
    public static final String UPDATE = Protocol.MODULE + ".CONFIG_CHANGED";
    private static final String[] HOSTS = {"android", Protocol.SECURITY_CORE, Protocol.SYSTEM_UI, Protocol.BLUETOOTH, Protocol.HOME, Protocol.SECURITY_CENTER};

    public static SharedPreferences preferences(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("hyperstylus", Context.MODE_PRIVATE);
    }

    public static EnhanceConfig load(Context context) { return EnhanceConfig.read(preferences(context)); }

    public static synchronized EnhanceConfig save(Context context, Bundle source) {
        SharedPreferences storage = preferences(context);
        long previous = EnhanceConfig.read(storage).revision;
        if (previous == Long.MAX_VALUE) throw new IllegalStateException("Stylus configuration revision exhausted");
        Bundle values = new Bundle(source);
        values.putLong("revision", Math.max(System.currentTimeMillis(), previous + 1));
        EnhanceConfig committed = new EnhanceConfig(values);
        committed.write(storage);
        publish(context, committed);
        return committed;
    }

    /** Reannounce a stored snapshot when the editor opens after an injected host's boot retries. */
    public static void publish(Context context, EnhanceConfig committed) {
        // Notification failures must not make the editor report a failed durable save.
        try { context.getContentResolver().notifyChange(URI, null); }
        catch (RuntimeException error) { Log.w("HyperStylus", "Configuration observer notification failed", error); }
        for (String host : HOSTS) {
            try { Protocol.send(context, new Intent(UPDATE).putExtras(committed.toBundle()), host); }
            catch (RuntimeException error) { Log.w("HyperStylus", "Configuration notification failed for " + host, error); }
        }
    }
    private EnhancePreferences() { }
}
