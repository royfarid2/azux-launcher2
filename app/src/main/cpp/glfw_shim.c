/*
 * Azux GLFW shim.
 *
 * LWJGL 3 calls GLFW through plain function pointers it looks up in a library by name
 * (-Dorg.lwjgl.glfw.libname=<this library>). This file exports those functions and implements
 * them for Android: one window = the app's Surface, an EGL context made by the platform layer,
 * and an input queue fed by the launcher UI (touch controls, keyboard).
 * Written from scratch for Azux.
 */
#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "azux_internal.h"

#define GLFW_PRESS 1
#define GLFW_RELEASE 0
#define GLFW_CURSOR 0x00033001
#define GLFW_STICKY_KEYS 0x00033002
#define GLFW_STICKY_MOUSE_BUTTONS 0x00033003
#define GLFW_CURSOR_NORMAL 0x00034001
#define GLFW_CURSOR_DISABLED 0x00034003
#define GLFW_FOCUSED 0x00020001
#define GLFW_ICONIFIED 0x00020002
#define GLFW_RESIZABLE 0x00020003
#define GLFW_VISIBLE 0x00020004
#define GLFW_DECORATED 0x00020005
#define GLFW_CLIENT_API 0x00022001
#define GLFW_CONTEXT_VERSION_MAJOR 0x00022002
#define GLFW_CONTEXT_VERSION_MINOR 0x00022003
#define GLFW_NO_API 0
#define GLFW_OPENGL_API 0x00030001
#define GLFW_KEY_LAST 348
#define GLFW_MOUSE_BUTTON_LAST 7
#define GLFW_NOT_INITIALIZED 0x00010001
#define GLFW_API_UNAVAILABLE 0x00010006
#define GLFW_PLATFORM_ERROR 0x00010008
#define GLFW_PLATFORM_X11 0x00060004

typedef struct GLFWwindow GLFWwindow;
typedef struct GLFWmonitor GLFWmonitor;
typedef struct GLFWcursor GLFWcursor;
typedef struct { int width, height, redBits, greenBits, blueBits, refreshRate; } GLFWvidmode;
typedef struct { int width, height; unsigned char *pixels; } GLFWimage;
typedef struct { unsigned short *red, *green, *blue; unsigned int size; } GLFWgammaramp;
typedef struct { unsigned char buttons[15]; float axes[6]; } GLFWgamepadstate;

typedef void (*errfun)(int, const char *);
typedef void (*posfun)(GLFWwindow *, int, int);
typedef void (*sizefun)(GLFWwindow *, int, int);
typedef void (*closefun)(GLFWwindow *);
typedef void (*reffun)(GLFWwindow *);
typedef void (*focusfun)(GLFWwindow *, int);
typedef void (*iconfun)(GLFWwindow *, int);
typedef void (*maxfun)(GLFWwindow *, int);
typedef void (*fbfun)(GLFWwindow *, int, int);
typedef void (*scalefun)(GLFWwindow *, float, float);
typedef void (*mbfun)(GLFWwindow *, int, int, int);
typedef void (*curposfun)(GLFWwindow *, double, double);
typedef void (*curenterfun)(GLFWwindow *, int);
typedef void (*scrollfun)(GLFWwindow *, double, double);
typedef void (*keyfun)(GLFWwindow *, int, int, int, int);
typedef void (*charfun)(GLFWwindow *, unsigned int);
typedef void (*charmodsfun)(GLFWwindow *, unsigned int, int);
typedef void (*dropfun)(GLFWwindow *, int, const char **);
typedef void (*monfun)(GLFWmonitor *, int);
typedef void (*joyfun)(int, int);

enum { EV_KEY, EV_CHAR, EV_MBTN, EV_CURSOR, EV_SCROLL, EV_SIZE, EV_FOCUS };
typedef struct { int type, a, b, c; double x, y; } Event;
#define QCAP 4096

struct GLFWwindow {
    azux_plat_ctx *ctx;
    int w, h, should_close, focused;
    void *user;
    posfun pos; sizefun size; closefun close; reffun refresh; focusfun focus; iconfun icon; maxfun max;
    fbfun fb; scalefun scale; mbfun mb; curposfun curpos; curenterfun enter; scrollfun scroll;
    keyfun key; charfun chr; charmodsfun charmods; dropfun drop;
};

