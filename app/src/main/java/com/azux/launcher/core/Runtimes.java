package com.azux.launcher.core;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;

/** Java runtimes live in runtime/jre<N>/ (unpacked from the APK on first use). Bundled: 8, 17, 21 and 25. */
public final class Runtimes {
    private Runtimes() { }

    public static final int[] ALL = {8, 17, 21, 25};

    /** Java version a Minecraft version asks for (8, 16, 17, 21...). */
    public static int required(JSONObject version) {
        JSONObject j = version.optJSONObject("javaVersion");
        return j == null ? 8 : j.optInt("majorVersion", 8);
    }

    /** Preferred runtime for a requirement: 8 for old versions, 17 for 9-20, 21 for 21-24, 25 for newer. */
    public static int pick(int required) {
        if (required <= 8) return 8;
        if (required >= 25) return 25;
        if (required >= 21) return 21;
        return 17;
    }

    /** Like {@link #pick(int)} but falls back to another runtime that is installed / in the APK. */
    public static int pick(Context c, int required) {
        int want = pick(required);
        int[] order = want == 8 ? new int[]{8, 17, 21, 25} : want == 17 ? new int[]{17, 21, 25, 8}
                : want == 21 ? new int[]{21, 25, 17, 8} : new int[]{25, 21, 17, 8};
        for (int m : order) if (installed(c, m) || bundled(c, m)) return m;
        return want;
    }

    public static File home(Context c, int major) { return new File(Paths.runtimes(c), "jre" + major); }
    public static File javaBin(Context c, int major) { return new File(home(c, major), "bin/java"); }

    /** libjvm.so: lib/server/ for Java 9+, lib/aarch64/server/ for Java 8. */
    public static File libjvm(Context c, int major) {
        File a = new File(home(c, major), "lib/server/libjvm.so");
        return a.exists() ? a : new File(home(c, major), "lib/aarch64/server/libjvm.so");
    }

    public static boolean installed(Context c, int major) { return libjvm(c, major).exists(); }

    /** True if this APK carries the runtime archive. */
    public static boolean bundled(Context c, int major) {
        try {
            String[] l = c.getAssets().list("runtime");
            if (l == null) return false;
            for (String s : l) if (s.equals("jre" + major + ".tar.gz")) return true;
        } catch (Exception ignored) { }
        return false;
    }
}
