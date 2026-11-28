package com.azux.launcher.store;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The real Modrinth as a dependency source, with a small per-session cache so one install does few requests. */
public final class ModrinthSource implements Resolver.Source {
    private final Map<String, Release> bestCache = new HashMap<>(), versionCache = new HashMap<>();
    private final Map<String, Project> projects = new HashMap<>();

    @Override
    public Release best(String projectId, String mc, String loader) throws Exception {
        String key = projectId + "|" + mc + "|" + loader;
        if (bestCache.containsKey(key)) return bestCache.get(key);
        List<Release> l = Modrinth.versionsFor(projectId, mc, loader);
        Release r = Modrinth.best(l);
        bestCache.put(key, r);
        return r;
    }

    @Override
    public Release byId(String versionId) throws Exception {
        if (versionCache.containsKey(versionId)) return versionCache.get(versionId);
        Release r = Modrinth.version(versionId);
        versionCache.put(versionId, r);
        return r;
    }

    @Override
    public String title(String projectId) {
        Project p = projects.get(projectId);
        if (p == null) {
            try {
                projects.putAll(Modrinth.projects(java.util.Collections.singletonList(projectId)));
                p = projects.get(projectId);
            } catch (Exception ignored) { }
        }
        return p == null || p.title.isEmpty() ? projectId : p.title;
    }

    /** Project details (icon, description, downloads, loaders) for the dependency dialog. */
    public Map<String, Project> details(java.util.Collection<String> ids) throws Exception {
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String id : ids) if (!projects.containsKey(id)) missing.add(id);
        if (!missing.isEmpty()) projects.putAll(Modrinth.projects(missing));
        Map<String, Project> out = new HashMap<>();
        for (String id : ids) if (projects.containsKey(id)) out.put(id, projects.get(id));
        return out;
    }
}