static struct {
    int inited, created, client_api, ver_major, ver_minor;
    pthread_mutex_t lock;
    pthread_cond_t cv;
    Event q[QCAP];
    int qh, qt;
    unsigned char keys[GLFW_KEY_LAST + 1];
    unsigned char pkeys[GLFW_KEY_LAST + 1];   /* producer-side (de-dupes presses) */
    unsigned char btns[GLFW_MOUSE_BUTTON_LAST + 1];
    int mods;
    double cx, cy;      /* cursor as the application saw it (consumer side) */
    double vx, vy;      /* virtual cursor (producer side) */
    int cursor_mode, sticky_keys, sticky_btns;
    struct timespec t0;
    errfun err;
    char *clip;
    GLFWwindow win;
    GLFWvidmode mode;
    void *gl_handle;
} G = { .lock = PTHREAD_MUTEX_INITIALIZER, .cv = PTHREAD_COND_INITIALIZER,
        .client_api = GLFW_OPENGL_API, .cursor_mode = GLFW_CURSOR_NORMAL };

static __thread GLFWwindow *tls_current;
void *azux_state_id(void) { return (void *) &G; }
const char *azux_gl_path = NULL;   /* set by the launcher (-Dazux.gl4es=...) */

static void gerr(int code, const char *msg) {
    azux_log("GLFW error 0x%x: %s", code, msg);
    if (G.err) G.err(code, msg);
}

/* ------------------------------------------------------------------ event queue */
static void push_locked(Event e) {
    int n = (G.qt + 1) % QCAP;
    if (n == G.qh) {                       /* full: drop the oldest event, never block the UI */
        G.qh = (G.qh + 1) % QCAP;
    }
    G.q[G.qt] = e;
    G.qt = n;
    pthread_cond_broadcast(&G.cv);
}

static void push(Event e) {
    pthread_mutex_lock(&G.lock);
    push_locked(e);
    pthread_mutex_unlock(&G.lock);
}

static int mod_bit(int key) {
    switch (key) {
        case 340: case 344: return 1;
        case 341: case 345: return 2;
        case 342: case 346: return 4;
        case 343: case 347: return 8;
        default: return 0;
    }
}

void azux_in_key(int key, int action) {
    if (key < 0 || key > GLFW_KEY_LAST) return;
    pthread_mutex_lock(&G.lock);
    if (action == GLFW_PRESS && G.pkeys[key]) { pthread_mutex_unlock(&G.lock); return; }
    if (action == GLFW_RELEASE && !G.pkeys[key]) { pthread_mutex_unlock(&G.lock); return; }
    G.pkeys[key] = action == GLFW_PRESS;
    int b = mod_bit(key);
    if (b) { if (action == GLFW_PRESS) G.mods |= b; else G.mods &= ~b; }
    Event e = {EV_KEY, key, action, G.mods, 0, 0};
    push_locked(e);
    pthread_mutex_unlock(&G.lock);
}

void azux_in_char(unsigned int cp) { Event e = {EV_CHAR, (int) cp, 0, 0, 0, 0}; push(e); }

void azux_in_mouse_button(int btn, int action) {
    if (btn < 0 || btn > GLFW_MOUSE_BUTTON_LAST) return;
    pthread_mutex_lock(&G.lock);
    Event e = {EV_MBTN, btn, action, G.mods, 0, 0};
    push_locked(e);
    pthread_mutex_unlock(&G.lock);
}

static void clamp_virtual(void) {
    if (G.cursor_mode == GLFW_CURSOR_DISABLED) return;   /* grabbed: unbounded, the game uses deltas */
    if (G.vx < 0) G.vx = 0;
    if (G.vy < 0) G.vy = 0;
    if (G.win.w > 0 && G.vx > G.win.w) G.vx = G.win.w;
    if (G.win.h > 0 && G.vy > G.win.h) G.vy = G.win.h;
}

void azux_in_mouse_delta(double dx, double dy) {
    pthread_mutex_lock(&G.lock);
    G.vx += dx;
    G.vy += dy;
    clamp_virtual();
    Event e = {EV_CURSOR, 0, 0, 0, G.vx, G.vy};
    push_locked(e);
    pthread_mutex_unlock(&G.lock);
}

void azux_in_cursor_abs(double x, double y) {
    pthread_mutex_lock(&G.lock);
    G.vx = x;
    G.vy = y;
    clamp_virtual();
    Event e = {EV_CURSOR, 0, 0, 0, G.vx, G.vy};
    push_locked(e);
    pthread_mutex_unlock(&G.lock);
}

