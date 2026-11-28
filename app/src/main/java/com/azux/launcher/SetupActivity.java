package com.azux.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.azux.launcher.core.Libs;
import com.azux.launcher.core.Paths;
import com.azux.launcher.core.RuntimeInstaller;
import com.azux.launcher.core.Runtimes;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * First start: access to all files, then every component the launcher needs is installed AND verified, with the real
 * progress of the real installer (Java runtimes are unpacked or downloaded by RuntimeInstaller, libraries and jars are
 * checked on disk). When everything is verified the main screen opens by itself.
 */
public class SetupActivity extends Activity {
    private static final int REQ_ALL = 91;
    private static final String PREF_DONE = "setup_done_v4";

    private static final int WAIT = 0, RUN = 1, READY = 2, FAIL = 3;
    private static final int BLUE = 0xFF7FB8FF, AMBER = 0xFFFFB74D;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private final List<Module> modules = new ArrayList<>();
    private Bar total;
    private TextView percent, summary, desc, button, skip;
    private ScrollView scroll;

    private volatile boolean cancelled;
    private boolean started, finished, failedOnce;
    private volatile Module current;
    private volatile RuntimeInstaller.Meter meter = new RuntimeInstaller.Meter();
    private volatile int javaIndex;

    // ------------------------------------------------------------------ public helper
    /** True when the first-run setup does not have to be shown. */
    public static boolean isDone(Context c) {
        if (!c.getSharedPreferences("azux", MODE_PRIVATE).getBoolean(PREF_DONE, false)) return false;
        for (int m : Runtimes.ALL) if (!Runtimes.installed(c, m)) return false;
        return true;
    }

    // ------------------------------------------------------------------ model
    private abstract class Module {
        final String name, info;
        final int icon, weight;
        volatile int state = WAIT;
        volatile String phase = "";
        volatile String detail = "";
        volatile float prog;
        String error;
        float shown;                 // what the bar currently displays (eased towards prog)
        TextView status, detailView;
        Bar bar;
        FrameLayout tile;
        View card;

        Module(String name, String info, int icon, int weight) {
            this.name = name; this.info = info; this.icon = icon; this.weight = weight;
        }

        /** Runs on the worker thread. Throws on failure. Sets phase/detail/prog while working. */
        abstract void run() throws Exception;
    }

    private File nativeDir() { return new File(getApplicationInfo().nativeLibraryDir); }

    /** True if the file is a 64-bit ARM ELF shared object, i.e. something that can really run on this phone. */
    static boolean isArm64Elf(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] h = new byte[20];
            if (in.read(h) < 20) return false;
            return h[0] == 0x7F && h[1] == 'E' && h[2] == 'L' && h[3] == 'F' && h[4] == 2 && (h[18] & 0xFF) == 0xB7 && h[19] == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void needLibs(String... names) throws Exception {
        for (String n : names) {
            File f = new File(nativeDir(), n);
            if (!f.exists()) throw new Exception(n + " is missing from this APK");
            if (!isArm64Elf(f)) throw new Exception(n + " is not an ARM64 library");
        }
    }

    private void verifyRuntime(int major) throws Exception {
        File jvm = Runtimes.libjvm(this, major);
        if (!jvm.exists()) throw new Exception("Java " + major + ": libjvm.so is missing");
        if (!isArm64Elf(jvm)) throw new Exception("Java " + major + ": libjvm.so is not an ARM64 library");
        File home = Runtimes.home(this, major);
        boolean java = new File(home, "lib/libjava.so").exists() || new File(home, "lib/aarch64/libjava.so").exists();
        if (!java) throw new Exception("Java " + major + ": libjava.so is missing");
        boolean classes = new File(home, "lib/modules").exists() || new File(home, "lib/rt.jar").exists();
        if (!classes) throw new Exception("Java " + major + ": class library (lib/modules or rt.jar) is missing");
    }

    private final class JavaModule extends Module {
        JavaModule() { super("Java Runtime", "Java 8, 17, 21 and 25 for ARM64 - the right one is picked for every Minecraft version", Icons.PACK, 4); }

        @Override void run() throws Exception {
            int[] all = Runtimes.ALL;
            for (int i = 0; i < all.length; i++) {
                int major = all[i];
                javaIndex = i;
                if (!Runtimes.installed(SetupActivity.this, major)) {
                    // never downloads again what is already installed and valid; unpacks the bundled copy or downloads it
                    meter = new RuntimeInstaller.Meter();
                    phase = Runtimes.bundled(SetupActivity.this, major) ? "Installing" : "Downloading";
                    detail = "Java " + major;
                    RuntimeInstaller.ensure(SetupActivity.this, major, msg -> { }, meter);
                } else {
                    meter = new RuntimeInstaller.Meter();
                    meter.done = 1;
                    meter.total = 1;
                }
                phase = "Verifying";
                detail = "Java " + major + ": checking libjvm.so";
                prog = (i + 1f) / all.length;
                verifyRuntime(major);
            }
            javaIndex = all.length;
        }
    }

