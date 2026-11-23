/* Host unit test for the Azux GLFW shim (uses the fake platform). Build: see tools/tests/run.sh */
#include <assert.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include "../../app/src/main/cpp/azux_internal.h"

typedef struct GLFWwindow GLFWwindow;
int glfwInit(void);
GLFWwindow *glfwCreateWindow(int, int, const char *, void *, void *);
void glfwPollEvents(void);
void glfwWaitEventsTimeout(double);
void glfwGetFramebufferSize(GLFWwindow *, int *, int *);
void glfwGetWindowSize(GLFWwindow *, int *, int *);
void glfwMakeContextCurrent(GLFWwindow *);
GLFWwindow *glfwGetCurrentContext(void);
void glfwSwapBuffers(GLFWwindow *);
void glfwSetInputMode(GLFWwindow *, int, int);
void glfwSetCursorPos(GLFWwindow *, double, double);
void glfwGetCursorPos(GLFWwindow *, double *, double *);
int glfwGetKey(GLFWwindow *, int);
int glfwGetMouseButton(GLFWwindow *, int);
int glfwWindowShouldClose(GLFWwindow *);
double glfwGetTime(void);
void glfwWindowHint(int, int);
void *glfwSetErrorCallback(void *);
void *glfwSetKeyCallback(GLFWwindow *, void *);
void *glfwSetCharCallback(GLFWwindow *, void *);
void *glfwSetMouseButtonCallback(GLFWwindow *, void *);
void *glfwSetCursorPosCallback(GLFWwindow *, void *);
void *glfwSetScrollCallback(GLFWwindow *, void *);
void *glfwSetWindowSizeCallback(GLFWwindow *, void *);
void *glfwSetFramebufferSizeCallback(GLFWwindow *, void *);
extern int fake_swaps;

void azux_log(const char *fmt, ...) { (void) fmt; }

static char trace[4096];
static void T(const char *fmt, ...) {
    va_list ap; va_start(ap, fmt);
    size_t n = strlen(trace);
    vsnprintf(trace + n, sizeof(trace) - n, fmt, ap);
    va_end(ap);
}
static void on_key(GLFWwindow *w, int k, int sc, int a, int m) { (void) w; (void) sc; T("K%d/%d/%d;", k, a, m); }
static void on_char(GLFWwindow *w, unsigned c) { (void) w; T("C%u;", c); }
static void on_mb(GLFWwindow *w, int b, int a, int m) { (void) w; T("M%d/%d/%d;", b, a, m); }
static void on_pos(GLFWwindow *w, double x, double y) { (void) w; T("P%.0f,%.0f;", x, y); }
static void on_scroll(GLFWwindow *w, double x, double y) { (void) w; T("S%.0f,%.0f;", x, y); }
static void on_size(GLFWwindow *w, int x, int y) { (void) w; T("W%d,%d;", x, y); }
static void on_fb(GLFWwindow *w, int x, int y) { (void) w; T("F%d,%d;", x, y); }
static int errors;
static void on_err(int c, const char *m) { (void) c; (void) m; errors++; }

#define CHECK(cond) do { if (!(cond)) { printf("FAIL line %d: %s\n   trace=%s\n", __LINE__, #cond, trace); return 1; } } while (0)
#define EXPECT(s) do { if (strcmp(trace, s) != 0) { printf("FAIL line %d\n   want %s\n   got  %s\n", __LINE__, s, trace); return 1; } trace[0] = 0; } while (0)

static void *injector(void *arg) {
    (void) arg;
    usleep(30000);
    azux_in_key(65, 1);
    return NULL;
}