void azux_in_scroll(double dx, double dy) { Event e = {EV_SCROLL, 0, 0, 0, dx, dy}; push(e); }
void azux_in_surface_size(int w, int h) { Event e = {EV_SIZE, w, h, 0, 0, 0}; push(e); }
void azux_in_focus(int f) { Event e = {EV_FOCUS, f, 0, 0, 0, 0}; push(e); }

void azux_in_request_close(void) {
    pthread_mutex_lock(&G.lock);
    G.win.should_close = 1;
    pthread_cond_broadcast(&G.cv);
    pthread_mutex_unlock(&G.lock);
}

void azux_in_get_cursor(double *x, double *y, int *mode) {
    pthread_mutex_lock(&G.lock);
    *x = G.vx;
    *y = G.vy;
    *mode = G.cursor_mode;
    pthread_mutex_unlock(&G.lock);
}

static void dispatch(const Event *e) {
    GLFWwindow *w = &G.win;
    switch (e->type) {
        case EV_KEY:
            G.keys[e->a] = e->b != GLFW_RELEASE;
            if (w->key) w->key(w, e->a, e->a, e->b, e->c);
            break;
        case EV_CHAR:
            if (w->chr) w->chr(w, (unsigned int) e->a);
            if (w->charmods) w->charmods(w, (unsigned int) e->a, G.mods);
            break;
        case EV_MBTN:
            G.btns[e->a] = e->b != GLFW_RELEASE;
            if (w->mb) w->mb(w, e->a, e->b, e->c);
            break;
        case EV_CURSOR:
            G.cx = e->x;
            G.cy = e->y;
            if (w->curpos) w->curpos(w, e->x, e->y);
            break;
        case EV_SCROLL:
            if (w->scroll) w->scroll(w, e->x, e->y);
            break;
        case EV_SIZE:
            w->w = e->a;
            w->h = e->b;
            G.mode.width = e->a;
            G.mode.height = e->b;
            if (w->size) w->size(w, e->a, e->b);
            if (w->fb) w->fb(w, e->a, e->b);
            break;
        case EV_FOCUS:
            w->focused = e->a;
            if (w->focus) w->focus(w, e->a);
            break;
        default:
            break;
    }
}

/* ------------------------------------------------------------------ init / terminate */
AZUX_EXPORT int glfwInit(void) {
    if (G.inited) return 1;
    if (!azux_plat_init()) { gerr(GLFW_PLATFORM_ERROR, "platform init failed"); return 0; }
    clock_gettime(CLOCK_MONOTONIC, &G.t0);
    G.mode = (GLFWvidmode){1920, 1080, 8, 8, 8, 60};
    G.inited = 1;
    azux_log("glfwInit ok (Azux shim, state %p)", azux_state_id());
    return 1;
}

AZUX_EXPORT void glfwTerminate(void) {
    if (G.created && G.win.ctx) {
        azux_plat_make_current(NULL);
        azux_plat_destroy_context(G.win.ctx);
        G.win.ctx = NULL;
    }
    G.created = 0;
    G.inited = 0;
}

AZUX_EXPORT void glfwInitHint(int hint, int value) { (void) hint; (void) value; }
AZUX_EXPORT void glfwGetVersion(int *major, int *minor, int *rev) {
    if (major) *major = 3;
    if (minor) *minor = 4;
    if (rev) *rev = 0;
}
AZUX_EXPORT const char *glfwGetVersionString(void) { return "3.4.0 Azux Android"; }
/* GLFW 3.4 API that LWJGL 3.3.x looks up at startup: a missing symbol is fatal ("A required function is missing") */
AZUX_EXPORT int glfwGetPlatform(void) { return GLFW_PLATFORM_X11; }
AZUX_EXPORT int glfwPlatformSupported(int platform) { return platform == GLFW_PLATFORM_X11; }
AZUX_EXPORT void glfwInitAllocator(const void *allocator) { (void) allocator; }
AZUX_EXPORT const char *glfwGetWindowTitle(void *window) { (void) window; return "Minecraft"; }
/* IME / preedit API (newest GLFW): the Android keyboard is handled by the launcher, so these are inert */
AZUX_EXPORT void *glfwSetPreeditCallback(void *w, void *f) { (void) w; (void) f; return NULL; }
AZUX_EXPORT void *glfwSetPreeditCandidateCallback(void *w, void *f) { (void) w; (void) f; return NULL; }
AZUX_EXPORT void *glfwSetIMEStatusCallback(void *w, void *f) { (void) w; (void) f; return NULL; }
AZUX_EXPORT void glfwResetPreeditText(void *w) { (void) w; }
AZUX_EXPORT void glfwSetPreeditCursorRectangle(void *w, int x, int y, int cw, int ch) { (void) w; (void) x; (void) y; (void) cw; (void) ch; }
AZUX_EXPORT void glfwGetPreeditCursorRectangle(void *w, int *x, int *y, int *cw, int *ch) {
    (void) w;
    if (x) *x = 0;
    if (y) *y = 0;
    if (cw) *cw = 0;
    if (ch) *ch = 0;
}
AZUX_EXPORT const unsigned int *glfwGetPreeditCandidate(void *w, int index, int *textCount) {
    (void) w; (void) index;
    if (textCount) *textCount = 0;
    return NULL;
}
AZUX_EXPORT int glfwGetError(const char **desc) { if (desc) *desc = NULL; return 0; }
AZUX_EXPORT errfun glfwSetErrorCallback(errfun f) { errfun o = G.err; G.err = f; return o; }

