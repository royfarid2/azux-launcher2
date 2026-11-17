package com.azux.launcher.store;

import java.util.ArrayList;
import java.util.List;

/** One downloadable file of a project. url is empty when the author blocks direct download. */
public final class Release {
    public String name = "", fileName = "", url = "", sha1 = "", webUrl = "", type = "release", date = "";
    public long size;
    public final List<String> loaders = new ArrayList<>();
    public final List<String> gameVersions = new ArrayList<>();

    /** True if at least one game version is a normal release like 1.20.1 (no snapshots / pre-releases). */
    public boolean hasOfficialVersion() {
        for (String v : gameVersions) if (v.matches("\\d+\\.\\d+(\\.\\d+)?")) return true;
        return false;
    }
}
