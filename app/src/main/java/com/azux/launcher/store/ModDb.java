package com.azux.launcher.store;

import com.azux.launcher.core.Net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Record of the mods Azux installed (azux-mods.json next to the mods folder): file -> project, version, title and why
 * it is there. Lets the launcher know what is installed without guessing from file names. Files the user copied by
 * hand are listed too, without a project id.
 */
public final class ModDb {
    public static final class Entry {
        public String file = "", projectId = "", versionId = "", title = "", version = "", date = "";
        public boolean dependency;                 // installed only because another mod needs it
        public final List<String> neededBy = new ArrayList<>();
        public boolean enabled = true;             // false: file ends with .disabled
    }

    private final File modsDir, dbFile;
    private final Map<String, Entry> entries = new HashMap<>();   // keyed by the enabled file name (without .disabled)

    public ModDb(File gameDir) {
        modsDir = new File(gameDir, "mods");
        modsDir.mkdirs();
        dbFile = new File(gameDir, "azux-mods.json");
        load();
    }

    public File modsDir() { return modsDir; }

    private void load() {
        entries.clear();
        try {
            if (dbFile.exists()) {
                JSONArray a = new JSONArray(Net.readText(dbFile));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    Entry e = new Entry();
                    e.file = o.optString("file");
                    e.projectId = o.optString("projectId");
                    e.versionId = o.optString("versionId");
                    e.title = o.optString("title");
                    e.version = o.optString("version");
                    e.date = o.optString("date");
                    e.dependency = o.optBoolean("dependency");
                    JSONArray nb = o.optJSONArray("neededBy");
                    if (nb != null) for (int k = 0; k < nb.length(); k++) e.neededBy.add(nb.optString(k));
                    entries.put(e.file, e);
                }
            }
        } catch (Exception ignored) { }
        // forget entries whose file is gone
        List<String> gone = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (new File(modsDir, e.file).exists()) e.enabled = true;
            else if (new File(modsDir, e.file + ".disabled").exists()) e.enabled = false;
            else gone.add(e.file);
        }
        for (String g : gone) entries.remove(g);
    }

    public void save() throws Exception {
        JSONArray a = new JSONArray();
        for (Entry e : entries.values()) {
            JSONObject o = new JSONObject();
            o.put("file", e.file).put("projectId", e.projectId).put("versionId", e.versionId).put("title", e.title)
                    .put("version", e.version).put("date", e.date).put("dependency", e.dependency);
            o.put("neededBy", new JSONArray(e.neededBy));
            a.put(o);
        }
        Net.writeText(dbFile, a.toString());
    }

    public void put(Entry e) { entries.put(e.file, e); }

    public Entry byProject(String projectId) {
        for (Entry e : entries.values()) if (!projectId.isEmpty() && projectId.equals(e.projectId)) return e;
        return null;
    }

    /** Installed (enabled) mods known to Modrinth: projectId -> what is installed. Disabled mods do not count. */
    public Map<String, Resolver.Installed> installedProjects() {
        Map<String, Resolver.Installed> m = new HashMap<>();
        for (Entry e : entries.values()) {
            if (e.enabled && !e.projectId.isEmpty()) m.put(e.projectId, new Resolver.Installed(e.projectId, e.versionId, e.file));
        }
        return m;
    }

    /** Every mod file in the folder (enabled and disabled), including ones Azux did not install. */
    public List<Entry> list() {
        load();
        List<Entry> out = new ArrayList<>(entries.values());
        File[] files = modsDir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                boolean dis = n.endsWith(".jar.disabled");
                if (!n.endsWith(".jar") && !dis) continue;
                String base = dis ? n.substring(0, n.length() - ".disabled".length()) : n;
                if (entries.containsKey(base)) continue;
                Entry e = new Entry();
                e.file = base;
                e.title = base.replace(".jar", "");
                e.enabled = !dis;
                out.add(e);
            }
        }
        java.util.Collections.sort(out, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return out;
    }

    /** Duplicate detection: several files of the same Modrinth project. */
    public List<String> duplicates() {
        Map<String, List<String>> by = new HashMap<>();
        for (Entry e : list()) {
            if (e.projectId.isEmpty() || !e.enabled) continue;
            if (!by.containsKey(e.projectId)) by.put(e.projectId, new ArrayList<String>());
            by.get(e.projectId).add(e.file);
        }
        List<String> out = new ArrayList<>();
        for (List<String> l : by.values()) if (l.size() > 1) out.add(android.text.TextUtils.join(", ", l));
        return out;
    }

    public void setEnabled(Entry e, boolean on) throws Exception {
        File a = new File(modsDir, e.file), b = new File(modsDir, e.file + ".disabled");
        if (on && b.exists()) b.renameTo(a);
        if (!on && a.exists()) a.renameTo(b);
        e.enabled = on;
        if (entries.containsKey(e.file)) save();
    }

    /** Installed mods that need this one (so deleting it would break them). */
    public List<String> neededByOthers(Entry e) {
        List<String> out = new ArrayList<>();
        for (String nb : e.neededBy) {
            for (Entry o : entries.values()) if (o != e && o.enabled && o.title.equals(nb) && !out.contains(o.title)) out.add(o.title);
        }
        return out;
    }

    public void delete(Entry e) throws Exception {
        new File(modsDir, e.file).delete();
        new File(modsDir, e.file + ".disabled").delete();
        entries.remove(e.file);
        save();
    }
}
