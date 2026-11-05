package cc.star0.xposed.hyperstylus.common;


import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/** Package-scoped module messages and stable ROM host/action identifiers. */
public final class Protocol {
    public static final String MODULE = "cc.star0.xposed.hyperstylus";
    public static final String SYSTEM_UI = "com.android.systemui";
    public static final String BLUETOOTH = "com.xiaomi.bluetooth";
    public static final String SECURITY_CORE = "com.miui.securitycore";
    public static final String SECURITY_CENTER = "com.miui.securitycenter";
    public static final String HOME = "com.miui.home";
    public static final String BATTERY = "com.android.settings.stylus.STYLUS_BATTERY_NOTIFY";
    public static final String SOC = "com.android.settings.stylus.STYLUS_STATE_SOC";
    public static final String ATTACH = "com.android.settings.stylus.STYLUS_STATE_ATT";
    public static final String RELAY = MODULE + ".RELAY";
    public static final String REQUEST = MODULE + ".REQUEST";
    public static final String CHARGE_ACTION = MODULE + ".CHARGE_ACTION";
    private Protocol() {}

    public static boolean from(Context context, int uid, String pkg) {
        if (context == null || uid < 0 || pkg == null) return false;
        try { return context.getPackageManager().getApplicationInfo(pkg, 0).uid == uid; }
        catch (PackageManager.NameNotFoundException | RuntimeException unavailable) { return false; }
    }
    public static void send(Context context, Intent event, String destination) {
        Intent addressed = new Intent(event).setPackage(destination).addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        context.sendBroadcast(addressed, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
}
