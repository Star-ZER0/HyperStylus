package cc.star0.xposed.hyperstylus.storage;

import cc.star0.xposed.hyperstylus.config.StylusGestureConfig;
import cc.star0.xposed.hyperstylus.runtime.nativegesture.SystemBrushController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Current version-one native settings ownership in /data/system/HyperStylus/brush_settings. */
public final class SystemBrushJournal implements SystemBrushController.Journal {
    private static final SystemDataFiles.Codec<Map<SystemBrushController.Key, SystemBrushController.Claim>> CODEC =
            new SystemDataFiles.Codec<>() {
        @Override public Map<SystemBrushController.Key, SystemBrushController.Claim> decode(InputStream input) throws IOException {
            try {
                JSONObject root = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                if (!(root.opt("version") instanceof Number format) || format.doubleValue() != 1)
                    throw new JSONException("Unsupported recovery format");
                Map<SystemBrushController.Key, SystemBrushController.Claim> result = new LinkedHashMap<>();
                JSONArray claims = root.getJSONArray("claims");
                for (int index = 0; index < claims.length(); index++) {
                    JSONObject record = claims.getJSONObject(index);
                    Object userValue = record.opt("user");
                    if (!(userValue instanceof Number user) || user.doubleValue() != user.intValue() || user.intValue() < 0)
                        throw new JSONException("Invalid recovery user");
                    StylusGestureConfig.Kind kind = StylusGestureConfig.Kind.valueOf(record.getString("kind"));
                    Object before = record.opt("original"), after = record.opt("written");
                    if (before != JSONObject.NULL && !(before instanceof String) || !(after instanceof String written))
                        throw new JSONException("Invalid recovery values");
                    int action = Integer.parseInt(written);
                    if (!kind.valid(action) || !Integer.toString(action).equals(written)) throw new JSONException("Invalid recovery action");
                    var key = new SystemBrushController.Key(user.intValue(), kind);
                    var value = new SystemBrushController.Claim(before == JSONObject.NULL ? null : (String) before, written);
                    if (result.putIfAbsent(key, value) != null) throw new JSONException("Duplicate recovery record");
                }
                return Map.copyOf(result);
            } catch (JSONException | IllegalArgumentException error) { throw new IOException("Invalid system gesture journal", error); }
        }
        @Override public byte[] encode(Map<SystemBrushController.Key, SystemBrushController.Claim> claims) throws IOException {
            try {
                JSONArray records = new JSONArray();
                for (var entry : claims.entrySet()) {
                    records.put(new JSONObject().put("user", entry.getKey().user()).put("kind", entry.getKey().kind().name())
                            .put("original", entry.getValue().original() == null ? JSONObject.NULL : entry.getValue().original())
                            .put("written", entry.getValue().written()));
                }
                return new JSONObject().put("version", 1).put("claims", records).toString().getBytes(StandardCharsets.UTF_8);
            } catch (JSONException | RuntimeException error) { throw new IOException("Encode native gesture recovery journal", error); }
        }
    };
    private final android.util.AtomicFile file;
    public SystemBrushJournal() { this(new SystemDataFiles()); }
    public SystemBrushJournal(SystemDataFiles files) { file = files.brushSettings(); }
    @Override public Map<SystemBrushController.Key, SystemBrushController.Claim> read() throws IOException {
        return SystemDataFiles.read(file, CODEC, Map.of());
    }
    @Override public void write(Map<SystemBrushController.Key, SystemBrushController.Claim> claims) throws IOException {
        SystemDataFiles.write(file, CODEC, claims);
    }
}

