package com.azux.engine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Own Minecraft downloader (written from scratch from Mojang's public launcher-meta format):
 * version json, client jar, libraries (without LWJGL natives, those come from our own Android build) and assets.
 */
public final class MinecraftInstaller {
    public interface Log { void line(String s); }

    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String ASSET_HOST = "https://resources.download.minecraft.net/";
    private static final String UA = "AzuxEngine/0.2";

    private final File root;
    private final Log log;

    public MinecraftInstaller(File root, Log log) {
        this.root = root;
        this.log = log;
    }

    private static final class Job {
        final String url; final File dest; final long size;
        Job(String url, File dest, long size) { this.url = url; this.dest = dest; this.size = size; }
    }

    public void install(String id) throws Exception {
        log.line("reading Mojang version manifest ...");
        JSONArray vs = new JSONObject(get(MANIFEST)).getJSONArray("versions");
        String vurl = null;
        for (int i = 0; i < vs.length(); i++) {
            JSONObject v = vs.getJSONObject(i);
            if (id.equals(v.getString("id"))) { vurl = v.getString("url"); break; }
        }
        if (vurl == null) throw new Exception("unknown version: " + id);

        File vdir = new File(root, "versions/" + id);
        vdir.mkdirs();
        String vjson = get(vurl);
        writeFile(new File(vdir, id + ".json"), vjson);
        JSONObject v = new JSONObject(vjson);

        List<Job> jobs = new ArrayList<>();

        JSONObject client = v.getJSONObject("downloads").getJSONObject("client");
        jobs.add(new Job(client.getString("url"), new File(vdir, id + ".jar"), client.optLong("size", -1)));

        int skippedNatives = 0;
        JSONArray libs = v.getJSONArray("libraries");
        for (int i = 0; i < libs.length(); i++) {
            JSONObject lib = libs.getJSONObject(i);
            if (!allowed(lib.optJSONArray("rules"))) continue;
            JSONObject d = lib.optJSONObject("downloads");
            if (d == null) continue;
            JSONObject art = d.optJSONObject("artifact");
            if (art == null) continue;
            if (lib.optString("name").contains(":natives-")) { skippedNatives++; continue; }
            jobs.add(new Job(art.getString("url"), new File(root, "libraries/" + art.getString("path")), art.optLong("size", -1)));
        }
        log.line("libraries: " + (jobs.size() - 1) + " (skipped " + skippedNatives + " glibc natives)");

        JSONObject ai = v.getJSONObject("assetIndex");
        File idx = new File(root, "assets/indexes/" + ai.getString("id") + ".json");
        download(ai.getString("url"), idx, ai.optLong("size", -1));
        JSONObject objs = new JSONObject(readFile(idx)).getJSONObject("objects");
        Iterator<String> it = objs.keys();
        while (it.hasNext()) {
            JSONObject o = objs.getJSONObject(it.next());
            String h = o.getString("hash");
            String p = h.substring(0, 2) + "/" + h;
            jobs.add(new Job(ASSET_HOST + p, new File(root, "assets/objects/" + p), o.optLong("size", -1)));
        }
        log.line("files to check: " + jobs.size());
        runAll(jobs);
        log.line("DONE: " + id + " is ready in " + root);
    }

    private void runAll(List<Job> jobs) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(6);
        final AtomicInteger done = new AtomicInteger();
        final int total = jobs.size();
        List<Future<?>> fs = new ArrayList<>();
        for (final Job j : jobs) {
            fs.add(ex.submit(() -> {
                try {
                    download(j.url, j.dest, j.size);
                } catch (Exception e) {
                    throw new RuntimeException(j.url + " : " + e.getMessage(), e);
                }
                int n = done.incrementAndGet();
                if (n % 250 == 0 || n == total) log.line("downloaded " + n + " / " + total);
            }));
        }
        try {
            for (Future<?> f : fs) f.get();
        } finally {
            ex.shutdownNow();
        }
    }

    private static boolean allowed(JSONArray rules) {
        if (rules == null || rules.length() == 0) return true;
        boolean ok = false;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject r = rules.optJSONObject(i);
            if (r == null) continue;
            boolean match = true;
            JSONObject os = r.optJSONObject("os");
            if (os != null && os.has("name")) match = "linux".equals(os.optString("name"));
            if (r.has("features")) match = false;
            if (match) ok = "allow".equals(r.optString("action"));
        }
        return ok;
    }

    // ---- io helpers
    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", UA);
        return c;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() / 100 != 2) throw new Exception("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                return bo.toString("UTF-8");
            }
        } finally { c.disconnect(); }
    }

    private static void download(String url, File dest, long size) throws Exception {
        if (dest.exists() && dest.length() > 0 && (size < 0 || dest.length() == size)) return;
        dest.getParentFile().mkdirs();
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            File tmp = new File(dest.getPath() + ".part");
            HttpURLConnection c = null;
            try {
                c = open(url);
                if (c.getResponseCode() / 100 != 2) throw new Exception("HTTP " + c.getResponseCode());
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] b = new byte[32768];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
                if (size >= 0 && tmp.length() != size) throw new Exception("size mismatch");
                if (dest.exists()) dest.delete();
                if (!tmp.renameTo(dest)) throw new Exception("rename failed");
                return;
            } catch (Exception e) {
                last = e;
            } finally {
                if (c != null) c.disconnect();
                tmp.delete();
            }
        }
        throw last;
    }

    private static void writeFile(File f, String s) throws Exception {
        f.getParentFile().mkdirs();
        try (OutputStream o = new FileOutputStream(f)) { o.write(s.getBytes("UTF-8")); }
    }

    private static String readFile(File f) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        }
    }
}
