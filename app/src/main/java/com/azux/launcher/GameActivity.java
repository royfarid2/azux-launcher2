package com.azux.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.azux.launcher.controls.ControlStore;
import com.azux.launcher.controls.ControlsOverlay;
import com.azux.launcher.controls.InputSink;
import com.azux.launcher.controls.KeyMap;
import com.azux.launcher.controls.Keys;
import com.azux.launcher.core.Accounts;
import com.azux.launcher.core.Downloader;
import com.azux.launcher.core.LaunchBuilder;
import com.azux.launcher.core.Libs;
import com.azux.launcher.core.Net;
import com.azux.launcher.core.Paths;
import com.azux.launcher.core.RuntimeInstaller;
import com.azux.launcher.core.Runtimes;
import com.azux.launcher.engine.Engine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Game screen (runs in its own process ":game", so when the JVM exits the launcher survives).
 * Prepares files, unpacks Java, starts the JVM through the native engine, shows the game on a
 * SurfaceView and feeds the Azux controls / keyboard into the engine.
 */
public class GameActivity extends Activity implements SurfaceHolder.Callback {
    private SharedPreferences prefs;
    private ControlsOverlay overlay;
    private SurfaceView surface;
    private PointerView pointer;
    private KeyboardCatcher keyboard;
    private TextView hud;
    private String statusLine = "";
    private String logTail = "";
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private boolean hudOn;
    private long lastProgress;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private File logFile;
    private boolean polling, jvmRunning, grabbed;
    private float bufScale = 1f;       // game pixels per screen pixel
    private int renderScale = 75;
    private float sensitivity = 1f;
    private final double[] cursor = new double[3];

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("azux", MODE_PRIVATE);
        hudOn = prefs.getBoolean("hud", true);
        renderScale = Math.max(40, Math.min(100, prefs.getInt("render_scale", 75)));
        sensitivity = prefs.getInt("sensitivity", 100) / 100f;
        logFile = new File(Paths.game(this), "latest.log");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        surface = new SurfaceView(this);
        surface.getHolder().setFormat(PixelFormat.RGBA_8888);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = new ControlsOverlay(this, ControlStore.load(this, ControlStore.currentName(this)), false, new GameSink());
        root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        pointer = new PointerView(this);
        root.addView(pointer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        keyboard = new KeyboardCatcher(this);
        root.addView(keyboard, new FrameLayout.LayoutParams(1, 1));

        hud = new TextView(this);
        hud.setTextColor(0xFFFFFFFF);
        hud.setTextSize(10);
        hud.setTypeface(Typeface.MONOSPACE);
        hud.setBackgroundColor(0x99000000);
        hud.setPadding(Ui.dpi(this, 8), Ui.dpi(this, 4), Ui.dpi(this, 8), Ui.dpi(this, 4));
        hud.setMaxWidth(Ui.dpi(this, 460));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hp.topMargin = Ui.dpi(this, 6);
        root.addView(hud, hp);

        TextView menu = Ui.text(this, "Menu", 12, Ui.TXT, false);
        menu.setPadding(Ui.dpi(this, 12), Ui.dpi(this, 6), Ui.dpi(this, 12), Ui.dpi(this, 6));
        menu.setBackground(Ui.round(this, 0x99000000, 14));
        menu.setOnClickListener(v -> menuDialog());
        Anim.press(menu);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        mp.topMargin = Ui.dpi(this, 8);
        mp.rightMargin = Ui.dpi(this, 12);
        root.addView(menu, mp);

        setContentView(root);
        render();
        startPolling();

        String url = getIntent().getStringExtra("url");
        pipeline(getIntent().getStringExtra("version"), url == null ? "" : url,
                getIntent().getStringExtra("account"), getIntent().getBooleanExtra("selftest", false));
    }

    // ------------------------------------------------------------------ surface
    @Override public void surfaceCreated(SurfaceHolder h) {
        DisplayMetricsHelper m = new DisplayMetricsHelper(this);
        int w = Math.max(320, m.w * renderScale / 100), hh = Math.max(180, m.h * renderScale / 100);
        h.setFixedSize(w, hh);
    }

    @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {
        int vw = Math.max(1, surface.getWidth());
        bufScale = w / (float) vw;
        Engine.surface(h.getSurface(), w, hh);
    }

    @Override public void surfaceDestroyed(SurfaceHolder h) { Engine.surface(null, 0, 0); }

