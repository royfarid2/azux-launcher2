package com.azux.launcher.store;

import com.azux.launcher.core.Net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Modrinth API v2 (public, no key needed). */
public final class Modrinth {
    private Modrinth() { }

    private static final String BASE = "https://api.modrinth.com/v2";
    private static final Set<String> LOADERS = new HashSet<>(Arrays.asList("fabric", "forge", "neoforge", "quilt", "liteloader", "rift"));

    public static List<Project> search(Category c, String query, String mcVersion, int offset, int limit) throws Exception {
        return search(c, query, mcVersion, "", offset, limit);
    }

    /** loader: "fabric", "quilt", "forge", "neoforge" or "" - only used for mods and modpacks. */
    public static List<Project> search(Category c, String query, String mcVersion, String loader, int offset, int limit) throws Exception {
        if (c.modrinthType == null) return new ArrayList<>();
        JSONArray facets = new JSONArray();
        facets.put(new JSONArray().put("project_type:" + c.modrinthType));
        if (!mcVersion.isEmpty()) facets.put(new JSONArray().put("versions:" + mcVersion));
        if (!loader.isEmpty() && (c == Category.MOD || c == Category.MODPACK)) {
            JSONArray l = new JSONArray().put("categories:" + loader);
            if (loader.equals("quilt")) l.put("categories:fabric");   // Quilt runs Fabric mods
            facets.put(l);
        }
        String url = BASE + "/search?query=" + enc(query) + "&facets=" + enc(facets.toString())
                + "&index=" + (query.isEmpty() ? "downloads" : "relevance") + "&offset=" + offset + "&limit=" + limit;
        return parseSearch(Net.getString(url));
    }

    /** Compatible releases of a project for a profile (newest first as Modrinth returns them). */
    public static List<Release> versionsFor(String projectId, String mc, String loader) throws Exception {
        String url = BASE + "/project/" + enc(projectId) + "/version";
        List<String> q = new ArrayList<>();
        if (!loader.isEmpty()) {
            JSONArray l = new JSONArray().put(loader);
            if (loader.equals("quilt")) l.put("fabric");
            q.add("loaders=" + enc(l.toString()));
        }
        if (!mc.isEmpty()) q.add("game_versions=" + enc(new JSONArray().put(mc).toString()));
        if (!q.isEmpty()) url += "?" + android.text.TextUtils.join("&", q);
        Project stub = new Project();
        stub.webUrl = "https://modrinth.com/mod/" + projectId;
        return parseVersions(Net.getString(url), stub);
    }

    /** Best release: stable first, then beta, then alpha. */
    public static Release best(List<Release> list) {
        for (String t : new String[]{"release", "beta", "alpha"}) {
            for (Release r : list) if (r.type.equals(t) && !r.url.isEmpty()) return r;
        }
        return null;
    }

    public static Release version(String versionId) throws Exception {
        Project stub = new Project();
        List<Release> l = parseVersions("[" + Net.getString(BASE + "/version/" + enc(versionId)) + "]", stub);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Names, icons, descriptions, download counts and loaders of several projects in one request. */
    public static java.util.Map<String, Project> projects(java.util.Collection<String> ids) throws Exception {
        java.util.Map<String, Project> out = new java.util.HashMap<>();
        if (ids.isEmpty()) return out;
        JSONArray a = new JSONArray();
        for (String id : ids) a.put(id);
        JSONArray arr = new JSONArray(Net.getString(BASE + "/projects?ids=" + enc(a.toString())));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject h = arr.getJSONObject(i);
            Project p = new Project();
            p.platform = "modrinth";
            p.id = h.optString("id");
            p.slug = h.optString("slug");
            p.title = h.optString("title");
            p.description = h.optString("description");
            p.icon = h.optString("icon_url", "");
            p.downloads = h.optLong("downloads");
            p.webUrl = "https://modrinth.com/" + h.optString("project_type", "mod") + "/" + p.slug;
            JSONArray ld = h.optJSONArray("loaders");
            if (ld != null) for (int k = 0; k < ld.length(); k++) if (LOADERS.contains(ld.optString(k))) p.loaders.add(cap(ld.optString(k)));
            JSONArray gv = h.optJSONArray("game_versions");
            if (gv != null) {
                // newest few Minecraft versions it supports
                for (int k = gv.length() - 1; k >= 0 && p.tags.size() < 3; k--) {
                    String v = gv.optString(k);
                    if (v.matches("\\d+\\.\\d+(\\.\\d+)?")) p.tags.add(v);
                }
            }
            out.put(p.id, p);
        }
        return out;
    }

