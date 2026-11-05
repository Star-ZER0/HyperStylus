package cc.star0.xposed.hyperstylus.config;


import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Selected applications replace the global suppression mask, including with a zero mask. */
public final class GestureAppConfig {
    private static final int VERSION = 2;
    public static final int BACK = 1, SYSTEM_BARS = 2, HOME_RECENTS = 4, CAPTION = 8, CORNER = 16,
            FOCUS_NOTIFICATION = 32, SPLIT_DIVIDER = 64, SIDEBAR = 128, ALL = 255;
    public final boolean enabled;
    private final Set<String> selected;
    private final Map<String, Integer> profiles;

    public GestureAppConfig(String json) {
        JSONObject values = ProfileData.object(json);
        boolean currentFormat = Integer.valueOf(VERSION).equals(values.opt("version"));
        enabled = currentFormat && Boolean.TRUE.equals(values.opt("enabled"));
        selected = currentFormat ? ProfileData.packages(values.optJSONArray("selected")) : Set.of();
        profiles = currentFormat ? ProfileData.integers(values.optJSONObject("profiles"), ALL) : Map.of();
    }

    private GestureAppConfig(boolean enabled, Set<String> selected, Map<String, Integer> profiles) {
        this.enabled = enabled;
        this.selected = Collections.unmodifiableSet(new TreeSet<>(selected));
        this.profiles = Collections.unmodifiableMap(new TreeMap<>(profiles));
    }

    public Set<String> selectedPackages() { return selected; }
    public int profileMask(String pkg, int globals) { return profiles.getOrDefault(pkg == null ? "" : pkg, globals & ALL); }
    public int mask(String pkg, int globals) { return enabled && selected.contains(pkg == null ? "" : pkg) ? profileMask(pkg, globals) : globals & ALL; }
    public GestureAppConfig withEnabled(boolean value) { return new GestureAppConfig(value, selected, profiles); }

    public GestureAppConfig withPackages(Set<String> packages, boolean value, int globals) {
        Set<String> selection = new TreeSet<>(selected);
        Map<String, Integer> retained = new TreeMap<>(profiles);
        for (String pkg : packages) if (EnhanceConfig.validPackage(pkg)) {
            if (value) { selection.add(pkg); retained.putIfAbsent(pkg, globals & ALL); }
            else selection.remove(pkg);
        }
        return new GestureAppConfig(enabled, selection, retained);
    }

    public GestureAppConfig withProfile(String pkg, int mask) {
        if (!EnhanceConfig.validPackage(pkg) || mask < 0 || mask > ALL) throw new IllegalArgumentException("Invalid application gesture mask");
        Map<String, Integer> retained = new TreeMap<>(profiles); retained.put(pkg, mask);
        return new GestureAppConfig(enabled, selected, retained);
    }

    public static int feature(String feature) {
        if (feature == null) return 0;
        return switch (feature) {
            case "back", "home-back" -> BACK;
            case "system-bars" -> SYSTEM_BARS;
            case "home-recents" -> HOME_RECENTS;
            case "window-caption" -> CAPTION;
            case "focus-notification" -> FOCUS_NOTIFICATION;
            case "split-divider" -> SPLIT_DIVIDER;
            case "sidebar" -> SIDEBAR;
            default -> 0;
        };
    }

    public String encode() {
        try { return ProfileData.encode(new JSONObject().put("version", VERSION).put("enabled", enabled)
                .put("selected", new JSONArray(selected)).put("profiles", new JSONObject(profiles))); }
        catch (JSONException error) { throw new IllegalStateException("Cannot encode gesture application policy", error); }
    }
}
