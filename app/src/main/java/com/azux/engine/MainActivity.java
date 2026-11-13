package com.azux.engine;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Stage 1: unpack JRE, start our own JVM launcher, show its log. */
public class MainActivity extends Activity {
    private TextView logView;
    private ScrollView scroll;
    private Button start;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private File logFile;
    private volatile boolean running;
    private String header = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(14, 16, 22));
        root.setPadding(24, 24, 24, 24);

        TextView title = new TextView(this);
        title.setText("AZUX ENGINE  -  stage 1 (JVM)");
        title.setTextColor(Color.rgb(120, 200, 255));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        start = new Button(this);
        start.setText("Start JVM test");
        start.setOnClickListener(v -> startTest());
        root.addView(start);

        scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextColor(Color.rgb(210, 220, 230));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setTextIsSelectable(true);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        logFile = new File(getFilesDir(), "azux.log");
        header = "device: " + Build.MANUFACTURER + " " + Build.MODEL + "  android " + Build.VERSION.RELEASE
                + "  abi " + Build.SUPPORTED_ABIS[0] + "\n\n";
        logView.setText(header + "Press the button.\n");
    }

    private void append(final String s) {
        ui.post(() -> { logView.append(s + "\n"); scroll.fullScroll(View.FOCUS_DOWN); });
    }

    private void startTest() {
        if (running) return;
        running = true;
        start.setEnabled(false);
        logView.setText(header);
        new Thread(() -> {
            try {
                File home = prepareJre();
                File jar = copyAsset("azux-test.jar", new File(getFilesDir(), "azux-test.jar"));
                File tmp = getCacheDir();
                Os.setenv("HOME", getFilesDir().getAbsolutePath(), true);
                Os.setenv("TMPDIR", tmp.getAbsolutePath(), true);
                Os.setenv("JAVA_HOME", home.getAbsolutePath(), true);
                Os.setenv("LD_LIBRARY_PATH", home + "/lib:" + home + "/lib/server", true);

                String[] jvm = {
                        "-Djava.home=" + home,
                        "-Djava.class.path=" + jar,
                        "-Djava.library.path=" + home + "/lib",
                        "-Djava.io.tmpdir=" + tmp,
                        "-Duser.home=" + getFilesDir(),
                        "-Dos.name=Linux",
                        "-Xms64m", "-Xmx512m", "-Xss1m",
                        "-XX:+UseSerialGC", "-Xshare:off", "-Xrs",
                };
                startLogPoll();
                append("starting JVM ...");
                final int rc = Native.launch(home.getAbsolutePath(), logFile.getAbsolutePath(), jvm, "AzuxTest", new String[0]);
                append("\nlauncher returned rc=" + rc + (rc == 0 ? "   SUCCESS" : "   FAILED"));
            } catch (Throwable t) {
                append("ERROR: " + t);
            } finally {
                ui.postDelayed(() -> { running = false; pollOnce(); }, 600);
                ui.post(() -> start.setText("Restart the app to run again"));
            }
        }, "azux-main").start();
    }

    // ---- log polling
    private void startLogPoll() {
        ui.post(new Runnable() {
            @Override public void run() {
                pollOnce();
                if (running) ui.postDelayed(this, 400);
            }
        });
    }

    private void pollOnce() {
        try (RandomAccessFile f = new RandomAccessFile(logFile, "r")) {
            long len = f.length();
            long from = Math.max(0, len - 30000);
            byte[] buf = new byte[(int) (len - from)];
            f.seek(from);
            f.readFully(buf);
            logView.setText(header + new String(buf, "UTF-8"));
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        } catch (Throwable ignored) { }
    }

    // ---- JRE unpacking
    private File prepareJre() throws Exception {
        File dir = new File(getFilesDir(), "jre");
        File mark = new File(dir, ".done");
        if (!mark.exists()) {
            append("unpacking JRE (first run, may take a minute) ...");
            deleteRec(dir);
            dir.mkdirs();
            try (InputStream in = getAssets().open("jre.zip")) { unzip(in, dir); }
            mark.createNewFile();
        }
        File jvm = find(dir, "libjvm.so", 0);
        if (jvm == null) throw new Exception("libjvm.so not found inside jre.zip");
        File home = jvm.getParentFile().getParentFile().getParentFile(); // <home>/lib/server/libjvm.so
        append("java home: " + home);
        return home;
    }

    private void unzip(InputStream in, File dest) throws Exception {
        String root = dest.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = z.getNextEntry()) != null) {
                File f = new File(dest, e.getName());
                if (!f.getCanonicalPath().startsWith(root)) continue; // zip-slip guard
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(f)) {
                    int n;
                    while ((n = z.read(buf)) > 0) o.write(buf, 0, n);
                }
                f.setReadable(true, false);
                f.setExecutable(true, false);
            }
        }
    }

    private File find(File dir, String name, int depth) {
        File[] fs = dir.listFiles();
        if (fs == null || depth > 6) return null;
        for (File f : fs) if (f.isFile() && f.getName().equals(name)) return f;
        for (File f : fs) if (f.isDirectory()) { File r = find(f, name, depth + 1); if (r != null) return r; }
        return null;
    }

    private File copyAsset(String asset, File out) throws Exception {
        try (InputStream in = getAssets().open(asset); OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
        return out;
    }

    private void deleteRec(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File c : k) deleteRec(c);
        f.delete();
    }
}
