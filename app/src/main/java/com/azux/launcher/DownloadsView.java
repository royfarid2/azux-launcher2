package com.azux.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.azux.launcher.core.Heads;
import com.azux.launcher.store.Category;
import com.azux.launcher.store.CurseForge;
import com.azux.launcher.store.Installer;
import com.azux.launcher.store.Modrinth;
import com.azux.launcher.store.Project;
import com.azux.launcher.store.Release;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Downloads screen: search Modrinth / CurseForge, open a project, install a file. */
public final class DownloadsView extends LinearLayout {
    public interface Host {
        void onReturn();
        void pickLocalFile(Category c);
    }

    private static final int PAGE = 20;

    private final Activity act;
    private final SharedPreferences prefs;
    private final Host host;
    private final Supplier<List<String>> mcVersions;

    private Category cat = Category.MOD;
    private String platform = "modrinth", mc = "", query = "";
    private final List<Project> items = new ArrayList<>();
    private int offset, gen;
    private boolean loading, hasMore = true;

    private Project detail;
    private final List<Release> all = new ArrayList<>(), shown = new ArrayList<>();
    private boolean officialOnly = true;
    private boolean releasesLoading;
    private String error = "";
    private final java.util.BitSet animated = new java.util.BitSet();

    private final FrameLayout left;
    private final FrameLayout right;
    private BaseAdapter adapter;
    private TextView status;

