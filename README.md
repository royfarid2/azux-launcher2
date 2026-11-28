# Azux Engine

Standalone Android launcher, written from scratch.

## v13
- **Java 8, 17, 21 and 25 are inside this project** (`app/src/main/assets/runtime/jre<N>.tar.gz`, ~165 MB); CI no longer
  downloads them, it only verifies them (`tools/verify_runtime.py`). They are Android (bionic, aarch64) builds of OpenJDK
  taken from the Zalith launcher's repository (runtime archives only, GPLv2 + Classpath Exception).
- Java 8 ships its jars as Pack200 - they were unpacked with the official `unpack200` (otherwise the JVM cannot start).
- Java 25 needs the NDK's `libc++_shared.so`; it is in `jniLibs` (CI replaces it with the NDK's own copy).
- To regenerate the archives: `python3 tools/fetch_runtime.py 8 17 21 25` (needs a host Java 8 for `unpack200`).

## v12
- Java 8, 17 and 21 runtimes are bundled again (Settings lists all three). CI downloads Android builds of OpenJDK
  (aarch64/bionic) pinned to one commit of the Zalith launcher's repository - runtime archives only, none of its code -
  and packs them into the APK (`tools/fetch_runtime.py`). OpenJDK is GPLv2 + Classpath Exception.
- The launcher picks the Java a version needs: 8 for 1.16.5 and older, 17 for 1.17 - 1.20.4, 21 for 1.20.5+.
- Settings > Test Java runs `JavaCheck` (our own small program) on the chosen Java: compute, zip, file IO, images, TLS.

## v11
- Fixed the native build under the real NDK (reproduced with Android's own jni.h):
  `JNI_VERSION_1_8` does not exist there, LWJGL's C files need `<stdio.h>` and an X11 header the NDK lacks, and
  CMake was merging the repeated `-include` flags.
- Game files and the Java runtime are downloaded / unpacked in the launcher (progress bar + Cancel), not in the game.

## v10
- Native libraries (including `libazuxengine.so`) are now built by `tools/build_natives.sh` with the runner's NDK and
  put into `jniLibs`; Gradle no longer needs an NDK/CMake. The CI run turns red when the engine or Java runtime is missing.
- Control editor: dragging a control no longer opens its settings (only a tap does).
- Animations: button press feel, page transitions, staggered cards, PLAY pulse, editor drawer slide, pop-in chips.

## v9 - the game pipeline
- **Window + input (written for Azux):** `glfw_shim.c` implements the GLFW API (118 functions) on top of the app's
  Surface + EGL. LWJGL loads it through `-Dorg.lwjgl.glfw.libname`. Touch controls, the camera pad, the soft/hardware
  keyboard and a mouse pointer feed it through JNI.
- **Native libraries built in CI from source:** LWJGL 3.2.2 natives (core, OpenGL, stb), gl4es (OpenGL -> GLES),
  OpenAL Soft. See `tools/build_natives.sh`.
- **Target for now:** Minecraft 1.13 - 1.16.5 (LWJGL 3.2.x, OpenGL <= 2.1). 1.17+ needs OpenGL 3.2 core and
  LWJGL 2 versions (<= 1.12.2) need a different binding: both are not supported yet and the game screen says so.

## Tests that run without a phone
- `tools/tests/run.sh [lwjgl3 checkout]` - unit test of the GLFW shim + check that it exports every GLFW function LWJGL asks for.
- `tools/fetch_runtime.py --selftest`

## v8
- Downloads (Modrinth / CurseForge), new controls editor, optional account, native JVM launcher, Java 17/21 runtimes.
