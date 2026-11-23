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
        if (c.modrinthType == null) return new ArrayList<>();
        JSONArray facets = new JSONArray();
        facets.put(new JSONArray().put("project_type:" + c.modrinthType));
        if (!mcVersion.isEmpty()) facets.put(new JSONArray().put("versions:" + mcVersion));
        String url = BASE + "/search?query=" + enc(query) + "&facets=" + enc(facets.toString())
                + "&index=" + (query.isEmpty() ? "downloads" : "relevance") + "&offset=" + offset + "&limit=" + limit;
        return parseSearch(Net.getString(url));
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
            r.name = v.optString("version_number", v.optString("name"));
            r.type = v.optString("version_type", "release");
            r.date = day(v.optString("date_published"));
            r.fileName = f.optString("filename");
            r.url = f.optString("url");
            r.size = f.optLong("size");
            JSONObject hashes = f.optJSONObject("hashes");
            if (hashes != null) r.sha1 = hashes.optString("sha1", "");
            r.webUrl = p.webUrl + "/version/" + v.optString("id");
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
