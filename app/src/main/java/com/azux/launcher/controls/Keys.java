package com.azux.launcher.controls;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Everything a control can trigger. Key codes are GLFW key codes (what Minecraft expects). */
public final class Keys {
    private Keys() { }

    public static final int KEY = 0, MOUSE = 1, SPECIAL = 2;

    public static final class K {
        public final String id, label;
        public final int kind, code;
        K(String id, String label, int kind, int code) { this.id = id; this.label = label; this.kind = kind; this.code = code; }
    }

    public static final List<K> ALL = new ArrayList<>();
    private static final Map<String, K> BY_ID = new HashMap<>();

    private static void add(String id, String label, int kind, int code) {
        K k = new K(id, label, kind, code);
        ALL.add(k);
        BY_ID.put(id, k);
    }

    private static void key(String id, String label, int code) { add(id, label, KEY, code); }

    static {
        for (char c = 'A'; c <= 'Z'; c++) key("KEY_" + c, String.valueOf(c), c);
        for (int d = 0; d <= 9; d++) key("KEY_" + d, String.valueOf(d), 48 + d);
        for (int f = 1; f <= 12; f++) key("KEY_F" + f, "F" + f, 289 + f);
        key("KEY_SPACE", "Space", 32);
        key("KEY_ESC", "Esc", 256);
        key("KEY_ENTER", "Enter", 257);
        key("KEY_TAB", "Tab", 258);
        key("KEY_BACKSPACE", "Backspace", 259);
        key("KEY_CAPS", "Caps", 280);
        key("KEY_LSHIFT", "Shift", 340);
        key("KEY_RSHIFT", "R-Shift", 344);
        key("KEY_LCTRL", "Ctrl", 341);
        key("KEY_RCTRL", "R-Ctrl", 345);
        key("KEY_LALT", "Alt", 342);
        key("KEY_RALT", "R-Alt", 346);
        key("KEY_GRAVE", "~", 96);
        key("KEY_MINUS", "-", 45);
        key("KEY_EQUAL", "=", 61);
        key("KEY_LBRACKET", "[", 91);
        key("KEY_RBRACKET", "]", 93);
        key("KEY_BACKSLASH", "\\", 92);
        key("KEY_SEMICOLON", ";", 59);
        key("KEY_APOSTROPHE", "'", 39);
        key("KEY_COMMA", ",", 44);
        key("KEY_PERIOD", ".", 46);
        key("KEY_SLASH", "/", 47);
        key("KEY_INSERT", "Insert", 260);
        key("KEY_DELETE", "Delete", 261);
        key("KEY_HOME", "Home", 268);
        key("KEY_END", "End", 269);
        key("KEY_PGUP", "PgUp", 266);
        key("KEY_PGDN", "PgDn", 267);
        key("KEY_PAUSE", "Pause", 284);
        key("KEY_UP", "Up", 265);
        key("KEY_DOWN", "Down", 264);
        key("KEY_LEFT", "Left", 263);
        key("KEY_RIGHT", "Right", 262);
        key("KEY_NUMLOCK", "Num Lock", 282);
        for (int n = 0; n <= 9; n++) key("KP_" + n, String.valueOf(n), 320 + n);
        key("KP_DECIMAL", ".", 330);
        key("KP_DIVIDE", "/", 331);
        key("KP_MULTIPLY", "*", 332);
        key("KP_SUBTRACT", "-", 333);
        key("KP_ADD", "+", 334);
        key("KP_ENTER", "Enter", 335);
        add("MOUSE_LEFT", "Left Click", MOUSE, 0);
        add("MOUSE_RIGHT", "Right Click", MOUSE, 1);
        add("MOUSE_MIDDLE", "Mouse Wheel - Middle Click", MOUSE, 2);
        add("SCROLL_UP", "Mouse Wheel - Scroll Up", SPECIAL, 0);
        add("SCROLL_DOWN", "Mouse Wheel - Scroll Down", SPECIAL, 0);
        add("KEYBOARD", "Keyboard", SPECIAL, 0);
        add("GUI", "GUI", SPECIAL, 0);
        add("MOUSE_MODE", "Mouse", SPECIAL, 0);
    }

    public static K find(String id) { return id == null ? null : BY_ID.get(id); }

    /** Full name for the mapping slots ("Unspecified" when empty). */
    public static String label(String id) {
        K k = find(id);
        return k == null ? "Unspecified" : k.label;
    }

    /** Short text suggested for a button's name. */
    public static String shortLabel(String id) {
        K k = find(id);
        if (k == null) return "Button";
        switch (id) {
            case "MOUSE_LEFT": return "PRI";
            case "MOUSE_RIGHT": return "SEC";
            case "MOUSE_MIDDLE": return "MID";
            case "SCROLL_UP": return "Up";
            case "SCROLL_DOWN": return "Down";
            case "KEYBOARD": return "Input";
            default: return k.label;
        }
    }
}