/* ------------------------------------------------------------------ monitor (one fixed monitor) */
static int g_mon_dummy;
AZUX_EXPORT GLFWmonitor **glfwGetMonitors(int *count) {
    static GLFWmonitor *list[1];
    list[0] = (GLFWmonitor *) &g_mon_dummy;
    if (count) *count = 1;
    return list;
}
AZUX_EXPORT GLFWmonitor *glfwGetPrimaryMonitor(void) { return (GLFWmonitor *) &g_mon_dummy; }
AZUX_EXPORT void glfwGetMonitorPos(GLFWmonitor *m, int *x, int *y) { (void) m; if (x) *x = 0; if (y) *y = 0; }
AZUX_EXPORT void glfwGetMonitorWorkarea(GLFWmonitor *m, int *x, int *y, int *w, int *h) {
    (void) m;
    int sw = G.win.w ? G.win.w : G.mode.width, sh = G.win.h ? G.win.h : G.mode.height;
    if (x) *x = 0;
    if (y) *y = 0;
    if (w) *w = sw;
    if (h) *h = sh;
}
AZUX_EXPORT void glfwGetMonitorPhysicalSize(GLFWmonitor *m, int *w, int *h) { (void) m; if (w) *w = 150; if (h) *h = 70; }
AZUX_EXPORT void glfwGetMonitorContentScale(GLFWmonitor *m, float *x, float *y) { (void) m; if (x) *x = 1; if (y) *y = 1; }
AZUX_EXPORT const char *glfwGetMonitorName(GLFWmonitor *m) { (void) m; return "Android display"; }
AZUX_EXPORT monfun glfwSetMonitorCallback(monfun f) { (void) f; return NULL; }
static void *g_mon_user;
AZUX_EXPORT void glfwSetMonitorUserPointer(GLFWmonitor *m, void *p) { (void) m; g_mon_user = p; }
AZUX_EXPORT void *glfwGetMonitorUserPointer(GLFWmonitor *m) { (void) m; return g_mon_user; }
AZUX_EXPORT const GLFWvidmode *glfwGetVideoModes(GLFWmonitor *m, int *count) {
    (void) m;
    if (count) *count = 1;
    return &G.mode;
}
AZUX_EXPORT const GLFWvidmode *glfwGetVideoMode(GLFWmonitor *m) { (void) m; return &G.mode; }
AZUX_EXPORT void glfwSetGamma(GLFWmonitor *m, float g) { (void) m; (void) g; }
AZUX_EXPORT const GLFWgammaramp *glfwGetGammaRamp(GLFWmonitor *m) { (void) m; return NULL; }
AZUX_EXPORT void glfwSetGammaRamp(GLFWmonitor *m, const GLFWgammaramp *r) { (void) m; (void) r; }

/* ------------------------------------------------------------------ window */
AZUX_EXPORT void glfwDefaultWindowHints(void) { G.client_api = GLFW_OPENGL_API; G.ver_major = 1; G.ver_minor = 0; }
AZUX_EXPORT void glfwWindowHint(int hint, int value) {
    if (hint == GLFW_CLIENT_API) G.client_api = value;
    else if (hint == GLFW_CONTEXT_VERSION_MAJOR) G.ver_major = value;
    else if (hint == GLFW_CONTEXT_VERSION_MINOR) G.ver_minor = value;
}
AZUX_EXPORT void glfwWindowHintString(int hint, const char *v) { (void) hint; (void) v; }

