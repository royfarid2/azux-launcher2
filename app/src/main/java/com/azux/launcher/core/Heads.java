package com.azux.launcher.core;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;

/** Shows an account's skin head. Cached on disk, so it keeps working offline after the first load. */
public final class Heads {
    private Heads() { }

    private static final ConcurrentHashMap<String, Bitmap> MEM = new ConcurrentHashMap<>();

    public static ImageView view(Context c, final int sizeDp) {
        final float d = c.getResources().getDisplayMetrics().density;
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2C2C2C);
        iv.setBackground(bg);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), (sizeDp >= 36 ? 8 : 6) * d);
            }
        });
        iv.setClipToOutline(true);
        return iv;
    }

    public static void load(final Activity act, final ImageView iv, final String name, final boolean premium, final String uuid) {
        if (name == null) return;
        iv.setTag(name);
        Bitmap cached = MEM.get(name);
        if (cached != null) { show(act, iv, cached); return; }
        new Thread(() -> {
            Bitmap b = fetch(act, name, premium ? uuid : name);
            if (b == null) return;
            MEM.put(name, b);
            act.runOnUiThread(() -> { if (name.equals(iv.getTag())) show(act, iv, b); });
        }).start();
    }

    private static void show(Activity act, ImageView iv, Bitmap b) {
        BitmapDrawable d = new BitmapDrawable(act.getResources(), b);
        d.setFilterBitmap(false); // keep pixels sharp
        iv.setImageDrawable(d);
    }

    private static Bitmap fetch(Context c, String name, String key) {
        File f = new File(Paths.cache(c), "head_" + name + ".png");
        try {
            HttpURLConnection con = (HttpURLConnection) new URL("https://mc-heads.net/avatar/" + key + "/64").openConnection();
            con.setConnectTimeout(6000);
            con.setReadTimeout(6000);
            if (con.getResponseCode() == 200) {
                try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
            con.disconnect();
        } catch (Throwable ignored) { }
        return f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
    }
}
