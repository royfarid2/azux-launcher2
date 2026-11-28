package com.azux.launcher;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/** Small reusable animations used across the launcher. */
public final class Anim {
    private Anim() { }

    /** Button feel: shrinks a little while pressed, springs back on release. Does not eat clicks. */
    public static void press(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(new OvershootInterpolator(2.5f)).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** Fade + slide up, optionally delayed (use a growing delay for a staggered list). */
    public static void enter(final View v, int delayMs) {
        float dy = 14 * v.getResources().getDisplayMetrics().density;
        v.setAlpha(0f);
        v.setTranslationY(dy);
        v.animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(280)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    /** Page switch: fade + slide in from the right. */
    public static void pageIn(View v) {
        float dx = 28 * v.getResources().getDisplayMetrics().density;
        v.setAlpha(0f);
        v.setTranslationX(dx);
        v.animate().alpha(1f).translationX(0f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
    }

    /** Pops in with a small overshoot (used for chips and new controls). */
    public static void popIn(View v) {
        v.setAlpha(0f);
        v.setScaleX(0.6f);
        v.setScaleY(0.6f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(new OvershootInterpolator(2.2f)).start();
    }

    /** Gentle endless "breathing" pulse; stops by itself when the view leaves the screen. */
    public static void pulse(final View v) {
        final ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.035f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.035f));
        a.setDuration(950);
        a.setRepeatCount(ObjectAnimator.INFINITE);
        a.setRepeatMode(ObjectAnimator.REVERSE);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View x) { if (!a.isStarted()) a.start(); }
            @Override public void onViewDetachedFromWindow(View x) { a.cancel(); x.setScaleX(1f); x.setScaleY(1f); }
        });
        if (v.isAttachedToWindow()) a.start();
    }
}
