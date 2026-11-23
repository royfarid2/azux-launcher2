package com.azux.launcher.core;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Downloads a version: json, client jar, libraries, asset index and asset objects. */
public final class Downloader {
    private Downloader() { }

    public interface Progress { void update(String stage, int done, int total); }

    private static final String RES = "https://resources.download.minecraft.net/";

    /** @param url version json url, or null/empty if the version json is already on disk. */
    public static JSONObject prepare(Context ctx, String id, String url, Progress p) throws Exception {
        return prepare(ctx, id, url, p, () -> false);
    }

    /** Same, but stops with an exception as soon as {@code cancelled} returns true. */
    public static JSONObject prepare(Context ctx, String id, String url, Progress p,
                                     java.util.function.BooleanSupplier cancelled) throws Exception {
        File vdir = new File(Paths.versions(ctx), id);
        File vjson = new File(vdir, id + ".json");
        if (!vjson.exists()) {
            if (url == null || url.isEmpty()) throw new Exception("Version json missing for " + id);
            p.update("Version info", 0, 1);
            Net.download(url, vjson);
        }
        JSONObject v = new JSONObject(Net.readText(vjson));

        // client jar
        JSONObject client = v.optJSONObject("downloads") == null ? null : v.getJSONObject("downloads").optJSONObject("client");
        if (client != null) {
            p.update("Game jar", 0, 1);
            ensure(client.getString("url"), new File(vdir, id + ".jar"), client.optString("sha1", ""));
        }

        // libraries
        List<Callable<Void>> tasks = new ArrayList<>();
        for (final Libs.Lib l : Libs.of(v)) {
            final File dest = new File(Paths.libraries(ctx), l.path);
            tasks.add(() -> { ensure(l.url, dest, l.sha1); return null; });
        }
        runAll(tasks, "Libraries", p, cancelled);

        // assets
        JSONObject ai = v.optJSONObject("assetIndex");
        if (ai != null) {
            String aid = ai.getString("id");
            File idx = new File(Paths.assets(ctx), "indexes/" + aid + ".json");
            p.update("Asset index", 0, 1);
            ensure(ai.getString("url"), idx, ai.optString("sha1", ""));
            JSONObject index = new JSONObject(Net.readText(idx));
            JSONObject objs = index.getJSONObject("objects");
            List<Callable<Void>> at = new ArrayList<>();
            Iterator<String> keys = objs.keys();
            while (keys.hasNext()) {
                JSONObject o = objs.getJSONObject(keys.next());
                final String hash = o.getString("hash");
                final long size = o.optLong("size", -1);
                final String pre = hash.substring(0, 2);
                final File dest = new File(Paths.assets(ctx), "objects/" + pre + "/" + hash);
                at.add(() -> {
                    if (dest.exists() && (size < 0 || dest.length() == size)) return null;
                    retryDownload(RES + pre + "/" + hash, dest);
                    return null;
                });
            }
            runAll(at, "Assets", p, cancelled);
            mapLegacyAssets(ctx, aid, index);
        }
        p.update("Done", 1, 1);
        return v;
    }

    /** Old versions (virtual / map_to_resources) want real file names instead of hashed objects. */
    private static void mapLegacyAssets(Context ctx, String aid, JSONObject index) throws Exception {
        boolean virt = index.optBoolean("virtual", false);
        boolean toRes = index.optBoolean("map_to_resources", false);
        if (!virt && !toRes) return;
        File base = virt ? new File(Paths.assets(ctx), "virtual/legacy") : new File(Paths.game(ctx), "resources");
        JSONObject objs = index.getJSONObject("objects");
        Iterator<String> keys = objs.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            String hash = objs.getJSONObject(name).getString("hash");
            File src = new File(Paths.assets(ctx), "objects/" + hash.substring(0, 2) + "/" + hash);
            File dst = new File(base, name);
            if (dst.exists() || !src.exists()) continue;
            dst.getParentFile().mkdirs();
            java.nio.file.Files.copy(src.toPath(), dst.toPath());
        }
    }

    private static void ensure(String url, File dest, String sha1) throws Exception {
        if (dest.exists() && (sha1.isEmpty() || sha1.equalsIgnoreCase(Net.sha1(dest)))) return;
        Exception last = null;
        for (int i = 0; i < 3; i++) {
            try {
                Net.download(url, dest);
                if (sha1.isEmpty() || sha1.equalsIgnoreCase(Net.sha1(dest))) return;
                dest.delete();
                last = new Exception("Checksum mismatch: " + dest.getName());
            } catch (Exception e) {
                last = e;
            }
        }
        throw last;
    }

    private static void retryDownload(String url, File dest) throws Exception {
        Exception last = null;
        for (int i = 0; i < 3; i++) {
            try { Net.download(url, dest); return; } catch (Exception e) { last = e; }
        }
        throw last;
    }

    private static void runAll(List<Callable<Void>> tasks, String stage, Progress p,
                               java.util.function.BooleanSupplier cancelled) throws Exception {
        if (cancelled.getAsBoolean()) throw new Exception("Cancelled");
        final int total = tasks.size();
        p.update(stage, 0, total);
        if (total == 0) return;
        ExecutorService ex = Executors.newFixedThreadPool(8);
        final AtomicInteger done = new AtomicInteger();
        List<Future<Void>> fs = new ArrayList<>();
        for (final Callable<Void> t : tasks) {
            fs.add(ex.submit(() -> {
                if (cancelled.getAsBoolean()) throw new Exception("Cancelled");
                t.call();
                p.update(stage, done.incrementAndGet(), total);
                return null;
            }));
        }
        try {
            for (Future<Void> f : fs) f.get();
        } catch (ExecutionException e) {
            ex.shutdownNow();
            Throwable c = e.getCause();
            throw c instanceof Exception ? (Exception) c : e;
        } finally {
            ex.shutdown();
        }
    }
}
