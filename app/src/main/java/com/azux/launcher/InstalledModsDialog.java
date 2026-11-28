package com.azux.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.azux.launcher.core.Paths;
import com.azux.launcher.store.ModDb;
import com.azux.launcher.store.ModrinthSource;
import com.azux.launcher.store.Release;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Installed mods of the game folder: enable / disable, delete (with a warning when others need it), updates, duplicates. */
public final class InstalledModsDialog {
    private InstalledModsDialog() { }

    public static void show(final Activity act, final String mc, final String loader) {
        final Dialog d = new Dialog(act);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        final LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(act, Ui.BG, 18));
        int pad = Ui.dpi(act, 16);
        card.setPadding(pad, pad, pad, pad);

        LinearLayout head = new LinearLayout(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(act);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.text(act, "Installed Mods", 17, Ui.TXT, true));
        titles.addView(Ui.text(act, "Minecraft " + (mc.isEmpty() ? "?" : mc) + (loader.isEmpty() ? "" : "  -  " + loader), 12, Ui.DIM, false));
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView check = Ui.flat(act, "Check updates");
        head.addView(check);
        card.addView(head);

        final LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(act);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(list);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(Ui.dpi(act, 330), act.getResources().getDisplayMetrics().heightPixels - Ui.dpi(act, 170)));
        sl.topMargin = Ui.dpi(act, 10);
        card.addView(sv, sl);

        TextView close = Ui.flat(act, "Close");
        close.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.gravity = Gravity.END;
        cl.topMargin = Ui.dpi(act, 12);
        card.addView(close, cl);

        final Map<String, Release> updates = new HashMap<>();
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> fill(act, d, list, updates, mc, loader, refresh[0]);
        check.setOnClickListener(v -> {
            check.setText("Checking...");
            new Thread(() -> {
                ModrinthSource src = new ModrinthSource();
                ModDb db = new ModDb(Paths.game(act));
                updates.clear();
                for (ModDb.Entry e : db.list()) {
                    if (e.projectId.isEmpty() || !e.enabled) continue;
                    try {
                        Release best = src.best(e.projectId, mc, loader);
                        if (best != null && !best.id.equals(e.versionId)) updates.put(e.file, best);
                    } catch (Exception ignored) { }
                }
                act.runOnUiThread(() -> {
                    check.setText(updates.isEmpty() ? "All up to date" : updates.size() + " update(s)");
                    refresh[0].run();
                });
            }).start();
        });

        refresh[0].run();
        d.setContentView(card);
        int w = Math.min(act.getResources().getDisplayMetrics().widthPixels - Ui.dpi(act, 32), Ui.dpi(act, 620));
        d.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        d.show();
        Anim.popIn(card);
    }

    private static void fill(final Activity act, final Dialog d, final LinearLayout list, final Map<String, Release> updates,
                             final String mc, final String loader, final Runnable again) {
        list.removeAllViews();
        final ModDb db = new ModDb(Paths.game(act));
        List<ModDb.Entry> mods = db.list();
        List<String> dups = db.duplicates();
        for (String dup : dups) list.addView(banner(act, "Duplicate: " + dup, 0xFFFFB74D, 0x33FFB74D));
        if (mods.isEmpty()) {
            list.addView(banner(act, "No mods installed yet. Open a mod in Downloads and press Install.", Ui.DIM, 0x22FFFFFF));
            return;
        }
        for (final ModDb.Entry e : mods) {
            LinearLayout row = new LinearLayout(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.round(act, Ui.CARD, 12));
            int pd = Ui.dpi(act, 10);
            row.setPadding(pd, pd, pd, pd);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rl.bottomMargin = Ui.dpi(act, 6);
            row.setLayoutParams(rl);

            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout top = new LinearLayout(act);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = Ui.text(act, e.title, 14, e.enabled ? Ui.TXT : Ui.DIM, true);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            String kind = e.projectId.isEmpty() ? "MANUAL" : e.dependency ? "DEPENDENCY" : "MOD";
            top.addView(Ui.chip(act, kind, 0xFF2E2E2E, e.dependency ? 0xFF7FB8FF : Ui.DIM));
            col.addView(top);
            TextView info = Ui.text(act, e.file + (e.version.isEmpty() ? "" : "  -  " + e.version)
                    + (e.neededBy.isEmpty() ? "" : "  -  needed by " + android.text.TextUtils.join(", ", e.neededBy)), 10, Ui.DIM, false);
            info.setSingleLine(true);
            info.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            col.addView(info);
            final Release up = updates.get(e.file);
            if (up != null) {
                TextView upd = Ui.text(act, "Update available: " + up.name, 11, Ui.TEAL, true);
                upd.setPadding(0, Ui.dpi(act, 4), 0, 0);
                upd.setOnClickListener(v -> {
                    d.dismiss();
                    ModFlow.install(act, null, up, mc, loader, null);
                });
                col.addView(upd);
            }
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Switch sw = new Switch(act);
            sw.setChecked(e.enabled);
            sw.setOnCheckedChangeListener((b, on) -> {
                try { db.setEnabled(e, on); } catch (Exception ex) { /* shown by the next refresh */ }
                again.run();
            });
            row.addView(sw);

            TextView del = Ui.text(act, "Delete", 12, Ui.BAD, true);
            del.setPadding(Ui.dpi(act, 12), Ui.dpi(act, 6), Ui.dpi(act, 4), Ui.dpi(act, 6));
            del.setOnClickListener(v -> {
                List<String> need = db.neededByOthers(e);
                String msg = need.isEmpty() ? "Delete " + e.title + "?" : e.title + " is required by: " + android.text.TextUtils.join(", ", need)
                        + ".\nThey will stop working without it. Delete anyway?";
                new AlertDialog.Builder(act).setTitle("Delete mod").setMessage(msg)
                        .setPositiveButton("Delete", (x, w) -> { try { db.delete(e); } catch (Exception ex) { } again.run(); })
                        .setNegativeButton("Cancel", null).show();
            });
            row.addView(del);
            list.addView(row);
        }
    }

    private static View banner(Activity act, String text, int color, int bg) {
        TextView t = Ui.text(act, text, 12, color, false);
        t.setPadding(Ui.dpi(act, 12), Ui.dpi(act, 9), Ui.dpi(act, 12), Ui.dpi(act, 9));
        t.setBackground(Ui.round(act, bg, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dpi(act, 6);
        t.setLayoutParams(lp);
        return t;
    }
}
