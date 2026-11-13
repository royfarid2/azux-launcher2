# Azux Engine (from scratch)

Stage 1: our own C launcher (`azux_jvm.c`, JNI_CreateJavaVM) starts an OpenJDK 17 JVM inside the app
and runs a test class. No launcher code is used. The only external binary is the Android OpenJDK build,
downloaded by GitHub Actions (see `.github/workflows/build.yml`).

Next stages: GLFW/EGL window, Minecraft main menu, controls and audio.
