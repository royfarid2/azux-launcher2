/* JNI entry points the launcher UI uses to feed the engine (surface + input). */
#include <jni.h>

#include "azux_internal.h"

void azux_plat_set_surface(JNIEnv *env, jobject surface, int w, int h);

#define J(name) Java_com_azux_launcher_engine_Engine_##name

JNIEXPORT void JNICALL J(nativeSurface)(JNIEnv *env, jclass c, jobject s, jint w, jint h) { (void) c; azux_plat_set_surface(env, s, w, h); }
JNIEXPORT void JNICALL J(nativeKey)(JNIEnv *env, jclass c, jint key, jint action) { (void) env; (void) c; azux_in_key(key, action); }
JNIEXPORT void JNICALL J(nativeChar)(JNIEnv *env, jclass c, jint cp) { (void) env; (void) c; azux_in_char((unsigned int) cp); }
JNIEXPORT void JNICALL J(nativeMouseButton)(JNIEnv *env, jclass c, jint b, jint a) { (void) env; (void) c; azux_in_mouse_button(b, a); }
JNIEXPORT void JNICALL J(nativeMouseDelta)(JNIEnv *env, jclass c, jfloat dx, jfloat dy) { (void) env; (void) c; azux_in_mouse_delta(dx, dy); }
JNIEXPORT void JNICALL J(nativeCursorAbs)(JNIEnv *env, jclass c, jfloat x, jfloat y) { (void) env; (void) c; azux_in_cursor_abs(x, y); }
JNIEXPORT void JNICALL J(nativeScroll)(JNIEnv *env, jclass c, jfloat dx, jfloat dy) { (void) env; (void) c; azux_in_scroll(dx, dy); }
JNIEXPORT void JNICALL J(nativeFocus)(JNIEnv *env, jclass c, jint f) { (void) env; (void) c; azux_in_focus(f); }
JNIEXPORT void JNICALL J(nativeRequestClose)(JNIEnv *env, jclass c) { (void) env; (void) c; azux_in_request_close(); }

/* out = {x, y, mode}  (mode 0x34001 normal, 0x34003 grabbed) */
JNIEXPORT void JNICALL J(nativeCursorState)(JNIEnv *env, jclass c, jdoubleArray out) {
    (void) c;
    double x, y;
    int m;
    azux_in_get_cursor(&x, &y, &m);
    jdouble v[3] = {x, y, (jdouble) m};
    (*env)->SetDoubleArrayRegion(env, out, 0, 3, v);
}
