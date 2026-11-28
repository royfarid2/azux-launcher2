package com.azux.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.azux.launcher.store.Project;
import com.azux.launcher.store.Resolver;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * "Mod Dependencies & Compatibility": shows what installing a mod will download, from the real Modrinth metadata.
 * Required dependencies are selected and locked, optional ones are ticked by the user, conflicts block the install.
 */
public final class DependencyDialog {
    private DependencyDialog() { }

    public interface Callback { void onConfirm(Set<String> optionalChosen); }

    private static final int REQ = 0xFF5ED3C6, OPT = 0xFF7FB8FF, WARN = 0xFFFFB74D;

    public static void show(final Activity act, final Project mainProject, final Resolver.Plan plan,
                            final Map<String, Project> details, String mc, String loader, final Callback cb) {
        final Dialog d = new Dialog(act);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(act, Ui.BG, 18));
        int pad = Ui.dpi(act, 16);
        card.setPadding(pad, pad, pad, pad);

        // ---- header
        LinearLayout head = new LinearLayout(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(act);
        icon.setBackground(Ui.round(act, Ui.CARD2, 10));
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Project mp = mainProject != null ? mainProject : details.get(plan.main == null ? "" : plan.main.projectId);
        if (mp != null && !mp.icon.isEmpty()) Images.load(act, icon, mp.icon);
        head.addView(icon, new LinearLayout.LayoutParams(Ui.dpi(act, 44), Ui.dpi(act, 44)));
        LinearLayout titles = new LinearLayout(act);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dpi(act, 12), 0, 0, 0);
        titles.addView(Ui.text(act, "Mod Dependencies & Compatibility", 17, Ui.TXT, true));
        String what = plan.main != null ? plan.main.title : "";
        TextView sub = Ui.text(act, what + "   -   Minecraft " + (mc.isEmpty() ? "?" : mc)
                + (loader.isEmpty() ? "" : "   -   " + cap(loader)), 12, Ui.DIM, false);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(sub);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(head);

        // ---- body
        final LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        final Set<String> chosen = new LinkedHashSet<>();