AZUX_EXPORT GLFWwindow *glfwCreateWindow(int w, int h, const char *title, GLFWmonitor *mon, GLFWwindow *share) {
    (void) title; (void) mon; (void) share;
    if (!G.inited) { gerr(GLFW_NOT_INITIALIZED, "glfwInit was not called"); return NULL; }
    if (G.created) { gerr(GLFW_API_UNAVAILABLE, "only one window is supported"); return NULL; }
    int sw = 0, sh = 0;
    azux_log("glfwCreateWindow requested %dx%d, GL %d.%d - waiting for the Android surface", w, h, G.ver_major, G.ver_minor);
    if (!azux_plat_wait_surface(60000, &sw, &sh)) { gerr(GLFW_PLATFORM_ERROR, "no Android surface available"); return NULL; }
    memset(&G.win, 0, sizeof(G.win));
    G.win.w = sw;
    G.win.h = sh;
    G.win.focused = 1;
    G.mode.width = sw;
    G.mode.height = sh;
    if (G.client_api == GLFW_OPENGL_API) {
        G.win.ctx = azux_plat_create_context(G.ver_major, G.ver_minor);
        if (!G.win.ctx) { gerr(GLFW_PLATFORM_ERROR, "could not create an EGL context"); return NULL; }
    }
    pthread_mutex_lock(&G.lock);
    G.vx = sw / 2.0;
    G.vy = sh / 2.0;
    G.cx = G.vx;
    G.cy = G.vy;
    pthread_mutex_unlock(&G.lock);
    G.created = 1;
    azux_log("window created: %dx%d", sw, sh);
    return &G.win;
}

AZUX_EXPORT void glfwDestroyWindow(GLFWwindow *w) {
    if (!w || w != &G.win) return;
    if (tls_current == w) { azux_plat_make_current(NULL); tls_current = NULL; }
    if (w->ctx) azux_plat_destroy_context(w->ctx);
    w->ctx = NULL;
    G.created = 0;
}

