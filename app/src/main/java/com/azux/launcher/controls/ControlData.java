package com.azux.launcher.controls;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/**
 * One control. x/y = top-left as a fraction of the screen, w/h = size in dp.
 * A button can map up to 4 keys: pressing it triggers all of them together.
 */
public final class ControlData {
    public static final String BUTTON = "button", JOYSTICK = "joystick";
    public static final int SLOTS = 4;

    public String id = UUID.randomUUID().toString().substring(0, 8);
    public String type = BUTTON;
    public String label = "Button";
    public final String[] keys = {"", "", "", ""};
    public float x, y, w = 56f, h = 56f;
    public boolean toggle, passthrough, swipeable;
    public int bg = 0xE6121212;

    public ControlData() { }

    public static ControlData button(String label, float x, float y, float w, float h, String... keys) {
        ControlData d = new ControlData();
        d.label = label; d.x = x; d.y = y; d.w = w; d.h = h;
        for (int i = 0; i < keys.length && i < SLOTS; i++) d.keys[i] = keys[i];
        return d;
    }

    public static ControlData joystick(float x, float y, float size) {
        ControlData d = new ControlData();
        d.type = JOYSTICK; d.label = "WASD"; d.x = x; d.y = y; d.w = size; d.h = size;
        d.bg = 0x33000000;
        return d;
    }

    public boolean isJoystick() { return JOYSTICK.equals(type); }

    public ControlData copy() {
        ControlData d = new ControlData();
        d.type = type; d.label = label; d.x = x; d.y = y; d.w = w; d.h = h;
        d.toggle = toggle; d.passthrough = passthrough; d.swipeable = swipeable; d.bg = bg;
        System.arraycopy(keys, 0, d.keys, 0, SLOTS);
        return d;
    }

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id); o.put("type", type); o.put("label", label);
        JSONArray k = new JSONArray();
        for (String s : keys) k.put(s);
        o.put("keys", k);
        o.put("x", x); o.put("y", y); o.put("w", w); o.put("h", h);
        o.put("toggle", toggle); o.put("passthrough", passthrough); o.put("swipeable", swipeable);
        o.put("bg", bg);
        return o;
    }

    public static ControlData fromJson(JSONObject o) {
        ControlData d = new ControlData();
        d.id = o.optString("id", d.id);
        d.type = o.optString("type", BUTTON);
        d.label = o.optString("label", "");
        JSONArray k = o.optJSONArray("keys");
        if (k != null) for (int i = 0; i < SLOTS && i < k.length(); i++) d.keys[i] = k.optString(i, "");
        d.x = (float) o.optDouble("x", 0);
        d.y = (float) o.optDouble("y", 0);
        d.w = (float) o.optDouble("w", 56);
        d.h = (float) o.optDouble("h", 56);
        d.toggle = o.optBoolean("toggle", false);
        d.passthrough = o.optBoolean("passthrough", false);
        d.swipeable = o.optBoolean("swipeable", false);
        d.bg = o.optInt("bg", 0xE6121212);
        return d;
    }
}
