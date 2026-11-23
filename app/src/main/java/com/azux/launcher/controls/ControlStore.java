package com.azux.launcher.controls;

import android.content.Context;
import android.util.DisplayMetrics;

import com.azux.launcher.core.Net;
import com.azux.launcher.core.Paths;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Saves/loads control layouts as json in azux/controls/<name>.json. */
public final class ControlStore {
    private ControlStore() { }

    public static final String DEFAULT = "default";

    public static String currentName(Context c) {
        return c.getSharedPreferences("azux", Context.MODE_PRIVATE).getString("layout", DEFAULT);
    }

    public static void setCurrent(Context c, String name) {
        c.getSharedPreferences("azux", Context.MODE_PRIVATE).edit().putString("layout", name).apply();
    }

    public static List<String> names(Context c) {
        List<String> out = new ArrayList<>();
        File[] f = Paths.controls(c).listFiles();
        if (f != null) {
            Arrays.sort(f);
            for (File x : f) if (x.getName().endsWith(".json")) out.add(x.getName().substring(0, x.getName().length() - 5));
        }
        if (!out.contains(DEFAULT)) out.add(0, DEFAULT);
        return out;
    }

    public static List<ControlData> load(Context c, String name) {
        File f = new File(Paths.controls(c), name + ".json");
        if (f.exists()) {
            try {
                JSONObject root = new JSONObject(Net.readText(f));
                if (root.optInt("version", 1) >= 2) {
                    JSONArray arr = root.getJSONArray("controls");
                    List<ControlData> out = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) out.add(ControlData.fromJson(arr.getJSONObject(i)));
                    return out;
                }
            } catch (Exception ignored) { }
        }
        return defaults(c);
    }

    public static void save(Context c, String name, List<ControlData> list) throws Exception {
        JSONArray arr = new JSONArray();
        for (ControlData d : list) arr.put(d.toJson());
        JSONObject o = new JSONObject();
        o.put("version", 2);
        o.put("controls", arr);
        Net.writeText(new File(Paths.controls(c), name + ".json"), o.toString(2));
    }

    public static void delete(Context c, String name) {
        new File(Paths.controls(c), name + ".json").delete();
    }

    /** Default layout, built in dp so it looks the same on every phone. */
    public static List<ControlData> defaults(Context c) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        float a = m.widthPixels / m.density, b = m.heightPixels / m.density;
        final float W = Math.max(a, b), H = Math.min(a, b);
        List<ControlData> l = new ArrayList<>();
        float s = 50f, g = 4f;
        // top-left cluster
        l.add(box("Esc", g, g, s, s, W, H, "KEY_ESC"));
        l.add(box("F3", g + (s + g), g, s, s, W, H, "KEY_F3"));
        l.add(box("F5", g + 2 * (s + g), g, s, s, W, H, "KEY_F5"));
        l.add(box("Tab", g, g + s + g, s, s, W, H, "KEY_TAB"));
        l.add(box("T", g + (s + g), g + s + g, s, s, W, H, "KEY_T"));
        l.add(box("Input", g + 2 * (s + g), g + s + g, s, s, W, H, "KEYBOARD"));
        l.add(box("Ctrl", g, 2 * (s + g) + g, s, 36, W, H, "KEY_LCTRL"));
        l.add(box("Alt", g + (s + g), 2 * (s + g) + g, s, 36, W, H, "KEY_LALT"));
        // movement
        float js = 150f;
        l.add(ControlData.joystick(18f / W, (H - js - 18f) / H, js));
        // right side
        float rx = W - 4 * (s + g) - 6;
        l.add(box("PRI", rx, H - 3 * (s + g) - 22, 64, 64, W, H, "MOUSE_LEFT"));
        l.add(box("SEC", rx + 70, H - 3 * (s + g) - 22, 64, 64, W, H, "MOUSE_RIGHT"));
        l.add(box("Jump", W - 80, H - 150, 64, 64, W, H, "KEY_SPACE"));
        l.add(box("Sneak", W - 80, H - 80, 64, 64, W, H, "KEY_LSHIFT"));
        l.add(box("E", rx + 35, H - 3 * (s + g) + 54, 56, 56, W, H, "KEY_E"));
        // hotbar
        float slot = 40f, sx = (W - 9 * (slot + 2)) / 2f;
        for (int i = 1; i <= 9; i++) {
            ControlData d = box(String.valueOf(i), sx + (i - 1) * (slot + 2), H - slot - 4, slot, slot, W, H, "KEY_" + i);
            d.swipeable = true;
            l.add(d);
        }
        return l;
    }

    private static ControlData box(String label, float xdp, float ydp, float w, float h, float W, float H, String key) {
        return ControlData.button(label, xdp / W, ydp / H, w, h, key);
    }
}
