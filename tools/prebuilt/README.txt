Prebuilt Android arm64 natives, taken from ZalithLauncher (GPLv3), commit ed8a5c873b25f19d08aef5bdfc1084fd9aff836c
https://github.com/ZalithLauncher/ZalithLauncher

lwjgl/liblwjgl*.so, lwjgl/libfreetype.so  LWJGL 3.3.x natives (BSD) + FreeType, from ZalithLauncher/libs/lwjgl3-natives-release.aar
libgl4es_114.so                            gl4es fork used by PojavLauncher/ZalithLauncher (MIT) - OpenGL on top of GLES, good up to modern Minecraft

build_natives.sh copies them into app/src/main/jniLibs/arm64-v8a. OpenAL Soft is still built from source.

libmobileglues.so      MobileGlues (LGPL-2.1, see MOBILEGLUES-LGPL-2.1.txt) - OpenGL 4.x on top of GLES 3, used for Minecraft 1.17+
                       taken from ZalithLauncher 2 (libs/MobileGlues-release.aar). Source: https://github.com/MobileGL-Dev/MobileGlues
libjnidispatch.so      JNA native for Android (from ZalithLauncher), lets Minecraft's OSHI read CPU/RAM info

app/src/main/assets/lwjgl/*.jar  LWJGL 3.3.6-snapshot jars from ZalithLauncher (jre_lwjgl3glfw/libs): they are the SAME build as the
                       liblwjgl*.so above. Mixing them with the jars of the game (LWJGL 3.3.3) crashes with UnsatisfiedLinkError, so
                       the launcher always puts these jars on the class path instead of the game's org.lwjgl ones.
