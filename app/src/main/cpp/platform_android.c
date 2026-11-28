/* Azux platform layer for Android: EGL + the app's Surface (ANativeWindow). */
#include <EGL/egl.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <pthread.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "azux_internal.h"


extern const char *azux_gl_path;   /* defined in glfw_shim.c */

/* EGL entry points. Default: the system libEGL. MobileGlues exports its own egl* wrappers and must create the
 * context itself, so when it is the renderer the table is filled from it instead (same as Pojav/Zalith do). */
#define EGLFN(n) __typeof__(&egl##n) n
static struct {
    EGLFN(GetDisplay); EGLFN(Initialize); EGLFN(BindAPI); EGLFN(ChooseConfig); EGLFN(GetConfigAttrib);
    EGLFN(CreateContext); EGLFN(CreateWindowSurface); EGLFN(DestroyContext); EGLFN(DestroySurface);
    EGLFN(MakeCurrent); EGLFN(SwapBuffers); EGLFN(SwapInterval); EGLFN(GetError); EGLFN(GetProcAddress);
} E = {
    eglGetDisplay, eglInitialize, eglBindAPI, eglChooseConfig, eglGetConfigAttrib,
    eglCreateContext, eglCreateWindowSurface, eglDestroyContext, eglDestroySurface,
    eglMakeCurrent, eglSwapBuffers, eglSwapInterval, eglGetError, eglGetProcAddress
};
#define eglGetDisplay E.GetDisplay
#define eglInitialize E.Initialize
#define eglBindAPI E.BindAPI
#define eglChooseConfig E.ChooseConfig
#define eglGetConfigAttrib E.GetConfigAttrib
#define eglCreateContext E.CreateContext
#define eglCreateWindowSurface E.CreateWindowSurface
#define eglDestroyContext E.DestroyContext
#define eglDestroySurface E.DestroySurface
#define eglMakeCurrent E.MakeCurrent
#define eglSwapBuffers E.SwapBuffers
#define eglSwapInterval E.SwapInterval
#define eglGetError E.GetError
#define eglGetProcAddress E.GetProcAddress

static void use_renderer_egl(void) {
    if (!azux_gl_path || !strstr(azux_gl_path, "mobileglues")) return;
    void *h = dlopen(azux_gl_path, RTLD_NOW | RTLD_GLOBAL);
    if (!h) { azux_log("MobileGlues: dlopen failed: %s", dlerror()); return; }
    __typeof__(E) t = E;
#define PICK(n) do { void *s_ = dlsym(h, "egl" #n); if (!s_) { azux_log("MobileGlues has no egl" #n " - using system EGL"); return; } *(void **) &t.n = s_; } while (0)
    PICK(GetDisplay); PICK(Initialize); PICK(BindAPI); PICK(ChooseConfig); PICK(GetConfigAttrib);
    PICK(CreateContext); PICK(CreateWindowSurface); PICK(DestroyContext); PICK(DestroySurface);
    PICK(MakeCurrent); PICK(SwapBuffers); PICK(SwapInterval); PICK(GetError); PICK(GetProcAddress);
#undef PICK
    E = t;
    azux_log("EGL provided by MobileGlues");
}

struct azux_plat_ctx {
    EGLContext ctx;
    EGLSurface surf;
    int gen;
};

static struct {
    int inited;
    EGLDisplay dpy;
    EGLConfig cfg;
    EGLint fmt;
    ANativeWindow *win;
    int w, h, gen;
    pthread_mutex_t m;
    pthread_cond_t cv;
} P = { .m = PTHREAD_MUTEX_INITIALIZER, .cv = PTHREAD_COND_INITIALIZER, .gen = 1 };

int azux_plat_init(void) {
    if (P.inited) return 1;
    use_renderer_egl();
    P.dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (P.dpy == EGL_NO_DISPLAY) { azux_log("eglGetDisplay failed"); return 0; }
    if (!eglInitialize(P.dpy, NULL, NULL)) { azux_log("eglInitialize failed: 0x%x", eglGetError()); return 0; }
    eglBindAPI(EGL_OPENGL_ES_API);
    P.inited = 1;
    return 1;
}

/* Called from the UI thread with the SurfaceView's Surface (NULL when it is destroyed). */
void azux_plat_set_surface(JNIEnv *env, jobject surface, int w, int h) {
    pthread_mutex_lock(&P.m);
    ANativeWindow *nw = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    int changed_size = (w != P.w || h != P.h);
    if (nw && nw == P.win) {
        ANativeWindow_release(nw);          /* same window, drop the extra reference */
    } else {
        if (P.win) ANativeWindow_release(P.win);
        P.win = nw;
        P.gen++;
    }
    P.w = w;
    P.h = h;
    pthread_cond_broadcast(&P.cv);
    pthread_mutex_unlock(&P.m);
    if (nw && changed_size && w > 0 && h > 0) azux_in_surface_size(w, h);
}

