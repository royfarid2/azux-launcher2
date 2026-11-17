package com.azux.launcher.store;

import java.util.ArrayList;
import java.util.List;

/** A mod / pack / world found on Modrinth or CurseForge. */
public final class Project {
    public String platform = "modrinth";
    public String id = "", slug = "", title = "", description = "", icon = "", author = "", date = "", webUrl = "";
    public long downloads;
    public final List<String> tags = new ArrayList<>();
    public final List<String> loaders = new ArrayList<>();
}
