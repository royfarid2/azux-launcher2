package com.azux.launcher.core;

import android.content.Context;

import java.io.File;

/** All launcher folders live under <app external files>/azux. */
public final class Paths {
    private Paths() { }

    public static File root(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File r = new File(base, "azux");
        r.mkdirs();
        return r;
    }

    public static File dir(Context c, String name) {
        File f = new File(root(c), name);
        f.mkdirs();
        return f;
    }

    public static File versions(Context c) { return dir(c, "versions"); }
    public static File libraries(Context c) { return dir(c, "libraries"); }
    public static File assets(Context c) { return dir(c, "assets"); }
    public static File accounts(Context c) { return dir(c, "accounts"); }
    public static File controls(Context c) { return dir(c, "controls"); }
    /**
     * The Java runtime MUST live in the app's INTERNAL storage (/data/data/<pkg>/files): Android's linker refuses to
     * dlopen() libjvm.so and friends from external storage (/storage/emulated/0/Android/data/...), which fails with
     * "is not accessible for the namespace". Pojav/Zalith do the same.
     */
    public static File runtimes(Context c) {
        File f = new File(c.getFilesDir(), "runtime");
        f.mkdirs();
        return f;
    }

    /** Where older builds unpacked the runtime (external storage: unusable, only taking space). */
    public static File legacyRuntimes(Context c) { return new File(root(c), "runtime"); }
    public static File game(Context c) { return dir(c, "game"); }
    public static File cache(Context c) { return dir(c, "cache"); }
}
