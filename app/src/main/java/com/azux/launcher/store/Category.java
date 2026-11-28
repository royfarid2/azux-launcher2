package com.azux.launcher.store;

/** The download sections. Everything comes from Modrinth. */
public enum Category {
    MOD("Mod", "mod", "mods"),
    MODPACK("Modpack", "modpack", "modpacks"),
    RESOURCE("Resource Pack", "resourcepack", "resourcepacks"),
    SHADER("Shader Pack", "shader", "shaderpacks");

    public final String label, modrinthType, dir;

    Category(String label, String modrinthType, String dir) {
        this.label = label;
        this.modrinthType = modrinthType;
        this.dir = dir;
    }
}
