package com.azux.launcher.controls;

/** Where control events go. The native engine (v8) implements this; for now a debug sink does. */
public interface InputSink {
    void key(int glfwKey, boolean down);
    void mouseButton(int button, boolean down);
    void mouseMove(float dx, float dy);
    void scroll(double dy);
    void special(String id);

    /** First touch of the camera pad (view coordinates). Used to place the pointer in menus. */
    default void touchDown(float x, float y) { }
}
