package cc.star0.xposed.hyperstylus.runtime.keys;

import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

/** A standard virtual keyboard is independent of the pen's policy cancellation domain. */
final class KeyboardMapping {
    private KeyboardMapping() { }

    static boolean isEvent(KeyEvent event) {
        return event != null && event.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD
                && event.getSource() == InputDevice.SOURCE_KEYBOARD
                && event.getScanCode() == scanCode(event.getKeyCode());
    }

    // Linux EV_KEY positions from AOSP Generic.kl (also present in the supplied Android 15 OTA).
    // Choose the first position for duplicate labels. Android-only codes without a keyboard
    // position remain 0; never publish an internal marker as a physical key position.
    static int scanCode(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.KEYCODE_ESCAPE -> 1;
            case KeyEvent.KEYCODE_1 -> 2;
            case KeyEvent.KEYCODE_2 -> 3;
            case KeyEvent.KEYCODE_3 -> 4;
            case KeyEvent.KEYCODE_4 -> 5;
            case KeyEvent.KEYCODE_5 -> 6;
            case KeyEvent.KEYCODE_6 -> 7;
            case KeyEvent.KEYCODE_7 -> 8;
            case KeyEvent.KEYCODE_8 -> 9;
            case KeyEvent.KEYCODE_9 -> 10;
            case KeyEvent.KEYCODE_0 -> 11;
            case KeyEvent.KEYCODE_MINUS -> 12;
            case KeyEvent.KEYCODE_EQUALS -> 13;
            case KeyEvent.KEYCODE_DEL -> 14;
            case KeyEvent.KEYCODE_TAB -> 15;
            case KeyEvent.KEYCODE_Q -> 16;
            case KeyEvent.KEYCODE_W -> 17;
            case KeyEvent.KEYCODE_E -> 18;
            case KeyEvent.KEYCODE_R -> 19;
            case KeyEvent.KEYCODE_T -> 20;
            case KeyEvent.KEYCODE_Y -> 21;
            case KeyEvent.KEYCODE_U -> 22;
            case KeyEvent.KEYCODE_I -> 23;
            case KeyEvent.KEYCODE_O -> 24;
            case KeyEvent.KEYCODE_P -> 25;
            case KeyEvent.KEYCODE_LEFT_BRACKET -> 26;
            case KeyEvent.KEYCODE_RIGHT_BRACKET -> 27;
            case KeyEvent.KEYCODE_ENTER -> 28;
            case KeyEvent.KEYCODE_CTRL_LEFT -> 29;
            case KeyEvent.KEYCODE_A -> 30;
            case KeyEvent.KEYCODE_S -> 31;
            case KeyEvent.KEYCODE_D -> 32;
            case KeyEvent.KEYCODE_F -> 33;
            case KeyEvent.KEYCODE_G -> 34;
            case KeyEvent.KEYCODE_H -> 35;
            case KeyEvent.KEYCODE_J -> 36;
            case KeyEvent.KEYCODE_K -> 37;
            case KeyEvent.KEYCODE_L -> 38;
            case KeyEvent.KEYCODE_SEMICOLON -> 39;
            case KeyEvent.KEYCODE_APOSTROPHE -> 40;
            case KeyEvent.KEYCODE_GRAVE -> 41;
            case KeyEvent.KEYCODE_SHIFT_LEFT -> 42;
            case KeyEvent.KEYCODE_BACKSLASH -> 43;
            case KeyEvent.KEYCODE_Z -> 44;
            case KeyEvent.KEYCODE_X -> 45;
            case KeyEvent.KEYCODE_C -> 46;
            case KeyEvent.KEYCODE_V -> 47;
            case KeyEvent.KEYCODE_B -> 48;
            case KeyEvent.KEYCODE_N -> 49;
            case KeyEvent.KEYCODE_M -> 50;
            case KeyEvent.KEYCODE_COMMA -> 51;
            case KeyEvent.KEYCODE_PERIOD -> 52;
            case KeyEvent.KEYCODE_SLASH -> 53;
            case KeyEvent.KEYCODE_SHIFT_RIGHT -> 54;
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 55;
            case KeyEvent.KEYCODE_ALT_LEFT -> 56;
            case KeyEvent.KEYCODE_SPACE -> 57;
            case KeyEvent.KEYCODE_CAPS_LOCK -> 58;
            case KeyEvent.KEYCODE_F1 -> 59;
            case KeyEvent.KEYCODE_F2 -> 60;
            case KeyEvent.KEYCODE_F3 -> 61;
            case KeyEvent.KEYCODE_F4 -> 62;
            case KeyEvent.KEYCODE_F5 -> 63;
            case KeyEvent.KEYCODE_F6 -> 64;
            case KeyEvent.KEYCODE_F7 -> 65;
            case KeyEvent.KEYCODE_F8 -> 66;
            case KeyEvent.KEYCODE_F9 -> 67;
            case KeyEvent.KEYCODE_F10 -> 68;
            case KeyEvent.KEYCODE_NUM_LOCK -> 69;
            case KeyEvent.KEYCODE_SCROLL_LOCK -> 70;
            case KeyEvent.KEYCODE_NUMPAD_7 -> 71;
            case KeyEvent.KEYCODE_NUMPAD_8 -> 72;
            case KeyEvent.KEYCODE_NUMPAD_9 -> 73;
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 74;
            case KeyEvent.KEYCODE_NUMPAD_4 -> 75;
            case KeyEvent.KEYCODE_NUMPAD_5 -> 76;
            case KeyEvent.KEYCODE_NUMPAD_6 -> 77;
            case KeyEvent.KEYCODE_NUMPAD_ADD -> 78;
            case KeyEvent.KEYCODE_NUMPAD_1 -> 79;
            case KeyEvent.KEYCODE_NUMPAD_2 -> 80;
            case KeyEvent.KEYCODE_NUMPAD_3 -> 81;
            case KeyEvent.KEYCODE_NUMPAD_0 -> 82;
            case KeyEvent.KEYCODE_NUMPAD_DOT -> 83;
            case KeyEvent.KEYCODE_ZENKAKU_HANKAKU -> 85;
            case KeyEvent.KEYCODE_F11 -> 87;
            case KeyEvent.KEYCODE_F12 -> 88;
            case KeyEvent.KEYCODE_RO -> 89;
            case KeyEvent.KEYCODE_HENKAN -> 92;
            case KeyEvent.KEYCODE_KATAKANA_HIRAGANA -> 93;
            case KeyEvent.KEYCODE_MUHENKAN -> 94;
            case KeyEvent.KEYCODE_NUMPAD_COMMA -> 95;
            case KeyEvent.KEYCODE_NUMPAD_ENTER -> 96;
            case KeyEvent.KEYCODE_CTRL_RIGHT -> 97;
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 98;
            case KeyEvent.KEYCODE_SYSRQ -> 99;
            case KeyEvent.KEYCODE_ALT_RIGHT -> 100;
            case KeyEvent.KEYCODE_MOVE_HOME -> 102;
            case KeyEvent.KEYCODE_DPAD_UP -> 103;
            case KeyEvent.KEYCODE_PAGE_UP -> 104;
            case KeyEvent.KEYCODE_DPAD_LEFT -> 105;
            case KeyEvent.KEYCODE_DPAD_RIGHT -> 106;
            case KeyEvent.KEYCODE_MOVE_END -> 107;
            case KeyEvent.KEYCODE_DPAD_DOWN -> 108;
            case KeyEvent.KEYCODE_PAGE_DOWN -> 109;
            case KeyEvent.KEYCODE_INSERT -> 110;
            case KeyEvent.KEYCODE_FORWARD_DEL -> 111;
            case KeyEvent.KEYCODE_VOLUME_MUTE -> 113;
            case KeyEvent.KEYCODE_VOLUME_DOWN -> 114;
            case KeyEvent.KEYCODE_VOLUME_UP -> 115;
            case KeyEvent.KEYCODE_POWER -> 116;
            case KeyEvent.KEYCODE_NUMPAD_EQUALS -> 117;
            case KeyEvent.KEYCODE_BREAK -> 119;
            case KeyEvent.KEYCODE_RECENT_APPS -> 120;
            case KeyEvent.KEYCODE_KANA -> 122;
            case KeyEvent.KEYCODE_EISU -> 123;
            case KeyEvent.KEYCODE_YEN -> 124;
            case KeyEvent.KEYCODE_META_LEFT -> 125;
            case KeyEvent.KEYCODE_META_RIGHT -> 126;
            case KeyEvent.KEYCODE_MENU -> 127;
            case KeyEvent.KEYCODE_MEDIA_STOP -> 128;
            case KeyEvent.KEYCODE_COPY -> 133;
            case KeyEvent.KEYCODE_PASTE -> 135;
            case KeyEvent.KEYCODE_CUT -> 137;
            case KeyEvent.KEYCODE_CALCULATOR -> 140;
            case KeyEvent.KEYCODE_SLEEP -> 142;
            case KeyEvent.KEYCODE_WAKEUP -> 143;
            case KeyEvent.KEYCODE_EXPLORER -> 150;
            case KeyEvent.KEYCODE_ENVELOPE -> 155;
            case KeyEvent.KEYCODE_BOOKMARK -> 156;
            case KeyEvent.KEYCODE_BACK -> 158;
            case KeyEvent.KEYCODE_FORWARD -> 159;
            case KeyEvent.KEYCODE_MEDIA_CLOSE -> 160;
            case KeyEvent.KEYCODE_MEDIA_EJECT -> 161;
            case KeyEvent.KEYCODE_MEDIA_NEXT -> 163;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> 164;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS -> 165;
            case KeyEvent.KEYCODE_MEDIA_RECORD -> 167;
            case KeyEvent.KEYCODE_MEDIA_REWIND -> 168;
            case KeyEvent.KEYCODE_CALL -> 169;
            case KeyEvent.KEYCODE_MUSIC -> 171;
            case KeyEvent.KEYCODE_HOME -> 172;
            case KeyEvent.KEYCODE_REFRESH -> 173;
            case KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN -> 179;
            case KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN -> 180;
            case KeyEvent.KEYCODE_MEDIA_PLAY -> 200;
            case KeyEvent.KEYCODE_MEDIA_PAUSE -> 201;
            case KeyEvent.KEYCODE_NOTIFICATION -> 204;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 208;
            case KeyEvent.KEYCODE_CAMERA -> 212;
            case KeyEvent.KEYCODE_SEARCH -> 217;
            case KeyEvent.KEYCODE_BRIGHTNESS_DOWN -> 224;
            case KeyEvent.KEYCODE_BRIGHTNESS_UP -> 225;
            case KeyEvent.KEYCODE_HEADSETHOOK -> 226;
            case KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_TOGGLE -> 228;
            case KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_DOWN -> 229;
            case KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_UP -> 230;
            case KeyEvent.KEYCODE_MUTE -> 248;
            case KeyEvent.KEYCODE_FUNCTION -> 241;
            default -> 0;
        };
    }
}
