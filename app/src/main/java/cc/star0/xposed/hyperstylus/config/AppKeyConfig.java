package cc.star0.xposed.hyperstylus.config;


import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Disabling a profile retains both click/long actions and complete key mappings. */
public final class AppKeyConfig {
    public final boolean enabled;
    private final String[] bindings;
    private final String[] fullKeys;

    public AppKeyConfig(boolean enabled, String[] source) {
        this(enabled, source, new String[]{ActionConfig.NONE, ActionConfig.NONE, ActionConfig.NONE});
    }
    public AppKeyConfig(boolean enabled, String[] source, String[] fullSource) {
        if (source.length != ActionConfig.KEYS.length) throw new IllegalArgumentException("Expected six stylus bindings");
        if (fullSource.length != ActionConfig.FULL_KEYS.length) throw new IllegalArgumentException("Expected three complete key mappings");
        this.enabled = enabled;
        bindings = source.clone();
        fullKeys = fullSource.clone();
        for (int index = 0; index < bindings.length; index++) bindings[index] = ActionConfig.sanitize(index, bindings[index]);
        for (int index = 0; index < fullKeys.length; index++) fullKeys[index] = ActionConfig.sanitizeFullKey(fullKeys[index]);
    }

    public static AppKeyConfig decode(JSONObject values) {
        JSONArray actions = values.optJSONArray("actions");
        Object enabled = values.opt("enabled");
        if (!(enabled instanceof Boolean flag) || actions == null || actions.length() != ActionConfig.KEYS.length) return null;
        String[] bindings = new String[ActionConfig.KEYS.length];
        for (int index = 0; index < bindings.length; index++) {
            Object candidate = actions.opt(index);
            if (!(candidate instanceof String action) || !ActionConfig.valid(action)) return null;
            bindings[index] = action;
        }
        String[] fullKeys = {ActionConfig.NONE, ActionConfig.NONE, ActionConfig.NONE};
        if (values.has("fullKeys")) {
            JSONArray full = values.optJSONArray("fullKeys");
            if (full == null || full.length() != fullKeys.length) return null;
            for (int index = 0; index < fullKeys.length; index++) {
                Object candidate = full.opt(index);
                if (!(candidate instanceof String action) || !ActionConfig.validFullKey(action)) return null;
                fullKeys[index] = action;
            }
        }
        return new AppKeyConfig(flag, bindings, fullKeys);
    }

    public String action(int index) { ActionConfig.requireBinding(index); return bindings[index]; }
    public String fullAction(int button) { ActionConfig.requireButton(button); return fullKeys[button]; }
    public AppKeyConfig enabled(boolean value) { return new AppKeyConfig(value, bindings, fullKeys); }
    public AppKeyConfig action(int index, String value) {
        ActionConfig.requireBinding(index);
        String[] result = bindings.clone();
        result[index] = ActionConfig.sanitize(index, value);
        return new AppKeyConfig(enabled, result, fullKeys);
    }
    public AppKeyConfig fullAction(int button, String value) {
        ActionConfig.requireButton(button);
        String[] result = fullKeys.clone();
        result[button] = ActionConfig.sanitizeFullKey(value);
        return new AppKeyConfig(enabled, bindings, result);
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("enabled", enabled).put("actions", new JSONArray(Arrays.asList(bindings)))
                .put("fullKeys", new JSONArray(Arrays.asList(fullKeys)));
    }
}