AZUX_EXPORT int glfwWindowShouldClose(GLFWwindow *w) { return w ? w->should_close : 1; }
AZUX_EXPORT void glfwSetWindowShouldClose(GLFWwindow *w, int v) { if (w) w->should_close = v; }
AZUX_EXPORT void glfwSetWindowTitle(GLFWwindow *w, const char *t) { (void) w; (void) t; }
AZUX_EXPORT void glfwSetWindowIcon(GLFWwindow *w, int n, const GLFWimage *i) { (void) w; (void) n; (void) i; }
AZUX_EXPORT void glfwGetWindowPos(GLFWwindow *w, int *x, int *y) { (void) w; if (x) *x = 0; if (y) *y = 0; }
AZUX_EXPORT void glfwSetWindowPos(GLFWwindow *w, int x, int y) { (void) w; (void) x; (void) y; }
AZUX_EXPORT void glfwGetWindowSize(GLFWwindow *w, int *pw, int *ph) { if (pw) *pw = w ? w->w : 0; if (ph) *ph = w ? w->h : 0; }
AZUX_EXPORT void glfwSetWindowSizeLimits(GLFWwindow *w, int a, int b, int c, int d) { (void) w; (void) a; (void) b; (void) c; (void) d; }
AZUX_EXPORT void glfwSetWindowAspectRatio(GLFWwindow *w, int a, int b) { (void) w; (void) a; (void) b; }
AZUX_EXPORT void glfwSetWindowSize(GLFWwindow *w, int a, int b) { (void) w; (void) a; (void) b; }
AZUX_EXPORT void glfwGetFramebufferSize(GLFWwindow *w, int *pw, int *ph) { glfwGetWindowSize(w, pw, ph); }
AZUX_EXPORT void glfwGetWindowFrameSize(GLFWwindow *w, int *l, int *t, int *r, int *b) {
    (void) w;
    if (l) *l = 0;
    if (t) *t = 0;
    if (r) *r = 0;
    if (b) *b = 0;
}
AZUX_EXPORT void glfwGetWindowContentScale(GLFWwindow *w, float *x, float *y) { (void) w; if (x) *x = 1; if (y) *y = 1; }
AZUX_EXPORT float glfwGetWindowOpacity(GLFWwindow *w) { (void) w; return 1.0f; }
AZUX_EXPORT void glfwSetWindowOpacity(GLFWwindow *w, float o) { (void) w; (void) o; }
AZUX_EXPORT void glfwIconifyWindow(GLFWwindow *w) { (void) w; }
AZUX_EXPORT void glfwRestoreWindow(GLFWwindow *w) { (void) w; }
AZUX_EXPORT void glfwMaximizeWindow(GLFWwindow *w) { (void) w; }
AZUX_EXPORT void glfwShowWindow(GLFWwindow *w) { (void) w; }
AZUX_EXPORT void glfwHideWindow(GLFWwindow *w) { (void) w; }
AZUX_EXPORT void glfwFocusWindow(GLFWwindow *w) { (void) w; }
AZUX_EXPORT void glfwRequestWindowAttention(GLFWwindow *w) { (void) w; }
AZUX_EXPORT GLFWmonitor *glfwGetWindowMonitor(GLFWwindow *w) { (void) w; return (GLFWmonitor *) &g_mon_dummy; }
AZUX_EXPORT void glfwSetWindowMonitor(GLFWwindow *w, GLFWmonitor *m, int x, int y, int ww, int hh, int r) {
    (void) w; (void) m; (void) x; (void) y; (void) ww; (void) hh; (void) r;
}
AZUX_EXPORT int glfwGetWindowAttrib(GLFWwindow *w, int a) {
    switch (a) {
        case GLFW_FOCUSED: return w ? w->focused : 0;
        case GLFW_ICONIFIED: return 0;
        case GLFW_VISIBLE: return 1;
        case GLFW_RESIZABLE: return 0;
        case GLFW_DECORATED: return 0;
        case GLFW_CLIENT_API: return G.client_api;
        case GLFW_CONTEXT_VERSION_MAJOR: return G.ver_major;
        case GLFW_CONTEXT_VERSION_MINOR: return G.ver_minor;
        default: return 0;
    }
}
AZUX_EXPORT void glfwSetWindowAttrib(GLFWwindow *w, int a, int v) { (void) w; (void) a; (void) v; }
AZUX_EXPORT void glfwSetWindowUserPointer(GLFWwindow *w, void *p) { if (w) w->user = p; }
AZUX_EXPORT void *glfwGetWindowUserPointer(GLFWwindow *w) { return w ? w->user : NULL; }

#define SETTER(NAME, TYPE, FIELD) \
    AZUX_EXPORT TYPE NAME(GLFWwindow *w, TYPE f) { if (!w) return NULL; TYPE o = w->FIELD; w->FIELD = f; return o; }
SETTER(glfwSetWindowPosCallback, posfun, pos)
SETTER(glfwSetWindowSizeCallback, sizefun, size)
SETTER(glfwSetWindowCloseCallback, closefun, close)
SETTER(glfwSetWindowRefreshCallback, reffun, refresh)
SETTER(glfwSetWindowFocusCallback, focusfun, focus)
SETTER(glfwSetWindowIconifyCallback, iconfun, icon)
SETTER(glfwSetWindowMaximizeCallback, maxfun, max)
SETTER(glfwSetFramebufferSizeCallback, fbfun, fb)
SETTER(glfwSetWindowContentScaleCallback, scalefun, scale)
SETTER(glfwSetMouseButtonCallback, mbfun, mb)
SETTER(glfwSetCursorPosCallback, curposfun, curpos)
SETTER(glfwSetCursorEnterCallback, curenterfun, enter)
SETTER(glfwSetScrollCallback, scrollfun, scroll)
SETTER(glfwSetKeyCallback, keyfun, key)
SETTER(glfwSetCharCallback, charfun, chr)
SETTER(glfwSetCharModsCallback, charmodsfun, charmods)
SETTER(glfwSetDropCallback, dropfun, drop)

AZUX_EXPORT void glfwPollEvents(void) {
    Event local[256];
    for (;;) {
        int n = 0;
        pthread_mutex_lock(&G.lock);
        while (G.qh != G.qt && n < 256) {
            local[n++] = G.q[G.qh];
            G.qh = (G.qh + 1) % QCAP;
        }
        pthread_mutex_unlock(&G.lock);
        for (int i = 0; i < n; i++) dispatch(&local[i]);
        if (n < 256) break;
    }
}