        if (!plan.problems.isEmpty()) {
            body.addView(section(act, "CANNOT INSTALL", Ui.BAD, plan.problems.size()));
            for (String s : plan.problems) body.addView(note(act, s, Ui.BAD, 0x33E57373));
        }
        if (!plan.required.isEmpty()) {
            body.addView(section(act, "REQUIRED DEPENDENCIES", REQ, plan.required.size()));
            for (Resolver.Item it : plan.required) body.addView(depCard(act, it, details.get(it.projectId), "REQUIRED", REQ, null));
        }
        if (!plan.optional.isEmpty()) {
            body.addView(section(act, "OPTIONAL DEPENDENCIES", OPT, plan.optional.size()));
            for (final Resolver.Item it : plan.optional) {
                body.addView(depCard(act, it, details.get(it.projectId), "OPTIONAL", OPT, on -> {
                    if (on) chosen.add(it.projectId); else chosen.remove(it.projectId);
                }));
            }
        }
        if (!plan.alreadyInstalled.isEmpty()) {
            body.addView(section(act, "ALREADY INSTALLED", Ui.GOOD, plan.alreadyInstalled.size()));
            for (Resolver.Installed i : plan.alreadyInstalled) {
                Project p = details.get(i.projectId);
                body.addView(note(act, (p != null ? p.title : i.fileName) + "  -  " + i.fileName + " (no download needed)", Ui.GOOD, 0x2266BB6A));
            }
        }
        if (!plan.warnings.isEmpty()) {
            body.addView(section(act, "NOTES", WARN, plan.warnings.size()));
            for (String s : plan.warnings) body.addView(note(act, s, WARN, 0x33FFB74D));
        }
        if (plan.required.isEmpty() && plan.optional.isEmpty() && plan.problems.isEmpty()) {
            body.addView(note(act, "This mod has no dependencies. It is compatible with Minecraft " + mc
                    + (loader.isEmpty() ? "" : " and " + cap(loader)) + ".", Ui.TXT, 0x22FFFFFF));
        }
        ScrollView sv = new ScrollView(act);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(body);
        // the list scrolls when it is taller than the screen allows, otherwise the dialog fits its content
        final int w = Math.min(act.getResources().getDisplayMetrics().widthPixels - Ui.dpi(act, 32), Ui.dpi(act, 620));
        final int h = act.getResources().getDisplayMetrics().heightPixels - Ui.dpi(act, 24);
        body.measure(View.MeasureSpec.makeMeasureSpec(w - 2 * pad, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        LinearLayout.LayoutParams svl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(body.getMeasuredHeight(), Math.max(Ui.dpi(act, 80), h - Ui.dpi(act, 170))));
        svl.topMargin = Ui.dpi(act, 10);
        card.addView(sv, svl);

        // ---- buttons
        LinearLayout btns = new LinearLayout(act);
        btns.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView cancel = Ui.flat(act, plan.ok() ? "Cancel" : "Close");
        cancel.setOnClickListener(v -> d.dismiss());
        btns.addView(cancel);
        if (plan.ok()) {
            if (!plan.optional.isEmpty()) {
                TextView all = Ui.flat(act, "Download All");
                all.setOnClickListener(v -> {
                    Set<String> every = new HashSet<>();
                    for (Resolver.Item it : plan.optional) every.add(it.projectId);
                    d.dismiss();
                    cb.onConfirm(every);
                });
                LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                al.leftMargin = Ui.dpi(act, 8);
                btns.addView(all, al);
            }
            TextView go = Ui.text(act, plan.optional.isEmpty() && plan.required.isEmpty() ? "Install" : "Install Selected", 14, 0xFF0B1F1D, true);
            go.setGravity(Gravity.CENTER);
            go.setPadding(Ui.dpi(act, 18), Ui.dpi(act, 10), Ui.dpi(act, 18), Ui.dpi(act, 10));
            go.setBackground(Ui.round(act, Ui.TEAL, 8));
            Anim.press(go);
            go.setOnClickListener(v -> {
                d.dismiss();
                cb.onConfirm(new HashSet<>(chosen));
            });
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            gl.leftMargin = Ui.dpi(act, 8);
            btns.addView(go, gl);
        }
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Ui.dpi(act, 12);
        card.addView(btns, bl);

        d.setContentView(card);
        d.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        d.show();
        Anim.popIn(card);
    }

    private interface Toggle { void on(boolean checked); }

