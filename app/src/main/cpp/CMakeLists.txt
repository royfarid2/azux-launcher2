cmake_minimum_required(VERSION 3.18)
project(azuxengine C)

set(CMAKE_C_STANDARD 11)
add_compile_options(-fvisibility=hidden -O2)

add_library(azuxengine SHARED
    azuxengine.c
    azux_jni.c
    glfw_shim.c
    platform_android.c)

# plain library names: the NDK linker finds them (pthread/dl live in libc/libdl on Android)
target_link_libraries(azuxengine log EGL android dl)
