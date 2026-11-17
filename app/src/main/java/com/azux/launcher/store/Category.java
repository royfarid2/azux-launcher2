package com.azux.launcher.store;

/** The five download sections. */
public enum Category {
    MOD("Mod", "mod", 6, "mods"),
    MODPACK("Modpack", "modpack", 4471, "modpacks"),
    RESOURCE("Resource Pack", "resourcepack", 12, "resourcepacks"),
    WORLD("World", null, 17, "saves"),
    SHADER("Shader Pack", "shader", 6552, "shaderpacks");

    public final String label, modrinthType, dir;
    public final int curseClass;

    Category(String label, String modrinthType, int curseClass, String dir) {
        this.label = label;
        this.modrinthType = modrinthType;
        this.curseClass = curseClass;
        this.dir = dir;
    }
}