    private static final class DisplayMetricsHelper {
        final int w, h;
        DisplayMetricsHelper(Activity a) {
            android.util.DisplayMetrics m = a.getResources().getDisplayMetrics();
            w = Math.max(m.widthPixels, m.heightPixels);
            h = Math.min(m.widthPixels, m.heightPixels);
        }
    }

    // ------------------------------------------------------------------ pipeline
    private void pipeline(final String id, final String url, final String accName, final boolean selftest) {
        new Thread(() -> {
            try {
                Paths.game(this);
                if (selftest) {
                    final int major = getIntent().getIntExtra("major", 17);
                    ensureRuntime(major);
                    File jar = new File(Paths.game(this), "javacheck.jar");
                    try (java.io.InputStream in = getAssets().open("javacheck.jar"); java.io.FileOutputStream out = new java.io.FileOutputStream(jar)) {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    List<String> cmd = new ArrayList<>(Arrays.asList(Runtimes.javaBin(this, major).getPath(),
                            "-Xmx256M", "-XX:+UseSerialGC", "-XX:-UsePerfData", "-Djava.awt.headless=true",
                            "-Djava.io.tmpdir=" + new File(Paths.game(this), "tmp").getPath(),
                            "-Dazux.nativedir=" + getApplicationInfo().nativeLibraryDir,
                            "-cp", jar.getPath(), "JavaCheck"));
                    new File(Paths.game(this), "tmp").mkdirs();
                    runJvm(major, cmd);
                    return;
                }
                JSONObject v;
                if (getIntent().getBooleanExtra("prepared", false)) {
                    // the launcher already downloaded everything: just read the version file
                    v = new JSONObject(Net.readText(new File(new File(Paths.versions(this), id), id + ".json")));
                } else {
                    setStatus("Preparing " + id + " ...");
                    v = Downloader.prepare(this, id, url, (stage, done, total) -> {
                        long now = System.currentTimeMillis();
                        synchronized (this) {
                            if (done != total && now - lastProgress < 150) return;
                            lastProgress = now;
                        }
                        setStatus(stage + " " + done + "/" + total);
                    });
                }
                Accounts.Account acc = Accounts.get(this, accName);
                if (acc == null) throw new Exception("Account not found");
                int required = Runtimes.required(v);
                int major = Runtimes.pick(required);
                ensureRuntime(major);
                String warn = compatibilityWarning(v, required);

                File game = Paths.game(this);
                File natives = new File(getApplicationInfo().nativeLibraryDir);
                if (!new File(natives, "liblwjgl.so").exists()) {
                    throw new Exception("LWJGL natives are missing from this APK. The CI step 'Build native libraries' failed - send me its log.");
                }
                List<String> extra = new ArrayList<>(Arrays.asList(
                        "-Djava.awt.headless=true", "-XX:+UseSerialGC", "-XX:-UsePerfData", "-Dfile.encoding=UTF-8",
                        "-Djava.io.tmpdir=" + new File(game, "tmp").getPath(), "-Duser.home=" + game.getPath(),
                        "-Dazux.nativedir=" + natives.getPath(),
                        "-Dorg.lwjgl.librarypath=" + natives.getPath(),
                        "-Dorg.lwjgl.system.allocator=system",
                        "-Dorg.lwjgl.glfw.libname=" + new File(natives, "libazuxengine.so").getPath(),
                        "-Dorg.lwjgl.opengl.libname=" + new File(natives, "libgl4es.so").getPath(),
                        "-Dazux.gl4es=" + new File(natives, "libgl4es.so").getPath()));
                if (new File(natives, "libopenal.so").exists()) {
                    extra.add("-Dorg.lwjgl.openal.libname=" + new File(natives, "libopenal.so").getPath());
                }
                List<String> cmd = LaunchBuilder.build(this, v, id, acc, prefs.getInt("ram", 2048),
                        Runtimes.javaBin(this, major).getPath(), extra);
                Net.writeText(new File(game, "last-launch.txt"), TextUtils.join("\n", cmd));
                if (!warn.isEmpty()) setStatus(warn);
                runJvm(major, cmd);
            } catch (Throwable t) {
                setStatus("Error: " + t.getMessage());
            }
        }).start();
    }

    /** Honest heads-up about versions the graphics layer cannot run yet. */
    private String compatibilityWarning(JSONObject v, int requiredJava) {
        for (Libs.Lib l : Libs.of(v)) {
            if (l.path.startsWith("org/lwjgl/lwjgl/lwjgl/2.")) return "Note: LWJGL 2 versions (1.12.2 and older) are not supported yet.";
        }
        JSONArray libs = v.optJSONArray("libraries");
        if (libs != null) {
            for (int i = 0; i < libs.length(); i++) {
                String n = libs.optJSONObject(i) == null ? "" : libs.optJSONObject(i).optString("name");
                if (n.startsWith("org.lwjgl.lwjgl:lwjgl:2.")) return "Note: LWJGL 2 versions (1.12.2 and older) are not supported yet.";
            }
        }
        if (requiredJava >= 16) return "Note: 1.17+ needs OpenGL 3.2 core, not supported yet. Best results: 1.13 - 1.16.5.";
        return "";
    }

    private void ensureRuntime(int major) throws Exception {
        if (!Engine.available()) {
            File dir = new File(getApplicationInfo().nativeLibraryDir);
            String[] files = dir.list();
            throw new Exception("Native engine library not loaded (" + Engine.loadError() + ").\nlib dir: " + dir
                    + "\nfiles: " + (files == null ? "none" : Arrays.toString(files))
                    + "\nThe CI step 'Build native libraries' failed for this APK - open that step's log (or the build-logs artifact).");
        }
        if (!Runtimes.available(this, major)) {
            throw new Exception("This APK has no Java " + major + " runtime and no download address. Check the CI step 'What is inside the APK'.");
        }
        RuntimeInstaller.ensure(this, major, msg -> setStatus(msg));
    }

    private void runJvm(int major, List<String> cmd) throws Exception {
        Net.writeText(new File(Paths.game(this), ".show_log"), "1");
        setStatus("Starting Java " + major + " ...");
        final int[] code = {0};
        final String home = Runtimes.home(this, major).getPath();
        final String cwd = Paths.game(this).getPath();
        jvmRunning = true;
        Thread t = new Thread(null, () -> code[0] = Engine.run(home, cmd, cwd, logFile.getPath()), "azux-jvm", 128L * 1024 * 1024);
        t.start();
        t.join();
        jvmRunning = false;
        setStatus("Java ended with code " + code[0] + ". Open Menu > Copy log.");
    }

    private void setStatus(final String s) {
        runOnUiThread(() -> { statusLine = s; render(); });
    }

    // ------------------------------------------------------------------ hud + log
    private void startPolling() {
        if (polling) return;
        polling = true;
        handler.post(new Runnable() {
            int tick;
            @Override public void run() {
                if (Engine.cursorState(cursor)) {
                    grabbed = ((int) cursor[2]) == Engine.CURSOR_GRABBED;
                    pointer.set((float) (cursor[0] / bufScale), (float) (cursor[1] / bufScale), jvmRunning && !grabbed);
                }
                if (tick++ % 20 == 0) { logTail = tail(2500, 7); render(); }
                handler.postDelayed(this, 33);
            }
        });
    }

    private String tail(int bytes, int lines) {
        try (RandomAccessFile f = new RandomAccessFile(logFile, "r")) {
            long len = f.length();
            int n = (int) Math.min(bytes, len);
            byte[] b = new byte[n];
            f.seek(len - n);
            f.readFully(b);
            String[] l = new String(b, "UTF-8").split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = Math.max(0, l.length - lines); i < l.length; i++) sb.append(l[i].length() > 90 ? l[i].substring(0, 90) : l[i]).append('\n');
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void render() {
        if (!hudOn) { hud.setVisibility(View.GONE); return; }
        hud.setVisibility(View.VISIBLE);
        StringBuilder sb = new StringBuilder(statusLine);
        if (!logTail.isEmpty()) sb.append('\n').append(logTail);
        for (String e : events) sb.append('\n').append(e);
        hud.setText(sb.toString());
    }

    private void log(String s) {
        events.addLast(s);
        while (events.size() > 3) events.removeFirst();
        if (hudOn) render();
    }

    private void menuDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Azux")
                .setItems(new String[]{"Back to launcher", hudOn ? "Hide debug text" : "Show debug text", "Copy log", "Keyboard"}, (d, which) -> {
                    if (which == 0) { Engine.requestClose(); finish(); }
                    else if (which == 1) { hudOn = !hudOn; prefs.edit().putBoolean("hud", hudOn).apply(); render(); }
                    else if (which == 2) copyLog();
                    else showKeyboard();
                })
                .show();
    }

    private void copyLog() {
        String text = tail(60000, 400);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Azux log", text));
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ input
    /** Sends control events to the game and shows the last few on the debug text. */
    private final class GameSink implements InputSink {
        @Override public void key(int code, boolean down) {
            Engine.key(code, down);
            String name = "key " + code;
            for (Keys.K k : Keys.ALL) if (k.kind == Keys.KEY && k.code == code) { name = k.id; break; }
            log(name + (down ? " down" : " up"));
        }
        @Override public void mouseButton(int button, boolean down) { Engine.mouseButton(button, down); }
        @Override public void mouseMove(float dx, float dy) { Engine.mouseDelta(dx * bufScale * sensitivity, dy * bufScale * sensitivity); }
        @Override public void scroll(double dy) { Engine.scroll(0f, (float) dy); log("scroll " + (dy > 0 ? "up" : "down")); }
        @Override public void touchDown(float x, float y) { if (!grabbed && jvmRunning) Engine.cursorAbs(x * bufScale, y * bufScale); }
        @Override public void special(String id) {
            if (id.equals("GUI")) menuDialog();
            else if (id.equals("KEYBOARD")) showKeyboard();
            else log(id);
        }
    }

    private void showKeyboard() {
        keyboard.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(keyboard, InputMethodManager.SHOW_FORCED);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {   // Back = pause menu (Esc)
                Engine.key(256, true);
                Engine.key(256, false);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) return super.dispatchKeyEvent(e);
        int g = KeyMap.toGlfw(code);
        if (g < 0) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) Engine.key(g, true);
            int u = e.getUnicodeChar();
            if (u >= 32 && !e.isCtrlPressed()) Engine.character(u);
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            Engine.key(g, false);
        }
        return true;
    }

