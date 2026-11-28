package com.azux.launcher.core;

import org.json.JSONArray;
import org.json.JSONObject;

/** Evaluates Mojang "rules" arrays for a 64-bit Linux-like environment with no optional features. */
public final class Rules {
    private Rules() { }

    public static boolean allowed(JSONArray rules) {
        if (rules == null || rules.length() == 0) return true;
        boolean result = false;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject r = rules.optJSONObject(i);
            if (r == null || !applies(r)) continue;
            result = "allow".equals(r.optString("action", "allow"));
        }
        return result;
    }

    private static boolean applies(JSONObject r) {
        if (r.has("features")) return false; // demo user, custom resolution, ... : all off
        JSONObject os = r.optJSONObject("os");
        if (os != null) {
            String name = os.optString("name", "");
            if (!name.isEmpty() && !name.equals("linux")) return false;
            String arch = os.optString("arch", "");
            if (arch.equals("x86")) return false;
        }
        return true;
    }
}