    private static View section(Activity act, String label, int color, int count) {
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dpi(act, 12), 0, Ui.dpi(act, 6));
        View dot = new View(act);
        dot.setBackground(Ui.round(act, color, 3));
        row.addView(dot, new LinearLayout.LayoutParams(Ui.dpi(act, 6), Ui.dpi(act, 6)));
        TextView t = Ui.text(act, label, 11, color, true);
        t.setLetterSpacing(0.12f);
        t.setPadding(Ui.dpi(act, 8), 0, 0, 0);
        row.addView(t);
        TextView n = Ui.text(act, "  " + count, 11, Ui.DIM, false);
        row.addView(n);
        return row;
    }

    private static View note(Activity act, String text, int color, int bg) {
        TextView t = Ui.text(act, text, 12, color, false);
        t.setPadding(Ui.dpi(act, 12), Ui.dpi(act, 9), Ui.dpi(act, 12), Ui.dpi(act, 9));
        t.setBackground(Ui.round(act, bg, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dpi(act, 6);
        t.setLayoutParams(lp);
        return t;
    }

    private static View depCard(Activity act, final Resolver.Item it, Project p, String type, int typeColor, final Toggle toggle) {
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = Ui.round(act, Ui.CARD, 12);
        bg.setStroke(Ui.dpi(act, 1), typeColor & 0x55FFFFFF | 0x33000000);
        row.setBackground(bg);
        int pd = Ui.dpi(act, 10);
        row.setPadding(pd, pd, pd, pd);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.bottomMargin = Ui.dpi(act, 6);
        row.setLayoutParams(rl);

        ImageView icon = new ImageView(act);
        icon.setBackground(Ui.round(act, Ui.CARD2, 8));
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (p != null && !p.icon.isEmpty()) Images.load(act, icon, p.icon);
        row.addView(icon, new LinearLayout.LayoutParams(Ui.dpi(act, 42), Ui.dpi(act, 42)));

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dpi(act, 10), 0, Ui.dpi(act, 8), 0);

        LinearLayout top = new LinearLayout(act);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = Ui.text(act, p != null && !p.title.isEmpty() ? p.title : it.title, 14, Ui.TXT, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(Ui.chip(act, type, (typeColor & 0x00FFFFFF) | 0x33000000, typeColor));
        col.addView(top);

        if (p != null && !p.description.isEmpty()) {
            TextView desc = Ui.text(act, p.description, 11, Ui.DIM, false);
            desc.setMaxLines(2);
            desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(desc);
        }

        LinearLayout chips = new LinearLayout(act);
        chips.setPadding(0, Ui.dpi(act, 5), 0, 0);
        if (p != null && p.downloads > 0) addChip(act, chips, compact(p.downloads) + " downloads", 0xFF2E2E2E, Ui.DIM);
        if (p != null) for (String l : p.loaders) addChip(act, chips, l, 0xFF2E2E2E, Ui.TXT);
        if (p != null && !p.tags.isEmpty()) addChip(act, chips, p.tags.get(0) + (p.tags.size() > 1 ? "+" : ""), 0xFF2E2E2E, Ui.TXT);
        addChip(act, chips, "Modrinth", 0xFF1F3D2B, 0xFF7DDC9B);
        col.addView(chips);

        TextView status = Ui.text(act, it.release.fileName + (it.release.size > 0 ? "  -  " + compact(it.release.size) + "B" : "")
                + (it.neededBy.isEmpty() ? "" : "  -  needed by " + android.text.TextUtils.join(", ", it.neededBy)), 10, Ui.DIM, false);
        status.setPadding(0, Ui.dpi(act, 4), 0, 0);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(status);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // right side: locked tick for required, checkbox for optional
        final TextView box = new TextView(act);
        box.setGravity(Gravity.CENTER);
        box.setTypeface(Typeface.DEFAULT_BOLD);
        box.setTextSize(15);
        final int size = Ui.dpi(act, 28);
        if (toggle == null) {
            box.setText("\u2713");
            box.setTextColor(0xFF0B1F1D);
            box.setBackground(Ui.round(act, typeColor, 8));
            box.setAlpha(0.9f);
        } else {
            final boolean[] on = {false};
            paintBox(act, box, false, typeColor);
            row.setOnClickListener(v -> {
                on[0] = !on[0];
                paintBox(act, box, on[0], typeColor);
                toggle.on(on[0]);
            });
        }
        row.addView(box, new LinearLayout.LayoutParams(size, size));
        return row;
    }

    private static void paintBox(Activity act, TextView box, boolean on, int color) {
        GradientDrawable g = Ui.round(act, on ? color : Color.TRANSPARENT, 8);
        g.setStroke(Ui.dpi(act, 2), on ? color : 0x66A0A4A8);
        box.setBackground(g);
        box.setText(on ? "\u2713" : "");
        box.setTextColor(0xFF0B1F1D);
    }

    private static void addChip(Activity act, LinearLayout row, String text, int bg, int fg) {
        TextView c = Ui.chip(act, text, bg, fg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dpi(act, 5);
        row.addView(c, lp);
    }

    static String compact(long n) {
        if (n >= 1_000_000_000L) return String.format(java.util.Locale.US, "%.1fB", n / 1e9);
        if (n >= 1_000_000L) return String.format(java.util.Locale.US, "%.1fM", n / 1e6);
        if (n >= 1_000L) return String.format(java.util.Locale.US, "%.1fK", n / 1e3);
        return String.valueOf(n);
    }

    private static String cap(String s) {
        if (s.equalsIgnoreCase("neoforge")) return "NeoForge";
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