    /** Invisible view that owns the soft keyboard and turns typed text into GLFW char/key events. */
    private final class KeyboardCatcher extends View {
        KeyboardCatcher(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        @Override public boolean onCheckIsTextEditor() { return true; }

        @Override public InputConnection onCreateInputConnection(EditorInfo out) {
            out.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
            out.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE;
            return new BaseInputConnection(this, false) {
                @Override public boolean commitText(CharSequence t, int pos) {
                    for (int i = 0; i < t.length(); ) {
                        int cp = Character.codePointAt(t, i);
                        Engine.character(cp);
                        i += Character.charCount(cp);
                    }
                    return true;
                }
                @Override public boolean deleteSurroundingText(int before, int after) {
                    for (int i = 0; i < Math.max(1, before); i++) { Engine.key(259, true); Engine.key(259, false); }
                    return true;
                }
                @Override public boolean sendKeyEvent(KeyEvent e) {
                    int g = KeyMap.toGlfw(e.getKeyCode());
                    if (g >= 0) {
                        if (e.getAction() == KeyEvent.ACTION_DOWN) Engine.key(g, true);
                        else if (e.getAction() == KeyEvent.ACTION_UP) Engine.key(g, false);
                    }
                    return true;
                }
                @Override public boolean performEditorAction(int action) {
                    Engine.key(257, true);
                    Engine.key(257, false);
                    return true;
                }
            };
        }
    }

