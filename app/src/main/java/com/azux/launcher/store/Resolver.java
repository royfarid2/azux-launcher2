package com.azux.launcher.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recursive dependency resolver for Modrinth releases. Pure Java so it can be unit-tested on a desktop JVM.
 *
 * Reads the dependency types exactly as Modrinth reports them:
 *   required     - must be installed (resolved recursively, installed first)
 *   optional     - offered to the user, never installed without consent
 *   incompatible - blocks the installation when that project is installed or about to be installed
 *   embedded     - already bundled inside the mod file, ignored
 */
public final class Resolver {
    private Resolver() { }

    public static final int MAX_DEPTH = 16;

    /** Where releases come from (the Modrinth API in the app, a fake in the tests). */
    public interface Source {
        /** Newest compatible release of a project (release > beta > alpha), or null if there is none. */
        Release best(String projectId, String mc, String loader) throws Exception;

        /** A specific version, or null. */
        Release byId(String versionId) throws Exception;

        /** Display name of a project (for messages). */
        default String title(String projectId) { return projectId; }
    }

    public static final class Installed {
        public final String projectId, versionId, fileName;
        public Installed(String projectId, String versionId, String fileName) {
            this.projectId = projectId; this.versionId = versionId; this.fileName = fileName;
        }
    }

    public static final class Item {
        public final String projectId;
        public final Release release;
        /** Project name for messages and the dialog (release.name is only the version number). */
        public String title;
        /** Names of the mods that need this one. */
        public final List<String> neededBy = new ArrayList<>();
        public Item(String projectId, Release release, String title) { this.projectId = projectId; this.release = release; this.title = title; }
    }

    public static final class Plan {
        public Item main;
        /** Required dependencies (and optional ones the user chose), in install order: dependencies before dependents. */
        public final List<Item> required = new ArrayList<>();
        /** Optional dependencies that are not installed yet; the user decides. */
        public final List<Item> optional = new ArrayList<>();
        /** Required dependencies that are already there, so nothing is downloaded. */
        public final List<Installed> alreadyInstalled = new ArrayList<>();
        /** Blocking problems: the installation must not start. */
        public final List<String> problems = new ArrayList<>();
        /** Things worth telling the user that do not block. */
        public final List<String> warnings = new ArrayList<>();

        public boolean ok() { return problems.isEmpty(); }
    }

    public static boolean compatible(Release r, String mc, String loader) {
        return r != null && r.supportsGame(mc) && r.supportsLoader(loader);
    }

    /**
     * @param main      the release the user wants
     * @param mc        Minecraft version of the profile
     * @param loader    loader of the profile ("fabric", "quilt", "forge", "neoforge" or "" for none)
     * @param installed projectId -> what is installed now
     * @param chosen    project ids of optional dependencies the user ticked
     */
    public static Plan resolve(Release main, String mc, String loader, Map<String, Installed> installed,
                               Source src, Set<String> chosen) throws Exception {
        Plan plan = new Plan();
        String mainProject0 = main.projectId.isEmpty() ? main.id : main.projectId;
        String mainTitle = label(src, mainProject0, main);
        if (!compatible(main, mc, loader)) {
            plan.problems.add(mainTitle + " does not support Minecraft " + mc + (loader.isEmpty() ? "" : " with " + loader) + ".");
            return plan;
        }
        Ctx c = new Ctx(plan, mc, loader, installed, src, chosen == null ? new HashSet<String>() : chosen);
        String mainProject = main.projectId.isEmpty() ? main.id : main.projectId;
        plan.main = new Item(mainProject, main, mainTitle);
        c.planned.put(mainProject, plan.main);
        c.stack.add(mainProject);
        visit(c, main, mainProject, 0);
        c.stack.remove(mainProject);

        // optional dependencies the user chose are resolved like required ones (with their own dependencies)
        for (String id : new ArrayList<>(c.optionalFound.keySet())) {
            if (!c.chosen.contains(id) || c.planned.containsKey(id)) continue;
            Item o = c.optionalFound.get(id);
            c.stack.add(id);
            c.planned.put(id, o);
            visit(c, o.release, id, 1);
            c.stack.remove(id);
            plan.required.add(o);
        }
        for (Item o : c.optionalFound.values()) {
            if (!c.planned.containsKey(o.projectId)) plan.optional.add(o);
        }

        // incompatible projects that ended up in the plan or are installed
        for (Map.Entry<String, String> e : c.incompatible.entrySet()) {
            if (c.planned.containsKey(e.getKey()) && !e.getKey().equals(mainProject)) {
                plan.problems.add(e.getValue() + " is incompatible with a mod that is part of this installation.");
            } else if (installed.containsKey(e.getKey())) {
                plan.problems.add(e.getValue() + " is installed (" + installed.get(e.getKey()).fileName + ") but is incompatible with "
                        + mainTitle + ". Remove it first.");
            }
        }
        return plan;
    }

