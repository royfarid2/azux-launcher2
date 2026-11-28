package com.azux.launcher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import com.azux.launcher.core.Paths;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny image loader: memory cache + disk cache, decoded small. */
public final class Images {
    private Images() { }

    private static final LruCache<String, Bitmap> MEM = new LruCache<>(80);
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    public static void load(final Activity a, final ImageView iv, final String url) {
        iv.setTag(url);
        iv.setImageDrawable(null);
        if (url == null || url.isEmpty()) return;
        Bitmap b = MEM.get(url);
        if (b != null) { iv.setImageBitmap(b); return; }
        POOL.execute(() -> {
            Bitmap bm = fetch(a, url);
            if (bm == null) return;
            MEM.put(url, bm);
            a.runOnUiThread(() -> { if (url.equals(iv.getTag())) iv.setImageBitmap(bm); });
        });
    }

    private static Bitmap fetch(Activity a, String url) {
        File dir = new File(Paths.cache(a), "img");
        dir.mkdirs();
        File f = new File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length());
        try {
            if (!f.exists()) {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                c.setRequestProperty("User-Agent", "AzuxLauncher/8");
                if (c.getResponseCode() != 200) { c.disconnect(); return null; }
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally {
                    c.disconnect();
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int s = 1;
            while (o.outWidth / (s * 2) >= 160) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) {
            return null;
        }
    }
}
