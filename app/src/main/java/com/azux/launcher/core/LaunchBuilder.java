package com.azux.launcher.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds the full java command line from a version json. Pure logic, no Android UI. */
public final class LaunchBuilder {
    private LaunchBuilder() { }

    private static final Pattern VAR = Pattern.compile("\\$\\{(\\w+)\\}");

    public static List<String> build(Context ctx, JSONObject v, String versionId, Accounts.Account acc,
                                     int ramMb, String javaBin, List<String> extraJvm) throws Exception {
        File vdir = new File(Paths.versions(ctx), versionId);
        StringBuilder cp = new StringBuilder();
        for (File f : Libs.files(ctx, v)) cp.append(f.getAbsolutePath()).append(':');
        cp.append(new File(vdir, versionId + ".jar").getAbsolutePath());

        boolean virt = false;
        JSONObject ai = v.optJSONObject("assetIndex");
        String assetId = ai == null ? "legacy" : ai.optString("id", "legacy");
        File idx = new File(Paths.assets(ctx), "indexes/" + assetId + ".json");
        if (idx.exists()) virt = new JSONObject(Net.readText(idx)).optBoolean("virtual", false);
        File assetsRoot = virt ? new File(Paths.assets(ctx), "virtual/legacy") : Paths.assets(ctx);

        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", acc.name);
        vars.put("auth_uuid", acc.uuid.replace("-", ""));
        vars.put("auth_access_token", "0");
        vars.put("auth_xuid", "0");
        vars.put("clientid", "0");
        vars.put("user_type", "legacy");
        vars.put("user_properties", "{}");
        vars.put("version_name", versionId);
        vars.put("version_type", v.optString("type", "release"));
        vars.put("game_directory", Paths.game(ctx).getAbsolutePath());
        vars.put("assets_root", assetsRoot.getAbsolutePath());
        vars.put("game_assets", assetsRoot.getAbsolutePath());
        vars.put("assets_index_name", assetId);
        vars.put("natives_directory", new File(Paths.root(ctx), "natives").getAbsolutePath());
        vars.put("library_directory", Paths.libraries(ctx).getAbsolutePath());
        vars.put("classpath", cp.toString());
        vars.put("classpath_separator", ":");
        vars.put("launcher_name", "Azux");
        vars.put("launcher_version", "7");
        vars.put("resolution_width", "854");
        vars.put("resolution_height", "480");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-Xmx" + ramMb + "M");
        cmd.add("-Xms" + Math.min(ramMb, 512) + "M");
        if (extraJvm != null) cmd.addAll(extraJvm);

        JSONObject args = v.optJSONObject("arguments");
        if (args != null) {
            collect(args.optJSONArray("jvm"), vars, cmd);
        } else {
            cmd.add("-Djava.library.path=" + vars.get("natives_directory"));
            cmd.add("-cp");
            cmd.add(cp.toString());
        }
        cmd.add(v.getString("mainClass"));
        if (args != null) {
            collect(args.optJSONArray("game"), vars, cmd);
        } else {
            String legacy = v.optString("minecraftArguments", "");
            for (String part : legacy.trim().split("\\s+")) if (!part.isEmpty()) cmd.add(fill(part, vars));
        }
        return cmd;
    }

    private static void collect(JSONArray arr, Map<String, String> vars, List<String> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            Object o = arr.opt(i);
            if (o instanceof String) {
                out.add(fill((String) o, vars));
            } else if (o instanceof JSONObject) {
                JSONObject obj = (JSONObject) o;
                if (!Rules.allowed(obj.optJSONArray("rules"))) continue;
                Object val = obj.opt("value");
                if (val instanceof String) {
                    out.add(fill((String) val, vars));
                } else if (val instanceof JSONArray) {
                    JSONArray va = (JSONArray) val;
                    for (int j = 0; j < va.length(); j++) out.add(fill(va.optString(j), vars));
                }
            }
        }
    }

    private static String fill(String s, Map<String, String> vars) {
        Matcher m = VAR.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String r = vars.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(r == null ? "" : r));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
