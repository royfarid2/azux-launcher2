package com.azux.launcher.core;

import android.content.Context;

import java.io.File;
import java.io.InputStream;

/** Unpacks the Java runtime bundled in the APK (assets/runtime/jre<N>.tar.gz) into runtime/jre<N>/. */
public final class RuntimeInstaller {
    private RuntimeInstaller() { }

    public interface Progress { void update(String msg); }

    public static void ensure(Context c, int major, Progress p) throws Exception {
        if (Runtimes.installed(c, major)) return;
        boolean bundled = Runtimes.bundled(c, major);
        String url = Runtimes.remoteUrl(major);
        if (!bundled && url == null) throw new Exception("This APK has no Java " + major + " runtime inside and no download address is set.");
        File home = Runtimes.home(c, major);
        File tmp = new File(home.getPath() + ".tmp");
        deleteTree(tmp);
        deleteTree(home);
        p.update("Installing Java " + major + " (first time only) ...");
        if (bundled) {
            try (InputStream in = c.getAssets().open("runtime/jre" + major + ".tar.gz")) {
                TarGz.extract(in, tmp, bytes -> p.update("Installing Java " + major + " ... " + (bytes / (1024 * 1024)) + " MB"));
            }
        } else {
            // not inside the APK: download the archive once, then unpack it
            File arc = new File(Paths.cache(c), "jre" + major + ".tar.gz");
            p.update("Downloading Java " + major + " (first time only) ...");
            try {
                Net.download(url, arc);
            } catch (Exception e) {
                throw new Exception("Java " + major + " is not inside this APK and the download failed:\n" + url + "\n" + e.getMessage());
            }
            try (InputStream in = new java.io.FileInputStream(arc)) {
                TarGz.extract(in, tmp, bytes -> p.update("Installing Java " + major + " ... " + (bytes / (1024 * 1024)) + " MB"));
            } finally {
                arc.delete();
            }
        }
        if (!tmp.renameTo(home)) throw new Exception("Could not finish installing Java " + major);
        if (!Runtimes.installed(c, major)) throw new Exception("Java " + major + " runtime is incomplete (libjvm.so missing).");
    }

    public static void deleteTree(File f) {
        if (!f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null && !java.nio.file.Files.isSymbolicLink(f.toPath())) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