AZUX_EXPORT void glfwWaitEventsTimeout(double timeout) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    long ns = (long) (timeout * 1e9);
    ts.tv_sec += ns / 1000000000L;
    ts.tv_nsec += ns % 1000000000L;
    if (ts.tv_nsec >= 1000000000L) { ts.tv_sec++; ts.tv_nsec -= 1000000000L; }
    pthread_mutex_lock(&G.lock);
    if (G.qh == G.qt) pthread_cond_timedwait(&G.cv, &G.lock, &ts);
    pthread_mutex_unlock(&G.lock);
    glfwPollEvents();
}
AZUX_EXPORT void glfwWaitEvents(void) { glfwWaitEventsTimeout(0.25); }
AZUX_EXPORT void glfwPostEmptyEvent(void) { pthread_mutex_lock(&G.lock); pthread_cond_broadcast(&G.cv); pthread_mutex_unlock(&G.lock); }

/* ------------------------------------------------------------------ input state */
AZUX_EXPORT int glfwGetInputMode(GLFWwindow *w, int mode) {
    (void) w;
    if (mode == GLFW_CURSOR) return G.cursor_mode;
    if (mode == GLFW_STICKY_KEYS) return G.sticky_keys;
    if (mode == GLFW_STICKY_MOUSE_BUTTONS) return G.sticky_btns;
    return 0;
}
AZUX_EXPORT void glfwSetInputMode(GLFWwindow *w, int mode, int value) {
    (void) w;
    pthread_mutex_lock(&G.lock);
    if (mode == GLFW_CURSOR) {
        G.cursor_mode = value;
        if (value != GLFW_CURSOR_DISABLED) clamp_virtual();
    } else if (mode == GLFW_STICKY_KEYS) G.sticky_keys = value;
    else if (mode == GLFW_STICKY_MOUSE_BUTTONS) G.sticky_btns = value;
    pthread_mutex_unlock(&G.lock);
}
AZUX_EXPORT int glfwRawMouseMotionSupported(void) { return 0; }
AZUX_EXPORT const char *glfwGetKeyName(int key, int scancode) { (void) key; (void) scancode; return NULL; }
AZUX_EXPORT int glfwGetKeyScancode(int key) { return key; }
AZUX_EXPORT int glfwGetKey(GLFWwindow *w, int key) { (void) w; return (key >= 0 && key <= GLFW_KEY_LAST) ? G.keys[key] : 0; }
AZUX_EXPORT int glfwGetMouseButton(GLFWwindow *w, int b) { (void) w; return (b >= 0 && b <= GLFW_MOUSE_BUTTON_LAST) ? G.btns[b] : 0; }
AZUX_EXPORT void glfwGetCursorPos(GLFWwindow *w, double *x, double *y) { (void) w; if (x) *x = G.cx; if (y) *y = G.cy; }
AZUX_EXPORT void glfwSetCursorPos(GLFWwindow *w, double x, double y) {
    (void) w;
    pthread_mutex_lock(&G.lock);
    G.vx = G.cx = x;
    G.vy = G.cy = y;
    pthread_mutex_unlock(&G.lock);
}
static int g_cursor_dummy;
AZUX_EXPORT GLFWcursor *glfwCreateCursor(const GLFWimage *i, int x, int y) { (void) i; (void) x; (void) y; return (GLFWcursor *) &g_cursor_dummy; }
AZUX_EXPORT GLFWcursor *glfwCreateStandardCursor(int shape) { (void) shape; return (GLFWcursor *) &g_cursor_dummy; }
AZUX_EXPORT void glfwDestroyCursor(GLFWcursor *c) { (void) c; }
AZUX_EXPORT void glfwSetCursor(GLFWwindow *w, GLFWcursor *c) { (void) w; (void) c; }

AZUX_EXPORT const char *glfwGetClipboardString(GLFWwindow *w) { (void) w; return G.clip; }
AZUX_EXPORT void glfwSetClipboardString(GLFWwindow *w, const char *s) {
    (void) w;
    free(G.clip);
    G.clip = s ? strdup(s) : NULL;
}

