#!/usr/bin/env bash
# Builds the native libraries the game needs, for Android arm64, into app/src/main/jniLibs/arm64-v8a:
#   liblwjgl*.so  LWJGL 3 natives (prebuilt, tools/prebuilt)
#   libgl4es.so   OpenGL -> OpenGL ES translation (gl4es, prebuilt)
#   libopenal.so  OpenAL Soft (sound, OpenSL ES backend)
# Each part is independent; the script exits non-zero only if LWJGL or gl4es failed.
#
#   Uses the NDK preinstalled on the runner (any version). For a local dry run set AZUX_TEST_TOOLCHAIN=<cmake toolchain file>.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="${AZUX_BUILD_DIR:-$ROOT/build/natives}"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
LWJGL_TAG="${LWJGL_TAG:-3.2.2}"
OPENAL_TAG="${OPENAL_TAG:-1.23.1}"
JOBS="$(nproc 2>/dev/null || echo 4)"
mkdir -p "$BUILD" "$OUT"

if [ -n "${AZUX_TEST_TOOLCHAIN:-}" ]; then
  TC_ARGS=(-DCMAKE_TOOLCHAIN_FILE="$AZUX_TEST_TOOLCHAIN")
  JNI_ARGS=(-DJNI_INCLUDE="${AZUX_JNI_INCLUDE:-/usr/lib/jvm/java-21-openjdk-amd64/include}")
  ANDROID_REAL=0
else
  NDK=""
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
  for cand in "${ANDROID_NDK_LATEST_HOME:-}" "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}" \
              $(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -3 | sort -rV); do
    [ -n "$cand" ] && [ -f "$cand/build/cmake/android.toolchain.cmake" ] && { NDK="$cand"; break; }
  done
  if [ -z "$NDK" ] && [ -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "No NDK found, installing one with sdkmanager ..."
    yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
    "$SDK/cmdline-tools/latest/bin/sdkmanager" "ndk;26.3.11579264" || true
    [ -f "$SDK/ndk/26.3.11579264/build/cmake/android.toolchain.cmake" ] && NDK="$SDK/ndk/26.3.11579264"
  fi
  [ -n "$NDK" ] || { echo "ERROR: Android NDK not found (looked in env vars and $SDK/ndk)"; exit 2; }
  echo "Using NDK: $NDK"
  TC_ARGS=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26)
  JNI_ARGS=()
  ANDROID_REAL=1
fi
COMMON=(-DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON)

STATUS_ENGINE=FAILED; STATUS_LWJGL=FAILED; STATUS_GL4ES=FAILED; STATUS_OPENAL=FAILED

clone() { # url dir [branch]
  [ -d "$2/.git" ] && return 0
  if [ -n "${3:-}" ]; then git clone -q --depth 1 --branch "$3" "$1" "$2"; else git clone -q --depth 1 "$1" "$2"; fi
}

build_engine() {   # libazuxengine.so: JVM launcher + GLFW shim + input (our own C code)
  cmake -S "$ROOT/app/src/main/cpp" -B "$BUILD/engine_out" "${TC_ARGS[@]}" "${COMMON[@]}" >"$BUILD/engine.log" 2>&1 || { tail -30 "$BUILD/engine.log"; return 1; }
  cmake --build "$BUILD/engine_out" -j "$JOBS" >>"$BUILD/engine.log" 2>&1 || { tail -40 "$BUILD/engine.log"; return 1; }
  cp "$BUILD/engine_out/libazuxengine.so" "$OUT/" || return 1
}

copy_libcxx() {   # Java 25's libraries need the NDK C++ runtime; the repository also carries a copy as a fallback
  [ "$ANDROID_REAL" = 1 ] || return 0
  local f
  f="$(find "$NDK" -path '*aarch64-linux-android/libc++_shared.so' 2>/dev/null | head -1)"
  [ -n "$f" ] && cp "$f" "$OUT/libc++_shared.so" && echo "libc++_shared.so copied from the NDK"
  return 0
}