    // ------------------------------------------------------------------ internals
    private static final class Ctx {
        final Plan plan;
        final String mc, loader;
        final Map<String, Installed> installed;
        final Source src;
        final Set<String> chosen;
        final Map<String, Item> planned = new LinkedHashMap<>();       // everything that will be installed (incl. main)
        final Map<String, Item> optionalFound = new LinkedHashMap<>();
        final Map<String, String> incompatible = new HashMap<>();      // projectId -> name of the mod that forbids it
        final List<String> stack = new ArrayList<>();                  // projects being resolved (cycle detection)

        Ctx(Plan plan, String mc, String loader, Map<String, Installed> installed, Source src, Set<String> chosen) {
            this.plan = plan; this.mc = mc; this.loader = loader; this.installed = installed; this.src = src; this.chosen = chosen;
        }
    }

    private static void visit(Ctx c, Release r, String ownerProject, int depth) throws Exception {
        if (depth > MAX_DEPTH) {
            c.plan.problems.add("Dependency chain of " + (c.planned.containsKey(ownerProject) ? c.planned.get(ownerProject).title : r.name) + " is deeper than " + MAX_DEPTH + " levels; stopped.");
            return;
        }
        Item self = c.planned.get(ownerProject);
        String owner = self != null ? self.title : r.name;
        for (Release.Dep d : r.deps) {
            String type = d.type.toLowerCase();
            String pid = d.projectId;
            if (pid.isEmpty() && !d.versionId.isEmpty()) {
                Release pinned = c.src.byId(d.versionId);   // dependency given only by version id
                if (pinned != null) pid = pinned.projectId;
            }
            if (pid.isEmpty() || type.equals("embedded")) continue;

            if (type.equals("incompatible")) {
                c.incompatible.put(pid, c.src.title(pid));
                continue;
            }
            Installed have = c.installed.get(pid);
            if (type.equals("optional")) {
                if (have != null || c.planned.containsKey(pid) || c.optionalFound.containsKey(pid)) continue;
                Release o = pick(c, d, pid);
                if (o != null && compatible(o, c.mc, c.loader)) {
                    Item it = new Item(pid, o, label(c.src, pid, o));
                    it.neededBy.add(owner);
                    c.optionalFound.put(pid, it);
                } else {
                    c.plan.warnings.add("Optional dependency " + c.src.title(pid) + " has no version for Minecraft " + c.mc + " - skipped.");
                }
                continue;
            }

            // required
            if (have != null) {
                if (!d.versionId.isEmpty() && !d.versionId.equals(have.versionId)) {
                    c.plan.warnings.add(owner + " asks for a specific version of " + have.fileName + " (installed: another version).");
                }
                boolean known = false;
                for (Installed i : c.plan.alreadyInstalled) if (i.projectId.equals(pid)) known = true;
                if (!known) c.plan.alreadyInstalled.add(have);
                continue;
            }
            Item existing = c.planned.get(pid);
            if (existing != null) {
                if (!d.versionId.isEmpty() && !existing.release.id.isEmpty() && !d.versionId.equals(existing.release.id)) {
                    c.plan.problems.add("Conflicting requirements: " + owner + " needs version " + d.versionId + " of "
                            + existing.title + " but another mod needs " + existing.release.id + ".");
                }
                if (!existing.neededBy.contains(owner)) existing.neededBy.add(owner);
                if (c.stack.contains(pid)) c.plan.warnings.add("Circular dependency between " + owner + " and " + existing.title + " (ignored).");
                continue;
            }
            Release dep = pick(c, d, pid);
            if (dep == null) {
                c.plan.problems.add("Required dependency " + c.src.title(pid) + " of " + owner + " has no version for Minecraft " + c.mc
                        + (c.loader.isEmpty() ? "" : " with " + c.loader) + ".");
                continue;
            }
            if (!compatible(dep, c.mc, c.loader)) {
                c.plan.problems.add("Required dependency " + label(c.src, pid, dep) + " of " + owner + " is not compatible with Minecraft " + c.mc
                        + (c.loader.isEmpty() ? "" : " / " + c.loader) + ".");
                continue;
            }
            if (dep.url.isEmpty()) {
                c.plan.problems.add("Required dependency " + label(c.src, pid, dep) + " cannot be downloaded directly (blocked by its author).");
                continue;
            }
            Item it = new Item(pid, dep, label(c.src, pid, dep));
            it.neededBy.add(owner);
            c.planned.put(pid, it);
            c.stack.add(pid);
            visit(c, dep, pid, depth + 1);
            c.stack.remove(pid);
            c.plan.required.add(it);          // after its own dependencies: valid install order
        }
    }

    /** Project name if the source knows it, else the release's own name. */
    private static String label(Source src, String projectId, Release r) {
        String t = src.title(projectId);
        return t == null || t.isEmpty() || t.equals(projectId) ? (r.name.isEmpty() ? projectId : r.name) : t;
    }

    private static Release pick(Ctx c, Release.Dep d, String projectId) throws Exception {
        if (!d.versionId.isEmpty()) {
            Release r = c.src.byId(d.versionId);
            if (r != null) return r;
        }
        return c.src.best(projectId, c.mc, c.loader);
    }
}
