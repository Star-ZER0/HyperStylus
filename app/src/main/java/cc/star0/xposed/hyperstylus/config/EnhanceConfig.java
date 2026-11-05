package cc.star0.xposed.hyperstylus.config;


import android.content.SharedPreferences;
import android.os.Bundle;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/** Validated immutable snapshot; input hooks read this object without Binder or disk access. */
public final class EnhanceConfig {
    public static final String APP_MAPPINGS = "app_mappings", PINCH = "system_pinch", DOUBLE_TAP = "system_double_tap", GESTURE_APPS = "gesture_apps";
    public static final String[] KEY_ENABLED_KEYS = {"focus_key_enabled", "upper_key_enabled", "lower_key_enabled"};
    private static final Pattern PACKAGE = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*");
    private static final Map<String, Object> SCHEMA = schema();
    public final boolean batteryEnabled, chargeLimitEnabled, proSettings;
    public final boolean signalEnabled;
    public final boolean chargingNotificationEnabled, attachedNotificationEnabled;
    public final boolean ignoreStylusBack, ignoreStylusSystemBars, ignoreStylusHomeRecents, ignoreStylusCaption, ignoreStylusCornerFreeform;
    public final boolean ignoreStylusFocusNotification, ignoreStylusSplitDivider, ignoreStylusSidebar;
    public final int chargeStart, chargeStop, displayMode, signalOne, signalTwo;
    public final long revision;
    public final StylusGestureConfig pinch, doubleTap;
    public final GestureAppConfig gestureApps;
    private final Bundle scalars;
    private final String[] bindings;
    private final String[] fullBindings;
    private final Map<String, AppKeyConfig> appMappings;

    public EnhanceConfig(Bundle source) {
        // Only known keys and their exact persisted types enter the snapshot.
        scalars = normalize(source);
        batteryEnabled = scalars.getBoolean("battery_enabled");
        chargeLimitEnabled = scalars.getBoolean("charge_limit_enabled");
        chargingNotificationEnabled = scalars.getBoolean("charging_notification_enabled");
        attachedNotificationEnabled = scalars.getBoolean("attached_notification_enabled");
        proSettings = scalars.getBoolean("pro_settings");
        signalEnabled = scalars.getBoolean("signal_enabled");
        ignoreStylusBack = scalars.getBoolean("ignore_stylus_back");
        ignoreStylusSystemBars = scalars.getBoolean("ignore_stylus_system_bars");
        ignoreStylusHomeRecents = scalars.getBoolean("ignore_stylus_home_recents");
        ignoreStylusCaption = scalars.getBoolean("ignore_stylus_caption");
        ignoreStylusCornerFreeform = scalars.getBoolean("ignore_stylus_corner_freeform");
        ignoreStylusFocusNotification = scalars.getBoolean("ignore_stylus_focus_notification");
        ignoreStylusSplitDivider = scalars.getBoolean("ignore_stylus_split_divider");
        ignoreStylusSidebar = scalars.getBoolean("ignore_stylus_sidebar");
        int start = scalars.getInt("charge_start"), stop = scalars.getInt("charge_stop");
        boolean chargeValid = start >= 0 && start <= 95 && stop >= 5 && stop <= 100 && stop - start >= 5;
        chargeStart = chargeValid ? start : 50; chargeStop = chargeValid ? stop : 80;
        int one = scalars.getInt("signal_one"), two = scalars.getInt("signal_two");
        boolean signalValid = one >= -120 && two <= -20 && one < two;
        signalOne = signalValid ? one : -80; signalTwo = signalValid ? two : -60;
        int mode = scalars.getInt("display_mode");
        displayMode = DisplayPolicy.valid(mode) ? mode : DisplayPolicy.BOTH;
        revision = Math.max(0, scalars.getLong("revision"));
        scalars.putInt("charge_start", chargeStart); scalars.putInt("charge_stop", chargeStop);
        scalars.putInt("signal_one", signalOne); scalars.putInt("signal_two", signalTwo);
        scalars.putInt("display_mode", displayMode); scalars.putLong("revision", revision);
        pinch = new StylusGestureConfig(StylusGestureConfig.Kind.PINCH, scalars.getString(PINCH));
        doubleTap = new StylusGestureConfig(StylusGestureConfig.Kind.DOUBLE_TAP, scalars.getString(DOUBLE_TAP));
        gestureApps = new GestureAppConfig(scalars.getString(GESTURE_APPS));
        bindings = new String[ActionConfig.KEYS.length];
        for (int index = 0; index < bindings.length; index++) bindings[index] = ActionConfig.sanitize(index, scalars.getString(ActionConfig.KEYS[index]));
        fullBindings = new String[ActionConfig.FULL_KEYS.length];
        for (int button = 0; button < fullBindings.length; button++) fullBindings[button] = ActionConfig.sanitizeFullKey(scalars.getString(ActionConfig.FULL_KEYS[button]));
        Map<String, AppKeyConfig> mappings = new TreeMap<>();
        JSONObject stored = ProfileData.object(scalars.getString(APP_MAPPINGS));
        for (var keys = stored.keys(); keys.hasNext();) {
            String pkg = keys.next(); JSONObject profile = stored.optJSONObject(pkg);
            if (!validPackage(pkg) || profile == null) continue;
            AppKeyConfig decoded = AppKeyConfig.decode(profile);
            if (decoded != null) mappings.put(pkg, decoded);
        }
        appMappings = Collections.unmodifiableMap(mappings);
    }

