import com.azux.launcher.store.Release;
import com.azux.launcher.store.Resolver;
import com.azux.launcher.store.Resolver.*;

import java.util.*;

/** Desktop test of the dependency resolver with a fake Modrinth. Run by tools/tests/run.sh. */
public class ResolverTest {
    static int fails = 0;

    static void check(boolean ok, String what) {
        if (!ok) { fails++; System.out.println("FAIL: " + what); } else System.out.println("ok:   " + what);
    }

    static final class Fake implements Source {
        final Map<String, Release> byProject = new HashMap<>(), byVersion = new HashMap<>();
        Release add(String project, String name, String mc, String loader, Release.Dep... deps) {
            Release r = new Release();
            r.projectId = project; r.id = project + "-v1"; r.name = name; r.fileName = name + ".jar"; r.url = "https://x/" + name;
            r.gameVersions.add(mc); r.loaders.add(loader);
            r.deps.addAll(Arrays.asList(deps));
            byProject.put(project, r); byVersion.put(r.id, r);
            return r;
        }
        public Release best(String p, String mc, String l) { Release r = byProject.get(p); return r != null && Resolver.compatible(r, mc, l) ? r : null; }
        public Release byId(String v) { return byVersion.get(v); }
        public String title(String p) { Release r = byProject.get(p); return r == null ? p : r.name; }
    }

    static Release.Dep req(String p) { return new Release.Dep(p, null, "required"); }
    static Release.Dep opt(String p) { return new Release.Dep(p, null, "optional"); }
    static Release.Dep inc(String p) { return new Release.Dep(p, null, "incompatible"); }
    static Release.Dep emb(String p) { return new Release.Dep(p, null, "embedded"); }

    static List<String> names(List<Item> l) { List<String> o = new ArrayList<>(); for (Item i : l) o.add(i.release.name); return o; }

