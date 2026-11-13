package net.kdt.pojavlaunch;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Looper;
import android.os.Handler;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.lifecycle.ContextAwareDoneListener;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.prefs.screens.LauncherPreferenceFragment;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader;
import net.kdt.pojavlaunch.tasks.AsyncVersionList;
import net.kdt.pojavlaunch.tasks.MinecraftDownloader;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * AZUX Launcher v6 main screen. Own UI, own progress bar, own skin-head loader;
 * the native game runtime underneath is PojavLauncher's (LGPL-3.0).
 */
public class AzuxActivity extends BaseActivity {
    private static final int BG = 0xFF0D0D0D, SIDE = 0xFF141414, CARD = 0xFF1F1F1F, CARD2 = 0xFF2C2C2C;
    private static final int ACCENT = 0xFFDCEBFA, TXT = 0xFFFFFFFF, DIM = 0xFFA0A4A8, BAD = 0xFFE57373;
    /** Profile keys must be UUIDs, otherwise Pojav renames them on load. */
    private static final String AZUX_PROFILE = "a2a2a2a2-0000-4000-8000-a2a2a2a2a2a2";
    private static final Pattern NAME_OK = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private static class Item {
        final String id, type, date;
        Item(String id, String type, String date) { this.id = id; this.type = type; this.date = date; }
    }

    private FrameLayout content;
    private TextView accountName, title;
    private SkinHeadView accountHead;
    private LinearLayout progBox;
    private TextView progText;
    private BarView progBar;
    private TextView[] nav;
    private int tab = -1;
    private boolean askedForAccount = false;
    private Fragment settingsFragment;

    private final List<Item> remote = new ArrayList<>();
    private String filter = "release";
    private String query = "";
    private boolean versionsLoading = true;