int main(void) {
    glfwSetErrorCallback(on_err);
    CHECK(glfwInit() == 1);
    glfwWindowHint(0x22002, 2);
    GLFWwindow *w = glfwCreateWindow(854, 480, "t", NULL, NULL);
    CHECK(w != NULL);
    int fw, fh;
    glfwGetFramebufferSize(w, &fw, &fh);
    CHECK(fw == 1280 && fh == 720);
    CHECK(glfwCreateWindow(1, 1, "again", NULL, NULL) == NULL && errors == 1);

    glfwSetKeyCallback(w, on_key); glfwSetCharCallback(w, on_char); glfwSetMouseButtonCallback(w, on_mb);
    glfwSetCursorPosCallback(w, on_pos); glfwSetScrollCallback(w, on_scroll);
    glfwSetWindowSizeCallback(w, on_size); glfwSetFramebufferSizeCallback(w, on_fb);

    glfwMakeContextCurrent(w);
    CHECK(glfwGetCurrentContext() == w);
    glfwSwapBuffers(w);
    CHECK(fake_swaps == 1);

    /* keys: order, modifiers, de-dupe */
    azux_in_key(340, 1);      /* left shift down */
    azux_in_key(87, 1);       /* W down (with shift) */
    azux_in_key(87, 1);       /* duplicate press ignored */
    azux_in_key(87, 0);
    azux_in_key(340, 0);
    azux_in_key(65, 0);       /* release without press ignored */
    azux_in_char(0x20AC);
    glfwPollEvents();
    EXPECT("K340/1/1;K87/1/1;K87/0/1;K340/0/0;C8364;");
    CHECK(glfwGetKey(w, 87) == 0);

    azux_in_key(68, 1);
    glfwPollEvents();
    CHECK(glfwGetKey(w, 68) == 1);
    trace[0] = 0;
    azux_in_key(68, 0);
    glfwPollEvents();
    trace[0] = 0;

    /* mouse + scroll */
    azux_in_mouse_button(0, 1);
    azux_in_mouse_button(0, 0);
    azux_in_scroll(0, -1);
    glfwPollEvents();
    EXPECT("M0/1/0;M0/0/0;S0,-1;");
    CHECK(glfwGetMouseButton(w, 0) == 0);

    /* normal cursor mode: starts centred, clamps to the window */
    azux_in_mouse_delta(10, 5);
    glfwPollEvents();
    EXPECT("P650,365;");
    azux_in_mouse_delta(5000, 5000);
    azux_in_mouse_delta(-9999, -9999);
    glfwPollEvents();
    EXPECT("P1280,720;P0,0;");

    /* grabbed (camera) mode: unbounded, deltas accumulate */
    glfwSetInputMode(w, 0x33001, 0x34003);
    glfwSetCursorPos(w, 640, 360);
    azux_in_mouse_delta(5000, -300);
    azux_in_mouse_delta(5000, -300);
    glfwPollEvents();
    EXPECT("P5640,60;P10640,-240;");
    double cx, cy, vx; int mode;
    glfwGetCursorPos(w, &cx, &cy);
    CHECK(cx == 10640 && cy == -240);
    azux_in_get_cursor(&vx, &cy, &mode);
    CHECK(mode == 0x34003);
    glfwSetInputMode(w, 0x33001, 0x34001);

    /* absolute touch position */
    azux_in_cursor_abs(100, 200);
    glfwPollEvents();
    EXPECT("P100,200;");

    /* surface resize fires both callbacks and updates the size */
    azux_in_surface_size(2400, 1080);
    glfwPollEvents();
    EXPECT("W2400,1080;F2400,1080;");
    glfwGetWindowSize(w, &fw, &fh);
    CHECK(fw == 2400 && fh == 1080);

    /* waiting: times out without events, wakes up on an event from another thread */
    struct timespec a, b;
    clock_gettime(CLOCK_MONOTONIC, &a);
    glfwWaitEventsTimeout(0.05);
    clock_gettime(CLOCK_MONOTONIC, &b);
    double dt = (b.tv_sec - a.tv_sec) + (b.tv_nsec - a.tv_nsec) / 1e9;
    CHECK(dt >= 0.04 && dt < 0.5);
    pthread_t th;
    pthread_create(&th, NULL, injector, NULL);
    clock_gettime(CLOCK_MONOTONIC, &a);
    glfwWaitEventsTimeout(2.0);
    clock_gettime(CLOCK_MONOTONIC, &b);
    pthread_join(th, NULL);
    dt = (b.tv_sec - a.tv_sec) + (b.tv_nsec - a.tv_nsec) / 1e9;
    CHECK(dt < 1.0);
    EXPECT("K65/1/0;");

    /* event flood from the UI thread must never block or crash (queue drops the oldest) */
    for (int i = 0; i < 20000; i++) azux_in_mouse_delta(1, 1);
    glfwPollEvents();
    trace[0] = 0;

    /* close request + time */
    CHECK(glfwWindowShouldClose(w) == 0);
    azux_in_request_close();
    CHECK(glfwWindowShouldClose(w) == 1);
    double t1 = glfwGetTime(); usleep(2000);
    CHECK(glfwGetTime() > t1);
    printf("shim test: all checks passed\n");
    return 0;
}