    private void buildModules() {
        modules.add(new Module("Azux Engine", "Native engine and JNI bridge that starts the Java VM", Icons.GEAR, 1) {
            @Override void run() throws Exception {
                phase = "Verifying";
                detail = "libazuxengine.so";
                needLibs("libazuxengine.so");
                try {
                    Class.forName("com.azux.launcher.engine.Engine");   // loads the library: proves it links on this device
                } catch (Throwable t) {
                    throw new Exception("The engine library could not be loaded: " + t.getMessage());
                }
            }
        });
        modules.add(new JavaModule());
        modules.add(new Module("Graphics & Audio", "OpenGL translation layers (gl4es, MobileGlues) and OpenAL sound", Icons.LAYERS, 1) {
            @Override void run() throws Exception {
                phase = "Verifying";
                detail = "libgl4es.so";
                needLibs("libgl4es.so");
                File mg = new File(nativeDir(), "libmobileglues.so");
                if (mg.exists() && !isArm64Elf(mg)) throw new Exception("libmobileglues.so is not an ARM64 library");
                File al = new File(nativeDir(), "libopenal.so");
                if (al.exists() && !isArm64Elf(al)) throw new Exception("libopenal.so is not an ARM64 library");
                detail = al.exists() ? "OpenGL + OpenAL ready" : "OpenGL ready (sound library not included)";
            }
        });
        modules.add(new Module("LWJGL Libraries", "Java side of LWJGL, matched to the native libraries", Icons.PUZZLE, 1) {
            @Override void run() throws Exception {
                phase = "Installing";
                detail = "Copying LWJGL jars";
                Libs.installLwjgl(SetupActivity.this);
                phase = "Verifying";
                List<File> jars = Libs.lwjglJars(SetupActivity.this);
                for (int i = 0; i < jars.size(); i++) {
                    File j = jars.get(i);
                    detail = j.getName();
                    prog = (i + 1f) / jars.size();
                    if (!j.exists()) throw new Exception(j.getName() + " could not be installed");
                    try (ZipFile z = new ZipFile(j)) {
                        if (z.size() == 0) throw new Exception(j.getName() + " is empty");
                    } catch (java.io.IOException e) {
                        throw new Exception(j.getName() + " is damaged");
                    }
                }
            }
        });
        modules.add(new Module("Native Libraries", "LWJGL, image, font and system natives for ARM64", Icons.DOWNLOAD, 1) {
            @Override void run() throws Exception {
                phase = "Verifying";
                String[] libs = {"liblwjgl.so", "liblwjgl_opengl.so", "liblwjgl_stb.so", "liblwjgl_tinyfd.so"};
                for (int i = 0; i < libs.length; i++) {
                    detail = libs[i];
                    prog = (i + 1f) / libs.length;
                    needLibs(libs[i]);
                }
                for (String optional : new String[]{"libjnidispatch.so", "libfreetype.so"}) {
                    File f = new File(nativeDir(), optional);
                    if (f.exists() && !isArm64Elf(f)) throw new Exception(optional + " is not an ARM64 library");
                }
            }
        });
        modules.add(new Module("Launcher Components", "Essential launcher files and folders", Icons.HOME, 1) {
            @Override void run() throws Exception {
                phase = "Installing";
                detail = "Creating folders";
                Paths.versions(SetupActivity.this);
                Paths.libraries(SetupActivity.this);
                Paths.assets(SetupActivity.this);
                Paths.accounts(SetupActivity.this);
                Paths.controls(SetupActivity.this);
                File game = Paths.game(SetupActivity.this);
                new File(game, "tmp").mkdirs();
                Paths.cache(SetupActivity.this);
                detail = "javacheck.jar";
                try (InputStream in = getAssets().open("javacheck.jar");
                     OutputStream out = new java.io.FileOutputStream(new File(game, "javacheck.jar"))) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
        });
        modules.add(new Module("Required Assets & Dependencies", "Game folder, storage space and write access for versions, assets and mods", Icons.WORLD, 1) {
            @Override void run() throws Exception {
                phase = "Verifying";
                File game = Paths.game(SetupActivity.this);
                detail = "Write test";
                File t = new File(game, ".azux_write_test");
                try (OutputStream out = new java.io.FileOutputStream(t)) { out.write(1); }
                if (!t.delete()) throw new Exception("The game folder is not writable: " + game);
                detail = "Free space";
                long free = new StatFs(game.getPath()).getAvailableBytes();
                if (free < 600L * 1024 * 1024) {
                    throw new Exception("Only " + (free / (1024 * 1024)) + " MB free. Minecraft needs about 1 GB for a first version.");
                }
                detail = (free / (1024 * 1024 * 1024)) + " GB free";
            }
        });
    }

    // ------------------------------------------------------------------ lifecycle
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("azux", MODE_PRIVATE);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.BG));
        buildModules();
        buildUi();
        refreshAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (started || finished || failedOnce) return;
        if (hasAccess()) startInstall(); else showPermissionState();
    }

    @Override
    protected void onDestroy() {
        cancelled = true;
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ permission
    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())), REQ_ALL);
            } catch (Exception e) {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_ALL);
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_ALL);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_ALL && !started && hasAccess()) startInstall();
    }

    private void showPermissionState() {
        summary.setText("Storage access");
        desc.setText("Azux needs access to all files to keep game data, mods and screenshots in one place. Tap Grant access, "
                + "allow it on the next page and come back.");
        button.setText("Grant access");
        button.setAlpha(1f);
        button.setOnClickListener(v -> requestAccess());
        skip.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ install
    private void startInstall() {
        started = true;
        failedOnce = false;
        skip.setVisibility(View.GONE);
        summary.setText("Preparing Azux");
        desc.setText("The launcher is installing and checking its components. When everything is ready it opens by itself.");
        button.setText("Installing...");
        button.setAlpha(0.55f);
        button.setOnClickListener(null);
        for (Module m : modules) { m.state = WAIT; m.error = null; m.prog = 0; m.shown = 0; m.phase = ""; m.detail = ""; }
        refreshAll();
        ui.post(ticker);

        new Thread(() -> {
            for (final Module m : modules) {
                if (cancelled) return;
                current = m;
                m.state = RUN;
                m.phase = "Verifying";
                ui.post(() -> { refreshAll(); scrollTo(m); });
                try {
                    m.run();
                    m.prog = 1f;
                    m.state = READY;
                    ui.post(this::refreshAll);
                    try { Thread.sleep(150); } catch (InterruptedException ignored) { }
                } catch (final Throwable t) {
                    m.error = t.getMessage() == null ? t.toString() : t.getMessage();
                    m.state = FAIL;
                    ui.post(this::failed);
                    return;
                }
            }
            current = null;
            ui.post(this::allDone);
        }, "azux-setup").start();
    }

    private void failed() {
        started = false;
        failedOnce = true;
        current = null;
        refreshAll();
        summary.setText("Setup stopped");
        desc.setText(firstError());
        button.setText("Retry");
        button.setAlpha(1f);
        button.setOnClickListener(v -> startInstall());
    }

    private String firstError() {
        for (Module m : modules) if (m.state == FAIL && m.error != null) return m.name + ": " + m.error;
        return "Unknown error";
    }

    private void allDone() {
        finished = true;
        prefs.edit().putBoolean(PREF_DONE, true).apply();
        refreshAll();
        summary.setText("All set");
        desc.setText("Everything is installed and verified.");
        button.setText("Starting Azux...");
        ui.postDelayed(() -> {
            if (cancelled) return;
            startActivity(new Intent(this, MainActivity.class));
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }, 650);
    }

    /** Polls the real progress of the module being worked on and eases the bars towards it. */
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (cancelled || finished) return;
            Module m = current;
            if (m instanceof JavaModule && m.state == RUN) {
                int n = Runtimes.ALL.length;
                int i = Math.min(javaIndex, n - 1);
                boolean verifying = "Verifying".equals(m.phase);
                float frac = verifying ? 1f : meter.fraction();
                m.prog = (i + frac) / n;
                if (!verifying) {
                    String f = meter.file;
                    m.phase = meter.phase.isEmpty() ? m.phase : meter.phase;
                    m.detail = "Java " + Runtimes.ALL[i] + (f == null || f.isEmpty() ? "" : "  -  " + f);
                }
            }
            for (Module x : modules) {
                float target = x.state == READY ? 1f : x.prog;
                x.shown += (target - x.shown) * 0.25f;
                if (Math.abs(target - x.shown) < 0.002f) x.shown = target;
                if (x.bar != null && x.state == RUN) x.bar.set(x.shown);
            }
            if (m != null && m.state == RUN) paint(m);
            updateTotal();
            ui.postDelayed(this, 60);
        }
    };

    // ------------------------------------------------------------------ UI refresh
    private void refreshAll() {
        for (Module m : modules) paint(m);
        updateTotal();
    }

    private void updateTotal() {
        float sum = 0, all = 0;
        int ready = 0;
        for (Module m : modules) {
            all += m.weight;
            float f = m.state == READY ? 1f : m.state == RUN ? m.shown : 0f;
            sum += f * m.weight;
            if (m.state == READY) ready++;
        }
        float t = all == 0 ? 0 : sum / all;
        total.set(t);
        percent.setText(Math.round(t * 100) + "%");
        if (!finished && started && current != null) summary.setText(current.name);
        TextView count = (TextView) percent.getTag();
        if (count != null) count.setText(ready + " of " + modules.size() + " components ready");
    }

    private void paint(Module m) {
        int accent = m.state == READY ? Ui.GOOD : m.state == FAIL ? Ui.BAD : m.state == RUN ? Ui.TEAL : Ui.DIM;
        GradientDrawable card = Ui.round(this, Ui.CARD, 14);
        if (m.state == RUN) card.setStroke(Ui.dpi(this, 1), 0x665ED3C6);
        else if (m.state == FAIL) card.setStroke(Ui.dpi(this, 1), 0x88E57373);
        m.card.setBackground(card);

        String label;
        int fg, bg;
        if (m.state == READY) { label = "COMPLETED"; fg = Ui.GOOD; bg = 0x2266BB6A; }
        else if (m.state == FAIL) { label = "FAILED"; fg = Ui.BAD; bg = 0x33E57373; }
        else if (m.state == RUN) {
            label = m.phase.isEmpty() ? "INSTALLING" : m.phase.toUpperCase();
            fg = label.startsWith("DOWN") ? BLUE : label.startsWith("VERIF") ? AMBER : Ui.TEAL;
            bg = (fg & 0x00FFFFFF) | 0x26000000;
        } else { label = "WAITING"; fg = Ui.DIM; bg = 0xFF2E2E2E; }
        m.status.setText(label);
        m.status.setTextColor(fg);
        m.status.setBackground(Ui.round(this, bg, 6));

        ((TextView) m.tile.getChildAt(1)).setText(m.state == READY ? "\u2713" : m.state == FAIL ? "!" : "");
        m.tile.getChildAt(1).setVisibility(m.state == READY || m.state == FAIL ? View.VISIBLE : View.GONE);
        m.tile.getChildAt(0).setAlpha(m.state == READY || m.state == FAIL ? 0.25f : 1f);
        ((TextView) m.tile.getChildAt(1)).setTextColor(accent);

        boolean showBar = m.state == RUN;
        m.bar.setVisibility(showBar ? View.VISIBLE : View.GONE);
        boolean showDetail = m.state == RUN || m.state == FAIL || (m.state == READY && !m.detail.isEmpty());
        m.detailView.setVisibility(showDetail ? View.VISIBLE : View.GONE);
        if (m.state == FAIL) {
            m.detailView.setTextColor(Ui.BAD);
            m.detailView.setText(m.error == null ? "" : m.error);
            m.detailView.setSingleLine(false);
            m.detailView.setEllipsize(null);
        } else {
            m.detailView.setTextColor(m.state == READY ? Ui.DIM : Ui.TEAL);
            m.detailView.setText(m.detail);
            m.detailView.setSingleLine(true);
            m.detailView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        }
    }

    private void scrollTo(final Module m) {
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, m.card.getTop() - Ui.dpi(this, 8))));
    }

    // ------------------------------------------------------------------ layout
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // same top bar as the launcher
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(Ui.SIDE);
        top.setPadding(Ui.dpi(this, 14), Ui.dpi(this, 10), Ui.dpi(this, 14), Ui.dpi(this, 10));
        TextView logo = Ui.text(this, "AZUX", 16, Ui.ACCENT, true);
        logo.setLetterSpacing(0.2f);
        top.addView(logo);
        TextView title = Ui.text(this, "Setup", 14, Ui.DIM, false);
        title.setPadding(Ui.dpi(this, 12), 0, 0, 0);
        top.addView(title);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        int pad = Ui.dpi(this, 14);
        body.setPadding(pad, pad, pad, pad);
        body.addView(buildSummary(), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        body.addView(new View(this), new LinearLayout.LayoutParams(Ui.dpi(this, 14), 1));
        body.addView(buildList(), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.55f));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Anim.pageIn(body);
    }

    private View buildSummary() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, Ui.CARD, 16));
        int p = Ui.dpi(this, 18);
        card.setPadding(p, p, p, p);

        summary = Ui.text(this, "Preparing Azux", 18, Ui.TXT, true);
        card.addView(summary);
        TextView count = Ui.text(this, "", 12, Ui.DIM, false);
        card.addView(count);

        card.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        percent = Ui.text(this, "0%", 44, Ui.TXT, true);
        percent.setTag(count);
        card.addView(percent);
        total = new Bar(this, 8);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dpi(this, 8));
        tl.topMargin = Ui.dpi(this, 8);
        card.addView(total, tl);

        desc = Ui.text(this, "", 12, Ui.DIM, false);
        desc.setMaxLines(5);
        desc.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.topMargin = Ui.dpi(this, 14);
        card.addView(desc, dl);

        // primary button, same look as the launcher's main button
        button = Ui.text(this, "Install", 15, Color.BLACK, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(Ui.round(this, Ui.ACCENT, 12));
        Anim.press(button);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dpi(this, 46));
        bl.topMargin = Ui.dpi(this, 14);
        card.addView(button, bl);

        skip = Ui.text(this, "Skip for now", 12, Ui.DIM, false);
        skip.setGravity(Gravity.CENTER);
        skip.setPadding(0, Ui.dpi(this, 10), 0, Ui.dpi(this, 2));
        skip.setVisibility(View.GONE);
        skip.setOnClickListener(v -> { if (!started) startInstall(); });
        card.addView(skip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private View buildList() {
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < modules.size(); i++) {
            View c = buildCard(modules.get(i));
            list.addView(c);
            Anim.enter(c, i * 55);
        }
        return scroll;
    }

    private View buildCard(final Module m) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pd = Ui.dpi(this, 12);
        row.setPadding(pd, pd, pd, pd);
        m.card = row;
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.bottomMargin = Ui.dpi(this, 8);
        row.setLayoutParams(rl);

        // icon tile (turns into a tick / warning when the component is done / failed)
        m.tile = new FrameLayout(this);
        m.tile.setBackground(Ui.round(this, Ui.CARD2, 12));
        m.tile.addView(Icons.view(this, m.icon, 22, Ui.TEAL), new FrameLayout.LayoutParams(Ui.dpi(this, 22), Ui.dpi(this, 22), Gravity.CENTER));
        TextView mark = Ui.text(this, "", 20, Ui.GOOD, true);
        mark.setGravity(Gravity.CENTER);
        mark.setVisibility(View.GONE);
        m.tile.addView(mark, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(m.tile, new LinearLayout.LayoutParams(Ui.dpi(this, 44), Ui.dpi(this, 44)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dpi(this, 12), 0, 0, 0);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = Ui.text(this, m.name, 15, Ui.TXT, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        m.status = Ui.text(this, "WAITING", 10, Ui.DIM, true);
        m.status.setLetterSpacing(0.08f);
        m.status.setPadding(Ui.dpi(this, 8), Ui.dpi(this, 3), Ui.dpi(this, 8), Ui.dpi(this, 3));
        top.addView(m.status);
        col.addView(top);

        TextView info = Ui.text(this, m.info, 12, Ui.DIM, false);
        info.setPadding(0, Ui.dpi(this, 3), 0, 0);
        col.addView(info);

        m.detailView = Ui.text(this, "", 11, Ui.TEAL, false);
        m.detailView.setTypeface(Typeface.MONOSPACE);
        m.detailView.setPadding(0, Ui.dpi(this, 6), 0, 0);
        m.detailView.setVisibility(View.GONE);
        col.addView(m.detailView);

        m.bar = new Bar(this, 4);
        m.bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dpi(this, 4));
        bl.topMargin = Ui.dpi(this, 7);
        col.addView(m.bar, bl);

        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    // ------------------------------------------------------------------ progress bar
    /** Rounded progress bar in the launcher's accent colours. */
    private static final class Bar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float v;

        Bar(Context c, int heightDp) { super(c); }

        void set(float f) {
            float n = Math.max(0f, Math.min(1f, f));
            if (n != v) { v = n; invalidate(); }
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setShader(null);
            p.setColor(0xFF2E2E2E);
            r.set(0, 0, w, h);
            c.drawRoundRect(r, h / 2, h / 2, p);
            if (v <= 0f) return;
            p.setShader(new LinearGradient(0, 0, w, 0, 0xFF7FB8FF, 0xFF5ED3C6, Shader.TileMode.CLAMP));
            r.set(0, 0, Math.max(h, w * v), h);
            c.drawRoundRect(r, h / 2, h / 2, p);
        }
    }
}
