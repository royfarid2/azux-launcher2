package com.azux.launcher.controls;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** One drawn control. Play mode fires input; edit mode drags/resizes. Flat dark style. */
public final class ControlView extends View {
    public static final int TEAL = 0xFF5ED3C6;

    public final ControlData data;
    private final boolean edit;
    private final InputSink sink;
    private final ControlsOverlay overlay;
    private final float density;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private boolean pressed, latched, selected, selfHeld, resizing;
    private int activeId = -1;
    private float jx, jy, lastX, lastY, lastRawX, lastRawY, downRawX, downRawY;
    private boolean dragging;
    private final float slop;
    private ControlView swipeCur;
    private final boolean[] dir = new boolean[4]; // W A S D
    private static final int[] DIR_KEYS = {87, 65, 83, 68};

    public ControlView(Context c, ControlData data, boolean edit, InputSink sink, ControlsOverlay overlay) {
        super(c);
        this.data = data;
        this.edit = edit;
        this.sink = sink;
        this.overlay = overlay;
        this.density = c.getResources().getDisplayMetrics().density;
        this.slop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
        line.setStyle(Paint.Style.STROKE);
        txt.setTextAlign(Paint.Align.CENTER);
    }

    public void setSelected(boolean s) { selected = s; invalidate(); }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
    }

    // ------------------------------------------------------------------ drawing
    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        boolean on = pressed || latched;
        if (data.isJoystick()) {
            float r = Math.min(w, h) / 2f - density * 2;
            fill.setColor(data.bg);
            c.drawCircle(w / 2, h / 2, r, fill);
            line.setStrokeWidth(density * 1.5f);
            line.setColor(0xCCFFFFFF);
            c.drawCircle(w / 2, h / 2, r, line);
            float tr = r * 0.36f;
            float tx = w / 2 + jx * (r - tr), ty = h / 2 + jy * (r - tr);
            fill.setColor(on ? 0xFF2A2A2A : 0xFF000000);
            c.drawCircle(tx, ty, tr, fill);
        } else {
            rect.set(0, 0, w, h);
            float rad = density * 4;
            fill.setColor(on ? (data.bg & 0xFF000000) | 0x00606060 : data.bg);
            c.drawRoundRect(rect, rad, rad, fill);
            line.setStrokeWidth(density);
            line.setColor(0x33FFFFFF);
            rect.set(density / 2, density / 2, w - density / 2, h - density / 2);
            c.drawRoundRect(rect, rad, rad, line);
            txt.setColor(0xFFFFFFFF);
            float ts = Math.min(w, h) * 0.30f;
            txt.setTextSize(ts);
            while (txt.measureText(data.label) > w * 0.88f && ts > density * 7) { ts -= 1; txt.setTextSize(ts); }
            c.drawText(data.label, w / 2, h / 2 - (txt.descent() + txt.ascent()) / 2, txt);
        }
        if (edit && selected) {
            line.setColor(TEAL);
            line.setStrokeWidth(density * 2);
            rect.set(density, density, w - density, h - density);
            if (data.isJoystick()) c.drawCircle(w / 2, h / 2, Math.min(w, h) / 2f - density, line);
            else c.drawRoundRect(rect, density * 4, density * 4, line);
            fill.setColor(TEAL);
            c.drawCircle(w - density * 8, h - density * 8, density * 8, fill);
        }
    }

    // ------------------------------------------------------------------ touch
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return edit ? editTouch(e) : playTouch(e);
    }

    private boolean editTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = lastRawX = e.getRawX();
                downRawY = lastRawY = e.getRawY();
                dragging = false;
                float hit = density * 30;
                resizing = selected && e.getX() > getWidth() - hit && e.getY() > getHeight() - hit;
                overlay.selectQuiet(data);          // highlight only; the panel opens on a tap
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) {
                    if (Math.hypot(e.getRawX() - downRawX, e.getRawY() - downRawY) <= slop) return true;
                    dragging = true;
                    overlay.dragStarted();
                    lastRawX = downRawX;            // apply the whole distance from the touch point
                    lastRawY = downRawY;
                }
                float dx = e.getRawX() - lastRawX, dy = e.getRawY() - lastRawY;
                lastRawX = e.getRawX();
                lastRawY = e.getRawY();
                if (resizing) overlay.resizeControl(data, dx, dy);
                else overlay.moveControl(data, dx, dy);
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (!dragging) overlay.tapped(data);
                dragging = false;
                resizing = false;
                return true;
            default:
                dragging = false;
                resizing = false;
                return true;
        }
    }

    private boolean playTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activeId = e.getPointerId(0);
                lastX = e.getX(0);
                lastY = e.getY(0);
                down(lastX, lastY);
                return true;
            case MotionEvent.ACTION_MOVE: {
                int i = e.findPointerIndex(activeId);
                if (i >= 0) move(e.getX(i), e.getY(i));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                up();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (e.getPointerId(e.getActionIndex()) == activeId) up();
                return true;
            default:
                return true;
        }
    }

    private void bump(boolean on) {
        if (data.isJoystick()) return;
        if (on) animate().scaleX(0.9f).scaleY(0.9f).setDuration(50).start();
        else animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
    }

    private void down(float x, float y) {
        bump(true);
        pressed = true;
        selfHeld = true;
        if (data.isJoystick()) stick(x, y);
        else if (data.toggle) { latched = !latched; fireAll(latched); }
        else fireAll(true);
        invalidate();
    }

    private void move(float x, float y) {
        if (data.isJoystick()) { stick(x, y); return; }
        if (data.passthrough) sink.mouseMove(x - lastX, y - lastY);
        lastX = x;
        lastY = y;
        if (data.swipeable) swipe(x, y);
    }

    private void swipe(float x, float y) {
        boolean inside = x >= 0 && y >= 0 && x <= getWidth() && y <= getHeight();
        if (inside) {
            if (swipeCur != null) {
                swipeCur.virtualUp();
                swipeCur = null;
                if (!selfHeld) { selfHeld = true; fireAll(true); }
            }
            return;
        }
        ControlView t = overlay.swipeTarget(getLeft() + x, getTop() + y, this);
        if (t != swipeCur) {
            if (swipeCur != null) swipeCur.virtualUp();
            if (selfHeld && !data.toggle) { selfHeld = false; fireAll(false); }
            swipeCur = t;
            if (t != null) t.virtualDown();
        }
    }

    private void up() {
        bump(false);
        if (swipeCur != null) { swipeCur.virtualUp(); swipeCur = null; }
        pressed = false;
        activeId = -1;
        if (data.isJoystick()) {
            jx = jy = 0;
            for (int i = 0; i < 4; i++) setDir(i, false);
        } else if (!data.toggle && selfHeld) {
            fireAll(false);
        }
        selfHeld = false;
        invalidate();
    }

    private void virtualDown() { bump(true); pressed = true; fireAll(true); invalidate(); }
    private void virtualUp() { bump(false); pressed = false; fireAll(false); invalidate(); }

    /** Releases everything this control is holding (screen paused, activity closing). */
    public void release() {
        if (activeId != -1) up();
        if (latched) { latched = false; fireAll(false); invalidate(); }
    }

    private void stick(float x, float y) {
        float r = getWidth() / 2f;
        float dx = (x - r) / r, dy = (y - getHeight() / 2f) / r;
        float len = (float) Math.hypot(dx, dy);
        if (len > 1f) { dx /= len; dy /= len; }
        jx = dx;
        jy = dy;
        float dead = 0.3f;
        setDir(0, dy < -dead);
        setDir(1, dx < -dead);
        setDir(2, dy > dead);
        setDir(3, dx > dead);
        invalidate();
    }

    private void setDir(int i, boolean state) {
        if (dir[i] == state) return;
        dir[i] = state;
        sink.key(DIR_KEYS[i], state);
    }

    private void fireAll(boolean down) {
        for (String id : data.keys) if (id != null && !id.isEmpty()) fire(id, down);
    }

    private void fire(String id, boolean down) {
        Keys.K k = Keys.find(id);
        if (k == null) return;
        switch (k.kind) {
            case Keys.KEY:
                sink.key(k.code, down);
                break;
            case Keys.MOUSE:
                sink.mouseButton(k.code, down);
                break;
            default:
                if (!down) break;
                if (id.equals("SCROLL_UP")) sink.scroll(1);
                else if (id.equals("SCROLL_DOWN")) sink.scroll(-1);
                else sink.special(id);
        }
    }
}
