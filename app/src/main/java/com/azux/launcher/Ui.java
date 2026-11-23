package com.azux.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.TextView;

/** Small view helpers + the Azux palette. No XML layouts: the whole UI is built in code. */
public final class Ui {
    private Ui() { }

    public static final int BG = 0xFF121212, SIDE = 0xFF1A1A1A, CARD = 0xFF212121, CARD2 = 0xFF2E2E2E, FLAT = 0xFF3A3A3A;
    public static final int ACCENT = 0xFFDCEBFA, TXT = 0xFFFFFFFF, DIM = 0xFFA0A4A8, BAD = 0xFFE57373, TEAL = 0xFF5ED3C6, GOOD = 0xFF66BB6A;

    public static float dp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    public static int dpi(Context c, float v) { return Math.round(dp(c, v)); }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static Button button(Context c, String label) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.BLACK);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(round(c, ACCENT, 12));
        b.setStateListAnimator(null);
        Anim.press(b);
        return b;
    }

    /** Flat dark button (matches the Downloads / controls screens). */
    public static TextView flat(Context c, String label) {
        TextView t = text(c, label, 14, TXT, false);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(dpi(c, 14), dpi(c, 10), dpi(c, 14), dpi(c, 10));
        t.setBackground(round(c, FLAT, 8));
        Anim.press(t);
        return t;
    }

    public static TextView chip(Context c, String label, int bg, int fg) {
        TextView t = text(c, label, 10, fg, false);
        t.setPadding(dpi(c, 6), dpi(c, 2), dpi(c, 6), dpi(c, 2));
        t.setBackground(round(c, bg, 5));
        return t;
    }

    public static android.view.View iconBtn(Context c, int icon, int color, Runnable onClick) {
        android.widget.FrameLayout f = new android.widget.FrameLayout(c);
        f.addView(Icons.view(c, icon, 22, color), new android.widget.FrameLayout.LayoutParams(dpi(c, 22), dpi(c, 22), android.view.Gravity.CENTER));
        f.setOnClickListener(v -> onClick.run());
        Anim.press(f);
        return f;
    }
}
