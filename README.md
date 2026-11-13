# Azux Launcher v6
Minecraft: Java Edition launcher for Android with its own Azux interface.

- Azux screens (home, versions, accounts, settings, CONTROL LAYOUT) are in `overlay/` (`AzuxActivity.java`).
- The game engine (Java runtimes 8/17/21, renderers, LWJGL/GLFW bridge, input, touch/keyboard/mouse, control editor)
  is the open-source **PojavLauncher** (LGPL-3.0), fetched at a pinned commit by the CI. Its license is in `LICENSE`.
- Java is chosen automatically per Minecraft version by the engine.

Build: push -> Actions -> "Build Azux Launcher" -> download the `Azux-Launcher` artifact (arm64 devices).

v6: slim sidebar, own progress bar (no stock download banner), player skin head on accounts, no stock launcher links on the main screen.

Content tab: Modrinth browser for mods / resource packs / shaders (pick MC version + loader, then version; required dependencies auto-download), Fabric/Quilt installer, Forge/NeoForge installer, control layout import.