    public DownloadsView(Activity act, SharedPreferences prefs, Host host, Supplier<List<String>> mcVersions) {
        super(act);
        this.act = act;
        this.prefs = prefs;
        this.host = host;
        this.mcVersions = mcVersions;
        setOrientation(HORIZONTAL);
        left = new FrameLayout(act);
        right = new FrameLayout(act);
        addView(left, new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        addView(right, new LayoutParams(Ui.dpi(act, 200), ViewGroup.LayoutParams.MATCH_PARENT));
        render();
        reload();
    }

    public void setCategory(Category c) {
        if (c == cat && detail == null) return;
        cat = c;
        detail = null;
        render();
        reload();
    }

    public Category category() { return cat; }

    public void installLocal(Uri uri, String name) {
        new Thread(() -> {
            try (InputStream in = act.getContentResolver().openInputStream(uri)) {
                String msg = Installer.installLocal(act, cat, in, name);
                act.runOnUiThread(() -> Toast.makeText(act, msg, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                act.runOnUiThread(() -> Toast.makeText(act, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ------------------------------------------------------------------ search
    private void reload() {
        gen++;
        animated.clear();
        items.clear();
        offset = 0;
        hasMore = true;
        loading = false;
        error = "";
        loadMore();
        notifyAdapter();
    }

    private void loadMore() {
        if (loading || !hasMore || detail != null) return;
        loading = true;
        final int g = gen;
        final String q = query, v = mc, plat = platform;
        final Category c = cat;
        final int off = offset;
        new Thread(() -> {
            List<Project> res = null;
            String err = "";
            try {
                if (plat.equals("modrinth")) {
                    res = Modrinth.search(c, q, v, off, PAGE);
                } else {
                    String key = prefs.getString("cf_key", "").trim();
                    if (key.isEmpty()) throw new Exception("CurseForge needs a free API key.\nGet one at console.curseforge.com and paste it in Settings.");
                    res = CurseForge.search(key, c, q, v, off, PAGE);
                }
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final List<Project> r = res;
            final String e = err;
            act.runOnUiThread(() -> {
                if (g != gen) return;
                loading = false;
                if (r != null) {
                    items.addAll(r);
                    offset += r.size();
                    hasMore = r.size() >= PAGE;
                } else {
                    hasMore = false;
                    error = e;
                }
                notifyAdapter();
            });
        }).start();
    }

    private void notifyAdapter() {
        if (adapter != null) adapter.notifyDataSetChanged();
        if (status != null && detail == null) {
            if (!error.isEmpty()) { status.setText(error); status.setVisibility(VISIBLE); }
            else if (loading && items.isEmpty()) { status.setText("Loading..."); status.setVisibility(VISIBLE); }
            else if (items.isEmpty()) {
                status.setText(platform.equals("modrinth") && cat.modrinthType == null
                        ? cat.label + " downloads are not on Modrinth. Switch Search Platform to CurseForge."
                        : "No results.");
                status.setVisibility(VISIBLE);
            } else status.setVisibility(GONE);
        }
    }

    // ------------------------------------------------------------------ render
    private void render() {
        left.removeAllViews();
        right.removeAllViews();
        status = Ui.text(act, "", 13, Ui.DIM, false);
        status.setPadding(Ui.dpi(act, 16), Ui.dpi(act, 12), Ui.dpi(act, 16), Ui.dpi(act, 12));
        status.setVisibility(GONE);
        final ListView lv = new ListView(act);
        lv.setDivider(null);
        lv.setPadding(Ui.dpi(act, 10), Ui.dpi(act, 4), Ui.dpi(act, 10), Ui.dpi(act, 10));
        lv.setClipToPadding(false);
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(VERTICAL);
        col.addView(status);
        col.addView(lv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        left.addView(col);
        if (detail == null) {
            adapter = projectAdapter();
            lv.setAdapter(adapter);
            lv.setOnItemClickListener((p, v, pos, id) -> openDetail(items.get(pos)));
            lv.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override public void onScrollStateChanged(AbsListView view, int s) { }
                @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                    if (total > 0 && first + visible >= total - 3) loadMore();
                }
            });
            right.addView(listPanel());
        } else {
            adapter = releaseAdapter();
            lv.setAdapter(adapter);
            lv.setOnItemClickListener((p, v, pos, id) -> confirmInstall(shown.get(pos)));
            right.addView(detailPanel());
        }
        notifyAdapter();
        if (detail != null) updateDetailStatus();
    }

    private ImageView icon(int dp) {
        ImageView iv = Heads.view(act, dp);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return iv;
    }

    private static String count(long n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.2f M", n / 1_000_000.0);
        if (n >= 1_000) return String.format(Locale.US, "%.2f K", n / 1_000.0);
        return String.valueOf(n);
    }

    // ------------------------------------------------------------------ project rows
    private BaseAdapter projectAdapter() {
        return new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int p) { return items.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                Project p = items.get(pos);
                LinearLayout card = new LinearLayout(act);
                card.setPadding(Ui.dpi(act, 10), Ui.dpi(act, 10), Ui.dpi(act, 10), Ui.dpi(act, 10));
                card.setBackground(Ui.round(act, Ui.CARD, 10));
                ImageView iv = icon(56);
                Images.load(act, iv, p.icon);
                LayoutParams ip = new LayoutParams(Ui.dpi(act, 56), Ui.dpi(act, 56));
                ip.rightMargin = Ui.dpi(act, 10);
                card.addView(iv, ip);

                LinearLayout col = new LinearLayout(act);
                col.setOrientation(VERTICAL);
                LinearLayout head = new LinearLayout(act);
                head.setGravity(Gravity.CENTER_VERTICAL);
                TextView title = Ui.text(act, p.title, 15, Ui.TXT, true);
                title.setSingleLine(true);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                head.addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                head.addView(Ui.chip(act, p.platform.equals("modrinth") ? "Modrinth" : "CurseForge",
                        p.platform.equals("modrinth") ? 0xFF1F3D2B : 0xFF4A2A1A, p.platform.equals("modrinth") ? 0xFF7DDC9B : 0xFFFFA874));
                col.addView(head);
                if (!p.tags.isEmpty()) {
                    LinearLayout tags = new LinearLayout(act);
                    for (String t : p.tags) {
                        LayoutParams tp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        tp.rightMargin = Ui.dpi(act, 4);
                        tp.topMargin = Ui.dpi(act, 3);
                        tags.addView(Ui.chip(act, t, Ui.FLAT, Ui.TXT), tp);
                    }
                    col.addView(tags);
                }
                TextView d = Ui.text(act, p.description, 12, 0xFFD0D0D0, false);
                d.setMaxLines(2);
                d.setEllipsize(android.text.TextUtils.TruncateAt.END);
                d.setPadding(0, Ui.dpi(act, 3), 0, 0);
                col.addView(d);
                col.addView(Ui.text(act, "Downloads: " + count(p.downloads) + "   Author: " + p.author + "   Creation Date: " + p.date, 10, Ui.DIM, false));
                if (!p.loaders.isEmpty()) {
                    col.addView(Ui.text(act, "Compatible With: " + join(p.loaders), 10, Ui.DIM, false));
                }
                card.addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                FrameLayout w = new FrameLayout(act);
                w.setPadding(0, 0, 0, Ui.dpi(act, 6));
                w.addView(card);
                if (!animated.get(pos)) { animated.set(pos); Anim.enter(w, Math.min(pos % PAGE, 8) * 35); }
                return w;
            }
        };
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) { if (sb.length() > 0) sb.append(", "); sb.append(s); }
        return sb.toString();
    }

    // ------------------------------------------------------------------ list panel (right)
    private LinearLayout panelBase() {
        LinearLayout p = new LinearLayout(act);
        p.setOrientation(VERTICAL);
        p.setPadding(Ui.dpi(act, 10), Ui.dpi(act, 6), Ui.dpi(act, 14), Ui.dpi(act, 10));
        return p;
    }

    private LayoutParams gap(int dp) {
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dpi(act, dp);
        return lp;
    }

    private View listPanel() {
        LinearLayout p = panelBase();
        p.addView(Ui.text(act, "Search Name", 12, Ui.DIM, false));
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final EditText et = new EditText(act);
        et.setSingleLine(true);
        et.setText(query);
        et.setTextColor(Ui.TXT);
        et.setTextSize(14);
        et.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setBackground(Ui.round(act, Ui.FLAT, 8));
        et.setPadding(Ui.dpi(act, 10), Ui.dpi(act, 8), Ui.dpi(act, 10), Ui.dpi(act, 8));
        row.addView(et, new LayoutParams(0, Ui.dpi(act, 44), 1f));
        final Runnable go = () -> {
            query = et.getText().toString().trim();
            InputMethodManager imm = (InputMethodManager) act.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
            reload();
        };
        et.setOnEditorActionListener((v, a, e) -> { go.run(); return true; });
        View s = Ui.iconBtn(act, Icons.SEARCH, Ui.TXT, go);
        row.addView(s, new LayoutParams(Ui.dpi(act, 40), Ui.dpi(act, 44)));
        p.addView(row, gap(4));

        TextView local = Ui.flat(act, "Install from local");
        local.setOnClickListener(v -> host.pickLocalFile(cat));
        p.addView(local, gap(10));

        p.addView(Ui.text(act, "MC Version", 12, Ui.DIM, false), gap(10));
        final TextView ver = Ui.flat(act, mc.isEmpty() ? "Select version (Long press to reset)" : mc);
        ver.setTextSize(12);
        ver.setTextColor(mc.isEmpty() ? Ui.DIM : Ui.TXT);
        ver.setOnClickListener(v -> pickVersion());
        ver.setOnLongClickListener(v -> { mc = ""; render(); reload(); return true; });
        p.addView(ver, gap(4));

        p.addView(Ui.text(act, "Search Platform", 12, Ui.DIM, false), gap(10));
        TextView plat = Ui.flat(act, platform.equals("modrinth") ? "Modrinth   v" : "CurseForge   v");
        plat.setOnClickListener(v -> new AlertDialog.Builder(act).setTitle("Search Platform")
                .setItems(new String[]{"Modrinth", "CurseForge"}, (d, w) -> {
                    platform = w == 0 ? "modrinth" : "curseforge";
                    render();
                    reload();
                }).show());
        p.addView(plat, gap(4));

        LinearLayout btns = new LinearLayout(act);
        TextView reset = Ui.flat(act, "Reset");
        reset.setOnClickListener(v -> { query = ""; mc = ""; render(); reload(); });
        TextView ret = Ui.flat(act, "Return");
        ret.setOnClickListener(v -> host.onReturn());
        LayoutParams b1 = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b1.rightMargin = Ui.dpi(act, 6);
        btns.addView(reset, b1);
        btns.addView(ret, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        p.addView(btns, gap(14));

        ScrollView sv = new ScrollView(act);
        sv.addView(p);
        return sv;
    }

    private void pickVersion() {
        List<String> v = mcVersions.get();
        if (v == null || v.isEmpty()) { Toast.makeText(act, "Version list is still loading...", Toast.LENGTH_SHORT).show(); return; }
        final String[] arr = v.toArray(new String[0]);
        new AlertDialog.Builder(act).setTitle("MC Version").setItems(arr, (d, w) -> {
            mc = arr[w];
            if (detail != null) { loadReleases(); render(); } else { render(); reload(); }
        }).show();
    }

    // ------------------------------------------------------------------ detail
    private void openDetail(Project p) {
        detail = p;
        animated.clear();
        all.clear();
        shown.clear();
        render();
        loadReleases();
    }

    private void loadReleases() {
        releasesLoading = true;
        error = "";
        final Project p = detail;
        final String v = mc;
        new Thread(() -> {
            List<Release> res = null;
            String err = "";
            try {
                if (p.platform.equals("modrinth")) res = Modrinth.versions(p, v);
                else {
                    String key = prefs.getString("cf_key", "").trim();
                    if (key.isEmpty()) throw new Exception("CurseForge needs an API key (Settings).");
                    res = CurseForge.files(key, p, v);
                }
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final List<Release> r = res;
            final String e = err;
            act.runOnUiThread(() -> {
                if (detail != p) return;
                releasesLoading = false;
                all.clear();
                if (r != null) all.addAll(r); else error = e;
                applyFilter();
            });
        }).start();
        updateDetailStatus();
    }

    private void applyFilter() {
        shown.clear();
        for (Release r : all) if (!officialOnly || r.hasOfficialVersion()) shown.add(r);
        if (adapter != null) adapter.notifyDataSetChanged();
        updateDetailStatus();
    }

    private void updateDetailStatus() {
        if (status == null) return;
        if (!error.isEmpty()) { status.setText(error); status.setVisibility(VISIBLE); }
        else if (releasesLoading) { status.setText("Loading versions..."); status.setVisibility(VISIBLE); }
        else if (shown.isEmpty()) { status.setText("No files found" + (mc.isEmpty() ? "." : " for " + mc + ".")); status.setVisibility(VISIBLE); }
        else status.setVisibility(GONE);
    }

    private BaseAdapter releaseAdapter() {
        return new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int p) { return shown.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                Release r = shown.get(pos);
                LinearLayout row = new LinearLayout(act);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dpi(act, 12), Ui.dpi(act, 10), Ui.dpi(act, 12), Ui.dpi(act, 10));
                row.setBackground(Ui.round(act, Ui.CARD, 10));
                LinearLayout chips = new LinearLayout(act);
                chips.setOrientation(VERTICAL);
                for (String l : r.loaders) {
                    LayoutParams cp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    cp.bottomMargin = Ui.dpi(act, 3);
                    chips.addView(Ui.chip(act, l, Ui.FLAT, Ui.TXT), cp);
                }
                LayoutParams chp = new LayoutParams(Ui.dpi(act, 70), ViewGroup.LayoutParams.WRAP_CONTENT);
                row.addView(chips, chp);
                LinearLayout col = new LinearLayout(act);
                col.setOrientation(VERTICAL);
                col.addView(Ui.text(act, r.name, 15, Ui.TXT, false));
                String sub = join(r.gameVersions.size() > 4 ? r.gameVersions.subList(0, 4) : r.gameVersions)
                        + (r.gameVersions.size() > 4 ? " ..." : "") + (r.date.isEmpty() ? "" : "   " + r.date)
                        + (r.type.equals("release") ? "" : "   [" + r.type + "]");
                col.addView(Ui.text(act, sub, 11, Ui.DIM, false));
                row.addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                FrameLayout w = new FrameLayout(act);
                w.setPadding(0, 0, 0, Ui.dpi(act, 6));
                w.addView(row);
                if (!animated.get(pos)) { animated.set(pos); Anim.enter(w, Math.min(pos, 8) * 30); }
                return w;
            }
        };
    }

    private View detailPanel() {
        LinearLayout p = panelBase();
        final Project pr = detail;
        LinearLayout top = new LinearLayout(act);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = icon(64);
        Images.load(act, iv, pr.icon);
        top.addView(iv, new LayoutParams(Ui.dpi(act, 64), Ui.dpi(act, 64)));
        View sp = new View(act);
        top.addView(sp, new LayoutParams(0, 1, 1f));
        top.addView(Ui.iconBtn(act, Icons.LINK, Ui.TXT, () -> {
            if (!pr.webUrl.isEmpty()) act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(pr.webUrl)));
        }), new LayoutParams(Ui.dpi(act, 36), Ui.dpi(act, 36)));
        p.addView(top);
        TextView title = Ui.text(act, pr.title, 15, Ui.TXT, true);
        p.addView(title, gap(8));
        p.addView(Ui.text(act, pr.description, 12, 0xFFD0D0D0, false), gap(4));

        CheckBox cb = new CheckBox(act);
        cb.setText("Show only official Minecraft versions");
        cb.setTextColor(Ui.TXT);
        cb.setTextSize(12);
        cb.setChecked(officialOnly);
        cb.setOnCheckedChangeListener((b, on) -> { officialOnly = on; applyFilter(); });
        p.addView(cb, gap(10));

        TextView ver = Ui.flat(act, mc.isEmpty() ? "All MC versions" : "MC " + mc);
        ver.setTextSize(12);
        ver.setOnClickListener(v -> pickVersion());
        ver.setOnLongClickListener(v -> { mc = ""; render(); loadReleases(); return true; });
        p.addView(ver, gap(8));

        TextView refresh = Ui.flat(act, "Refresh");
        refresh.setOnClickListener(v -> loadReleases());
        p.addView(refresh, gap(8));
        TextView ret = Ui.flat(act, "Return");
        ret.setOnClickListener(v -> { detail = null; render(); notifyAdapter(); });
        p.addView(ret, gap(8));

        ScrollView sv = new ScrollView(act);
        sv.addView(p);
        return sv;
    }

    // ------------------------------------------------------------------ install
    private void confirmInstall(final Release r) {
        if (r.url.isEmpty()) {
            new AlertDialog.Builder(act).setTitle("Direct download disabled")
                    .setMessage("The author only allows downloading this file from the website.")
                    .setPositiveButton("Open website", (d, w) -> { if (!r.webUrl.isEmpty()) act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(r.webUrl))); })
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        new AlertDialog.Builder(act).setTitle("Install " + r.name + "?")
                .setMessage(r.fileName + (r.size > 0 ? "\n" + count(r.size) + " bytes" : "") + "\n\nGoes to: " + cat.dir + "/")
                .setPositiveButton("Install", (d, w) -> doInstall(r))
                .setNegativeButton("Cancel", null).show();
    }

    private void doInstall(final Release r) {
        Toast.makeText(act, "Downloading...", Toast.LENGTH_SHORT).show();
        final Category c = cat;
        new Thread(() -> {
            String msg;
            try {
                msg = Installer.install(act, c, r, m -> { });
            } catch (Exception e) {
                msg = "Failed: " + e.getMessage();
            }
            final String m = msg;
            act.runOnUiThread(() -> Toast.makeText(act, m, Toast.LENGTH_LONG).show());
        }).start();
    }
}