# LWJGL natives and gl4es are PREBUILT (tools/prebuilt, see README.txt there): they come from ZalithLauncher and are
# known to work on Android with Minecraft up to the newest versions. Building LWJGL 3.3.x from source needs libffi
# cross builds and often failed; building the stock gl4es cannot run Minecraft 1.17+.
build_lwjgl() {
  local d="$ROOT/tools/prebuilt/lwjgl" f
  [ -f "$d/liblwjgl.so" ] && [ -f "$d/liblwjgl_opengl.so" ] || { echo "tools/prebuilt/lwjgl is missing"; return 1; }
  for f in "$d"/*.so; do cp -f "$f" "$OUT/"; done
  echo "LWJGL natives copied: $(ls "$d" | tr '\n' ' ')"
}

build_gl4es() {
  [ -f "$ROOT/tools/prebuilt/libgl4es_114.so" ] || { echo "tools/prebuilt/libgl4es_114.so is missing"; return 1; }
  cp -f "$ROOT/tools/prebuilt/libgl4es_114.so" "$OUT/libgl4es.so"
  # MobileGlues (GL 4.x over GLES 3, for Minecraft 1.17+) and JNA's native: optional extras, same folder
  for f in libmobileglues.so libjnidispatch.so; do [ -f "$ROOT/tools/prebuilt/$f" ] && cp -f "$ROOT/tools/prebuilt/$f" "$OUT/$f"; done
  echo "gl4es (Pojav/Zalith build), MobileGlues and JNA natives copied"
}

build_openal() {
  clone https://github.com/kcat/openal-soft.git "$BUILD/openal" "$OPENAL_TAG" || return 1
  local extra=()
  [ "$ANDROID_REAL" = 1 ] && extra=(-DALSOFT_BACKEND_OPENSL=ON -DALSOFT_REQUIRE_OPENSL=ON)
  cmake -S "$BUILD/openal" -B "$BUILD/openal_out" "${TC_ARGS[@]}" "${COMMON[@]}" "${extra[@]}" \
        -DLIBTYPE=SHARED -DALSOFT_UTILS=OFF -DALSOFT_EXAMPLES=OFF -DALSOFT_TESTS=OFF -DALSOFT_INSTALL=OFF \
        -DALSOFT_BACKEND_WAVE=OFF >"$BUILD/openal.log" 2>&1 || { tail -30 "$BUILD/openal.log"; return 1; }
  cmake --build "$BUILD/openal_out" -j "$JOBS" >>"$BUILD/openal.log" 2>&1 || { tail -30 "$BUILD/openal.log"; return 1; }
  local lib
  lib="$(ls "$BUILD"/openal_out/libopenal.so* 2>/dev/null | head -1)"
  [ -n "$lib" ] || { echo "OpenAL library not found"; return 1; }
  cp -L "$lib" "$OUT/libopenal.so"
}

want() { [ -z "${AZUX_ONLY:-}" ] || [[ " $AZUX_ONLY " == *" $1 "* ]]; }   # dry runs: AZUX_ONLY="lwjgl gl4es"
if want engine; then
  echo "== Azux engine (libazuxengine.so)"
  if [ "$ANDROID_REAL" = 1 ]; then build_engine && STATUS_ENGINE=ok; else echo "(Android-only code: skipped in dry run)"; STATUS_ENGINE=skipped; fi
fi
copy_libcxx
want lwjgl  && { echo "== LWJGL $LWJGL_TAG natives"; build_lwjgl  && STATUS_LWJGL=ok; }
want gl4es  && { echo "== gl4es";                   build_gl4es  && STATUS_GL4ES=ok; }
want openal && { echo "== OpenAL Soft $OPENAL_TAG"; build_openal && STATUS_OPENAL=ok; }

echo
echo "Azux engine   : $STATUS_ENGINE"
echo "LWJGL natives : $STATUS_LWJGL"
echo "gl4es         : $STATUS_GL4ES"
echo "OpenAL        : $STATUS_OPENAL  (optional: the game runs without sound)"
ls -la "$OUT"
[ "$STATUS_ENGINE" != FAILED ] && [ "$STATUS_LWJGL" = ok ] && [ "$STATUS_GL4ES" = ok ]
