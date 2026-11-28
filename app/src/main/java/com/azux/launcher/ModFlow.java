package com.azux.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;

import com.azux.launcher.core.Paths;
import com.azux.launcher.store.ModDb;
import com.azux.launcher.store.ModInstaller;
import com.azux.launcher.store.ModrinthSource;
import com.azux.launcher.store.Project;
import com.azux.launcher.store.Release;
import com.azux.launcher.store.Resolver;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Install flow for a mod: resolve dependencies (Modrinth) -> dependency dialog -> safe staged install. */
public final class ModFlow {
    private ModFlow() { }

    /** Minecraft version of the selected profile, e.g. "1.21.4" from "1.21.4" or "fabric-loader-0.16.9-1.21.4". */
    public static String profileMc(SharedPreferences prefs) {
        String v = prefs.getString("version", "");
        if (v.matches("\\d+\\.\\d+(\\.\\d+)?")) return v;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)$").matcher(v);
        return m.find() ? m.group(1) : "";
    }

    /** Loader of the selected profile: from its id (fabric-loader-..., quilt-loader-..., ...forge...) or the chosen one. */
    public static String profileLoader(SharedPreferences prefs) {
        String v = prefs.getString("version", "").toLowerCase();
        if (v.startsWith("fabric-loader")) return "fabric";
        if (v.startsWith("quilt-loader")) return "quilt";
        if (v.contains("neoforge")) return "neoforge";
        if (v.contains("forge")) return "forge";
        return prefs.getString("modLoader", "");
    }

    public static void install(final Activity act, final Project mainProject, final Release r, final String mc,
                               final String loader, final Runnable onDone) {
        final AlertDialog wait = new AlertDialog.Builder(act).setMessage("Checking compatibility and dependencies...")
                .setCancelable(false).show();
        new Thread(() -> {
            try {
                final ModrinthSource src = new ModrinthSource();
                final ModDb db = new ModDb(Paths.game(act));
                final Resolver.Plan plan = Resolver.resolve(r, mc, loader, db.installedProjects(), src, null);
                Set<String> ids = new HashSet<>();
                if (plan.main != null) ids.add(plan.main.projectId);
                for (Resolver.Item i : plan.required) ids.add(i.projectId);
                for (Resolver.Item i : plan.optional) ids.add(i.projectId);
                for (Resolver.Installed i : plan.alreadyInstalled) ids.add(i.projectId);
                final Map<String, Project> details = new HashMap<>();
                try { details.putAll(src.details(ids)); } catch (Exception ignored) { }
                act.runOnUiThread(() -> {
                    wait.dismiss();
                    DependencyDialog.show(act, mainProject, plan, details, mc, loader,
                            chosen -> proceed(act, r, mc, loader, chosen, src, onDone));
                });
            } catch (Exception e) {
                final String m = e.getMessage() == null ? e.toString() : e.getMessage();
                act.runOnUiThread(() -> {
                    wait.dismiss();
                    new AlertDialog.Builder(act).setTitle("Could not check dependencies")
                            .setMessage(m + "\n\nNothing was installed.").setPositiveButton("OK", null).show();
                });
            }
        }, "azux-deps").start();
    }

    private static void proceed(final Activity act, final Release r, final String mc, final String loader,
                                final Set<String> chosen, final ModrinthSource src, final Runnable onDone) {
        final AlertDialog dlg = new AlertDialog.Builder(act).setMessage("Preparing...").setCancelable(false).show();
        new Thread(() -> {
            String title, msg;
            try {
                ModDb db = new ModDb(Paths.game(act));
                // resolve again with the optional dependencies the user chose (they bring their own requirements)
                Resolver.Plan plan = Resolver.resolve(r, mc, loader, db.installedProjects(), src, chosen);
                if (!plan.ok()) throw new Exception(plan.problems.get(0));
                msg = ModInstaller.install(act, plan, chosen, (m, done, total) ->
                        act.runOnUiThread(() -> dlg.setMessage(m + (total > 0 ? "   (" + Math.min(done + 1, total) + "/" + total + ")" : ""))));
                title = "Done";
            } catch (Exception e) {
                title = "Installation failed";
                msg = (e.getMessage() == null ? e.toString() : e.getMessage()) + "\n\nNothing was changed.";
            }
            final String t = title, m = msg;
            act.runOnUiThread(() -> {
                dlg.dismiss();
                new AlertDialog.Builder(act).setTitle(t).setMessage(m).setPositiveButton("OK", null).show();
                if (onDone != null) onDone.run();
            });
        }, "azux-mod-install").start();
    }
}