    private static Map<String, Object> schema() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("battery_enabled", true); result.put("charge_limit_enabled", false);
        result.put("charging_notification_enabled", false); result.put("attached_notification_enabled", false);
        result.put("charge_start", 50); result.put("charge_stop", 80);
        result.put("display_mode", DisplayPolicy.BOTH); result.put("pro_settings", true);
        result.put("signal_enabled", true);
        for (String key : KEY_ENABLED_KEYS) result.put(key, true);
        for (String key : new String[]{"ignore_stylus_back", "ignore_stylus_system_bars", "ignore_stylus_home_recents", "ignore_stylus_caption", "ignore_stylus_corner_freeform", "ignore_stylus_focus_notification", "ignore_stylus_split_divider", "ignore_stylus_sidebar"}) result.put(key, false);
        result.put("signal_one", -80); result.put("signal_two", -60); result.put("revision", 0L);
        result.put(APP_MAPPINGS, "{}"); result.put(PINCH, "{}"); result.put(DOUBLE_TAP, "{}"); result.put(GESTURE_APPS, "{}");
        for (int index = 0; index < ActionConfig.KEYS.length; index++) result.put(ActionConfig.KEYS[index], ActionConfig.DEFAULTS[index]);
        for (String key : ActionConfig.FULL_KEYS) result.put(key, ActionConfig.NONE);
        return Collections.unmodifiableMap(result);
    }

    @SuppressWarnings("deprecation")
    private static Bundle normalize(Bundle source) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (source != null) for (String key : SCHEMA.keySet()) values.put(key, source.get(key));
        return normalize(values);
    }

    private static Bundle normalize(Map<String, ?> values) {
        Bundle result = new Bundle();
        SCHEMA.forEach((key, fallback) -> {
            Object candidate = values.get(key);
            put(result, key, candidate != null && candidate.getClass() == fallback.getClass() ? candidate : fallback);
        });
        return result;
    }

    private static void put(Bundle values, String key, Object value) {
        if (value instanceof Boolean flag) values.putBoolean(key, flag);
        else if (value instanceof Integer number) values.putInt(key, number);
        else if (value instanceof Long number) values.putLong(key, number);
        else if (value instanceof String text) values.putString(key, text);
        else throw new IllegalArgumentException("Unsupported configuration type: " + key);
    }

    public static EnhanceConfig defaults() { return new EnhanceConfig(new Bundle()); }
    public boolean chargeNotificationsEnabled() { return chargingNotificationEnabled || attachedNotificationEnabled; }
    public String action(int binding) { ActionConfig.requireBinding(binding); return bindings[binding]; }
    public String fullAction(int button) { ActionConfig.requireButton(button); return fullBindings[button]; }
    public boolean keyEnabled(int button) { ActionConfig.requireButton(button); return scalars.getBoolean(KEY_ENABLED_KEYS[button]); }
    public boolean anyKeyEnabled() {
        for (int button = 0; button < KEY_ENABLED_KEYS.length; button++) if (keyEnabled(button)) return true;
        return false;
    }
    public Set<String> appPackages() { return appMappings.keySet(); }
    private AppKeyConfig profile(String pkg) { return pkg == null ? null : appMappings.get(pkg); }
    public boolean appEnabled(String pkg) { AppKeyConfig value = profile(pkg); return value != null && value.enabled; }
    public String appAction(String pkg, int binding) { AppKeyConfig value = profile(pkg); return value == null ? action(binding) : value.action(binding); }
    public String action(String pkg, int binding) { return appEnabled(pkg) ? appAction(pkg, binding) : action(binding); }
    public String appFullAction(String pkg, int button) { AppKeyConfig value = profile(pkg); return value == null ? fullAction(button) : value.fullAction(button); }
    public String fullAction(String pkg, int button) { return appEnabled(pkg) ? appFullAction(pkg, button) : fullAction(button); }
    public int fullKeyCodeFor(String pkg, int button) { return keyEnabled(button) ? Math.max(0, ActionConfig.keyCode(fullAction(pkg, button))) : 0; }
    public String[] actionsFor(String pkg, int button) {
        ActionConfig.requireButton(button);
        if (!keyEnabled(button)) return new String[]{ActionConfig.DEFAULT, ActionConfig.DEFAULT};
        return new String[]{action(pkg, button * 2), action(pkg, button * 2 + 1)};
    }

    public Bundle withAppEnabled(String pkg, boolean enabled) { return withProfile(pkg, editableProfile(pkg).enabled(enabled)); }
    public Bundle withAppAction(String pkg, int binding, String action) { return withProfile(pkg, editableProfile(pkg).action(binding, action)); }
    public Bundle withAppFullAction(String pkg, int button, String action) { return withProfile(pkg, editableProfile(pkg).fullAction(button, action)); }
    private AppKeyConfig editableProfile(String pkg) {
        if (!validPackage(pkg)) throw new IllegalArgumentException("Invalid application package");
        return appMappings.getOrDefault(pkg, new AppKeyConfig(false, bindings, fullBindings));
    }
    private Bundle withProfile(String pkg, AppKeyConfig value) {
        Map<String, AppKeyConfig> changed = new TreeMap<>(appMappings); changed.put(pkg, value);
        Bundle result = toBundle(); result.putString(APP_MAPPINGS, encode(changed)); return result;
    }
    public Bundle withAppsEnabled(Set<String> packages, boolean enabled) {
        Map<String, AppKeyConfig> changed = new TreeMap<>(appMappings);
        for (String pkg : packages) if (validPackage(pkg)) changed.put(pkg, changed.getOrDefault(pkg, new AppKeyConfig(false, bindings, fullBindings)).enabled(enabled));
        Bundle result = toBundle(); result.putString(APP_MAPPINGS, encode(changed)); return result;
    }

    public StylusGestureConfig gesture(StylusGestureConfig.Kind kind) { return kind == StylusGestureConfig.Kind.PINCH ? pinch : doubleTap; }
    public Bundle withStylusGesture(StylusGestureConfig value) {
        Bundle result = toBundle(); result.putString(value.kind == StylusGestureConfig.Kind.PINCH ? PINCH : DOUBLE_TAP, value.encode()); return result;
    }
    public int gestureMask() {
        return (ignoreStylusBack ? GestureAppConfig.BACK : 0) | (ignoreStylusSystemBars ? GestureAppConfig.SYSTEM_BARS : 0)
                | (ignoreStylusHomeRecents ? GestureAppConfig.HOME_RECENTS : 0) | (ignoreStylusCaption ? GestureAppConfig.CAPTION : 0)
                | (ignoreStylusCornerFreeform ? GestureAppConfig.CORNER : 0)
                | (ignoreStylusFocusNotification ? GestureAppConfig.FOCUS_NOTIFICATION : 0)
                | (ignoreStylusSplitDivider ? GestureAppConfig.SPLIT_DIVIDER : 0)
                | (ignoreStylusSidebar ? GestureAppConfig.SIDEBAR : 0);
    }
    public Bundle withGestureAppPackages(Set<String> packages, boolean selected) { return withGestureApps(gestureApps.withPackages(packages, selected, gestureMask())); }
    public Bundle withGestureApps(GestureAppConfig value) { Bundle result = toBundle(); result.putString(GESTURE_APPS, value.encode()); return result; }
    public static boolean validPackage(String pkg) { return pkg != null && pkg.length() <= 255 && PACKAGE.matcher(pkg).matches(); }

    private static String encode(Map<String, AppKeyConfig> profiles) {
        JSONObject result = new JSONObject();
        try { for (var entry : profiles.entrySet()) result.put(entry.getKey(), entry.getValue().toJson()); }
        catch (JSONException error) { throw new IllegalStateException("Cannot encode app mappings", error); }
        return ProfileData.encode(result);
    }

    public Bundle toBundle() {
        Bundle result = new Bundle(scalars);
        for (int index = 0; index < bindings.length; index++) result.putString(ActionConfig.KEYS[index], bindings[index]);
        for (int button = 0; button < fullBindings.length; button++) result.putString(ActionConfig.FULL_KEYS[button], fullBindings[button]);
        result.putString(APP_MAPPINGS, encode(appMappings)); result.putString(PINCH, pinch.encode());
        result.putString(DOUBLE_TAP, doubleTap.encode()); result.putString(GESTURE_APPS, gestureApps.encode());
        return result;
    }

    public static EnhanceConfig read(SharedPreferences preferences) { return new EnhanceConfig(normalize(preferences.getAll())); }

    @SuppressWarnings("deprecation")
    public void write(SharedPreferences preferences) {
        Bundle values = toBundle();
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : SCHEMA.keySet()) {
            Object value = values.get(key);
            if (value instanceof Boolean flag) editor.putBoolean(key, flag);
            else if (value instanceof Integer number) editor.putInt(key, number);
            else if (value instanceof Long number) editor.putLong(key, number);
            else if (value instanceof String text) editor.putString(key, text);
        }
        if (!editor.commit()) throw new IllegalStateException("Could not save stylus settings");
    }
}
