package com.azux.launcher.controls;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Holds the camera pad (play mode only) and all controls in ONE view group, so every finger is routed
 * to the right control independently (move + look + jump at the same time).
 */
public final class ControlsOverlay extends FrameLayout {
    public interface Listener {
        void onSelect(ControlData d);
        void onChanged();
        void onDelete(ControlData d);
        void onClone(ControlData d);
        /** The user started dragging/resizing a control (panels should close). */
        void onDragStart();
    }

    private final List<ControlData> data;
    private final boolean edit;
    private final InputSink sink;
    private final float density;
    private Listener listener;
    private ControlData selected;
    private LookPad look;
    private TextView chipDelete, chipClone;

    public ControlsOverlay(Context c, List<ControlData> data, boolean edit, InputSink sink) {
        super(c);
        this.data = data;
        this.edit = edit;
        this.sink = sink;
        this.density = c.getResources().getDisplayMetrics().density;
        setMotionEventSplittingEnabled(true);
        if (!edit) {
            look = new LookPad(c, sink);
            addView(look);
        } else {
            chipDelete = chip("Delete");
            chipClone = chip("Clone");
            chipDelete.setOnClickListener(v -> { if (listener != null && selected != null) listener.onDelete(selected); });
            chipClone.setOnClickListener(v -> { if (listener != null && selected != null) listener.onClone(selected); });
        }
        rebuild();
    }

    private TextView chip(String s) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding((int) (14 * density), (int) (8 * density), (int) (14 * density), (int) (8 * density));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF3A3A3A);
        g.setCornerRadius(density * 8);
        t.setBackground(g);
        t.setVisibility(GONE);
        return t;
    }

    public void setListener(Listener l) { listener = l; }
    public List<ControlData> getData() { return data; }
    public ControlData getSelected() { return selected; }

    public void rebuild() {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            if (getChildAt(i) instanceof ControlView) removeViewAt(i);
        }
        for (ControlData d : data) {
            ControlView v = new ControlView(getContext(), d, edit, sink, this);
            v.setSelected(d == selected);
            addView(v);
        }
        if (edit) {
            removeView(chipDelete);
            removeView(chipClone);
            addView(chipDelete);
            addView(chipClone);
            updateChips();
        }
        requestLayout();
    }

    public void add(ControlData d) {
        data.add(d);
        selected = d;
        rebuild();
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c instanceof ControlView && ((ControlView) c).data == d) com.azux.launcher.Anim.popIn(c);
        }
        notifyChanged();
        if (listener != null) listener.onSelect(d);
    }

    public void remove(ControlData d) {
        data.remove(d);
        if (selected == d) selected = null;
        rebuild();
        if (listener != null) listener.onSelect(selected);
        notifyChanged();
    }

    public void select(ControlData d) {
        selected = d;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c instanceof ControlView) ((ControlView) c).setSelected(((ControlView) c).data == d);
        }
        updateChips();
        requestLayout();
        if (listener != null) listener.onSelect(d);
    }

    /** Highlights a control (and shows Delete / Clone) without opening its settings. */
    public void selectQuiet(ControlData d) {
        selected = d;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c instanceof ControlView) ((ControlView) c).setSelected(((ControlView) c).data == d);
        }
        updateChips();
        requestLayout();
    }

    /** A plain tap on a control: now open its settings. */
    public void tapped(ControlData d) { if (listener != null) listener.onSelect(d); }

    public void dragStarted() { if (listener != null) listener.onDragStart(); }

    private void updateChips() {
        if (!edit) return;
        boolean was = chipDelete.getVisibility() == VISIBLE;
        int vis = selected == null ? GONE : VISIBLE;
        chipDelete.setVisibility(vis);
        chipClone.setVisibility(vis);
        if (vis == VISIBLE && !was) {
            com.azux.launcher.Anim.popIn(chipDelete);
            com.azux.launcher.Anim.popIn(chipClone);
        }
    }

    public void notifyChanged() { if (listener != null) listener.onChanged(); }

    /** Call after changing a ControlData's fields from outside (label, size, color...). */
    public void refresh() {
        requestLayout();
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).invalidate();
    }

    private void clamp(ControlData d) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float maxX = 1f - d.w * density / w, maxY = 1f - d.h * density / h;
        d.x = Math.max(0f, Math.min(d.x, Math.max(0f, maxX)));
        d.y = Math.max(0f, Math.min(d.y, Math.max(0f, maxY)));
    }

    public void moveControl(ControlData d, float dxPx, float dyPx) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        d.x += dxPx / w;
        d.y += dyPx / h;
        clamp(d);
        requestLayout();
        notifyChanged();
    }

    public void resizeControl(ControlData d, float dxPx, float dyPx) {
        float nw = Math.max(24f, d.w + dxPx / density), nh = Math.max(24f, d.h + dyPx / density);
        if (d.isJoystick()) { nw = nh = Math.max(nw, nh); }
        d.w = nw;
        d.h = nh;
        clamp(d);
        requestLayout();
        notifyChanged();
    }

    public ControlView swipeTarget(float px, float py, ControlView except) {
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c == except || !(c instanceof ControlView)) continue;
            ControlView cv = (ControlView) c;
            if (cv.data.swipeable && px >= cv.getLeft() && px <= cv.getRight() && py >= cv.getTop() && py <= cv.getBottom()) return cv;
        }
        return null;
    }

    /** Releases every held key/button (screen paused, activity closing). */
    public void releaseAll() {
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c instanceof ControlView) ((ControlView) c).release();
            else if (c instanceof LookPad) ((LookPad) c).release();
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c instanceof ControlView) {
                ControlData d = ((ControlView) c).data;
                c.measure(MeasureSpec.makeMeasureSpec(Math.max(1, (int) (d.w * density)), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.max(1, (int) (d.h * density)), MeasureSpec.EXACTLY));
            } else if (c == chipDelete || c == chipClone) {
                c.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST));
            } else {
                c.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        int selL = 0, selT = 0, selB = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c instanceof ControlView) {
                ControlData d = ((ControlView) c).data;
                int cw = Math.max(1, (int) (d.w * density)), ch = Math.max(1, (int) (d.h * density));
                int left = (int) (d.x * w), top = (int) (d.y * h);
                c.layout(left, top, left + cw, top + ch);
                if (d == selected) { selL = left; selT = top; selB = top + ch; }
            } else if (c != chipDelete && c != chipClone) {
                c.layout(0, 0, w, h);
            }
        }
        if (edit && selected != null) {
            int gap = (int) (6 * density);
            int cw1 = chipDelete.getMeasuredWidth(), ch1 = chipDelete.getMeasuredHeight(), cw2 = chipClone.getMeasuredWidth();
            int top = selT - ch1 - gap;
            if (top < 0) top = Math.min(h - ch1, selB + gap);
            int left = Math.max(0, Math.min(selL, w - cw1 - cw2 - gap));
            chipDelete.layout(left, top, left + cw1, top + ch1);
            chipClone.layout(left + cw1 + gap, top, left + cw1 + gap + cw2, top + ch1);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (edit && e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            select(null);
            return true;
        }
        return false;
    }
}