    public static void main(String[] a) throws Exception {
        Map<String, Installed> none = new HashMap<>();

        // 1. A -> B -> C : discovered recursively, dependencies installed first
        Fake f = new Fake();
        Release A = f.add("A", "A", "1.21.4", "fabric", req("B"));
        f.add("B", "B", "1.21.4", "fabric", req("C"));
        f.add("C", "C", "1.21.4", "fabric");
        Plan p = Resolver.resolve(A, "1.21.4", "fabric", none, f, null);
        check(p.ok() && names(p.required).equals(Arrays.asList("C", "B")), "recursive chain installs C then B (got " + names(p.required) + ")");

        // 2. optional stays optional; chosen optional brings its own required dependency
        f = new Fake();
        A = f.add("A", "Xaero", "1.21.4", "fabric", req("FAPI"), opt("OPAC"));
        f.add("FAPI", "Fabric API", "1.21.4", "fabric");
        f.add("OPAC", "Open Parties and Claims", "1.21.4", "fabric", req("LIB"));
        f.add("LIB", "Lib", "1.21.4", "fabric");
        p = Resolver.resolve(A, "1.21.4", "fabric", none, f, null);
        check(names(p.required).equals(Arrays.asList("Fabric API")) && names(p.optional).equals(Arrays.asList("Open Parties and Claims")),
                "optional is not installed unless chosen (req " + names(p.required) + ", opt " + names(p.optional) + ")");
        p = Resolver.resolve(A, "1.21.4", "fabric", none, f, new HashSet<>(Arrays.asList("OPAC")));
        check(names(p.required).equals(Arrays.asList("Fabric API", "Lib", "Open Parties and Claims")) && p.optional.isEmpty(),
                "chosen optional pulls its own dependency first (got " + names(p.required) + ")");

        // 3. circular dependency does not loop
        f = new Fake();
        A = f.add("A", "A", "1.21.4", "fabric", req("B"));
        f.add("B", "B", "1.21.4", "fabric", req("A"));
        p = Resolver.resolve(A, "1.21.4", "fabric", none, f, null);
        check(p.ok() && names(p.required).equals(Arrays.asList("B")) && !p.warnings.isEmpty(), "circular dependency is detected and ignored");

        // 4. incompatible mod installed -> blocked
        f = new Fake();
        A = f.add("A", "A", "1.21.4", "fabric", inc("BAD"));
        f.add("BAD", "Bad", "1.21.4", "fabric");
        Map<String, Installed> inst = new HashMap<>();
        inst.put("BAD", new Installed("BAD", "BAD-v1", "bad.jar"));
        p = Resolver.resolve(A, "1.21.4", "fabric", inst, f, null);
        check(!p.ok() && p.problems.get(0).contains("Bad"), "incompatible installed mod blocks installation");
        p = Resolver.resolve(A, "1.21.4", "fabric", none, f, null);
        check(p.ok() && p.required.isEmpty(), "incompatible dependency is never installed");

        // 5. required dependency without compatible version -> blocked, nothing to install
        f = new Fake();
        A = f.add("A", "A", "1.21.4", "fabric", req("OLD"));
        f.add("OLD", "Old", "1.16.5", "fabric");
        p = Resolver.resolve(A, "1.21.4", "fabric", none, f, null);
        check(!p.ok(), "required dependency with wrong Minecraft version blocks");

        // 6. already installed required dependency is not downloaded again
        f = new Fake();
        A = f.add("A", "A", "1.21.4", "fabric", req("FAPI"));
        f.add("FAPI", "Fabric API", "1.21.4", "fabric");
        inst = new HashMap<>();
        inst.put("FAPI", new Installed("FAPI", "FAPI-v1", "fabric-api.jar"));
        p = Resolver.resolve(A, "1.21.4", "fabric", inst, f, null);
        check(p.ok() && p.required.isEmpty() && p.alreadyInstalled.size() == 1, "installed dependency is reused");

        // 7. two mods pin different versions of the same dependency -> conflict
        f = new Fake();
        A = f.add("A", "A", "1.21.4", "fabric", req("B"), req("C"));
        f.add("B", "B", "1.21.4", "fabric", new Release.Dep("L", "L-v1", "required"));
        f.add("C", "C", "1.21.4", "fabric", new Release.Dep("L", "L-v2", "required"));
        Release l1 = f.add("L", "L", "1.21.4", "fabric");
        Release l2 = new Release(); l2.projectId = "L"; l2.id = "L-v2"; l2.name = "L2"; l2.url = "u"; l2.gameVersions.add("1.21.4"); l2.loaders.add("fabric");
        f.byVersion.put("L-v2", l2);
        p = Resolver.resolve(A, "1.21.4", "fabric", none, f, null);
        check(!p.ok(), "conflicting pinned versions are reported");

        // 8. embedded is ignored
        f = new Fake();
        A = f.add("A", "A", "1.21.4", "fabric", emb("JIJ"));
        p = Resolver.resolve(A, "1.21.4", "fabric", none, f, null);
        check(p.ok() && p.required.isEmpty() && p.optional.isEmpty(), "embedded dependency is ignored");

        // 9. wrong loader / version of the main mod
        f = new Fake();
        A = f.add("A", "A", "1.21.4", "forge");
        check(!Resolver.resolve(A, "1.21.4", "fabric", none, f, null).ok(), "main mod for another loader is refused");
        check(!Resolver.resolve(A, "1.20.1", "forge", none, f, null).ok(), "main mod for another Minecraft version is refused");

        // 10. very long chain stops
        f = new Fake();
        A = f.add("M0", "M0", "1.21.4", "fabric", req("M1"));
        for (int i = 1; i < 25; i++) f.add("M" + i, "M" + i, "1.21.4", "fabric", req("M" + (i + 1)));
        f.add("M25", "M25", "1.21.4", "fabric");
        p = Resolver.resolve(A, "1.21.4", "fabric", none, f, null);
        check(!p.ok(), "chain deeper than " + Resolver.MAX_DEPTH + " stops instead of looping");

        // 11. Quilt runs Fabric mods
        f = new Fake();
        A = f.add("A", "A", "1.21.4", "fabric");
        check(Resolver.resolve(A, "1.21.4", "quilt", none, f, null).ok(), "Quilt accepts Fabric mods");

        // 12. a dependency shared by two mods is installed once
        f = new Fake();
        A = f.add("A", "A", "1.21.4", "fabric", req("B"), req("C"));
        f.add("B", "B", "1.21.4", "fabric", req("L"));
        f.add("C", "C", "1.21.4", "fabric", req("L"));
        f.add("L", "L", "1.21.4", "fabric");
        p = Resolver.resolve(A, "1.21.4", "fabric", none, f, null);
        check(p.ok() && names(p.required).equals(Arrays.asList("L", "B", "C")), "shared dependency installed once (got " + names(p.required) + ")");

        System.out.println(fails == 0 ? "resolver test: all checks passed" : "resolver test: " + fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
