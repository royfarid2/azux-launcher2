package com.azux.launcher.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Official Mojang version manifest (cached on disk for offline use). */
public final class VersionList {
    private VersionList() { }

    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    public static final class Item {
        public final String id, type, date, url;
        public Item(String id, String type, String date, String url) {
            this.id = id; this.type = type; this.date = date; this.url = url;
        }
    }

    public static List<Item> load(Context ctx) throws Exception {
        File cache = new File(Paths.cache(ctx), "version_manifest.json");
        String json;
        try {
            json = Net.getString(MANIFEST);
            Net.writeText(cache, json);
        } catch (Exception e) {
            if (!cache.exists()) throw e;
            json = Net.readText(cache);
        }
        JSONArray arr = new JSONObject(json).getJSONArray("versions");
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String t = o.optString("releaseTime", "");
            out.add(new Item(o.getString("id"), o.optString("type", "release"),
                    t.length() >= 10 ? t.substring(0, 10) : t, o.optString("url", "")));
        }
        return out;
    }

    public static List<Item> installed(Context ctx) {
        List<Item> out = new ArrayList<>();
        File[] dirs = Paths.versions(ctx).listFiles();
        if (dirs == null) return out;
        Arrays.sort(dirs);
        for (File d : dirs) {
            if (d.isDirectory() && new File(d, d.getName() + ".json").exists()) {
                out.add(new Item(d.getName(), "installed", "", ""));
            }
        }
        return out;
    }
}
