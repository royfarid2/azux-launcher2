/* Azux engine - shared internal declarations. */
#pragma once

#include <stddef.h>

#define AZUX_EXPORT __attribute__((visibility("default")))

void azux_log(const char *fmt, ...);

/* ---- platform layer (EGL + ANativeWindow on Android, a fake on the host test) ---- */
typedef struct azux_plat_ctx azux_plat_ctx;

int  azux_plat_init(void);
/* blocks until the Android surface exists; returns 1 and its size, or 0 on timeout */
int  azux_plat_wait_surface(int timeout_ms, int *w, int *h);
void azux_plat_surface_size(int *w, int *h);
azux_plat_ctx *azux_plat_create_context(int major, int minor);
int  azux_plat_make_current(azux_plat_ctx *ctx);   /* NULL releases */
int  azux_plat_swap(azux_plat_ctx *ctx);
void azux_plat_swap_interval(int interval);
void azux_plat_destroy_context(azux_plat_ctx *ctx);
void *azux_plat_proc(const char *name);

/* ---- input entry points (called from the Android UI thread / JNI) ---- */
void azux_in_key(int glfw_key, int action);
void azux_in_char(unsigned int codepoint);
void azux_in_mouse_button(int button, int action);
void azux_in_mouse_delta(double dx, double dy);
void azux_in_cursor_abs(double x, double y);
void azux_in_scroll(double dx, double dy);
void azux_in_surface_size(int w, int h);
void azux_in_focus(int focused);
void azux_in_request_close(void);
void azux_in_get_cursor(double *x, double *y, int *mode);

/* identity of the shim's global state - logged by both the launcher and the shim to prove they are one copy */
void *azux_state_id(void);
