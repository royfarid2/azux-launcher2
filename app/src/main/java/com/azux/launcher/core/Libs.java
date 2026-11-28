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
            if (name.startsWith("org.lwjgl:")) continue;   // replaced by the jars that match our natives, see lwjglJars()
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
        installLwjgl(ctx);
        out.addAll(lwjglJars(ctx));
        return out;
    }

    private static final String[] LWJGL = {"lwjgl", "lwjgl-glfw", "lwjgl-openal", "lwjgl-opengl", "lwjgl-stb",
            "lwjgl-tinyfd", "lwjgl-jemalloc", "lwjgl-freetype"};

    /** LWJGL jars of the same build as liblwjgl*.so (LWJGL 3.3.6-snapshot). The game's own LWJGL jars would not match them. */
    public static List<File> lwjglJars(Context ctx) {
        List<File> out = new ArrayList<>();
        for (String n : LWJGL) out.add(new File(ctx.getFilesDir(), "lwjgl/" + n + ".jar"));
        return out;
    }

    /** Copies the bundled LWJGL jars (assets/lwjgl) to internal storage when missing or changed. */
    public static void installLwjgl(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "lwjgl");
        dir.mkdirs();
        for (String n : LWJGL) {
            File dst = new File(dir, n + ".jar");
            try (java.io.InputStream in = ctx.getAssets().open("lwjgl/" + n + ".jar")) {
                if (dst.exists() && dst.length() == in.available()) continue;
                File tmp = new File(dir, n + ".jar.tmp");
                try (java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                }
                dst.delete();
                tmp.renameTo(dst);
            } catch (Exception ignored) { }
        }
    }
}
