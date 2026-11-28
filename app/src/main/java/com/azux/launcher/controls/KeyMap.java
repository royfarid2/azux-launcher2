package com.azux.launcher.controls;

import android.view.KeyEvent;

/** Android key codes -> GLFW key codes (for the soft keyboard and hardware keyboards). */
public final class KeyMap {
    private KeyMap() { }

    /** @return GLFW key code, or -1 if the key has no equivalent. */
    public static int toGlfw(int a) {
        if (a >= KeyEvent.KEYCODE_A && a <= KeyEvent.KEYCODE_Z) return 65 + (a - KeyEvent.KEYCODE_A);
        if (a >= KeyEvent.KEYCODE_0 && a <= KeyEvent.KEYCODE_9) return 48 + (a - KeyEvent.KEYCODE_0);
        if (a >= KeyEvent.KEYCODE_F1 && a <= KeyEvent.KEYCODE_F12) return 290 + (a - KeyEvent.KEYCODE_F1);
        if (a >= KeyEvent.KEYCODE_NUMPAD_0 && a <= KeyEvent.KEYCODE_NUMPAD_9) return 320 + (a - KeyEvent.KEYCODE_NUMPAD_0);
        switch (a) {
            case KeyEvent.KEYCODE_SPACE: return 32;
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: return 257;
            case KeyEvent.KEYCODE_TAB: return 258;
            case KeyEvent.KEYCODE_DEL: return 259;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 261;
            case KeyEvent.KEYCODE_ESCAPE: return 256;
            case KeyEvent.KEYCODE_INSERT: return 260;
            case KeyEvent.KEYCODE_MOVE_HOME: return 268;
            case KeyEvent.KEYCODE_MOVE_END: return 269;
            case KeyEvent.KEYCODE_PAGE_UP: return 266;
            case KeyEvent.KEYCODE_PAGE_DOWN: return 267;
            case KeyEvent.KEYCODE_DPAD_UP: return 265;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 264;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 263;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 262;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return 340;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return 344;
            case KeyEvent.KEYCODE_CTRL_LEFT: return 341;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return 345;
            case KeyEvent.KEYCODE_ALT_LEFT: return 342;
            case KeyEvent.KEYCODE_ALT_RIGHT: return 346;
            case KeyEvent.KEYCODE_CAPS_LOCK: return 280;
            case KeyEvent.KEYCODE_GRAVE: return 96;
            case KeyEvent.KEYCODE_MINUS: return 45;
            case KeyEvent.KEYCODE_EQUALS: return 61;
            case KeyEvent.KEYCODE_LEFT_BRACKET: return 91;
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return 93;
            case KeyEvent.KEYCODE_BACKSLASH: return 92;
            case KeyEvent.KEYCODE_SEMICOLON: return 59;
            case KeyEvent.KEYCODE_APOSTROPHE: return 39;
            case KeyEvent.KEYCODE_COMMA: return 44;
            case KeyEvent.KEYCODE_PERIOD: return 46;
            case KeyEvent.KEYCODE_SLASH: return 47;
            default: return -1;
        }
    }
}