    public static List<Release> versions(Project p, String mcVersion) throws Exception {
        String url = BASE + "/project/" + enc(p.id) + "/version";
        if (!mcVersion.isEmpty()) url += "?game_versions=" + enc(new JSONArray().put(mcVersion).toString());
        return parseVersions(Net.getString(url), p);
    }

    public static List<Project> parseSearch(String json) throws Exception {
        JSONArray hits = new JSONObject(json).getJSONArray("hits");
        List<Project> out = new ArrayList<>();
        for (int i = 0; i < hits.length(); i++) {
            JSONObject h = hits.getJSONObject(i);
            Project p = new Project();
            p.platform = "modrinth";
            p.id = h.optString("project_id");
            p.slug = h.optString("slug");
            p.title = h.optString("title");
            p.description = h.optString("description");
            p.icon = h.optString("icon_url", "");
            p.author = h.optString("author");
            p.downloads = h.optLong("downloads");
            p.date = day(h.optString("date_created"));
            p.webUrl = "https://modrinth.com/" + h.optString("project_type", "mod") + "/" + p.slug;
            JSONArray cats = h.optJSONArray("categories");
            if (cats != null) {
                for (int k = 0; k < cats.length(); k++) {
                    String s = cats.optString(k);
                    if (LOADERS.contains(s)) p.loaders.add(cap(s));
                }
            }
            JSONArray disp = h.optJSONArray("display_categories");
            if (disp == null) disp = cats;
            if (disp != null) {
                for (int k = 0; k < disp.length(); k++) {
                    String s = disp.optString(k);
                    if (!LOADERS.contains(s) && p.tags.size() < 3) p.tags.add(cap(s));
                }
            }
            out.add(p);
        }
        return out;
    }

    public static List<Release> parseVersions(String json, Project p) throws Exception {
        JSONArray arr = new JSONArray(json);
        List<Release> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject v = arr.getJSONObject(i);
            JSONArray files = v.optJSONArray("files");
            if (files == null || files.length() == 0) continue;
            JSONObject f = files.getJSONObject(0);
            for (int k = 0; k < files.length(); k++) {
                if (files.getJSONObject(k).optBoolean("primary", false)) { f = files.getJSONObject(k); break; }
            }
            Release r = new Release();
            r.id = v.optString("id");
            r.projectId = v.optString("project_id");
            r.name = v.optString("version_number", v.optString("name"));
            JSONArray deps = v.optJSONArray("dependencies");
            if (deps != null) {
                for (int k = 0; k < deps.length(); k++) {
                    JSONObject d = deps.optJSONObject(k);
                    if (d == null) continue;
                    r.deps.add(new Release.Dep(d.isNull("project_id") ? "" : d.optString("project_id"),
                            d.isNull("version_id") ? "" : d.optString("version_id"), d.optString("dependency_type", "required")));
                }
            }
            r.type = v.optString("version_type", "release");
            r.date = day(v.optString("date_published"));
            r.fileName = f.optString("filename");
            r.url = f.optString("url");
            r.size = f.optLong("size");
            JSONObject hashes = f.optJSONObject("hashes");
            if (hashes != null) r.sha1 = hashes.optString("sha1", "");
            r.webUrl = p.webUrl + "/version/" + v.optString("id");
            r.id = v.optString("id");
            JSONArray ld = v.optJSONArray("loaders");
            if (ld != null) for (int k = 0; k < ld.length(); k++) r.loaders.add(cap(ld.optString(k)));
            JSONArray gv = v.optJSONArray("game_versions");
            if (gv != null) for (int k = 0; k < gv.length(); k++) r.gameVersions.add(gv.optString(k));
            out.add(r);
        }
        return out;
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }
    static String day(String iso) { return iso == null || iso.length() < 10 ? "" : iso.substring(0, 10); }
    static String cap(String s) {
        if (s.equalsIgnoreCase("neoforge")) return "NeoForge";
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
