package com.azux.launcher.store;

import android.content.Context;

import com.azux.launcher.core.Net;
import com.azux.launcher.core.Paths;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Installs a resolved plan safely: every file is downloaded into a staging folder and verified first; only when ALL
 * of them are fine they are moved into the mods folder. If anything fails, nothing is changed (files that were
 * replaced are put back). The installed-mod database is updated at the end.
 */
public final class ModInstaller {
    private ModInstaller() { }

    public interface Progress { void update(String message, int done, int total); }

    /** @param optionalChosen project ids of optional dependencies the user ticked (already inside plan.required) */
    public static String install(Context ctx, Resolver.Plan plan, Set<String> optionalChosen, Progress prog) throws Exception {
        if (!plan.ok()) throw new Exception(plan.problems.get(0));
        ModDb db = new ModDb(Paths.game(ctx));
        File mods = db.modsDir();

        List<Resolver.Item> all = new ArrayList<>(plan.required);
        all.add(plan.main);   // dependencies first, the mod itself last
        File stage = new File(Paths.cache(ctx), "stage_" + System.currentTimeMillis());
        stage.mkdirs();
        File backup = new File(stage, "old");
        backup.mkdirs();
        List<File> placed = new ArrayList<>();
        List<File[]> moved = new ArrayList<>();      // {original, backup}
        try {
            // 1) download + verify everything into the staging folder
            int n = all.size();
            for (int i = 0; i < n; i++) {
                Resolver.Item it0 = all.get(i);
                Release r = it0.release;
                if (r.url.isEmpty()) throw new Exception(it0.title + " cannot be downloaded directly.");
                prog.update("Downloading " + r.fileName, i, n);
                File f = new File(stage, safe(r.fileName));
                Net.download(r.url, f);
                if (!r.sha1.isEmpty() && !r.sha1.equalsIgnoreCase(Net.sha1(f))) {
                    throw new Exception("Checksum mismatch for " + r.fileName + " - nothing was installed.");
                }
                if (r.size > 0 && f.length() != r.size) throw new Exception("Incomplete download of " + r.fileName + " - nothing was installed.");
            }

            // 2) move into the mods folder; older versions of the same project are kept in the backup until it all worked
            prog.update("Installing", n, n);
            for (Resolver.Item it : all) {
                Release r = it.release;
                ModDb.Entry old = db.byProject(it.projectId);
                if (old != null) {
                    File of = new File(mods, old.file);
                    if (of.exists()) {
                        File bk = new File(backup, old.file);
                        if (!of.renameTo(bk)) throw new Exception("Could not replace " + old.file);
                        moved.add(new File[]{of, bk});
                    }
                }
                File dest = new File(mods, safe(r.fileName));
                if (dest.exists()) {
                    File bk = new File(backup, dest.getName() + ".same");
                    if (!dest.renameTo(bk)) throw new Exception("Could not replace " + dest.getName());
                    moved.add(new File[]{dest, bk});
                }
                File src = new File(stage, safe(r.fileName));
                if (!src.renameTo(dest)) throw new Exception("Could not install " + r.fileName);
                placed.add(dest);
            }

            // 3) database
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            for (Resolver.Item it : all) {
                ModDb.Entry old = db.byProject(it.projectId);
                if (old != null) db.delete(old);
                ModDb.Entry e = new ModDb.Entry();
                e.file = safe(it.release.fileName);
                e.projectId = it.projectId;
                e.versionId = it.release.id;
                e.version = it.release.name;
                e.title = it.title;
                e.date = today;
                e.dependency = it != plan.main && !optionalChosen.contains(it.projectId);
                e.neededBy.addAll(it.neededBy);
                db.put(e);
            }
            db.save();
        } catch (Exception ex) {
            // rollback: remove what was placed, put the old files back
            for (File f : placed) f.delete();
            for (File[] m : moved) m[1].renameTo(m[0]);
            throw ex;
        } finally {
            deleteTree(stage);
        }
        StringBuilder sb = new StringBuilder("Installed " + plan.main.title);
        if (all.size() > 1) sb.append(" and ").append(all.size() - 1).append(all.size() == 2 ? " dependency" : " dependencies");
        return sb.toString();
    }

    static String safe(String name) { return name.replaceAll("[\\\\/:*?\"<>|]", "_"); }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
