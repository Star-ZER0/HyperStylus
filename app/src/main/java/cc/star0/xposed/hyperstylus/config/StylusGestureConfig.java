package cc.star0.xposed.hyperstylus.config;


import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Native brush choices share one immutable selection model for both physical gestures. */
public final class StylusGestureConfig {
    private static final int VERSION = 1;
    public enum Kind {
        PINCH("stylus_pinch_status", 5, 5), DOUBLE_TAP("stylus_double_click_status", 1, 4);
        public final String key;
        public final int defaultAction, maxAction;
        Kind(String key, int defaultAction, int maxAction) { this.key = key; this.defaultAction = defaultAction; this.maxAction = maxAction; }
        public boolean valid(int action) { return action >= 0 && action <= maxAction; }
    }

    public final Kind kind;
    public final boolean enabled;
    public final int globalAction;
    private final Set<String> selected;
    private final Map<String, Integer> actions;

    public StylusGestureConfig(Kind kind, String json) {
        this(kind, ProfileData.object(json));
    }

    private StylusGestureConfig(Kind kind, JSONObject values) {
        this.kind = kind;
        boolean currentFormat = Integer.valueOf(VERSION).equals(values.opt("version"));
        enabled = currentFormat && Boolean.TRUE.equals(values.opt("enabled"));
        globalAction = currentFormat ? ProfileData.integer(values.opt("global_action"), kind.maxAction, kind.defaultAction) : kind.defaultAction;
        selected = currentFormat ? ProfileData.packages(values.optJSONArray("selected")) : Set.of();
        actions = currentFormat ? ProfileData.integers(values.optJSONObject("app_actions"), kind.maxAction) : Map.of();
    }

    private StylusGestureConfig(Kind kind, boolean enabled, int global, Set<String> selected, Map<String, Integer> actions) {
        this.kind = kind; this.enabled = enabled; globalAction = global;
        this.selected = Collections.unmodifiableSet(new TreeSet<>(selected));
        this.actions = Collections.unmodifiableMap(new TreeMap<>(actions));
    }

    public Set<String> selectedPackages() { return selected; }
    public int appAction(String pkg) { return actions.getOrDefault(pkg == null ? "" : pkg, globalAction); }
    public int action(String pkg) { return selected.contains(pkg == null ? "" : pkg) ? appAction(pkg) : globalAction; }
    public StylusGestureConfig withEnabled(boolean value) { return new StylusGestureConfig(kind, value, globalAction, selected, actions); }
    public StylusGestureConfig withGlobalAction(int value) {
        requireAction(value);
        return new StylusGestureConfig(kind, enabled, value, selected, actions);
    }

    public StylusGestureConfig withPackages(Set<String> packages, boolean value) {
        Set<String> selection = new TreeSet<>(selected);
        Map<String, Integer> profiles = new TreeMap<>(actions);
        for (String pkg : packages) if (EnhanceConfig.validPackage(pkg)) {
            if (value) { selection.add(pkg); profiles.putIfAbsent(pkg, globalAction); }
            else selection.remove(pkg);
        }
        return new StylusGestureConfig(kind, enabled, globalAction, selection, profiles);
    }

    public StylusGestureConfig withAppAction(String pkg, int value) {
        if (!EnhanceConfig.validPackage(pkg)) throw new IllegalArgumentException("Invalid application package");
        requireAction(value);
        Map<String, Integer> profiles = new TreeMap<>(actions); profiles.put(pkg, value);
        return new StylusGestureConfig(kind, enabled, globalAction, selected, profiles);
    }

    private void requireAction(int value) {
        if (!kind.valid(value)) throw new IllegalArgumentException("Invalid " + kind + " action: " + value);
    }

    public String encode() {
        try {
            return ProfileData.encode(new JSONObject().put("version", VERSION).put("enabled", enabled).put("global_action", globalAction)
                    .put("selected", new JSONArray(selected)).put("app_actions", new JSONObject(actions)));
        } catch (JSONException error) { throw new IllegalStateException("Cannot encode native gesture configuration", error); }
    }
}
