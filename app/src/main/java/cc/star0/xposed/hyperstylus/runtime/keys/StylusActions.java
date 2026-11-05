package cc.star0.xposed.hyperstylus.runtime.keys;

import cc.star0.xposed.hyperstylus.common.Reflect;
import cc.star0.xposed.hyperstylus.config.ActionConfig;
import cc.star0.xposed.hyperstylus.module.FocusStylusModule;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import java.util.Map;

/** Routes one resolved action; native gesture adaptation and key delivery have separate owners. */
public final class StylusActions {
    private static final Map<String, Integer> KEYBOARD = Map.ofEntries(
            Map.entry("dpad_up", KeyEvent.KEYCODE_DPAD_UP), Map.entry("dpad_down", KeyEvent.KEYCODE_DPAD_DOWN),
            Map.entry("dpad_left", KeyEvent.KEYCODE_DPAD_LEFT), Map.entry("dpad_right", KeyEvent.KEYCODE_DPAD_RIGHT),
            Map.entry("page_up", KeyEvent.KEYCODE_PAGE_UP), Map.entry("page_down", KeyEvent.KEYCODE_PAGE_DOWN),
            Map.entry("back", KeyEvent.KEYCODE_BACK), Map.entry("media_play_pause", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
            Map.entry("power", KeyEvent.KEYCODE_POWER));
    private final FocusStylusModule module;
    private final Context context;
    private final ClassLoader loader;
    private final StylusKeyInjector keys;
    private final NativeStylusDispatch nativeActions;

    public StylusActions(FocusStylusModule module, ClassLoader loader, Context context) {
        this.module = module; this.loader = loader; this.context = context;
        keys = new StylusKeyInjector(context);
        nativeActions = new NativeStylusDispatch(module, loader, keys);
        StylusHover.configure(context);
    }

    public interface Sink {
        public void key(int code) throws Throwable;
        public void command(String action) throws Throwable;
    }

    /** The actual entry classification used by both physical buttons and app profiles. */
    public static void dispatch(String requested, int button, boolean longPress, Sink sink) throws Throwable {
        String action = requested;
        if (ActionConfig.DEFAULT.equals(action)) action = longPress
                ? (button == 1 ? "partial_screenshot" : "quick_note") : (button == 1 ? "page_up" : "page_down");
        int custom = ActionConfig.keyCode(action);
        if (custom > 0) {
            sink.key(custom);
            return;
        }
        // Keep the optional lookup boxed. A mixed int/Integer ternary would unbox null
        // before reaching the native branch, and R8 would remove that branch entirely.
        Integer code = KEYBOARD.get(action);
        if (code != null) sink.key(code);
        else sink.command(action);
    }

    public void execute(String requested, Object manager, int device, int button, boolean longPress) {
        try {
            dispatch(requested, button, longPress, new Sink() {
                @Override public void key(int code) throws Throwable { keys.deliver(code, -1, InputDevice.SOURCE_KEYBOARD); }
                @Override public void command(String action) throws Throwable { runCommand(action, manager, device); }
            });
        } catch (Throwable failure) { module.error("Stylus action " + requested, failure); }
    }

    private void runCommand(String action, Object manager, int device) throws Throwable {
        switch (action) {
            case "none" -> { }
            case "pinch" -> nativeActions.gesture(194, device);
            case "double_tap" -> nativeActions.gesture(195, device);
            case "slide_up" -> nativeActions.slide(manager, device, true);
            case "slide_down" -> nativeActions.slide(manager, device, false);
            case "home" -> context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            case "recents" -> recents();
            case "partial_screenshot" -> shortcut("stylus_partial_screenshot", "long_press_page_up_key", null);
            case "full_screenshot" -> shortcut("screen_shot", "stylus", null);
            case "quick_note" -> { Bundle values = new Bundle(); values.putString("scene", "app"); shortcut("note", "stylus", values); }
            case "volume_up" -> volume(AudioManager.ADJUST_RAISE);
            case "volume_down" -> volume(AudioManager.ADJUST_LOWER);
            case "flashlight" -> shortcut("turn_on_torch", "stylus", null);
            case "screen_off" -> Reflect.call(context.getSystemService(PowerManager.class), "goToSleep", SystemClock.uptimeMillis());
            case "voice_assistant" -> shortcut("launch_voice_assistant", "stylus", null);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        }
    }

    public boolean nativeFocus(Object manager, KeyEvent original, boolean down) { return nativeActions.focus(manager, original, down); }
    public Runnable nativeFocusLong(Object manager, KeyEvent original) { return nativeActions.focusLong(manager, original); }

    // Runs in system_server: the native task-switcher service is intentionally reached through libxposed.
    @android.annotation.SuppressLint("PrivateApi")
    private void recents() throws Throwable {
        Object service = Reflect.call(loader.loadClass("com.android.server.LocalServices"), "getService",
                loader.loadClass("com.android.server.statusbar.StatusBarManagerInternal"));
        Reflect.call(service, "toggleRecentApps");
    }

    private void shortcut(String function, String source, Bundle extras) throws Throwable {
        Object adapter = Reflect.call(loader.loadClass("com.miui.server.input.util.ShortCutActionsUtils"), "getInstance", context);
        Object result = Reflect.call(adapter, "triggerFunction", function, source, extras, false);
        if (Boolean.FALSE.equals(result)) module.info("Shortcut declined: " + function);
    }
    private void volume(int direction) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio != null) audio.adjustVolume(direction, AudioManager.FLAG_SHOW_UI);
    }
}