    /** Draws the on-screen mouse pointer while a menu is open (the game does not draw one). */
    private static final class PointerView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private float x, y;
        private boolean show;

        PointerView(Context c) {
            super(c);
            fill.setColor(0xFFFFFFFF);
            line.setColor(0xFF000000);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2f * c.getResources().getDisplayMetrics().density);
        }

        void set(float nx, float ny, boolean s) {
            if (nx != x || ny != y || s != show) { x = nx; y = ny; show = s; invalidate(); }
        }

        @Override protected void onDraw(Canvas c) {
            if (!show) return;
            float d = getResources().getDisplayMetrics().density;
            arrow.reset();
            arrow.moveTo(x, y);
            arrow.lineTo(x, y + 20 * d);
            arrow.lineTo(x + 5 * d, y + 15 * d);
            arrow.lineTo(x + 10 * d, y + 22 * d);
            arrow.lineTo(x + 13 * d, y + 20 * d);
            arrow.lineTo(x + 8 * d, y + 13 * d);
            arrow.lineTo(x + 15 * d, y + 13 * d);
            arrow.close();
            c.drawPath(arrow, fill);
            c.drawPath(arrow, line);
        }
    }

    // ------------------------------------------------------------------ window
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
        Engine.focus(hasFocus);
    }

    @Override protected void onPause() {
        super.onPause();
        overlay.releaseAll();
    }
}
