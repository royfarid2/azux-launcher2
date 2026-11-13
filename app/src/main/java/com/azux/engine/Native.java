package com.azux.engine;

/** Thin JNI bridge to libazux.so (our own JVM launcher). */
public final class Native {
    static { System.loadLibrary("azux"); }

    /** Starts a JVM inside this process and blocks until it exits. One JVM per process lifetime. */
    public static native int launch(String javaHome, String logPath, String[] jvmArgs,
                                    String mainClass, String[] progArgs);

    private Native() {}
}
