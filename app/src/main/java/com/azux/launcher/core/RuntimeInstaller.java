package com.azux.launcher.core;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Unpacks the Java runtime bundled in the APK (assets/runtime/jre<N>.tar.gz) into runtime/jre<N>/. */
public final class RuntimeInstaller {
    private RuntimeInstaller() { }

    public interface Progress { void update(String msg); }

    /** Live progress of one runtime install; the setup screen polls it. */
    public static final class Meter {
        public volatile long done, total;
        public volatile String file = "";
        public volatile String phase = "";
        public float fraction() { return total <= 0 ? 0f : Math.min(1f, done / (float) total); }
    }

    public static void ensure(Context c, int major, Progress p) throws Exception {
        ensure(c, major, p, new Meter());
    }

    public static void ensure(Context c, int major, Progress p, Meter m) throws Exception {
        deleteTree(Paths.legacyRuntimes(c));   // old external copy from earlier builds
        if (Runtimes.installed(c, major)) return;
        boolean bundled = Runtimes.bundled(c, major);
        String url = Runtimes.remoteUrl(major);
        if (!bundled && url == null) throw new Exception("This APK has no Java " + major + " runtime inside and no download address is set.");
        File home = Runtimes.home(c, major);
        File tmp = new File(home.getPath() + ".tmp");
        deleteTree(tmp);
        deleteTree(home);
        m.done = 0;
        m.total = 0;
        m.phase = "Installing";
        p.update("Installing Java " + major + " (first time only) ...");
        if (bundled) {
            String asset = "runtime/jre" + major + ".tar.gz";
            long len = 0;
            try (AssetFileDescriptor afd = c.getAssets().openFd(asset)) { len = afd.getLength(); } catch (Exception ignored) { }
            try (InputStream in = c.getAssets().open(asset)) {
                if (len <= 0) len = in.available();
                unpack(in, len, tmp, major, p, m);
            }
        } else {
            // not inside the APK: download the archive once, then unpack it
            File arc = new File(Paths.cache(c), "jre" + major + ".tar.gz");
            m.phase = "Downloading";
            p.update("Downloading Java " + major + " (first time only) ...");
            try {
                Net.download(url, arc);
            } catch (Exception e) {
                throw new Exception("Java " + major + " is not inside this APK and the download failed:\n" + url + "\n" + e.getMessage());
            }
            m.phase = "Installing";
            try (InputStream in = new java.io.FileInputStream(arc)) {
                unpack(in, arc.length(), tmp, major, p, m);
            } finally {
                arc.delete();
            }
        }
        if (!tmp.renameTo(home)) throw new Exception("Could not finish installing Java " + major);
        if (!Runtimes.installed(c, major)) throw new Exception("Java " + major + " runtime is incomplete (libjvm.so missing).");
        m.done = m.total;
    }

    private static void unpack(InputStream raw, long total, File tmp, int major, Progress p, Meter m) throws Exception {
        m.total = total;
        InputStream counted = new FilterInputStream(raw) {
            @Override public int read() throws IOException { int b = super.read(); if (b >= 0) m.done++; return b; }
            @Override public int read(byte[] b, int off, int len) throws IOException { int n = super.read(b, off, len); if (n > 0) m.done += n; return n; }
        };
        TarGz.extract(counted, tmp,
                bytes -> p.update("Installing Java " + major + " ... " + (bytes / (1024 * 1024)) + " MB"),
                name -> m.file = name);
    }

    public static void deleteTree(File f) {
        if (!f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null && !java.nio.file.Files.isSymbolicLink(f.toPath())) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
