package com.azux.launcher.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Library list of a version. Desktop native jars (":natives-*" / "natives" classifiers) are skipped:
 * they hold x86 desktop binaries that are useless on Android. The Azux engine supplies its own natives.
 */
public final class Libs {
    private Libs() { }

    public static final class Lib {
        public final String path, url, sha1;
        Lib(String path, String url, String sha1) { this.path = path; this.url = url; this.sha1 = sha1; }
    }

    public static List<Lib> of(JSONObject version) {
        List<Lib> out = new ArrayList<>();
        JSONArray libs = version.optJSONArray("libraries");
        if (libs == null) return out;
        for (int i = 0; i < libs.length(); i++) {
            JSONObject lib = libs.optJSONObject(i);
            if (lib == null || !Rules.allowed(lib.optJSONArray("rules"))) continue;
            String name = lib.optString("name", "");
            if (name.contains(":natives-")) continue;
            JSONObject dl = lib.optJSONObject("downloads");
            JSONObject art = dl == null ? null : dl.optJSONObject("artifact");
            if (art != null) {
                String url = art.optString("url", "");
                if (!url.isEmpty()) out.add(new Lib(art.optString("path", mavenPath(name)), url, art.optString("sha1", "")));
            } else if (dl == null && !name.isEmpty()) {
                String base = lib.optString("url", "https://libraries.minecraft.net/");
                String p = mavenPath(name);
                out.add(new Lib(p, base + p, ""));
            }
        }
        return out;
    }

    /** group:artifact:version[:classifier] -> group/path/artifact/version/artifact-version[-classifier].jar */
    public static String mavenPath(String name) {
        String[] p = name.split(":");
        if (p.length < 3) return name;
        String file = p[1] + "-" + p[2] + (p.length > 3 ? "-" + p[3] : "") + ".jar";
        return p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + file;
    }

    public static List<File> files(Context ctx, JSONObject version) {
        List<File> out = new ArrayList<>();
        for (Lib l : of(version)) out.add(new File(Paths.libraries(ctx), l.path));
        return out;
    }
}