int azux_plat_wait_surface(int timeout_ms, int *w, int *h) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += timeout_ms / 1000;
    pthread_mutex_lock(&P.m);
    while (!P.win || P.w <= 0) {
        if (pthread_cond_timedwait(&P.cv, &P.m, &ts) != 0) break;
    }
    int ok = P.win != NULL && P.w > 0;
    if (ok) { *w = P.w; *h = P.h; }
    pthread_mutex_unlock(&P.m);
    return ok;
}

void azux_plat_surface_size(int *w, int *h) {
    pthread_mutex_lock(&P.m);
    *w = P.w;
    *h = P.h;
    pthread_mutex_unlock(&P.m);
}

static int choose_config(void) {
    static const EGLint tries[][4] = { {8, 8, 24, 8}, {8, 0, 24, 8}, {8, 0, 16, 0}, {0, 0, 16, 0} };
    for (int i = 0; i < 4; i++) {
        const EGLint attrs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, tries[i][0],
            EGL_DEPTH_SIZE, tries[i][2],
            EGL_STENCIL_SIZE, tries[i][3],
            EGL_NONE };
        EGLint n = 0;
        if (eglChooseConfig(P.dpy, attrs, &P.cfg, 1, &n) && n > 0) {
            eglGetConfigAttrib(P.dpy, P.cfg, EGL_NATIVE_VISUAL_ID, &P.fmt);
            azux_log("EGL config chosen (alpha %d depth %d stencil %d)", tries[i][0], tries[i][2], tries[i][3]);
            return 1;
        }
    }
    return 0;
}

static EGLSurface make_surface(void) {
    pthread_mutex_lock(&P.m);
    ANativeWindow *w = P.win;
    pthread_mutex_unlock(&P.m);
    if (!w) return EGL_NO_SURFACE;
    ANativeWindow_setBuffersGeometry(w, 0, 0, P.fmt);
    return eglCreateWindowSurface(P.dpy, P.cfg, (EGLNativeWindowType) w, NULL);
}

azux_plat_ctx *azux_plat_create_context(int major, int minor) {
    (void) major; (void) minor;
    if (!choose_config()) { azux_log("no usable EGL config"); return NULL; }
    azux_plat_ctx *c = (azux_plat_ctx *) calloc(1, sizeof(*c));
    int es = 3;
    const EGLint c3[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    const EGLint c2[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    c->ctx = eglCreateContext(P.dpy, P.cfg, EGL_NO_CONTEXT, c3);
    if (c->ctx == EGL_NO_CONTEXT) { es = 2; c->ctx = eglCreateContext(P.dpy, P.cfg, EGL_NO_CONTEXT, c2); }
    if (c->ctx == EGL_NO_CONTEXT) { azux_log("eglCreateContext failed: 0x%x", eglGetError()); free(c); return NULL; }
    azux_log("OpenGL ES %d context created", es);
    setenv("LIBGL_ES", es == 3 ? "3" : "2", 1);   /* read by gl4es when it is loaded, right after this */
    c->surf = make_surface();
    if (c->surf == EGL_NO_SURFACE) { azux_log("eglCreateWindowSurface failed: 0x%x", eglGetError()); eglDestroyContext(P.dpy, c->ctx); free(c); return NULL; }
    c->gen = P.gen;
    return c;
}

int azux_plat_make_current(azux_plat_ctx *c) {
    if (!c) return eglMakeCurrent(P.dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    return eglMakeCurrent(P.dpy, c->surf, c->surf, c->ctx);
}

int azux_plat_swap(azux_plat_ctx *c) {
    if (c->gen != P.gen) {                   /* the Surface was replaced or destroyed */
        eglMakeCurrent(P.dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (c->surf != EGL_NO_SURFACE) eglDestroySurface(P.dpy, c->surf);
        c->surf = make_surface();
        c->gen = P.gen;
        if (c->surf == EGL_NO_SURFACE) { usleep(30000); return 0; }
        eglMakeCurrent(P.dpy, c->surf, c->surf, c->ctx);
    }
    if (c->surf == EGL_NO_SURFACE) { usleep(30000); return 0; }
    if (!eglSwapBuffers(P.dpy, c->surf)) {
        if (eglGetError() == EGL_BAD_SURFACE) c->gen = -1;   /* rebuild on the next frame */
        return 0;
    }
    return 1;
}

void azux_plat_swap_interval(int i) { if (P.inited) eglSwapInterval(P.dpy, i); }

void azux_plat_destroy_context(azux_plat_ctx *c) {
    if (!c) return;
    eglMakeCurrent(P.dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (c->surf != EGL_NO_SURFACE) eglDestroySurface(P.dpy, c->surf);
    eglDestroyContext(P.dpy, c->ctx);
    free(c);
}

void *azux_plat_proc(const char *name) { return (void *) eglGetProcAddress(name); }
