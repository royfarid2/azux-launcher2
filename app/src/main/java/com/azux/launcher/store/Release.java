package com.azux.launcher.store;

import java.util.ArrayList;
import java.util.List;

/** One downloadable file of a project. url is empty when the author blocks direct download. */
public final class Release {
    public String name = "", fileName = "", url = "", sha1 = "", webUrl = "", type = "release", date = "";
    /** Modrinth version id / project id (empty for other sources). */
    public String id = "", projectId = "";
    public long size;
    public final List<String> loaders = new ArrayList<>();
    public final List<String> gameVersions = new ArrayList<>();
    public final List<Dep> deps = new ArrayList<>();

    /** A dependency as Modrinth reports it: type is required, optional, incompatible or embedded. */
    public static final class Dep {
        public final String projectId, versionId, type;
        public Dep(String projectId, String versionId, String type) {
            this.projectId = projectId == null ? "" : projectId;
            this.versionId = versionId == null ? "" : versionId;
            this.type = type == null ? "required" : type;
        }
    }

    /** True if at least one game version is a normal release like 1.20.1 (no snapshots / pre-releases). */
    public boolean hasOfficialVersion() {
        for (String v : gameVersions) if (v.matches("\\d+\\.\\d+(\\.\\d+)?")) return true;
        return false;
    }

    public boolean supportsGame(String mc) {
        if (mc == null || mc.isEmpty() || gameVersions.isEmpty()) return true;
        for (String v : gameVersions) if (v.equalsIgnoreCase(mc)) return true;
        return false;
    }

    /** Loader match; Quilt also runs Fabric mods. Empty loader means "do not filter". */
    public boolean supportsLoader(String loader) {
        if (loader == null || loader.isEmpty() || loaders.isEmpty()) return true;
        for (String l : loaders) {
            if (l.equalsIgnoreCase(loader)) return true;
            if (loader.equalsIgnoreCase("quilt") && l.equalsIgnoreCase("fabric")) return true;
        }
        return false;
    }
}
