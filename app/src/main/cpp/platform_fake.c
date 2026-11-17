/* Host-only fake platform used by the shim unit test (no EGL, no Android). */
#include <stdlib.h>
#include "azux_internal.h"

struct azux_plat_ctx { int current; };
int fake_swaps, fake_current_calls, fake_surface_w = 1280, fake_surface_h = 720, fake_surface_ready = 1;

int azux_plat_init(void) { return 1; }
int azux_plat_wait_surface(int timeout_ms, int *w, int *h) { (void) timeout_ms; if (!fake_surface_ready) return 0; *w = fake_surface_w; *h = fake_surface_h; return 1; }
void azux_plat_surface_size(int *w, int *h) { *w = fake_surface_w; *h = fake_surface_h; }
azux_plat_ctx *azux_plat_create_context(int major, int minor) { (void) major; (void) minor; return calloc(1, sizeof(azux_plat_ctx)); }
int azux_plat_make_current(azux_plat_ctx *c) { fake_current_calls++; if (c) c->current = 1; return 1; }
int azux_plat_swap(azux_plat_ctx *c) { (void) c; fake_swaps++; return 1; }
void azux_plat_swap_interval(int i) { (void) i; }
void azux_plat_destroy_context(azux_plat_ctx *c) { free(c); }
void *azux_plat_proc(const char *name) { (void) name; return NULL; }
