package com.azux.launcher.store;

import com.azux.launcher.core.Net;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** CurseForge API v1. CurseForge only allows access with a (free) API key from console.curseforge.com. */
public final class CurseForge {
    private CurseForge() { }

    private static final String BASE = "https://api.curseforge.com";
    private static final int GAME_MINECRAFT = 432;

    private static Map<String, String> headers(String key) {
        Map<String, String> h = new HashMap<>();
        h.put("x-api-key", key);
        h.put("Accept", "application/json");
        return h;
    }

    public static List<Project> search(String key, Category c, String query, String mcVersion, int offset, int limit) throws Exception {
        String url = BASE + "/v1/mods/search?gameId=" + GAME_MINECRAFT + "&classId=" + c.curseClass
                + "&searchFilter=" + enc(query) + (mcVersion.isEmpty() ? "" : "&gameVersion=" + enc(mcVersion))
                + "&sortField=2&sortOrder=desc&pageSize=" + limit + "&index=" + offset;
        return parseSearch(Net.getString(url, headers(key)));
    }

    public static List<Release> files(String key, Project p, String mcVersion) throws Exception {
        List<Release> out = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            String url = BASE + "/v1/mods/" + p.id + "/files?pageSize=50&index=" + (page * 50)
                    + (mcVersion.isEmpty() ? "" : "&gameVersion=" + enc(mcVersion));
            List<Release> part = parseFiles(Net.getString(url, headers(key)), p);
            out.addAll(part);
            if (part.size() < 50) break;
        }
        return out;
    }

    public static List<Project> parseSearch(String json) throws Exception {
        JSONArray data = new JSONObject(json).getJSONArray("data");
        List<Project> out = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject m = data.getJSONObject(i);
            Project p = new Project();
            p.platform = "curseforge";
            p.id = String.valueOf(m.optLong("id"));
            p.slug = m.optString("slug");
            p.title = m.optString("name");
            p.description = m.optString("summary");
            JSONObject logo = m.optJSONObject("logo");
            if (logo != null) p.icon = logo.optString("thumbnailUrl", logo.optString("url", ""));
            JSONArray au = m.optJSONArray("authors");
            if (au != null && au.length() > 0) p.author = au.getJSONObject(0).optString("name");
            p.downloads = (long) m.optDouble("downloadCount", 0);
            p.date = Modrinth.day(m.optString("dateCreated"));
            JSONObject links = m.optJSONObject("links");
            p.webUrl = links != null ? links.optString("websiteUrl", "") : "";
            JSONArray cats = m.optJSONArray("categories");
            if (cats != null) for (int k = 0; k < cats.length() && p.tags.size() < 3; k++) p.tags.add(cats.getJSONObject(k).optString("name"));
            JSONArray idx = m.optJSONArray("latestFilesIndexes");
            if (idx != null) {
                for (int k = 0; k < idx.length(); k++) {
                    String l = loaderName(idx.getJSONObject(k).optInt("modLoader", 0));
                    if (!l.isEmpty() && !p.loaders.contains(l)) p.loaders.add(l);
                }
            }
            out.add(p);
        }
        return out;
    }

    public static List<Release> parseFiles(String json, Project p) throws Exception {
        JSONArray data = new JSONObject(json).getJSONArray("data");
        List<Release> out = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject f = data.getJSONObject(i);
            Release r = new Release();
            r.name = f.optString("displayName", f.optString("fileName"));
            r.fileName = f.optString("fileName");
            r.url = f.isNull("downloadUrl") ? "" : f.optString("downloadUrl", "");
            r.size = f.optLong("fileLength");
            r.date = Modrinth.day(f.optString("fileDate"));
            int rt = f.optInt("releaseType", 1);
            r.type = rt == 1 ? "release" : rt == 2 ? "beta" : "alpha";
            r.webUrl = p.webUrl.isEmpty() ? "" : p.webUrl + "/files/" + f.optLong("id");
            JSONArray hs = f.optJSONArray("hashes");
            if (hs != null) for (int k = 0; k < hs.length(); k++) {
                if (hs.getJSONObject(k).optInt("algo") == 1) r.sha1 = hs.getJSONObject(k).optString("value", "");
            }
            JSONArray gv = f.optJSONArray("gameVersions");
            if (gv != null) {
                for (int k = 0; k < gv.length(); k++) {
                    String v = gv.optString(k);
                    if (v.equalsIgnoreCase("fabric") || v.equalsIgnoreCase("forge") || v.equalsIgnoreCase("neoforge") || v.equalsIgnoreCase("quilt")) {
                        r.loaders.add(Modrinth.cap(v.toLowerCase()));
                    } else if (!v.isEmpty() && Character.isDigit(v.charAt(0))) {
                        r.gameVersions.add(v);
                    }
                }
            }
            out.add(r);
        }
        return out;
    }

    private static String loaderName(int t) {
        switch (t) {
            case 1: return "Forge";
            case 4: return "Fabric";
            case 5: return "Quilt";
            case 6: return "NeoForge";
            default: return "";
        }
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }
}
