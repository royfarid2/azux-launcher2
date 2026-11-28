package com.azux.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.azux.launcher.core.Accounts;
import com.azux.launcher.core.Downloader;
import com.azux.launcher.core.RuntimeInstaller;
import com.azux.launcher.core.Heads;
import com.azux.launcher.core.Net;
import com.azux.launcher.core.Paths;
import com.azux.launcher.core.Runtimes;
import com.azux.launcher.core.VersionList;
import com.azux.launcher.engine.Engine;
import com.azux.launcher.store.Category;

import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements DownloadsView.Host {
    private static final int REQ_LOCAL = 77;

    private SharedPreferences prefs;
    private FrameLayout content;
    private LinearLayout side;
    private TextView title, accountName;
    private ImageView accountHead;
    private View dlButton;

    private boolean downloadsMode;
    private int tab = 0;
    private Category dlCat = Category.MOD;
    private DownloadsView downloads;
    private Category pendingLocal;
    private boolean pendingPlay;
    private volatile boolean prepCancelled;
    private AlertDialog prepDialog;

    private final List<VersionList.Item> remote = new ArrayList<>();
    private boolean loadingVersions = true, versionsFailed = false;
    private String filter = "release", query = "";

    private static final String[] TABS = {"Home", "Versions", "Accounts", "Settings"};
    private static final int[] TAB_ICONS = {Icons.HOME, Icons.LAYERS, Icons.USER, Icons.GEAR};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("azux", MODE_PRIVATE);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.BG));
        if (!SetupActivity.isDone(this)) {
            // first start (or a runtime is missing): permission + module setup, which opens this screen when done
            startActivity(new android.content.Intent(this, SetupActivity.class));
            finish();
            return;
        }
        buildUi();
        showTab(0);
        loadVersions();
    }

    @Override
    protected void onDestroy() {
        prepCancelled = true;
        dismissPrep();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (accountName == null) return;   // sent to the setup screen
        refreshAccountPill();
        File marker = new File(Paths.game(this), ".show_log");
        if (marker.exists()) {
            marker.delete();
            showLog("Game log");
        }
    }

    @Override
    public void onBackPressed() {
        if (downloadsMode || tab != 0) goHome(); else super.onBackPressed();
    }

    // ------------------------------------------------------------------ frame
    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // top bar
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(Ui.SIDE);
        top.setPadding(Ui.dpi(this, 14), Ui.dpi(this, 4), Ui.dpi(this, 8), Ui.dpi(this, 4));
        TextView logo = Ui.text(this, "AZUX", 16, Ui.ACCENT, true);
        logo.setLetterSpacing(0.2f);
        top.addView(logo);
        title = Ui.text(this, "", 14, Ui.DIM, false);
        title.setPadding(Ui.dpi(this, 12), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(Ui.dpi(this, 5), Ui.dpi(this, 4), Ui.dpi(this, 12), Ui.dpi(this, 4));
        pill.setBackground(Ui.round(this, Ui.CARD2, 20));
        pill.setOnClickListener(v -> accountClicked());
        Anim.press(pill);
        accountHead = Heads.view(this, 26);
        pill.addView(accountHead, new LinearLayout.LayoutParams(Ui.dpi(this, 26), Ui.dpi(this, 26)));
        accountName = Ui.text(this, "Add Account", 13, Ui.TXT, false);
        accountName.setPadding(Ui.dpi(this, 8), 0, 0, 0);
        pill.addView(accountName);
        top.addView(pill);

        dlButton = Ui.iconBtn(this, Icons.DOWNLOAD, Ui.TXT, () -> { if (downloadsMode) goHome(); else showDownloads(dlCat); });
        top.addView(dlButton, new LinearLayout.LayoutParams(Ui.dpi(this, 44), Ui.dpi(this, 40)));
        top.addView(Ui.iconBtn(this, Icons.HOME, Ui.TXT, this::goHome), new LinearLayout.LayoutParams(Ui.dpi(this, 44), Ui.dpi(this, 40)));
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dpi(this, 48)));

        // body: sidebar + content
        LinearLayout body = new LinearLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setBackgroundColor(Ui.SIDE);
        side.setPadding(Ui.dpi(this, 6), Ui.dpi(this, 8), Ui.dpi(this, 6), Ui.dpi(this, 8));
        body.addView(side, new LinearLayout.LayoutParams(Ui.dpi(this, 104), ViewGroup.LayoutParams.MATCH_PARENT));
        content = new FrameLayout(this);
        body.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        setContentView(root);
    }

    private View navItem(int icon, String label, boolean selected, final Runnable click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dpi(this, 8), Ui.dpi(this, 9), Ui.dpi(this, 6), Ui.dpi(this, 9));
        if (selected) r.setBackground(Ui.round(this, Ui.CARD2, 10));
        r.addView(Icons.view(this, icon, 18, selected ? Ui.TEAL : Ui.DIM), new LinearLayout.LayoutParams(Ui.dpi(this, 18), Ui.dpi(this, 18)));
        TextView t = Ui.text(this, label, 12, selected ? Ui.TXT : Ui.DIM, selected);
        t.setPadding(Ui.dpi(this, 8), 0, 0, 0);
        r.addView(t);
        r.setOnClickListener(v -> click.run());
        Anim.press(r);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dpi(this, 2);
        r.setLayoutParams(lp);
        return r;
    }

    private void buildSidebar() {
        side.removeAllViews();
        ViewGroup.LayoutParams sp = side.getLayoutParams();
        sp.width = Ui.dpi(this, downloadsMode ? 124 : 104);
        side.setLayoutParams(sp);
        if (downloadsMode) {
            int[] ic = {Icons.GEAR, Icons.PACK, Icons.PUZZLE, Icons.BULB};
            for (Category c : Category.values()) {
                final Category cc = c;
                side.addView(navItem(ic[c.ordinal()], c.label, c == dlCat, () -> showDownloads(cc)));
            }
        } else {
            for (int i = 0; i < TABS.length; i++) {
                final int idx = i;
                side.addView(navItem(TAB_ICONS[i], TABS[i], i == tab, () -> showTab(idx)));
            }
            View spacer = new View(this);
            side.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
            TextView controls = Ui.flat(this, "Controls");
            controls.setTextSize(12);
            controls.setOnClickListener(v -> startActivity(new Intent(this, ControlEditorActivity.class)));
            side.addView(controls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void setPage(View page) {
        content.removeAllViews();
        content.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Anim.pageIn(page);
    }

    private void goHome() { showTab(0); }

    private void showTab(int i) {
        downloadsMode = false;
        downloads = null;
        tab = i;
        title.setText(TABS[i]);
        buildSidebar();
        switch (i) {
            case 1: setPage(versionsPage()); break;
            case 2: setPage(accountsPage()); break;
            case 3: setPage(settingsPage()); break;
            default: setPage(homePage());
        }
    }

    private void showDownloads(Category c) {
        dlCat = c;
        boolean fresh = !downloadsMode || downloads == null;
        downloadsMode = true;
        title.setText("Downloads  /  " + c.label);
        buildSidebar();
        if (fresh) {
            downloads = new DownloadsView(this, prefs, this, () -> {
                List<String> v = new ArrayList<>();
                for (VersionList.Item it : remote) if (it.type.equals("release")) v.add(it.id);
                return v;
            });
            downloads.setCategory(c);
            setPage(downloads);
        } else {
            downloads.setCategory(c);
        }
    }

    // DownloadsView.Host
    @Override public void onReturn() { goHome(); }

    @Override
    public void pickLocalFile(Category c) {
        pendingLocal = c;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_LOCAL);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_LOCAL || res != RESULT_OK || data == null || data.getData() == null || downloads == null) return;
        Uri uri = data.getData();
        String name = "file";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ix >= 0) name = c.getString(ix);
            }
        } catch (Exception ignored) { }
        downloads.installLocal(uri, name);
    }

    // ------------------------------------------------------------------ account (optional)
    private void refreshAccountPill() {
        Accounts.Account a = Accounts.current(this);
        accountName.setText(a == null ? "Add Account" : a.name);
        if (a == null) { accountHead.setTag(null); accountHead.setImageDrawable(null); }
        else Heads.load(this, accountHead, a.name, !a.type.equals("offline"), a.uuid);
    }

    private void accountClicked() {
        if (Accounts.list(this).isEmpty()) addAccountDialog(); else showTab(2);
    }

    private void addAccountDialog() {
        final EditText et = new EditText(this);
        et.setHint("Username");
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(Ui.dpi(this, 20), Ui.dpi(this, 8), Ui.dpi(this, 20), 0);
        box.addView(et);
        new AlertDialog.Builder(this)
                .setTitle("Add account")
                .setMessage("Offline account: just pick a name (3-16 letters, digits or _). Its skin head shows if the name exists.")
                .setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    try {
                        Accounts.addOffline(this, et.getText().toString().trim());
                        refreshAccountPill();
                        if (!downloadsMode && (tab == 0 || tab == 2)) showTab(tab);
                        if (pendingPlay) { pendingPlay = false; play(); }
                    } catch (Exception e) {
                        pendingPlay = false;
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", (d, w) -> pendingPlay = false)
                .show();
    }

    // ------------------------------------------------------------------ shared view helpers
    private ScrollView scroller(LinearLayout inner) {
        ScrollView sv = new ScrollView(this);
        inner.setPadding(Ui.dpi(this, 16), Ui.dpi(this, 8), Ui.dpi(this, 16), Ui.dpi(this, 16));
        sv.addView(inner);
        for (int i = 0; i < inner.getChildCount(); i++) Anim.enter(inner.getChildAt(i), 60 + i * 55);
        return sv;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(Ui.dpi(this, 16), Ui.dpi(this, 14), Ui.dpi(this, 16), Ui.dpi(this, 14));
        c.setBackground(Ui.round(this, Ui.CARD, 16));
        return c;
    }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dpi(this, 10);
        return lp;
    }

    private int countFiles(String dir) {
        String[] l = new File(Paths.game(this), dir).list();
        return l == null ? 0 : l.length;
    }

    // ------------------------------------------------------------------ Home
    private View homePage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // hero
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(Ui.dpi(this, 20), Ui.dpi(this, 18), Ui.dpi(this, 20), Ui.dpi(this, 18));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF173F4A, 0xFF1D2547});
        g.setCornerRadius(Ui.dp(this, 18));
        hero.setBackground(g);
        hero.addView(Ui.text(this, "READY TO PLAY", 11, Ui.TEAL, true));
        String v = prefs.getString("version", "");
        TextView ver = Ui.text(this, v.isEmpty() ? "Pick a version" : "Minecraft " + v, 26, Ui.TXT, true);
        ver.setPadding(0, Ui.dpi(this, 2), 0, Ui.dpi(this, 6));
        hero.addView(ver);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Accounts.Account a = Accounts.current(this);
        if (a != null) {
            ImageView h = Heads.view(this, 26);
            Heads.load(this, h, a.name, !a.type.equals("offline"), a.uuid);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(Ui.dpi(this, 26), Ui.dpi(this, 26));
            hp.rightMargin = Ui.dpi(this, 8);
            row.addView(h, hp);
            row.addView(Ui.text(this, a.name, 14, Ui.TXT, false));
        } else {
            TextView add = Ui.text(this, "+ Add Account (optional until you play)", 13, Ui.ACCENT, false);
            add.setOnClickListener(x -> addAccountDialog());
            row.addView(add);
        }
        hero.addView(row);
        LinearLayout btns = new LinearLayout(this);
        btns.setPadding(0, Ui.dpi(this, 14), 0, 0);
        Button play = Ui.button(this, "PLAY");
        play.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        play.setOnClickListener(x -> play());
        Anim.pulse(play);
        btns.addView(play, new LinearLayout.LayoutParams(0, Ui.dpi(this, 52), 1.4f));
        TextView change = Ui.flat(this, "Change version");
        change.setOnClickListener(x -> showTab(1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, Ui.dpi(this, 52), 1f);
        cp.leftMargin = Ui.dpi(this, 10);
        btns.addView(change, cp);
        hero.addView(btns);
        col.addView(hero, gap());

        // quick tiles
        LinearLayout tiles = new LinearLayout(this);
        tiles.addView(tile(Icons.DOWNLOAD, "Downloads", () -> showDownloads(Category.MOD)), tileLp(false));
        tiles.addView(tile(Icons.PAD, "Controls", () -> startActivity(new Intent(this, ControlEditorActivity.class))), tileLp(false));
        tiles.addView(tile(Icons.GEAR, "Settings", () -> showTab(3)), tileLp(true));
        col.addView(tiles, gap());

        // library stats
        LinearLayout st = card();
        st.addView(Ui.text(this, "Your game folder", 12, Ui.DIM, false));
        st.addView(Ui.text(this, countFiles("mods") + " mods   " + countFiles("resourcepacks") + " resource packs   "
                + countFiles("shaderpacks") + " shaders", 14, Ui.TXT, false));
        col.addView(st, gap());
        return scroller(col);
    }

    private LinearLayout.LayoutParams tileLp(boolean last) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dpi(this, 64), 1f);
        if (!last) lp.rightMargin = Ui.dpi(this, 10);
        return lp;
    }

    private View tile(int icon, String label, Runnable r) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.round(this, Ui.CARD, 14));
        t.addView(Icons.view(this, icon, 22, Ui.TEAL), new LinearLayout.LayoutParams(Ui.dpi(this, 22), Ui.dpi(this, 22)));
        TextView l = Ui.text(this, label, 12, Ui.TXT, false);
        l.setPadding(0, Ui.dpi(this, 4), 0, 0);
        t.addView(l);
        t.setOnClickListener(v -> r.run());
        Anim.press(t);
        return t;
    }

    private void play() {
        String v = prefs.getString("version", "");
        if (v.isEmpty()) { Toast.makeText(this, "Pick a version first", Toast.LENGTH_SHORT).show(); showTab(1); return; }
        Accounts.Account a = Accounts.current(this);
        if (a == null) { pendingPlay = true; addAccountDialog(); return; }
        startPreparing(v, prefs.getString("version_url", ""), a);
    }

    /** Downloads the game files and unpacks Java here in the launcher (with a progress bar), then opens the game. */
    private void startPreparing(final String id, final String url, final Accounts.Account acc) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dpi(this, 22), Ui.dpi(this, 10), Ui.dpi(this, 22), Ui.dpi(this, 4));
        final TextView stage = Ui.text(this, "Starting ...", 14, Ui.TXT, false);
        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(ColorStateList.valueOf(Ui.TEAL));
        bar.setIndeterminate(true);
        final TextView pct = Ui.text(this, "", 12, Ui.DIM, false);
        box.addView(stage);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = Ui.dpi(this, 12);
        box.addView(bar, bp);
        box.addView(pct);
        prepCancelled = false;
        prepDialog = new AlertDialog.Builder(this).setTitle("Preparing " + id).setView(box).setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> prepCancelled = true).create();
        prepDialog.show();

        new Thread(() -> {
            try {
                final long[] last = {0};
                JSONObject json = Downloader.prepare(this, id, url, (st, done, total) -> {
                    long now = System.currentTimeMillis();
                    if (done != total && now - last[0] < 100) return;
                    last[0] = now;
                    final float f = overall(st, done, total);
                    final String text = st + (total > 1 ? "  " + done + " / " + total : "");
                    runOnUiThread(() -> {
                        stage.setText(text);
                        bar.setIndeterminate(false);
                        bar.setProgress((int) (f * 1000));
                        pct.setText((int) (f * 100) + "%");
                    });
                }, () -> prepCancelled);
                int major = Runtimes.pick(this, Runtimes.required(json));
                if (!Runtimes.installed(this, major)) {
                    runOnUiThread(() -> { bar.setIndeterminate(true); pct.setText(""); });
                    RuntimeInstaller.ensure(this, major, msg -> runOnUiThread(() -> stage.setText(msg)));
                }
                runOnUiThread(() -> {
                    dismissPrep();
                    Intent i = new Intent(this, GameActivity.class);
                    i.putExtra("version", id);
                    i.putExtra("url", url);
                    i.putExtra("account", acc.name);
                    i.putExtra("prepared", true);
                    startActivity(i);
                });
            } catch (final Throwable t) {
                runOnUiThread(() -> {
                    dismissPrep();
                    if (prepCancelled) return;
                    new AlertDialog.Builder(this).setTitle("Could not prepare " + id)
                            .setMessage(String.valueOf(t.getMessage()))
                            .setPositiveButton("Retry", (d, w) -> startPreparing(id, url, acc))
                            .setNegativeButton("Close", null).show();
                });
            }
        }).start();
    }

    private void dismissPrep() {
        if (prepDialog != null && prepDialog.isShowing()) prepDialog.dismiss();
        prepDialog = null;
    }

    /** Maps a download stage to one overall progress value (0..1). */
    private static float overall(String stage, int done, int total) {
        float f = total <= 0 ? 0f : Math.min(1f, done / (float) total);
        switch (stage) {
            case "Version info": return 0.02f * f;
            case "Game jar": return 0.02f + 0.08f * f;
            case "Libraries": return 0.10f + 0.20f * f;
            case "Asset index": return 0.30f + 0.02f * f;
            case "Assets": return 0.32f + 0.66f * f;
            default: return 0.99f;
        }
    }

    // ------------------------------------------------------------------ Versions
    private void loadVersions() {
        loadingVersions = true;
        versionsFailed = false;
        new Thread(() -> {
            List<VersionList.Item> l = null;
            try { l = VersionList.load(this); } catch (Throwable ignored) { }
            final List<VersionList.Item> res = l;
            runOnUiThread(() -> {
                remote.clear();
                if (res != null) remote.addAll(res);
                loadingVersions = false;
                versionsFailed = res == null;
                if (!downloadsMode && tab == 1) showTab(1);
            });
        }).start();
    }

    private List<VersionList.Item> filtered() {
        List<VersionList.Item> out = new ArrayList<>();
        List<VersionList.Item> src = filter.equals("installed") ? VersionList.installed(this) : remote;
        for (VersionList.Item it : src) {
            boolean ok;
            switch (filter) {
                case "release": ok = it.type.equals("release"); break;
                case "snapshot": ok = it.type.equals("snapshot"); break;
                case "old": ok = it.type.startsWith("old_"); break;
                default: ok = true;
            }
            if (ok && (query.isEmpty() || it.id.toLowerCase().contains(query))) out.add(it);
        }
        return out;
    }

    private View versionsPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dpi(this, 16), Ui.dpi(this, 6), Ui.dpi(this, 16), Ui.dpi(this, 8));

        final EditText search = new EditText(this);
        search.setHint("Search");
        search.setHintTextColor(Ui.DIM);
        search.setTextColor(Ui.TXT);
        search.setSingleLine(true);
        search.setText(query);
        search.setBackground(Ui.round(this, Ui.CARD, 12));
        search.setPadding(Ui.dpi(this, 14), Ui.dpi(this, 8), Ui.dpi(this, 14), Ui.dpi(this, 8));
        col.addView(search, gap());

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        String[][] cs = {{"release", "Release"}, {"snapshot", "Snapshot"}, {"old", "Old"}, {"installed", "Installed"}, {"all", "All"}};
        for (final String[] c : cs) {
            TextView t = Ui.text(this, c[1], 13, filter.equals(c[0]) ? Color.BLACK : Ui.TXT, false);
            t.setPadding(Ui.dpi(this, 14), Ui.dpi(this, 6), Ui.dpi(this, 14), Ui.dpi(this, 6));
            t.setBackground(Ui.round(this, filter.equals(c[0]) ? Ui.ACCENT : Ui.CARD2, 16));
            t.setOnClickListener(x -> { filter = c[0]; showTab(1); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dpi(this, 8);
            chips.addView(t, lp);
        }
        hs.addView(chips);
        col.addView(hs, gap());

        if (!filter.equals("installed") && (loadingVersions || (versionsFailed && remote.isEmpty()))) {
            col.addView(Ui.text(this, loadingVersions ? "Loading versions..." : "Could not load versions. Check your internet.", 14, Ui.DIM, false));
            if (!loadingVersions) {
                Button retry = Ui.button(this, "Retry");
                retry.setOnClickListener(x -> { loadVersions(); showTab(1); });
                col.addView(retry, new LinearLayout.LayoutParams(Ui.dpi(this, 120), Ui.dpi(this, 44)));
            }
            return col;
        }

        final List<VersionList.Item> items = filtered();
        final ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int p) { return items.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View cv, ViewGroup parent) {
                VersionList.Item it = items.get(p);
                boolean sel = it.id.equals(prefs.getString("version", ""));
                LinearLayout r = new LinearLayout(MainActivity.this);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(Ui.dpi(MainActivity.this, 14), Ui.dpi(MainActivity.this, 12), Ui.dpi(MainActivity.this, 14), Ui.dpi(MainActivity.this, 12));
                r.setBackground(Ui.round(MainActivity.this, sel ? Ui.CARD2 : Ui.CARD, 12));
                r.addView(Ui.text(MainActivity.this, it.id, 16, sel ? Ui.TEAL : Ui.TXT, sel), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                r.addView(Ui.text(MainActivity.this, it.type + (it.date.isEmpty() ? "" : "  " + it.date), 12, Ui.DIM, false));
                FrameLayout w = new FrameLayout(MainActivity.this);
                w.setPadding(0, 0, 0, Ui.dpi(MainActivity.this, 6));
                w.addView(r);
                return w;
            }
        });
        lv.setOnItemClickListener((parent, v, pos, id) -> {
            VersionList.Item it = items.get(pos);
            prefs.edit().putString("version", it.id).putString("version_url", it.url).apply();
            ((BaseAdapter) lv.getAdapter()).notifyDataSetChanged();
            Toast.makeText(this, it.id + " selected", Toast.LENGTH_SHORT).show();
        });
        col.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                String q = e.toString().trim().toLowerCase();
                if (q.equals(query)) return;
                query = q;
                items.clear();
                items.addAll(filtered());
                ((BaseAdapter) lv.getAdapter()).notifyDataSetChanged();
            }
        });
        return col;
    }

    // ------------------------------------------------------------------ Accounts
    private View accountsPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        Button add = Ui.button(this, "Add Account");
        add.setOnClickListener(x -> addAccountDialog());
        col.addView(add, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dpi(this, 48)));
        TextView note = Ui.text(this, "Accounts are optional. Microsoft sign-in comes in a later version.", 12, Ui.DIM, false);
        note.setPadding(0, Ui.dpi(this, 6), 0, Ui.dpi(this, 10));
        col.addView(note);

        Accounts.Account cur = Accounts.current(this);
        for (final Accounts.Account a : Accounts.list(this)) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(Ui.dpi(this, 12), Ui.dpi(this, 10), Ui.dpi(this, 12), Ui.dpi(this, 10));
            r.setBackground(Ui.round(this, Ui.CARD, 14));
            ImageView h = Heads.view(this, 40);
            Heads.load(this, h, a.name, !a.type.equals("offline"), a.uuid);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(Ui.dpi(this, 40), Ui.dpi(this, 40));
            hp.rightMargin = Ui.dpi(this, 12);
            r.addView(h, hp);
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(Ui.text(this, a.name, 16, Ui.TXT, true));
            info.addView(Ui.text(this, a.type, 12, Ui.DIM, false));
            r.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            boolean active = cur != null && cur.name.equals(a.name);
            TextView use = Ui.text(this, active ? "Active" : "Use", 14, active ? Ui.DIM : Ui.ACCENT, false);
            use.setPadding(Ui.dpi(this, 12), Ui.dpi(this, 8), Ui.dpi(this, 12), Ui.dpi(this, 8));
            if (!active) use.setOnClickListener(x -> { Accounts.setCurrent(this, a.name); refreshAccountPill(); showTab(2); });
            r.addView(use);
            TextView del = Ui.text(this, "Remove", 14, Ui.BAD, false);
            del.setPadding(Ui.dpi(this, 12), Ui.dpi(this, 8), Ui.dpi(this, 4), Ui.dpi(this, 8));
            del.setOnClickListener(x -> { Accounts.remove(this, a.name); refreshAccountPill(); showTab(2); });
            r.addView(del);
            col.addView(r, gap());
        }
        return scroller(col);
    }

    // ------------------------------------------------------------------ Settings
    private View settingsPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout ram = card();
        final TextView ramLabel = Ui.text(this, "", 15, Ui.TXT, false);
        ram.addView(ramLabel);
        SeekBar sb = new SeekBar(this);
        sb.setMax(22); // 512 .. 6144 MB in 256 steps
        int cur = prefs.getInt("ram", 2048);
        sb.setProgress(Math.max(0, Math.min(22, (cur - 512) / 256)));
        ramLabel.setText("Memory: " + cur + " MB");
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int mb = 512 + p * 256;
                ramLabel.setText("Memory: " + mb + " MB");
                prefs.edit().putInt("ram", mb).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        ram.addView(sb);
        col.addView(ram, gap());

        LinearLayout gfx = card();
        final TextView scaleLabel = Ui.text(this, "", 15, Ui.TXT, false);
        gfx.addView(scaleLabel);
        SeekBar sc = new SeekBar(this);
        sc.setMax(12); // 40% .. 100% in steps of 5
        int curScale = prefs.getInt("render_scale", 75);
        sc.setProgress(Math.max(0, Math.min(12, (curScale - 40) / 5)));
        scaleLabel.setText("Render resolution: " + curScale + "%  (lower = faster)");
        sc.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int v = 40 + p * 5;
                scaleLabel.setText("Render resolution: " + v + "%  (lower = faster)");
                prefs.edit().putInt("render_scale", v).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        gfx.addView(sc);
        final TextView sensLabel = Ui.text(this, "", 15, Ui.TXT, false);
        sensLabel.setPadding(0, Ui.dpi(this, 10), 0, 0);
        gfx.addView(sensLabel);
        SeekBar se = new SeekBar(this);
        se.setMax(28); // 20% .. 300% in steps of 10
        int curSens = prefs.getInt("sensitivity", 100);
        se.setProgress(Math.max(0, Math.min(28, (curSens - 20) / 10)));
        sensLabel.setText("Camera sensitivity: " + curSens + "%");
        se.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                int v = 20 + p * 10;
                sensLabel.setText("Camera sensitivity: " + v + "%");
                prefs.edit().putInt("sensitivity", v).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        gfx.addView(se);
        col.addView(gfx, gap());

        LinearLayout hud = card();
        CheckBox cb = new CheckBox(this);
        cb.setText("Show log and input debug text in game");
        cb.setTextColor(Ui.TXT);
        cb.setChecked(prefs.getBoolean("hud", true));
        cb.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("hud", on).apply());
        hud.addView(cb);
        col.addView(hud, gap());

        LinearLayout rt = card();
        rt.addView(Ui.text(this, "Engine", 12, Ui.DIM, false));
        boolean eng = Engine.available();
        rt.addView(Ui.text(this, "Native engine: " + (eng ? "loaded" : "not loaded (" + Engine.loadError() + ")"), 14, eng ? Ui.GOOD : Ui.BAD, false));
        File natives = new File(getApplicationInfo().nativeLibraryDir);
        for (String lib : new String[]{"libazuxengine.so", "liblwjgl.so", "libgl4es.so", "libopenal.so"}) {
            boolean ok = new File(natives, lib).exists();
            rt.addView(Ui.text(this, lib + ": " + (ok ? "in APK" : (lib.equals("libopenal.so") ? "missing (no sound)" : "missing")), 14,
                    ok ? Ui.TXT : (lib.equals("libopenal.so") ? Ui.DIM : Ui.BAD), false));
        }
        for (int major : Runtimes.ALL) {
            String s = Runtimes.installed(this, major) ? "installed" : Runtimes.bundled(this, major) ? "in APK, installs on first launch" : Runtimes.downloadable(major) ? "not in APK - downloads on first launch" : "missing from this APK";
            boolean ok = !s.startsWith("missing");
            rt.addView(Ui.text(this, "Java " + major + ": " + s, 14, ok ? Ui.TXT : Ui.BAD, false));
        }
        LinearLayout tb = new LinearLayout(this);
        tb.setPadding(0, Ui.dpi(this, 10), 0, 0);
        TextView test = Ui.flat(this, "Test Java");
        test.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Test which Java?")
                .setItems(new String[]{"Java 8", "Java 17", "Java 21", "Java 25"}, (d, which) -> {
                    Intent i = new Intent(this, GameActivity.class);
                    i.putExtra("selftest", true);
                    i.putExtra("major", new int[]{8, 17, 21, 25}[which]);
                    startActivity(i);
                }).show());
        LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        t1.rightMargin = Ui.dpi(this, 8);
        tb.addView(test, t1);
        TextView log = Ui.flat(this, "View last log");
        log.setOnClickListener(v -> showLog("Last log"));
        tb.addView(log, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        rt.addView(tb);
        col.addView(rt, gap());

        LinearLayout st = card();
        st.addView(Ui.text(this, "Storage", 12, Ui.DIM, false));
        st.addView(Ui.text(this, Paths.root(this).getAbsolutePath(), 12, Ui.TXT, false));
        col.addView(st, gap());
        return scroller(col);
    }

    private void showLog(String titleText) {
        final String text = readTail(new File(Paths.game(this), "latest.log"), 6000);
        TextView tv = Ui.text(this, text.isEmpty() ? "(no log yet)" : text, 11, Ui.TXT, false);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(Ui.dpi(this, 16), Ui.dpi(this, 8), Ui.dpi(this, 16), Ui.dpi(this, 8));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle(titleText).setView(sv)
                .setPositiveButton("Copy", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Azux log", readTail(new File(Paths.game(this), "latest.log"), 60000)));
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Close", null).show();
    }

    private static String readTail(File f, int max) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length();
            int n = (int) Math.min(max, len);
            byte[] b = new byte[n];
            r.seek(len - n);
            r.readFully(b);
            return new String(b, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
