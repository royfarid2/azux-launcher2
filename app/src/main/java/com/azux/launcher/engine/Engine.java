package com.azux.launcher.engine;

import android.view.Surface;

import java.util.List;

/**
 * Entry to the native layer (libazuxengine.so): starts the Java runtime inside this process, owns the
 * GLFW-style window (the app's Surface + an EGL context) and receives input from the launcher UI.
 * {@link #available()} is false when the library could not be loaded (the APK was built without it).
 */
public final class Engine {
    private Engine() { }

    public static final int PRESS = 1, RELEASE = 0;
    public static final int CURSOR_GRABBED = 0x34003;

    private static boolean loaded;
    private static String error = "";

    static {
        try {
            System.loadLibrary("azuxengine");
            loaded = true;
        } catch (Throwable t) {
            loaded = false;
            error = String.valueOf(t.getMessage());
        }
    }

    public static boolean available() { return loaded; }
    public static String loadError() { return error; }

    /** Blocks until the JVM ends. args[0] is the java path (ignored), then JVM options, main class, program args. */
    public static int run(String javaHome, List<String> args, String workDir, String logFile) {
        return nativeRun(javaHome, args.toArray(new String[0]), workDir, logFile);
    }

    // ---- surface + input (safe to call even if the game is not running yet) ----
    public static void surface(Surface s, int w, int h) { if (loaded) nativeSurface(s, w, h); }
    public static void key(int glfwKey, boolean down) { if (loaded) nativeKey(glfwKey, down ? PRESS : RELEASE); }
    public static void character(int codepoint) { if (loaded) nativeChar(codepoint); }
    public static void mouseButton(int button, boolean down) { if (loaded) nativeMouseButton(button, down ? PRESS : RELEASE); }
    public static void mouseDelta(float dx, float dy) { if (loaded) nativeMouseDelta(dx, dy); }
    public static void cursorAbs(float x, float y) { if (loaded) nativeCursorAbs(x, y); }
    public static void scroll(float dx, float dy) { if (loaded) nativeScroll(dx, dy); }
    public static void focus(boolean f) { if (loaded) nativeFocus(f ? 1 : 0); }
    public static void requestClose() { if (loaded) nativeRequestClose(); }

    /** out = {x, y, mode}; mode == CURSOR_GRABBED while the game controls the camera. */
    public static boolean cursorState(double[] out) {
        if (!loaded) return false;
        nativeCursorState(out);
        return true;
    }

    private static native int nativeRun(String javaHome, String[] args, String workDir, String logFile);
    private static native void nativeSurface(Surface s, int w, int h);
    private static native void nativeKey(int key, int action);
    private static native void nativeChar(int codepoint);
    private static native void nativeMouseButton(int button, int action);
    private static native void nativeMouseDelta(float dx, float dy);
    private static native void nativeCursorAbs(float x, float y);
    private static native void nativeScroll(float dx, float dy);
    private static native void nativeFocus(int focused);
    private static native void nativeRequestClose();
    private static native void nativeCursorState(double[] out);
}