    private float dp(float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }
    private int dpi(float v) { return Math.round(dp(v)); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().setBackgroundDrawable(new ColorDrawable(BG));
        buildUi();

        hookProgress();

        new AsyncVersionList().getVersionList(list -> runOnUiThread(() -> {
            remote.clear();
            if (list != null && list.versions != null) {
                for (JMinecraftVersionList.Version v : list.versions) {
                    if (v == null || v.id == null) continue;
                    String d = v.releaseTime == null ? "" : v.releaseTime.substring(0, Math.min(10, v.releaseTime.length()));
                    remote.add(new Item(v.id, v.type == null ? "release" : v.type, d));
                }
            }
            versionsLoading = false;
            if (tab == 1) showTab(1);
        }), false);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (tab == 3 && getSupportFragmentManager().getBackStackEntryCount() > 0) {
                    getSupportFragmentManager().popBackStack();
                } else if (tab != 0) {
                    showTab(0);
                } else {
                    finish();
                }
            }
        });
        showTab(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        try { LauncherProfiles.load(); } catch (Throwable ignored) { }
        refreshAccountPill();
        if (accounts().isEmpty() && !askedForAccount) {
            askedForAccount = true;
            showTab(2);
            askOfflineAccount();
        } else if (tab == 0) {
            showTab(0);
        }
    }

    @Override
    protected void onDestroy() {
        unhookProgress();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI skeleton
    private Object progProxy;
    private final Handler pollH = new Handler(Looper.getMainLooper());
    private boolean hooked;

    private void showProg(final int p, final String t) {
        runOnUiThread(() -> { progBox.setVisibility(View.VISIBLE); progBar.set(p); progText.setText(t); });
    }

    /** Percent-only progress bar fed by the engine's progress keeper (looked up by reflection, so a changed API cannot break the build). */
    private void hookProgress() {
        try {
            Class<?> pl = Class.forName("net.kdt.pojavlaunch.progresskeeper.ProgressListener");
            progProxy = java.lang.reflect.Proxy.newProxyInstance(pl.getClassLoader(), new Class<?>[]{pl}, (proxy, m, args) -> {
                String n = m.getName();
                if (n.equals("onProgressStarted")) { hooked = true; showProg(0, "Preparing..."); }
                else if (n.equals("onProgressUpdated")) { hooked = true; int p = (Integer) args[0]; showProg(p, p < 0 ? "Working..." : p + "%"); }
                else if (n.equals("onProgressEnded")) { runOnUiThread(() -> progBox.setVisibility(View.GONE)); }
                return null;
            });
            Class<?> pk = Class.forName("net.kdt.pojavlaunch.progresskeeper.ProgressKeeper");
            java.lang.reflect.Method add = pk.getMethod("addListener", String.class, pl);
            add.invoke(null, ProgressLayout.DOWNLOAD_MINECRAFT, progProxy);
            add.invoke(null, ProgressLayout.UNPACK_RUNTIME, progProxy);
        } catch (Throwable t) {
            progProxy = null;
        }
        // fallback: if the hook gave nothing, at least show that work is running
        pollH.postDelayed(new Runnable() {
            @Override public void run() {
                if (!hooked) {
                    if (ProgressKeeper.getTaskCount() > 0) showProg(-1, "Working...");
                    else progBox.setVisibility(View.GONE);
                }
                pollH.postDelayed(this, 700);
            }
        }, 700);
    }

    private void unhookProgress() {
        pollH.removeCallbacksAndMessages(null);
        if (progProxy == null) return;
        try {
            Class<?> pl = Class.forName("net.kdt.pojavlaunch.progresskeeper.ProgressListener");
            Class<?> pk = Class.forName("net.kdt.pojavlaunch.progresskeeper.ProgressKeeper");
            java.lang.reflect.Method rm = pk.getMethod("removeListener", String.class, pl);
            rm.invoke(null, ProgressLayout.DOWNLOAD_MINECRAFT, progProxy);
            rm.invoke(null, ProgressLayout.UNPACK_RUNTIME, progProxy);
        } catch (Throwable ignored) { }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);

        // ---- slim sidebar
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setBackgroundColor(SIDE);
        side.setPadding(dpi(6), dpi(8), dpi(6), dpi(8));
        root.addView(side, new LinearLayout.LayoutParams(dpi(96), ViewGroup.LayoutParams.MATCH_PARENT));

        TextView logo = text("AZUX", 16, ACCENT, true);
        logo.setLetterSpacing(0.25f);
        logo.setPadding(dpi(6), dpi(2), 0, 0);
        side.addView(logo);
        TextView ver = text("v6", 10, DIM, false);
        ver.setPadding(dpi(6), 0, 0, dpi(10));
        side.addView(ver);

        String[] names = {"Home", "Versions", "Accounts", "Settings", "Content"};
        nav = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = navItem(names[i]);
            t.setOnClickListener(v -> showTab(idx));
            nav[i] = t;
            side.addView(t, navLp());
        }
        View spacer = new View(this);
        side.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        TextView controls = navItem("Controls");
        controls.setBackground(round(CARD2, 8));
        controls.setTextColor(ACCENT);
        controls.setOnClickListener(v -> startActivity(new Intent(this, CustomControlsActivity.class)));
        side.addView(controls, navLp());

        // ---- main column
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        root.addView(main, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dpi(16), dpi(8), dpi(12), dpi(8));
        title = text("Home", 18, TXT, true);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dpi(6), dpi(5), dpi(14), dpi(5));
        pill.setBackground(round(CARD2, 20));
        pill.setOnClickListener(v -> showTab(2));
        accountHead = new SkinHeadView(this);
        pill.addView(accountHead, new LinearLayout.LayoutParams(dpi(28), dpi(28)));
        accountName = text("Add account", 14, TXT, false);
        accountName.setPadding(dpi(8), 0, 0, 0);
        pill.addView(accountName);
        top.addView(pill);
        main.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content = new FrameLayout(this);
        main.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- own progress bar (percent only)
        progBox = new LinearLayout(this);
        progBox.setOrientation(LinearLayout.HORIZONTAL);
        progBox.setGravity(Gravity.CENTER_VERTICAL);
        progBox.setPadding(dpi(16), dpi(6), dpi(16), dpi(10));
        progBox.setVisibility(View.GONE);
        progBar = new BarView(this);
        progBox.addView(progBar, new LinearLayout.LayoutParams(0, dpi(8), 1f));
        progText = text("", 12, TXT, true);
        progText.setPadding(dpi(10), 0, 0, 0);
        progBox.addView(progText);
        main.addView(progBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void showTab(int t) {
        if (tab == 3 && t != 3) removeSettingsFragment();
        tab = t;
        for (int i = 0; i < nav.length; i++) {
            nav[i].setBackground(i == t ? round(CARD2, 8) : null);
            nav[i].setTextColor(i == t ? ACCENT : TXT);
        }
        title.setText(new String[]{"Home", "Versions", "Accounts", "Settings", "Content"}[t]);
        content.removeAllViews();
        View page;
        switch (t) {
            case 1: page = versionsPage(); break;
            case 2: page = accountsPage(); break;
            case 3: page = settingsPage(); break;
            case 4: page = contentPage(); break;
            default: page = homePage();
        }
        content.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ------------------------------------------------------------------ Home
    private View homePage() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dpi(16), dpi(8), dpi(16), dpi(16));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(CARD, 16));
        card.setPadding(dpi(20), dpi(18), dpi(20), dpi(18));
        String ver = currentVersion();
        card.addView(text("Selected version", 12, DIM, false));
        card.addView(text(ver.isEmpty() ? "None" : ver, 28, TXT, true));
        TextView hint = text("Java and the right renderer are picked automatically for every version.", 12, DIM, false);
        hint.setPadding(0, dpi(6), 0, dpi(12));
        card.addView(hint);
        Button change = actionButton("Change version");
        change.setOnClickListener(v -> showTab(1));
        card.addView(change, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View sp = new View(this);
        card.addView(sp, new LinearLayout.LayoutParams(1, 0, 1f));
        card.addView(text("Account", 12, DIM, false));
        LinearLayout who = new LinearLayout(this);
        who.setOrientation(LinearLayout.HORIZONTAL);
        who.setGravity(Gravity.CENTER_VERTICAL);
        who.setPadding(0, dpi(4), 0, 0);
        SkinHeadView h = new SkinHeadView(this);
        who.addView(h, new LinearLayout.LayoutParams(dpi(36), dpi(36)));
        TextView wn = text(accountLabel(), 16, TXT, false);
        wn.setPadding(dpi(10), 0, 0, 0);
        who.addView(wn);
        card.addView(who);
        MinecraftAccount cur = PojavProfile.getCurrentProfileContent(this, null);
        loadHead(cur == null ? null : cur.username, h);
        row.addView(card, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        Button play = new Button(this);
        play.setText("PLAY");
        play.setAllCaps(true);
        play.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        play.setTypeface(Typeface.DEFAULT_BOLD);
        play.setTextColor(0xFF0D0D0D);
        play.setBackground(round(ACCENT, 18));
        play.setOnClickListener(v -> play());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        plp.leftMargin = dpi(12);
        row.addView(play, plp);
        return row;
    }

    private String currentVersion() {
        try {
            if (LauncherProfiles.mainProfileJson == null) LauncherProfiles.load();
            String key = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
            MinecraftProfile p = LauncherProfiles.mainProfileJson.profiles.get(key);
            if (p != null && p.lastVersionId != null && !"Unknown".equals(p.lastVersionId)) return p.lastVersionId;
        } catch (Throwable ignored) { }
        return "";
    }

    private void play() {
        if (ProgressKeeper.getTaskCount() > 0) {
            Toast.makeText(this, "A task is still running, please wait.", Toast.LENGTH_LONG).show();
            return;
        }
        String ver = currentVersion();
        if (ver.isEmpty()) {
            Toast.makeText(this, "Pick a version first.", Toast.LENGTH_LONG).show();
            showTab(1);
            return;
        }
        MinecraftAccount acc = PojavProfile.getCurrentProfileContent(this, null);
        if (acc == null) {
            Toast.makeText(this, "Add an account first.", Toast.LENGTH_LONG).show();
            showTab(2);
            return;
        }
        String normalized = AsyncMinecraftDownloader.normalizeVersionId(ver);
        JMinecraftVersionList.Version listed = AsyncMinecraftDownloader.getListedVersion(normalized);
        new MinecraftDownloader().start(this, listed, normalized, new ContextAwareDoneListener(this, normalized));
    }

    // ------------------------------------------------------------------ Versions
    private View versionsPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dpi(16), dpi(4), dpi(16), dpi(12));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        EditText search = new EditText(this);
        search.setHint("Search versions");
        search.setHintTextColor(DIM);
        search.setTextColor(TXT);
        search.setSingleLine(true);
        search.setText(query);
        search.setBackground(round(CARD, 12));
        search.setPadding(dpi(12), dpi(8), dpi(12), dpi(8));
        bar.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final ListView list = new ListView(this);
        final VersionAdapter adapter = new VersionAdapter();
        String[][] filters = {{"Release", "release"}, {"Snapshot", "snapshot"}, {"Old", "old"}, {"Installed", "installed"}, {"All", "all"}};
        final List<TextView> chips = new ArrayList<>();
        for (String[] f : filters) {
            final String key = f[1];
            TextView c = text(f[0], 13, TXT, false);
            c.setPadding(dpi(12), dpi(8), dpi(12), dpi(8));
            c.setOnClickListener(v -> {
                filter = key;
                paintChips(chips, filters);
                adapter.reload();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dpi(6);
            bar.addView(c, lp);
            chips.add(c);
        }
        paintChips(chips, filters);
        col.addView(bar);

        TextView status = text(versionsLoading ? "Loading versions..." : (remote.isEmpty() ? "No versions yet. Check your internet connection." : ""), 12, DIM, false);
        status.setPadding(dpi(4), dpi(6), 0, dpi(4));
        col.addView(status);

        list.setAdapter(adapter);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dpi(6));
        list.setOnItemClickListener((p, v, pos, id) -> {
            Item it = adapter.getItem(pos);
            selectVersion(it.id);
            Toast.makeText(this, "Selected " + it.id, Toast.LENGTH_SHORT).show();
            showTab(0);
        });
        col.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { query = e.toString(); adapter.reload(); }
        });
        adapter.reload();
        return col;
    }

    private void paintChips(List<TextView> chips, String[][] filters) {
        for (int i = 0; i < chips.size(); i++) {
            boolean on = filters[i][1].equals(filter);
            chips.get(i).setBackground(round(on ? CARD2 : CARD, 14));
            chips.get(i).setTextColor(on ? ACCENT : TXT);
        }
    }

    private List<Item> installedLocal() {
        List<Item> out = new ArrayList<>();
        try {
            File[] dirs = new File(Tools.DIR_HOME_VERSION).listFiles();
            if (dirs != null) {
                Arrays.sort(dirs);
                for (File d : dirs) {
                    if (d.isDirectory() && new File(d, d.getName() + ".json").exists()) out.add(new Item(d.getName(), "installed", ""));
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private class VersionAdapter extends BaseAdapter {
        private List<Item> shown = new ArrayList<>();

        void reload() {
            List<Item> src = new ArrayList<>();
            if ("installed".equals(filter)) src.addAll(installedLocal());
            else src.addAll(remote);
            List<Item> res = new ArrayList<>();
            String q = query.trim().toLowerCase();
            for (Item it : src) {
                boolean typeOk;
                switch (filter) {
                    case "release": typeOk = "release".equals(it.type); break;
                    case "snapshot": typeOk = "snapshot".equals(it.type); break;
                    case "old": typeOk = it.type.startsWith("old"); break;
                    default: typeOk = true;
                }
                if (typeOk && (q.isEmpty() || it.id.toLowerCase().contains(q))) res.add(it);
            }
            shown = res;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return shown.size(); }
        @Override public Item getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View convert, ViewGroup parent) {
            Item it = shown.get(i);
            LinearLayout l = new LinearLayout(AzuxActivity.this);
            l.setOrientation(LinearLayout.VERTICAL);
            l.setBackground(round(CARD, 12));
            l.setPadding(dpi(14), dpi(10), dpi(14), dpi(10));
            l.addView(text(it.id, 16, TXT, true));
            l.addView(text(it.type + (it.date.isEmpty() ? "" : "  |  " + it.date), 12, DIM, false));
            return l;
        }
    }

    private void selectVersion(String id) {
        try {
            if (LauncherProfiles.mainProfileJson == null) LauncherProfiles.load();
            MinecraftProfile p = LauncherProfiles.mainProfileJson.profiles.get(AZUX_PROFILE);
            if (p == null) {
                p = MinecraftProfile.getDefaultProfile();
                p.name = "Azux";
            }
            p.lastVersionId = id;
            LauncherProfiles.mainProfileJson.profiles.put(AZUX_PROFILE, p);
            LauncherProfiles.write();
            LauncherPreferences.DEFAULT_PREF.edit().putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, AZUX_PROFILE).apply();
        } catch (Throwable t) {
            Tools.showError(this, t);
        }
    }

    // ------------------------------------------------------------------ Accounts
    private List<String> accounts() {
        List<String> out = new ArrayList<>();
        try {
            String[] files = new File(Tools.DIR_ACCOUNT_NEW).list();
            if (files != null) {
                Arrays.sort(files);
                for (String f : files) if (f.endsWith(".json")) out.add(f.substring(0, f.length() - 5));
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private String accountLabel() {
        MinecraftAccount a = PojavProfile.getCurrentProfileContent(this, null);
        if (a == null) return "No account";
        return a.username + (a.isMicrosoft ? "  (Microsoft)" : "  (Offline)");
    }

    private void refreshAccountPill() {
        MinecraftAccount a = PojavProfile.getCurrentProfileContent(this, null);
        accountName.setText(a == null ? "Add account" : a.username);
        loadHead(a == null ? null : a.username, accountHead);
    }

    private View accountsPage() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dpi(16), dpi(4), dpi(16), dpi(16));
        sv.addView(col);

        Button add = actionButton("+ Add Account (offline)");
        add.setOnClickListener(v -> askOfflineAccount());
        col.addView(add, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button msBtn = actionButton("Microsoft login");
        msBtn.setOnClickListener(v -> startActivity(new Intent(this, LauncherActivity.class)));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dpi(6);
        mlp.bottomMargin = dpi(10);
        col.addView(msBtn, mlp);

        String current = PojavProfile.getCurrentProfileName(this);
        List<String> names = accounts();
        if (names.isEmpty()) col.addView(text("No accounts yet.", 14, DIM, false));
        for (final String n : names) {
            MinecraftAccount a = MinecraftAccount.load(n);
            boolean ms2 = a != null && a.isMicrosoft;
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setBackground(round(CARD, 12));
            r.setPadding(dpi(14), dpi(10), dpi(10), dpi(10));
            SkinHeadView rowHead = new SkinHeadView(this);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dpi(36), dpi(36));
            hp.rightMargin = dpi(12);
            r.addView(rowHead, hp);
            loadHead(n, rowHead);
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(text(n, 16, TXT, true));
            info.addView(text(ms2 ? "Microsoft account" : "Offline account", 12, DIM, false));
            r.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (n.equals(current)) {
                r.addView(text("SELECTED", 12, ACCENT, true));
            } else {
                Button sel = actionButton("Select");
                sel.setOnClickListener(v -> {
                    PojavProfile.setCurrentProfile(this, n);
                    refreshAccountPill();
                    showTab(2);
                });
                r.addView(sel);
            }
            Button del = actionButton("Remove");
            del.setTextColor(BAD);
            del.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Remove " + n + "?")
                    .setPositiveButton("Remove", (d, w) -> {
                        new File(Tools.DIR_ACCOUNT_NEW, n + ".json").delete();
                        if (n.equals(PojavProfile.getCurrentProfileName(this))) {
                            List<String> left = accounts();
                            PojavProfile.setCurrentProfile(this, left.isEmpty() ? null : left.get(0));
                        }
                        refreshAccountPill();
                        showTab(2);
                    })
                    .setNegativeButton("Cancel", null).show());
            r.addView(del);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dpi(8);
            col.addView(r, lp);
        }
        return sv;
    }

    private void askOfflineAccount() {
        final EditText et = new EditText(this);
        et.setHint("Player name (3-16 letters, numbers, _)");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Add offline account")
                .setView(et)
                .setPositiveButton("Add", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (!NAME_OK.matcher(n).matches()) {
                        Toast.makeText(this, "3-16 characters: letters, numbers or _ only", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (new File(Tools.DIR_ACCOUNT_NEW, n + ".json").exists()) {
                        Toast.makeText(this, "This account already exists", Toast.LENGTH_LONG).show();
                        return;
                    }
                    try {
                        MinecraftAccount a = new MinecraftAccount();
                        a.username = n;
                        a.save();
                        PojavProfile.setCurrentProfile(this, n);
                    } catch (Throwable t) {
                        Tools.showError(this, t);
                    }
                    refreshAccountPill();
                    showTab(2);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------ Settings (engine settings screen)
    private View settingsPage() {
        FrameLayout host = new FrameLayout(this);
        host.setId(R.id.container_fragment);
        host.setBackgroundColor(BG);
        settingsFragment = new LauncherPreferenceFragment();
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.container_fragment, settingsFragment)
                .commitAllowingStateLoss();
        return host;
    }

    private void removeSettingsFragment() {
        try {
            getSupportFragmentManager().popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
            if (settingsFragment != null) {
                getSupportFragmentManager().beginTransaction().remove(settingsFragment).commitAllowingStateLoss();
            }
        } catch (Throwable ignored) { }
        settingsFragment = null;
    }

    // ------------------------------------------------------------------ Content: mods / packs / shaders / loaders / controls
    private static final String UA = "AzuxLauncher/6 (github.com/royfarid2/azux-launcher2)";
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private int cTab = 0;
    private String cQuery = "", cMc = "", cLoader = "fabric", cStatus = "";
    private final List<String[]> hits = new ArrayList<>(); // project_id, title, description, author
    private BaseAdapter hitAdapter;
    private TextView statusView;

    private void toast(final String s) { runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show()); }

    private void setStatus(final String s) {
        cStatus = s;
        runOnUiThread(() -> { if (statusView != null) statusView.setText(s); });
    }

    private File gameDir() { return new File(Tools.DIR_HOME_VERSION).getParentFile(); }

    private File controlDir() {
        try {
            Object o = Tools.class.getField("CTRLMAP_PATH").get(null);
            if (o != null) return new File(o.toString());
        } catch (Throwable ignored) { }
        return new File(gameDir().getParentFile(), "controlmap");
    }

    private String mcVersion() {
        if (!cMc.isEmpty()) return cMc;
        String v = currentVersion();
        java.util.regex.Matcher m = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)$").matcher(v);
        if (m.find()) return m.group(1);
        m = Pattern.compile("^(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(v);
        return m.find() ? m.group(1) : v;
    }

    /** {modrinth project type, folder in the game dir} */
    private String[] typeInfo() {
        switch (cTab) {
            case 1: return new String[]{"resourcepack", "resourcepacks"};
            case 2: return new String[]{"shader", "shaderpacks"};
            default: return new String[]{"mod", "mods"};
        }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(25000);
        c.setRequestProperty("User-Agent", UA);
        return c;
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new Exception("HTTP " + code);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toString("UTF-8");
            }
        } finally { c.disconnect(); }
    }

    private void httpDownload(String url, File dest, final String label) throws Exception {
        HttpURLConnection c = open(url);
        File tmp = new File(dest.getPath() + ".part");
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new Exception("HTTP " + code);
            long total = c.getContentLengthLong();
            long done = 0;
            int last = -1;
            dest.getParentFile().mkdirs();
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    final int pct = total > 0 ? (int) (done * 100 / total) : -1;
                    if (pct != last) {
                        last = pct;
                        runOnUiThread(() -> { progBox.setVisibility(View.VISIBLE); progBar.set(pct); progText.setText(label + (pct >= 0 ? "  " + pct + "%" : "")); });
                    }
                }
            }
            if (dest.exists()) dest.delete();
            if (!tmp.renameTo(dest)) throw new Exception("Could not save " + dest.getName());
        } finally {
            c.disconnect();
            tmp.delete();
            runOnUiThread(() -> progBox.setVisibility(View.GONE));
        }
    }

    private View contentPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dpi(16), dpi(4), dpi(16), dpi(12));

        String[] tn = {"Mods", "Packs", "Shaders", "Loaders", "Controls"};
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < tn.length; i++) {
            final int idx = i;
            TextView c = text(tn[i], 13, i == cTab ? ACCENT : TXT, false);
            c.setPadding(dpi(12), dpi(7), dpi(12), dpi(7));
            c.setBackground(round(i == cTab ? CARD2 : CARD, 14));
            c.setOnClickListener(v -> { cTab = idx; hits.clear(); cStatus = ""; showTab(4); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dpi(6);
            tabs.addView(c, lp);
        }
        col.addView(tabs);

        if (cTab == 3) loadersBody(col);
        else if (cTab == 4) controlsBody(col);
        else browseBody(col);
        return col;
    }

    // ---- browse (mods / resource packs / shaders) via Modrinth
    private void browseBody(LinearLayout col) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dpi(8), 0, dpi(4));
        final EditText search = new EditText(this);
        search.setHint("Search " + (cTab == 0 ? "mods" : cTab == 1 ? "resource packs" : "shaders"));
        search.setHintTextColor(DIM);
        search.setTextColor(TXT);
        search.setSingleLine(true);
        search.setText(cQuery);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setBackground(round(CARD, 12));
        search.setPadding(dpi(12), dpi(8), dpi(12), dpi(8));
        bar.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button go = actionButton("Search");
        go.setOnClickListener(v -> { cQuery = search.getText().toString().trim(); doSearch(); });
        search.setOnEditorActionListener((v, a, e) -> { cQuery = search.getText().toString().trim(); doSearch(); return true; });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.leftMargin = dpi(6);
        bar.addView(go, glp);
        col.addView(bar);

        LinearLayout opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.HORIZONTAL);
        opts.setGravity(Gravity.CENTER_VERTICAL);
        Button mcBtn = actionButton("MC " + mcVersion());
        mcBtn.setOnClickListener(v -> askMc());
        opts.addView(mcBtn);
        if (cTab == 0) {
            String[][] ls = {{"Fabric", "fabric"}, {"Forge", "forge"}, {"NeoForge", "neoforge"}, {"Quilt", "quilt"}};
            for (String[] l : ls) {
                final String key = l[1];
                TextView c = text(l[0], 13, key.equals(cLoader) ? ACCENT : TXT, false);
                c.setPadding(dpi(12), dpi(7), dpi(12), dpi(7));
                c.setBackground(round(key.equals(cLoader) ? CARD2 : CARD, 14));
                c.setOnClickListener(v -> { cLoader = key; showTab(4); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.leftMargin = dpi(6);
                opts.addView(c, lp);
            }
        }
        View sp = new View(this);
        opts.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        Button inst = actionButton("Installed");
        inst.setOnClickListener(v -> showInstalled());
        opts.addView(inst);
        col.addView(opts);

        statusView = text(cStatus.isEmpty() && hits.isEmpty() ? "Search, tap an item, pick a version and it downloads into the game folder." : cStatus, 12, DIM, false);
        statusView.setPadding(dpi(4), dpi(8), 0, dpi(4));
        col.addView(statusView);

        ListView list = new ListView(this);
        hitAdapter = new BaseAdapter() {
            @Override public int getCount() { return hits.size(); }
            @Override public String[] getItem(int i) { return hits.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                String[] h = hits.get(i);
                LinearLayout l = new LinearLayout(AzuxActivity.this);
                l.setOrientation(LinearLayout.VERTICAL);
                l.setBackground(round(CARD, 12));
                l.setPadding(dpi(14), dpi(10), dpi(14), dpi(10));
                l.addView(text(h[1] + "   by " + h[3], 15, TXT, true));
                TextView d = text(h[2], 12, DIM, false);
                d.setMaxLines(2);
                l.addView(d);
                return l;
            }
        };
        list.setAdapter(hitAdapter);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dpi(6));
        list.setOnItemClickListener((p, v, pos, id) -> showVersions(hits.get(pos)[0], hits.get(pos)[1]));
        col.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void askMc() {
        final EditText et = new EditText(this);
        et.setText(mcVersion());
        et.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle("Minecraft version").setView(et)
                .setPositiveButton("OK", (d, w) -> { cMc = et.getText().toString().trim(); hits.clear(); showTab(4); })
                .setNeutralButton("Use selected", (d, w) -> { cMc = ""; hits.clear(); showTab(4); })
                .setNegativeButton("Cancel", null).show();
    }

    private void doSearch() {
        final String[] ti = typeInfo();
        final String q = cQuery, mc = mcVersion(), ld = cLoader;
        final boolean mod = cTab == 0;
        hits.clear();
        if (hitAdapter != null) hitAdapter.notifyDataSetChanged();
        setStatus("Searching...");
        io.execute(() -> {
            try {
                StringBuilder f = new StringBuilder("[[\"project_type:" + ti[0] + "\"]");
                if (!mc.isEmpty()) f.append(",[\"versions:").append(mc).append("\"]");
                if (mod) f.append(",[\"categories:").append(ld).append("\"]");
                f.append("]");
                String url = "https://api.modrinth.com/v2/search?limit=30&query=" + enc(q) + "&facets=" + enc(f.toString());
                JSONArray arr = new JSONObject(httpGet(url)).getJSONArray("hits");
                final List<String[]> tmp = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject h = arr.getJSONObject(i);
                    tmp.add(new String[]{h.getString("project_id"), h.optString("title", "?"), h.optString("description", ""), h.optString("author", "")});
                }
                runOnUiThread(() -> {
                    hits.clear();
                    hits.addAll(tmp);
                    if (hitAdapter != null) hitAdapter.notifyDataSetChanged();
                });
                setStatus(tmp.isEmpty() ? "Nothing found for this version/loader." : "");
            } catch (Throwable t) {
                setStatus("Search failed: " + t.getMessage());
            }
        });
    }

    private void showVersions(final String pid, final String title) {
        final String[] ti = typeInfo();
        final String mc = mcVersion(), ld = cLoader;
        final boolean mod = cTab == 0;
        toast("Loading versions...");
        io.execute(() -> {
            try {
                String url = "https://api.modrinth.com/v2/project/" + pid + "/version?game_versions=" + enc("[\"" + mc + "\"]")
                        + (mod ? "&loaders=" + enc("[\"" + ld + "\"]") : "");
                final JSONArray vs = new JSONArray(httpGet(url));
                if (vs.length() == 0) { toast("No " + (mod ? ld + " " : "") + "version of " + title + " for " + mc); return; }
                final String[] labels = new String[vs.length()];
                for (int i = 0; i < vs.length(); i++) {
                    JSONObject v = vs.getJSONObject(i);
                    labels[i] = v.optString("version_number", v.optString("name")) + "   [" + v.optString("version_type", "release") + "]";
                }
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle(title + "  (" + mc + (mod ? ", " + ld : "") + ")")
                        .setItems(labels, (d, w) -> {
                            try { startDownload(vs.getJSONObject(w), ti[1], mc, ld, mod); } catch (Throwable t) { toast("Error: " + t.getMessage()); }
                        })
                        .setNegativeButton("Close", null).show());
            } catch (Throwable t) {
                toast("Could not load versions: " + t.getMessage());
            }
        });
    }

    private void startDownload(final JSONObject v, final String dirName, final String mc, final String ld, final boolean mod) {
        toast("Downloading...");
        io.execute(() -> {
            try {
                Set<String> seen = new HashSet<>();
                seen.add(v.optString("project_id"));
                installVersion(v, dirName, mc, ld, mod, seen, 0);
                toast("Done. Saved in " + dirName);
            } catch (Throwable t) {
                toast("Download failed: " + t.getMessage());
            }
        });
    }

    private void installVersion(JSONObject v, String dirName, String mc, String ld, boolean mod, Set<String> seen, int depth) throws Exception {
        JSONArray files = v.getJSONArray("files");
        JSONObject f = files.getJSONObject(0);
        for (int i = 0; i < files.length(); i++) if (files.getJSONObject(i).optBoolean("primary")) f = files.getJSONObject(i);
        String name = new File(f.getString("filename")).getName();
        File dir = new File(gameDir(), dirName);
        dir.mkdirs();
        File out = new File(dir, name);
        if (!out.exists()) httpDownload(f.getString("url"), out, name);
        if (mod && depth < 3) {
            JSONArray deps = v.optJSONArray("dependencies");
            if (deps == null) return;
            for (int i = 0; i < deps.length(); i++) {
                JSONObject d = deps.getJSONObject(i);
                if (!"required".equals(d.optString("dependency_type"))) continue;
                String pid = d.optString("project_id", "");
                if (pid.isEmpty() || !seen.add(pid)) continue;
                String url = "https://api.modrinth.com/v2/project/" + pid + "/version?game_versions=" + enc("[\"" + mc + "\"]") + "&loaders=" + enc("[\"" + ld + "\"]");
                JSONArray vs = new JSONArray(httpGet(url));
                if (vs.length() > 0) installVersion(vs.getJSONObject(0), dirName, mc, ld, true, seen, depth + 1);
            }
        }
    }

    private void showInstalled() {
        final File dir = new File(gameDir(), typeInfo()[1]);
        final File[] fs = dir.listFiles();
        if (fs == null || fs.length == 0) { toast("Nothing installed in " + dir.getName()); return; }
        Arrays.sort(fs);
        final String[] names = new String[fs.length];
        for (int i = 0; i < fs.length; i++) names[i] = fs[i].getName();
        new AlertDialog.Builder(this).setTitle("Installed (tap to delete)")
                .setItems(names, (d, w) -> new AlertDialog.Builder(this)
                        .setTitle("Delete " + names[w] + "?")
                        .setPositiveButton("Delete", (d2, w2) -> { deleteRec(fs[w]); toast("Deleted"); })
                        .setNegativeButton("Cancel", null).show())
                .setNegativeButton("Close", null).show();
    }

    private void deleteRec(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        f.delete();
    }

    // ---- loaders
    private void loadersBody(LinearLayout col) {
        TextView hint = text("Minecraft " + mcVersion() + "  -  pick a loader to install it as a launchable version.", 13, DIM, false);
        hint.setPadding(dpi(4), dpi(12), 0, dpi(8));
        col.addView(hint);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button mcBtn = actionButton("Change MC version");
        mcBtn.setOnClickListener(v -> askMc());
        row.addView(mcBtn);
        col.addView(row);
        String[][] ls = {{"Fabric", "fabric"}, {"Quilt", "quilt"}, {"Forge", "forge"}, {"NeoForge", "neoforge"}};
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dpi(10), 0, 0);
        for (final String[] l : ls) {
            Button b = actionButton("Install " + l[0]);
            b.setOnClickListener(v -> installLoader(l[1]));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dpi(8);
            row2.addView(b, lp);
        }
        col.addView(row2);
        statusView = text(cStatus, 12, DIM, false);
        statusView.setPadding(dpi(4), dpi(10), 0, 0);
        col.addView(statusView);
    }

    private void installLoader(final String kind) {
        final String mc = mcVersion();
        setStatus("Installing " + kind + " for " + mc + "...");
        io.execute(() -> {
            try {
                if (kind.equals("fabric") || kind.equals("quilt")) {
                    String base = kind.equals("fabric") ? "https://meta.fabricmc.net/v2/versions/loader/" : "https://meta.quiltmc.org/v3/versions/loader/";
                    JSONArray arr = new JSONArray(httpGet(base + mc));
                    if (arr.length() == 0) throw new Exception(kind + " has no build for " + mc);
                    JSONObject pick = arr.getJSONObject(0);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject l = arr.getJSONObject(i).getJSONObject("loader");
                        if (l.optBoolean("stable", true) && !l.optString("version").contains("beta")) { pick = arr.getJSONObject(i); break; }
                    }
                    String lv = pick.getJSONObject("loader").getString("version");
                    String json = httpGet(base + mc + "/" + lv + "/profile/json");
                    final String id = new JSONObject(json).getString("id");
                    File vd = new File(Tools.DIR_HOME_VERSION, id);
                    vd.mkdirs();
                    try (FileOutputStream o = new FileOutputStream(new File(vd, id + ".json"))) { o.write(json.getBytes("UTF-8")); }
                    runOnUiThread(() -> { selectVersion(id); setStatus("Installed " + id + " and selected it. Press PLAY on Home."); });
                } else if (kind.equals("forge")) {
                    JSONObject promos = new JSONObject(httpGet("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json")).getJSONObject("promos");
                    String fv = promos.optString(mc + "-recommended", promos.optString(mc + "-latest", ""));
                    if (fv.isEmpty()) throw new Exception("Forge has no build for " + mc);
                    String full = mc + "-" + fv;
                    runInstaller("https://maven.minecraftforge.net/net/minecraftforge/forge/" + full + "/forge-" + full + "-installer.jar", "forge-" + full + "-installer.jar");
                } else {
                    JSONArray vs = new JSONObject(httpGet("https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge")).getJSONArray("versions");
                    String[] p = mc.split("\\.");
                    if (p.length < 2) throw new Exception("Bad Minecraft version");
                    String prefix = p[1] + "." + (p.length > 2 ? p[2] : "0") + ".";
                    String best = "", bestAny = "";
                    for (int i = 0; i < vs.length(); i++) {
                        String s = vs.getString(i);
                        if (!s.startsWith(prefix)) continue;
                        bestAny = s;
                        if (!s.contains("beta") && !s.contains("alpha")) best = s;
                    }
                    if (best.isEmpty()) best = bestAny;
                    if (best.isEmpty()) throw new Exception("NeoForge supports Minecraft 1.20.2 and newer");
                    runInstaller("https://maven.neoforged.net/releases/net/neoforged/neoforge/" + best + "/neoforge-" + best + "-installer.jar", "neoforge-" + best + "-installer.jar");
                }
            } catch (Throwable t) {
                setStatus("Failed: " + t.getMessage());
            }
        });
    }

    /** Forge/NeoForge ship an installer jar: download it and run it through the engine's Java GUI launcher. */
    private void runInstaller(String url, String name) throws Exception {
        final File jar = new File(gameDir(), "azux_installers/" + name);
        if (!jar.exists()) httpDownload(url, jar, name);
        setStatus("Running the installer. The vanilla version must already be downloaded (play it once).");
        runOnUiThread(() -> {
            try {
                Intent i = new Intent();
                i.setClassName(getPackageName(), "net.kdt.pojavlaunch.JavaGUILauncherActivity");
                i.putExtra("modFile", (Serializable) jar);
                startActivity(i);
            } catch (Throwable t) {
                setStatus("Could not start the installer: " + t.getMessage());
            }
        });
    }

    // ---- control layouts
    private void controlsBody(LinearLayout col) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dpi(10), 0, dpi(6));
        Button edit = actionButton("Open editor");
        edit.setOnClickListener(v -> startActivity(new Intent(this, CustomControlsActivity.class)));
        Button link = actionButton("Import from link");
        link.setOnClickListener(v -> askControlLink());
        Button file = actionButton("Import from file");
        file.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, 7001);
        });
        for (Button b : new Button[]{edit, link, file}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dpi(8);
            row.addView(b, lp);
        }
        col.addView(row);
        TextView hint = text("Layouts (.json) saved on this device. Tap one to use it in game.", 12, DIM, false);
        hint.setPadding(dpi(4), dpi(4), 0, dpi(6));
        col.addView(hint);

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        File[] fs = controlDir().listFiles();
        String cur = LauncherPreferences.DEFAULT_PREF.getString("defaultCtrl", "");
        if (fs != null) Arrays.sort(fs);
        boolean any = false;
        if (fs != null) for (final File f : fs) {
            if (!f.getName().endsWith(".json")) continue;
            any = true;
            boolean on = f.getAbsolutePath().equals(cur);
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setBackground(round(CARD, 12));
            r.setPadding(dpi(14), dpi(10), dpi(10), dpi(10));
            r.addView(text(f.getName().replace(".json", ""), 15, on ? ACCENT : TXT, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (on) r.addView(text("IN USE", 12, ACCENT, true));
            else {
                Button use = actionButton("Use");
                use.setOnClickListener(v -> { LauncherPreferences.DEFAULT_PREF.edit().putString("defaultCtrl", f.getAbsolutePath()).apply(); showTab(4); });
                r.addView(use);
            }
            Button del = actionButton("Delete");
            del.setTextColor(BAD);
            del.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Delete layout?")
                    .setPositiveButton("Delete", (d, w) -> { f.delete(); showTab(4); })
                    .setNegativeButton("Cancel", null).show());
            r.addView(del);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dpi(8);
            list.addView(r, lp);
        }
        if (!any) list.addView(text("No layouts yet.", 14, DIM, false));
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void askControlLink() {
        final EditText et = new EditText(this);
        et.setHint("https://.../layout.json");
        et.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle("Import control layout").setView(et)
                .setPositiveButton("Import", (d, w) -> {
                    final String url = et.getText().toString().trim();
                    io.execute(() -> {
                        try {
                            String body = httpGet(url);
                            String n = url.substring(url.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9_.-]", "_");
                            if (n.endsWith(".json")) n = n.substring(0, n.length() - 5);
                            if (n.isEmpty()) n = "imported";
                            saveControl(n, body);
                        } catch (Throwable t) { toast("Import failed: " + t.getMessage()); }
                    });
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void saveControl(String name, String body) throws Exception {
        new JSONObject(body); // must be valid JSON
        File dir = controlDir();
        dir.mkdirs();
        File out = new File(dir, name + ".json");
        try (FileOutputStream o = new FileOutputStream(out)) { o.write(body.getBytes("UTF-8")); }
        LauncherPreferences.DEFAULT_PREF.edit().putString("defaultCtrl", out.getAbsolutePath()).apply();
        toast("Layout imported and selected: " + name);
        runOnUiThread(() -> { if (tab == 4) showTab(4); });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 7001 || res != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();
        io.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                saveControl("imported_" + (System.currentTimeMillis() / 1000), bo.toString("UTF-8"));
            } catch (Throwable t) { toast("Import failed: " + t.getMessage()); }
        });
    }

    // ------------------------------------------------------------------ skin head
    private static final Map<String, Bitmap> HEADS = new HashMap<>();

    private void loadHead(final String name, final SkinHeadView view) {
        view.setHead(null);
        if (name == null || name.isEmpty()) return;
        Bitmap cached;
        synchronized (HEADS) { cached = HEADS.get(name); }
        if (cached != null) { view.setHead(cached); return; }
        new Thread(() -> {
            final Bitmap b = fetchHead(name);
            if (b == null) return;
            synchronized (HEADS) { HEADS.put(name, b); }
            runOnUiThread(() -> view.setHead(b));
        }, "azux-skin").start();
    }

    /** Downloads the player's skin PNG and cuts face + hat layer out of it. */
    private Bitmap fetchHead(String name) {
        File cache = new File(getCacheDir(), "azux_skin_" + name + ".png");
        Bitmap skin = null;
        try {
            boolean fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < 24L * 3600 * 1000;
            if (fresh) skin = BitmapFactory.decodeFile(cache.getPath());
            if (skin == null) {
                HttpURLConnection c = (HttpURLConnection) new URL("https://minotar.net/skin/" + name).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "AzuxLauncher/6");
                if (c.getResponseCode() == 200) {
                    try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(cache)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    skin = BitmapFactory.decodeFile(cache.getPath());
                }
                c.disconnect();
            }
        } catch (Throwable ignored) { }
        if (skin == null && cache.exists()) skin = BitmapFactory.decodeFile(cache.getPath());
        if (skin == null || skin.getWidth() < 64 || skin.getHeight() < 32) return null;
        int k = skin.getWidth() / 64;
        Bitmap out = Bitmap.createBitmap(8 * k, 8 * k, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint();
        cv.drawBitmap(Bitmap.createBitmap(skin, 8 * k, 8 * k, 8 * k, 8 * k), 0, 0, p);
        cv.drawBitmap(Bitmap.createBitmap(skin, 40 * k, 8 * k, 8 * k, 8 * k), 0, 0, p);
        return out;
    }

    /** Square player head, pixel-perfect (no smoothing), rounded corners. */
    private static class SkinHeadView extends View {
        private Bitmap head;
        private final Paint paint = new Paint();
        private final Path clip = new Path();
        private static Bitmap steve;

        SkinHeadView(Context c) {
            super(c);
            paint.setFilterBitmap(false);
            paint.setAntiAlias(false);
        }

        void setHead(Bitmap b) { head = b; invalidate(); }

        private static Bitmap defaultHead() {
            if (steve == null) {
                int skin = 0xFFB98A6B, hair = 0xFF3B2A1A, eye = 0xFF3A4FA0, mouth = 0xFF7A4A3A;
                int[] px = new int[64];
                for (int i = 0; i < 64; i++) px[i] = (i / 8 < 2) ? hair : skin;
                px[16] = hair; px[23] = hair;
                px[17] = 0xFFFFFFFF; px[18] = eye; px[21] = eye; px[22] = 0xFFFFFFFF;
                steve = Bitmap.createBitmap(px, 8, 8, Bitmap.Config.ARGB_8888);
            }
            return steve;
        }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            float r = Math.min(w, h) * 0.18f;
            clip.reset();
            clip.addRoundRect(new RectF(0, 0, w, h), r, r, Path.Direction.CW);
            cv.save();
            cv.clipPath(clip);
            Bitmap b = head != null ? head : defaultHead();
            cv.drawBitmap(b, new Rect(0, 0, b.getWidth(), b.getHeight()), new Rect(0, 0, w, h), paint);
            cv.restore();
        }
    }

    /** Thin rounded progress bar. */
    private static class BarView extends View {
        private int value = 0;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        BarView(Context c) { super(c); }

        void set(int v) { value = Math.max(0, Math.min(100, v)); invalidate(); }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            paint.setColor(CARD2);
            cv.drawRoundRect(new RectF(0, 0, w, h), r, r, paint);
            if (value > 0) {
                paint.setColor(ACCENT);
                cv.drawRoundRect(new RectF(0, 0, Math.max(h, w * value / 100f), h), r, r, paint);
            }
        }
    }

    // ------------------------------------------------------------------ view helpers
    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView navItem(String label) {
        TextView t = text(label, 12, TXT, false);
        t.setPadding(dpi(10), dpi(8), dpi(8), dpi(8));
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    private LinearLayout.LayoutParams navLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dpi(3);
        return lp;
    }

    private Button actionButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTextColor(TXT);
        b.setBackground(round(CARD2, 12));
        b.setPadding(dpi(14), dpi(8), dpi(14), dpi(8));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }
}
