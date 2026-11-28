/* Replacement for LWJGL's linux/LinuxLWJGL.h, which only includes the X11 headers (not available in the
 * Android NDK). The one file that includes it (DynamicLinkLoader.c) does not use any X11 symbol. */
#pragma once
