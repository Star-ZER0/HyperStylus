package cc.star0.xposed.hyperstylus.config;


import android.view.KeyEvent;
import java.util.List;

/** Stable action identifiers used by preferences, the editor and input dispatch. */
public final class ActionConfig {
    public static final String DEFAULT = "default", NONE = "none", KEYBOARD = "keyboard";
    public static final String[] KEYS = {"focus_click", "focus_long", "upper_click", "upper_long", "lower_click", "lower_long"};
    public static final String[] FULL_KEYS = {"focus_full_key", "upper_full_key", "lower_full_key"};
    public static final String[] DEFAULTS = {"pinch", "double_tap", "slide_up", "slide_up", "slide_down", "slide_down"};
    public static final List<String> ACTIONS = List.of(DEFAULT, "pinch", "double_tap", "slide_up", "slide_down",
            "dpad_up", "dpad_down", "dpad_left", "dpad_right", "back", "home", "recents",
            "partial_screenshot", "full_screenshot", "quick_note", "media_play_pause", "volume_up", "volume_down",
            "flashlight", "power", "screen_off", "voice_assistant", KEYBOARD, NONE);

    public static String sanitize(int binding, String value) {
        requireBinding(binding);
        return valid(value) ? value : DEFAULTS[binding];
    }

    public static void requireBinding(int binding) {
        if (binding < 0 || binding >= KEYS.length) throw new IllegalArgumentException("Unknown stylus binding: " + binding);
    }

    public static void requireButton(int button) {
        if (button < 0 || button >= FULL_KEYS.length) throw new IllegalArgumentException("Unknown stylus button: " + button);
    }

    public static boolean validFullKey(String value) { return NONE.equals(value) || keyCode(value) > 0; }
    public static String sanitizeFullKey(String value) { return validFullKey(value) ? value : NONE; }

    public static boolean valid(String value) {
        return keyCode(value) > 0 || value != null && !KEYBOARD.equals(value) && ACTIONS.contains(value);
    }

    public static int keyCode(String value) {
        if (value == null) return -1;
        int delimiter = value.indexOf(':');
        if (delimiter < 0) return -1;
        String kind = value.substring(0, delimiter);
        if (!KEYBOARD.equals(kind)) return -1;
        String digits = value.substring(delimiter + 1);
        if (digits.isEmpty() || digits.length() > 4) return -1;
        int code = 0;
        for (int i = 0; i < digits.length(); i++) {
            char digit = digits.charAt(i);
            if (digit < '0' || digit > '9') return -1;
            code = code * 10 + digit - '0';
        }
        return validKeyCode(code) ? code : -1;
    }

    public static boolean validKeyCode(int code) { return code > KeyEvent.KEYCODE_UNKNOWN && code <= KeyEvent.getMaxKeyCode(); }

    public static String customKey(String kind, int code) {
        if (!KEYBOARD.equals(kind) || !validKeyCode(code)) throw new IllegalArgumentException("Invalid custom key");
        return kind + ":" + code;
    }

    public static String option(String value) {
        return keyCode(value) < 0 ? value : KEYBOARD;
    }

    public static String keyLabel(int code) { return KeyEvent.keyCodeToString(code).replace("KEYCODE_", "") + " (" + code + ")"; }
    private ActionConfig() { }
}
