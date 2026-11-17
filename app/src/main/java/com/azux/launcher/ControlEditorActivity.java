package com.azux.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.azux.launcher.controls.ControlData;
import com.azux.launcher.controls.ControlStore;
import com.azux.launcher.controls.ControlsOverlay;
import com.azux.launcher.controls.InputSink;
import com.azux.launcher.controls.KeyPicker;
import com.azux.launcher.controls.Keys;

import java.util.ArrayList;
import java.util.List;

/** Azux control editor: flat buttons, gear menu, property drawer, on-screen keyboard picker. */
public class ControlEditorActivity extends Activity {
    private ControlsOverlay overlay;
    private FrameLayout drawer;
    private ScrollView drawerScroll;
    private boolean dirty, menuOpen;
    private String layoutName;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        layoutName = ControlStore.currentName(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF1B1B1B);

        List<ControlData> data = new ArrayList<>(ControlStore.load(this, layoutName));
        overlay = new ControlsOverlay(this, data, true, new NoopSink());
        overlay.setListener(new ControlsOverlay.Listener() {
            @Override public void onSelect(ControlData d) { if (d != null) showProps(d); else if (!menuOpen) hideDrawer(); }
            @Override public void onChanged() { dirty = true; }
            @Override public void onDragStart() { menuOpen = false; hideDrawer(); }
            @Override public void onDelete(ControlData d) { overlay.remove(d); }
            @Override public void onClone(ControlData d) {
                ControlData c = d.copy();
                c.x = Math.min(0.9f, c.x + 0.04f);
                c.y = Math.min(0.9f, c.y + 0.06f);
                overlay.add(c);
            }
        });
        root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // gear + hint (top centre)
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout gear = new FrameLayout(this);
        gear.setBackground(Ui.round(this, 0xFF000000, 20));
        gear.addView(Icons.view(this, Icons.GEAR, 22, 0xFFFFFFFF), new FrameLayout.LayoutParams(Ui.dpi(this, 24), Ui.dpi(this, 24), Gravity.CENTER));
        gear.setOnClickListener(v -> toggleMenu());
        top.addView(gear, new LinearLayout.LayoutParams(Ui.dpi(this, 40), Ui.dpi(this, 40)));
        TextView hint = Ui.text(this, "Tap the gear icon to open the menu.\nTap a button to customize: edit, resize, delete, or remap.", 11, Ui.DIM, false);
        hint.setPadding(Ui.dpi(this, 12), 0, 0, 0);
        top.addView(hint);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tp.topMargin = Ui.dpi(this, 6);
        root.addView(top, tp);

        drawer = new FrameLayout(this);
        drawer.setBackgroundColor(0xF2262626);
        drawer.setVisibility(View.GONE);
        drawer.setClickable(true);
        drawerScroll = new ScrollView(this);
        drawer.addView(drawerScroll);
        root.addView(drawer, new FrameLayout.LayoutParams(Ui.dpi(this, 280), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));

