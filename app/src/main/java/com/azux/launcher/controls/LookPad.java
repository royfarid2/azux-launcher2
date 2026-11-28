package com.azux.launcher.controls;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Full-screen camera pad (bottom layer of the overlay).
 * Drag = look around. Quick tap = left click. Press and hold = hold left click (mine / attack).
 */
public final class LookPad extends View {
    private final InputSink sink;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float slop;
    private int pid = -1;
    private float lx, ly, sx, sy;
    private long downAt;
    private boolean moved, holding;

    private final Runnable holdRun;

    public LookPad(Context c, InputSink sink) {
        super(c);
        this.sink = sink;
        this.slop = ViewConfiguration.get(c).getScaledTouchSlop();
        this.holdRun = () -> {
            if (!moved && pid != -1) {
                holding = true;
                this.sink.mouseButton(0, true);
            }
        };
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pid = e.getPointerId(0);
                lx = sx = e.getX(0);
                ly = sy = e.getY(0);
                downAt = e.getEventTime();
                moved = false;
                holding = false;
                sink.touchDown(lx, ly);
                handler.postDelayed(holdRun, 350);
                return true;
            case MotionEvent.ACTION_MOVE: {
                int i = e.findPointerIndex(pid);
                if (i < 0) return true;
                float x = e.getX(i), y = e.getY(i);
                if (!moved && Math.hypot(x - sx, y - sy) > slop) {
                    moved = true;
                    handler.removeCallbacks(holdRun);
                }
                if (moved || holding) sink.mouseMove(x - lx, y - ly);
                lx = x;
                ly = y;
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                finish(e.getEventTime(), e.getActionMasked() == MotionEvent.ACTION_UP);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (e.getPointerId(e.getActionIndex()) == pid) finish(e.getEventTime(), true);
                return true;
            default:
                return true;
        }
    }

    private void finish(long now, boolean allowTap) {
        handler.removeCallbacks(holdRun);
        if (holding) {
            sink.mouseButton(0, false);
        } else if (allowTap && !moved && now - downAt < 300) {
            sink.mouseButton(0, true);
            handler.postDelayed(() -> sink.mouseButton(0, false), 40);
        }
        holding = false;
        pid = -1;
    }

    public void release() {
        handler.removeCallbacks(holdRun);
        if (holding) sink.mouseButton(0, false);
        holding = false;
        pid = -1;
    }
}
