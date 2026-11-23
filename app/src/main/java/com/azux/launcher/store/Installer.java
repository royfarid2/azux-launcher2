package com.azux.launcher.store;

import android.content.Context;

import com.azux.launcher.core.Net;
import com.azux.launcher.core.Paths;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Downloads a Release and puts it in the right game folder (mods, resourcepacks, shaderpacks, saves, modpacks). */
public final class Installer {
    private Installer() { }

    public interface Progress { void update(String msg); }

    public static String install(Context ctx, Category cat, Release r, Progress prog) throws Exception {
        if (r.url.isEmpty()) throw new Exception("The author disabled direct download for this file.");
        File game = Paths.game(ctx);
        File tmp = new File(Paths.cache(ctx), "dl_" + System.currentTimeMillis() + "_" + safe(r.fileName));
        prog.update("Downloading " + r.fileName + " ...");
        Net.download(r.url, tmp);
        try {
            if (!r.sha1.isEmpty() && !r.sha1.equalsIgnoreCase(Net.sha1(tmp))) throw new Exception("Checksum mismatch, file discarded.");
            if (cat == Category.WORLD) return installWorld(game, tmp, r.fileName);
            if (cat == Category.MODPACK && r.fileName.endsWith(".mrpack")) return installMrpack(game, tmp, prog);
            File dir = new File(game, cat.dir);
            dir.mkdirs();
            File dest = new File(dir, safe(r.fileName));
            if (dest.exists()) dest.delete();
            if (!tmp.renameTo(dest)) copy(tmp, dest);
            return "Installed in " + cat.dir + "/" + dest.getName();
        } finally {
            tmp.delete();
        }
    }

    /** Copies a file the user picked ("Install from local"). */
    public static String installLocal(Context ctx, Category cat, InputStream in, String name) throws Exception {
        File dir = new File(Paths.game(ctx), cat.dir);
        dir.mkdirs();
        File dest = new File(dir, safe(name));
        try (FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (cat == Category.WORLD && name.endsWith(".zip")) {
            String msg = installWorld(Paths.game(ctx), dest, name);
            dest.delete();
            return msg;
        }
        return "Installed in " + cat.dir + "/" + dest.getName();
    }

    private static String installWorld(File game, File zip, String fileName) throws Exception {
        File saves = new File(game, "saves");
        saves.mkdirs();
        String base = fileName.replaceAll("\\.zip$", "");
        boolean rootLevel = false;
        try (ZipFile z = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) if (en.nextElement().getName().equals("level.dat")) rootLevel = true;
            File target = rootLevel ? new File(saves, safe(base)) : saves;
            extract(z, "", target);
        }
        return "World installed in saves/";
    }

    private static String installMrpack(File game, File pack, Progress prog) throws Exception {
        String deps = "";
        try (ZipFile z = new ZipFile(pack)) {
            ZipEntry idx = z.getEntry("modrinth.index.json");
            if (idx == null) throw new Exception("Not a valid .mrpack (modrinth.index.json missing).");
            JSONObject index;
            try (InputStream in = z.getInputStream(idx)) { index = new JSONObject(readAll(in)); }
            JSONArray files = index.optJSONArray("files");
            int total = files == null ? 0 : files.length();
            for (int i = 0; i < total; i++) {
                JSONObject f = files.getJSONObject(i);
                JSONObject env = f.optJSONObject("env");
                if (env != null && "unsupported".equals(env.optString("client"))) continue;
                JSONArray dl = f.optJSONArray("downloads");
                if (dl == null || dl.length() == 0) continue;
                File dest = inside(game, f.getString("path"));
                prog.update("Pack files " + (i + 1) + "/" + total);
                Net.download(dl.getString(0), dest);
                String sha = f.optJSONObject("hashes") == null ? "" : f.getJSONObject("hashes").optString("sha1", "");
                if (!sha.isEmpty() && !sha.equalsIgnoreCase(Net.sha1(dest))) { dest.delete(); throw new Exception("Checksum mismatch: " + dest.getName()); }
            }
            extract(z, "overrides/", game);
            extract(z, "client-overrides/", game);
            JSONObject d = index.optJSONObject("dependencies");
            if (d != null) deps = " Needs: " + d.toString().replace("\"", "");
        }
        return "Modpack files installed." + deps;
    }

    private static void extract(ZipFile z, String prefix, File target) throws Exception {
        Enumeration<? extends ZipEntry> en = z.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String name = e.getName();
            if (!name.startsWith(prefix) || name.equals(prefix)) continue;
            File out = inside(target, name.substring(prefix.length()));
            if (e.isDirectory()) { out.mkdirs(); continue; }
            out.getParentFile().mkdirs();
            try (InputStream in = z.getInputStream(e); FileOutputStream fo = new FileOutputStream(out)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            }
        }
    }

    /** Resolves a relative path and refuses anything that escapes the folder (zip-slip). */
    private static File inside(File root, String rel) throws Exception {
        File f = new File(root, rel);
        String rp = root.getCanonicalPath() + File.separator;
        if (!(f.getCanonicalPath() + File.separator).startsWith(rp)) throw new Exception("Unsafe path in archive: " + rel);
        return f;
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        return o.toString("UTF-8");
    }

    private static void copy(File a, File b) throws Exception {
        try (InputStream in = new java.io.FileInputStream(a); FileOutputStream out = new FileOutputStream(b)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static String safe(String s) { return s.replaceAll("[\\\\/:*?\"<>|]", "_"); }
}