        setContentView(root);
    }

    // ------------------------------------------------------------------ drawer
    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dpi(this, 14), Ui.dpi(this, 12), Ui.dpi(this, 14), Ui.dpi(this, 14));
        return col;
    }

    private LinearLayout.LayoutParams row(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dpi(this, topDp);
        return lp;
    }

    private TextView heading(String s) {
        TextView t = Ui.text(this, s, 15, Ui.TXT, true);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView section(String s) {
        TextView t = Ui.text(this, s, 12, Ui.TXT, true);
        t.setPadding(0, Ui.dpi(this, 14), 0, Ui.dpi(this, 4));
        return t;
    }

    private void toggleMenu() {
        if (menuOpen) { menuOpen = false; hideDrawer(); return; }
        overlay.select(null);
        showMenu();
    }

    private void showDrawer() {
        boolean wasHidden = drawer.getVisibility() != View.VISIBLE;
        drawer.animate().cancel();
        drawer.setVisibility(View.VISIBLE);
        if (wasHidden) drawer.setTranslationX(Ui.dp(this, 280));
        drawer.animate().translationX(0).setDuration(210).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    private void hideDrawer() {
        if (drawer.getVisibility() != View.VISIBLE) return;
        drawer.animate().cancel();
        drawer.animate().translationX(Ui.dp(this, 280)).setDuration(160)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> drawer.setVisibility(View.GONE)).start();
    }

    private void showMenu() {
        menuOpen = true;
        LinearLayout col = column();
        col.addView(heading("Edit Control Layout"));
        col.addView(section("Edit Control Layout"), row(6));
        col.addView(flatAction("Add Button", () -> {
            overlay.add(ControlData.button("Button", 0.40f, 0.40f, 64, 64));
            menuOpen = false;
        }), row(4));
        col.addView(flatAction("Add Joystick", () -> {
            overlay.add(ControlData.joystick(0.40f, 0.30f, 150));
            menuOpen = false;
        }), row(6));
        col.addView(section("Save  (" + layoutName + ")"));
        col.addView(flatAction("Load", this::loadDialog), row(4));
        col.addView(flatAction("Save", this::saveDialog), row(6));
        col.addView(flatAction("Reset to default", this::confirmReset), row(6));
        col.addView(flatAction("Exit", this::exit), row(14));
        drawerScroll.removeAllViews();
        drawerScroll.addView(col);
        col.setAlpha(0f);
        col.animate().alpha(1f).setDuration(180).start();
        showDrawer();
    }

    private TextView flatAction(String label, Runnable r) {
        TextView t = Ui.flat(this, label);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    // ------------------------------------------------------------------ properties
    private void showProps(final ControlData d) {
        menuOpen = false;
        LinearLayout col = column();
        col.addView(heading(d.isJoystick() ? "Joystick" : "Button"));

        if (!d.isJoystick()) {
            col.addView(section("Name"));
            EditText name = field(d.label, InputType.TYPE_CLASS_TEXT);
            name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12)});
            name.addTextChangedListener(new Watch(s -> { d.label = s; overlay.refresh(); dirty = true; }));
            col.addView(name);
        }

        col.addView(section("Size"));
        LinearLayout sz = new LinearLayout(this);
        sz.setGravity(Gravity.CENTER_VERTICAL);
        final EditText w = field(fmt(d.w), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        final EditText h = field(fmt(d.h), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        w.addTextChangedListener(new Watch(s -> { float v = num(s); if (v >= 24) { d.w = v; if (d.isJoystick()) d.h = v; overlay.refresh(); dirty = true; } }));
        h.addTextChangedListener(new Watch(s -> { float v = num(s); if (v >= 24 && !d.isJoystick()) { d.h = v; overlay.refresh(); dirty = true; } }));
        sz.addView(w, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sz.addView(Ui.text(this, "  x  ", 14, Ui.TXT, false));
        sz.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(sz);

        if (!d.isJoystick()) {
            col.addView(section("Mapping"));
            for (int r = 0; r < 2; r++) {
                LinearLayout line = new LinearLayout(this);
                line.setGravity(Gravity.CENTER_VERTICAL);
                for (int c = 0; c < 2; c++) {
                    final int slot = r * 2 + c;
                    final TextView t = slotView(d, slot);
                    line.addView(t, new LinearLayout.LayoutParams(0, Ui.dpi(this, 40), 1f));
                    if (c == 0) line.addView(Ui.text(this, " + ", 14, Ui.TXT, false));
                }
                col.addView(line, row(r == 0 ? 0 : 6));
            }
            col.addView(toggle("Toggleable", d.toggle, v -> { d.toggle = v; dirty = true; }), row(10));
            col.addView(toggle("Mouse Passthrough", d.passthrough, v -> { d.passthrough = v; dirty = true; }), row(2));
            col.addView(toggle("Swipeable", d.swipeable, v -> { d.swipeable = v; dirty = true; }), row(2));
        }

        TextView color = Ui.flat(this, "Background Color   >");
        color.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        color.setOnClickListener(v -> colorDialog(d));
        col.addView(color, row(14));

        drawerScroll.removeAllViews();
        drawerScroll.addView(col);
        col.setAlpha(0f);
        col.animate().alpha(1f).setDuration(180).start();
        showDrawer();
    }

    private TextView slotView(final ControlData d, final int slot) {
        final TextView t = Ui.flat(this, Keys.label(d.keys[slot]));
        t.setTextSize(12);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setOnClickListener(v -> KeyPicker.show(this, id -> {
            String old = d.keys[slot];
            d.keys[slot] = id;
            // first key names the button, unless the user typed their own name
            if (slot == 0 && (d.label.isEmpty() || d.label.equals("Button") || d.label.equals(Keys.shortLabel(old)))) {
                d.label = id.isEmpty() ? "Button" : Keys.shortLabel(id);
                showProps(d);
            }
            t.setText(Keys.label(id));
            overlay.refresh();
            dirty = true;
        }));
        return t;
    }

    private Switch toggle(String label, boolean on, final BoolCb cb) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(Ui.TXT);
        s.setChecked(on);
        s.setPadding(0, Ui.dpi(this, 6), 0, Ui.dpi(this, 6));
        s.setOnCheckedChangeListener((b, v) -> cb.on(v));
        return s;
    }

    private EditText field(String text, int type) {
        EditText e = new EditText(this);
        e.setText(text);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextColor(Ui.TXT);
        e.setTextSize(14);
        e.setGravity(Gravity.CENTER);
        e.setBackground(Ui.round(this, Ui.FLAT, 8));
        e.setPadding(Ui.dpi(this, 10), Ui.dpi(this, 10), Ui.dpi(this, 10), Ui.dpi(this, 10));
        return e;
    }

    private static String fmt(float v) { return String.format(java.util.Locale.US, "%.1f", v); }
    private static float num(String s) { try { return Float.parseFloat(s); } catch (Exception e) { return -1; } }

    private void colorDialog(final ControlData d) {
        final int[] colors = {0xFF000000, 0xFF121212, 0xFF3A3A3A, 0xFFFFFFFF, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835,
                0xFF43A047, 0xFF26A69A, 0xFF1E88E5, 0xFF8E24AA, 0xFFD81B60};
        final int[] cur = {d.bg & 0x00FFFFFF};
        final int[] alpha = {Math.max(20, Math.round(((d.bg >>> 24) / 255f) * 100))};
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dpi(this, 18), Ui.dpi(this, 8), Ui.dpi(this, 18), Ui.dpi(this, 4));
        for (int r = 0; r < 2; r++) {
            LinearLayout line = new LinearLayout(this);
            for (int c = 0; c < 6; c++) {
                final int color = colors[r * 6 + c];
                View sw = new View(this);
                sw.setBackground(Ui.round(this, color, 8));
                sw.setOnClickListener(v -> { cur[0] = color & 0x00FFFFFF; d.bg = ((int) (alpha[0] / 100f * 255) << 24) | cur[0]; overlay.refresh(); dirty = true; });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dpi(this, 40), 1f);
                lp.setMargins(Ui.dpi(this, 3), Ui.dpi(this, 3), Ui.dpi(this, 3), Ui.dpi(this, 3));
                line.addView(sw, lp);
            }
            col.addView(line);
        }
        col.addView(Ui.text(this, "Opacity", 12, Ui.DIM, false));
        SeekBar sb = new SeekBar(this);
        sb.setMax(80);
        sb.setProgress(alpha[0] - 20);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                alpha[0] = p + 20;
                d.bg = ((int) (alpha[0] / 100f * 255) << 24) | cur[0];
                overlay.refresh();
                dirty = true;
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        col.addView(sb);
        new AlertDialog.Builder(this).setTitle("Background color").setView(col).setPositiveButton("Done", null).show();
    }

    // ------------------------------------------------------------------ save / load / reset / exit
    private void saveDialog() {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(layoutName);
        new AlertDialog.Builder(this)
                .setTitle("Save layout as")
                .setView(et)
                .setPositiveButton("Save", (dd, w) -> {
                    String n = et.getText().toString().trim().replaceAll("[^A-Za-z0-9_ -]", "");
                    if (n.isEmpty()) n = ControlStore.DEFAULT;
                    try {
                        ControlStore.save(this, n, overlay.getData());
                        ControlStore.setCurrent(this, n);
                        layoutName = n;
                        dirty = false;
                        Toast.makeText(this, "Saved: " + n, Toast.LENGTH_SHORT).show();
                        showMenu();
                    } catch (Exception e) {
                        Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void loadDialog() {
        final List<String> names = ControlStore.names(this);
        new AlertDialog.Builder(this)
                .setTitle("Load layout")
                .setItems(names.toArray(new String[0]), (dd, which) -> {
                    layoutName = names.get(which);
                    ControlStore.setCurrent(this, layoutName);
                    overlay.select(null);
                    overlay.getData().clear();
                    overlay.getData().addAll(ControlStore.load(this, layoutName));
                    overlay.rebuild();
                    dirty = false;
                    showMenu();
                })
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("Reset to the default layout?")
                .setPositiveButton("Reset", (dd, w) -> {
                    overlay.select(null);
                    overlay.getData().clear();
                    overlay.getData().addAll(ControlStore.defaults(this));
                    overlay.rebuild();
                    dirty = true;
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void exit() {
        if (!dirty) { finish(); return; }
        new AlertDialog.Builder(this)
                .setTitle("Save changes?")
                .setPositiveButton("Save", (dd, w) -> {
                    try { ControlStore.save(this, layoutName, overlay.getData()); ControlStore.setCurrent(this, layoutName); } catch (Exception ignored) { }
                    finish();
                })
                .setNegativeButton("Discard", (dd, w) -> finish())
                .setNeutralButton("Cancel", null)
                .show();
    }

    @Override public void onBackPressed() { exit(); }

    // ------------------------------------------------------------------ helpers
    private interface BoolCb { void on(boolean v); }
    private interface StrCb { void on(String s); }

    private static final class Watch implements TextWatcher {
        private final StrCb cb;
        Watch(StrCb cb) { this.cb = cb; }
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void afterTextChanged(Editable e) { cb.on(e.toString()); }
    }

    private static final class NoopSink implements InputSink {
        @Override public void key(int k, boolean d) { }
        @Override public void mouseButton(int b, boolean d) { }
        @Override public void mouseMove(float dx, float dy) { }
        @Override public void scroll(double dy) { }
        @Override public void special(String id) { }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
}