/* joysticks: none */
AZUX_EXPORT int glfwJoystickPresent(int j) { (void) j; return 0; }
AZUX_EXPORT const float *glfwGetJoystickAxes(int j, int *n) { (void) j; if (n) *n = 0; return NULL; }
AZUX_EXPORT const unsigned char *glfwGetJoystickButtons(int j, int *n) { (void) j; if (n) *n = 0; return NULL; }
AZUX_EXPORT const unsigned char *glfwGetJoystickHats(int j, int *n) { (void) j; if (n) *n = 0; return NULL; }
AZUX_EXPORT const char *glfwGetJoystickName(int j) { (void) j; return NULL; }
AZUX_EXPORT const char *glfwGetJoystickGUID(int j) { (void) j; return NULL; }
AZUX_EXPORT void glfwSetJoystickUserPointer(int j, void *p) { (void) j; (void) p; }
AZUX_EXPORT void *glfwGetJoystickUserPointer(int j) { (void) j; return NULL; }
AZUX_EXPORT int glfwJoystickIsGamepad(int j) { (void) j; return 0; }
AZUX_EXPORT joyfun glfwSetJoystickCallback(joyfun f) { (void) f; return NULL; }
AZUX_EXPORT int glfwUpdateGamepadMappings(const char *s) { (void) s; return 1; }
AZUX_EXPORT const char *glfwGetGamepadName(int j) { (void) j; return NULL; }
AZUX_EXPORT int glfwGetGamepadState(int j, GLFWgamepadstate *s) { (void) j; (void) s; return 0; }

/* ------------------------------------------------------------------ time */
static double now_s(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (double) (t.tv_sec - G.t0.tv_sec) + (double) (t.tv_nsec - G.t0.tv_nsec) / 1e9;
}
static double g_time_offset;
AZUX_EXPORT double glfwGetTime(void) { return now_s() - g_time_offset; }
AZUX_EXPORT void glfwSetTime(double t) { g_time_offset = now_s() - t; }
AZUX_EXPORT uint64_t glfwGetTimerValue(void) { return (uint64_t) (now_s() * 1e9); }
AZUX_EXPORT uint64_t glfwGetTimerFrequency(void) { return 1000000000ULL; }

/* ------------------------------------------------------------------ OpenGL context */
/* gl4es initialises itself the first time it is loaded; its probe releases the current context,
 * so load it here once and make our context current again. */
static void load_gl_library(GLFWwindow *w) {
    if (G.gl_handle || !azux_gl_path) return;
    G.gl_handle = dlopen(azux_gl_path, RTLD_NOW | RTLD_GLOBAL);
    if (!G.gl_handle) azux_log("could not load GL library %s: %s", azux_gl_path, dlerror());
    else azux_log("GL library loaded: %s", azux_gl_path);
    azux_plat_make_current(w->ctx);
}

AZUX_EXPORT void glfwMakeContextCurrent(GLFWwindow *w) {
    if (!w) { azux_plat_make_current(NULL); tls_current = NULL; return; }
    if (!azux_plat_make_current(w->ctx)) { gerr(GLFW_PLATFORM_ERROR, "eglMakeCurrent failed"); return; }
    tls_current = w;
    load_gl_library(w);
}
AZUX_EXPORT GLFWwindow *glfwGetCurrentContext(void) { return tls_current; }
AZUX_EXPORT void glfwSwapBuffers(GLFWwindow *w) { if (w && w->ctx) azux_plat_swap(w->ctx); }
AZUX_EXPORT void glfwSwapInterval(int i) { azux_plat_swap_interval(i); }
AZUX_EXPORT int glfwExtensionSupported(const char *e) { (void) e; return 0; }

AZUX_EXPORT void *glfwGetProcAddress(const char *name) {
    void *p = NULL;
    if (G.gl_handle) {
        void *(*gp)(const char *) = (void *(*)(const char *)) dlsym(G.gl_handle, "glXGetProcAddress");
        if (gp) p = gp(name);
    }
    return p ? p : azux_plat_proc(name);
}

/* Vulkan: not supported */
AZUX_EXPORT int glfwVulkanSupported(void) { return 0; }
AZUX_EXPORT const char **glfwGetRequiredInstanceExtensions(uint32_t *count) { if (count) *count = 0; return NULL; }
AZUX_EXPORT void *glfwGetInstanceProcAddress(void *inst, const char *name) { (void) inst; (void) name; return NULL; }
AZUX_EXPORT int glfwGetPhysicalDevicePresentationSupport(void *i, void *d, uint32_t q) { (void) i; (void) d; (void) q; return 0; }
AZUX_EXPORT int glfwCreateWindowSurface(void *i, GLFWwindow *w, const void *a, void *s) { (void) i; (void) w; (void) a; (void) s; return -1; }
