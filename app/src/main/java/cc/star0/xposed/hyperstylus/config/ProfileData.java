package cc.star0.xposed.hyperstylus.config;


import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** All JSON decoding passes through the same validation boundary. */
public final class ProfileData {
    public static JSONObject object(String json) {
        if (json == null || json.isBlank()) return new JSONObject();
        try { return new JSONObject(json); }
        catch (JSONException error) { return new JSONObject(); }
    }

    public static int integer(Object value, int maximum, int fallback) {
        if (!(value instanceof Number number)) return fallback;
        double numeric = number.doubleValue();
        if (!Double.isFinite(numeric) || numeric < 0 || numeric > maximum || numeric != Math.rint(numeric)) return fallback;
        return (int) numeric;
    }

    public static Set<String> packages(JSONArray source) {
        Set<String> result = new TreeSet<>();
        if (source != null) for (int index = 0; index < source.length(); index++) {
            Object item = source.opt(index);
            if (item instanceof String name && EnhanceConfig.validPackage(name)) result.add(name);
        }
        return Collections.unmodifiableSet(result);
    }

    public static Map<String, Integer> integers(JSONObject source, int maximum) {
        Map<String, Integer> result = new TreeMap<>();
        if (source != null) for (var keys = source.keys(); keys.hasNext();) {
            String name = keys.next();
            int value = integer(source.opt(name), maximum, -1);
            if (EnhanceConfig.validPackage(name) && value >= 0) result.put(name, value);
        }
        return Collections.unmodifiableMap(result);
    }

    public static String encode(JSONObject value) { return value.toString(); }
    private ProfileData() { }
}
