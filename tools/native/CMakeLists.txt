# Builds LWJGL 3 native libraries for Android (arm64) from LWJGL's own sources.
# The generated JNI C files are in the LWJGL repo, so no Kotlin generator is needed.
#
#   cmake -DLWJGL_SRC=<lwjgl3 checkout> -DDYNCALL_BUILD=<dyncall build dir> [-DJNI_INCLUDE=<dir>] ...
cmake_minimum_required(VERSION 3.18)
project(azux_lwjgl C)

set(LWJGL_SRC "" CACHE PATH "LWJGL 3 source checkout")
set(DYNCALL_SRC "" CACHE PATH "dyncall source")
set(DYNCALL_BUILD "" CACHE PATH "dyncall build dir with the three static libs")
set(JNI_INCLUDE "" CACHE PATH "extra JNI include dir (only for host test builds)")

if(NOT LWJGL_SRC OR NOT DYNCALL_BUILD)
  message(FATAL_ERROR "LWJGL_SRC and DYNCALL_BUILD are required")
endif()

set(M "${LWJGL_SRC}/modules/lwjgl")
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# LWJGL's C files rely on <stdio.h>/<stdlib.h>/<string.h> arriving through the JDK's jni.h. Android's jni.h
# does not include them, so force-include them (clang 16+ treats implicit declarations as errors).
# ("SHELL:" stops CMake from merging the repeated -include options into one broken flag)
add_compile_options(-O2 -fPIC -w -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0 -D_GNU_SOURCE -DNDEBUG -DLWJGL_LINUX -DLWJGL_arm64
                    "SHELL:-include stdio.h" "SHELL:-include stdlib.h" "SHELL:-include string.h" "SHELL:-include stdint.h")

# stubs/ comes first so our X11-free LinuxLWJGL.h wins over LWJGL's
set(COMMON_INC "${CMAKE_CURRENT_SOURCE_DIR}/stubs" "${M}/core/src/main/c" "${M}/core/src/main/c/linux")
if(JNI_INCLUDE)
  list(APPEND COMMON_INC "${JNI_INCLUDE}" "${JNI_INCLUDE}/linux")
endif()

function(lwjgl_lib name)
  add_library(${name} SHARED ${ARGN})
  target_include_directories(${name} PRIVATE ${COMMON_INC})
  set_target_properties(${name} PROPERTIES PREFIX "lib" OUTPUT_NAME ${name})
endfunction()

# ---- core: liblwjgl.so ------------------------------------------------------
file(GLOB CORE_MAIN "${M}/core/src/main/c/*.c")
file(GLOB CORE_GEN "${M}/core/src/generated/c/*.c")
file(GLOB CORE_LINUX "${M}/core/src/generated/c/linux/*.c")
lwjgl_lib(lwjgl ${CORE_MAIN} ${CORE_GEN} ${CORE_LINUX})
target_include_directories(lwjgl PRIVATE "${M}/core/src/main/c/dyncall")
target_link_libraries(lwjgl
  "${DYNCALL_BUILD}/dyncallback/libdyncallback_s.a"
  "${DYNCALL_BUILD}/dyncall/libdyncall_s.a"
  "${DYNCALL_BUILD}/dynload/libdynload_s.a"
  dl)

# ---- OpenGL: liblwjgl_opengl.so --------------------------------------------
file(GLOB GL_GEN "${M}/opengl/src/generated/c/*.c")
lwjgl_lib(lwjgl_opengl ${GL_GEN})
target_include_directories(lwjgl_opengl PRIVATE "${M}/opengl/src/main/c")

# ---- stb: liblwjgl_stb.so (image loading) -----------------------------------
file(GLOB STB_GEN "${M}/stb/src/generated/c/*.c")
lwjgl_lib(lwjgl_stb ${STB_GEN})
target_include_directories(lwjgl_stb SYSTEM PRIVATE "${M}/stb/src/main/c")
target_link_libraries(lwjgl_stb m)

# ---- tinyfd: liblwjgl_tinyfd.so (only loaded if a file dialog is requested) --
file(GLOB TFD_SRC "${M}/tinyfd/src/main/c/*.c" "${M}/tinyfd/src/generated/c/*.c")
if(TFD_SRC)
  lwjgl_lib(lwjgl_tinyfd ${TFD_SRC})
  target_include_directories(lwjgl_tinyfd PRIVATE "${M}/tinyfd/src/main/c")
endif()
