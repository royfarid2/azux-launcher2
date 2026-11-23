package com.azux.launcher.controls;

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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** On-screen keyboard used to choose what a control does. */
public final class KeyPicker {
    private KeyPicker() { }

    public interface Callback { void picked(String id); }

    private static final class Cell {
        final String label, id;
        final float weight;
        Cell(String label, String id, float weight) { this.label = label; this.id = id; this.weight = weight; }
    }

    private static Cell c(String label, String id) { return new Cell(label, id, 1f); }
    private static Cell c(String label, String id, float w) { return new Cell(label, id, w); }
    private static Cell gap(float w) { return new Cell(null, null, w); }
    private static Cell k(String id) { return new Cell(Keys.find(id).label, id, 1f); }
    private static Cell k(String id, float w) { return new Cell(Keys.find(id).label, id, w); }

    public static void show(final Activity a, final Callback cb) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final float dn = a.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF262626);
        bg.setCornerRadius(12 * dn);
        root.setBackground(bg);
        root.setPadding((int) (8 * dn), (int) (6 * dn), (int) (8 * dn), (int) (8 * dn));

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView clear = btn(a, "Unspecified (clear)", dn, 0xFF3A3A3A);
        clear.setOnClickListener(v -> { d.dismiss(); cb.picked(""); });
        top.addView(clear, new LinearLayout.LayoutParams(0, (int) (30 * dn), 1f));
        TextView x = btn(a, "X", dn, 0xFF3A3A3A);
        x.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams xp = new LinearLayout.LayoutParams((int) (40 * dn), (int) (30 * dn));
        xp.leftMargin = (int) (8 * dn);
        top.addView(x, xp);
        root.addView(top);

        LinearLayout rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        Cell[][] layout = {
                {k("KEY_ESC"), gap(0.5f), k("KEY_F1"), k("KEY_F2"), k("KEY_F3"), k("KEY_F4"), gap(0.3f), k("KEY_F5"), k("KEY_F6"), k("KEY_F7"), k("KEY_F8"), gap(0.3f), k("KEY_F9"), k("KEY_F10"), k("KEY_F11"), k("KEY_F12"), gap(0.3f), k("KEY_PAUSE"), k("KEY_INSERT"), k("KEY_HOME")},
                {k("KEY_GRAVE"), k("KEY_1"), k("KEY_2"), k("KEY_3"), k("KEY_4"), k("KEY_5"), k("KEY_6"), k("KEY_7"), k("KEY_8"), k("KEY_9"), k("KEY_0"), k("KEY_MINUS"), k("KEY_EQUAL"), k("KEY_BACKSPACE", 2f), gap(0.3f), k("KEY_END"), k("KEY_PGUP"), k("KEY_PGDN"), gap(0.3f), k("KEY_NUMLOCK"), k("KP_DIVIDE")},
                {k("KEY_TAB", 1.5f), k("KEY_Q"), k("KEY_W"), k("KEY_E"), k("KEY_R"), k("KEY_T"), k("KEY_Y"), k("KEY_U"), k("KEY_I"), k("KEY_O"), k("KEY_P"), k("KEY_LBRACKET"), k("KEY_RBRACKET"), k("KEY_BACKSLASH", 1.5f), gap(3.6f), k("KP_7"), k("KP_8"), k("KP_9")},
                {k("KEY_CAPS", 1.8f), k("KEY_A"), k("KEY_S"), k("KEY_D"), k("KEY_F"), k("KEY_G"), k("KEY_H"), k("KEY_J"), k("KEY_K"), k("KEY_L"), k("KEY_SEMICOLON"), k("KEY_APOSTROPHE"), k("KEY_ENTER", 2.2f), gap(3.6f), k("KP_4"), k("KP_5"), k("KP_6")},
                {k("KEY_LSHIFT", 2.3f), k("KEY_Z"), k("KEY_X"), k("KEY_C"), k("KEY_V"), k("KEY_B"), k("KEY_N"), k("KEY_M"), k("KEY_COMMA"), k("KEY_PERIOD"), k("KEY_SLASH"), k("KEY_RSHIFT", 2.7f), gap(1.3f), k("KEY_UP"), gap(1.3f), k("KP_1"), k("KP_2"), k("KP_3")},
                {k("KEY_LCTRL", 1.5f), k("KEY_LALT", 1.5f), k("KEY_SPACE", 7f), k("KEY_RALT", 1.5f), k("KEY_RCTRL", 1.5f), gap(1.3f), k("KEY_LEFT"), k("KEY_DOWN"), k("KEY_RIGHT"), gap(0.3f), k("KP_0", 2f)},
                {k("KEYBOARD", 1.2f), k("GUI"), k("MOUSE_LEFT", 1.3f), k("MOUSE_RIGHT", 1.3f), k("MOUSE_MODE"), k("MOUSE_MIDDLE", 2.4f), k("SCROLL_UP", 2.4f), k("SCROLL_DOWN", 2.4f)},
        };
        for (Cell[] row : layout) {
            LinearLayout r = new LinearLayout(a);
            for (Cell cell : row) {
                View v;
                if (cell.id == null) {
                    v = new View(a);
                } else {
                    TextView t = btn(a, cell.label, dn, 0xFF3A3A3A);
                    t.setTextSize(cell.label.length() > 8 ? 9 : 11);
                    final String id = cell.id;
                    t.setOnClickListener(vv -> { d.dismiss(); cb.picked(id); });
                    v = t;
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (32 * dn), cell.weight);
                lp.setMargins((int) dn, (int) dn, (int) dn, (int) dn);
                r.addView(v, lp);
            }
            rows.addView(r);
        }
        ScrollView sv = new ScrollView(a);
        sv.addView(rows);
        root.addView(sv);

        d.setContentView(root);
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (a.getResources().getDisplayMetrics().widthPixels * 0.94f), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static TextView btn(Activity a, String label, float dn, int color) {
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(11);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(5 * dn);
        t.setBackground(g);
        return t;
    }
}
