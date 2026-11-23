package com.azux.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Hand-drawn vector icons (no image files, any colour, any size). */
public final class Icons {
    private Icons() { }

    public static final int HOME = 0, DOWNLOAD = 1, GEAR = 2, SEARCH = 3, CLOSE = 4, PACK = 5, PUZZLE = 6, WORLD = 7,
            BULB = 8, LAYERS = 9, USER = 10, PAD = 11, PLAY = 12, LINK = 13, PLUS = 14;

    public static View view(Context c, int type, float sizeDp, int color) {
        return new IconView(c, type, color);
    }

    private static final class IconView extends View {
        private final int type;
        private int color;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();

        IconView(Context c, int type, int color) {
            super(c);
            this.type = type;
            this.color = color;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()) / 24f;
            c.save();
            c.translate((getWidth() - 24 * s) / 2f, (getHeight() - 24 * s) / 2f);
            c.scale(s, s);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            switch (type) {
                case HOME:
                    path.reset(); path.moveTo(3, 11); path.lineTo(12, 3); path.lineTo(21, 11); c.drawPath(path, p);
                    path.reset(); path.moveTo(5, 10); path.lineTo(5, 21); path.lineTo(19, 21); path.lineTo(19, 10); c.drawPath(path, p);
                    path.reset(); path.moveTo(10, 21); path.lineTo(10, 14); path.lineTo(14, 14); path.lineTo(14, 21); c.drawPath(path, p);
                    break;
                case DOWNLOAD:
                    c.drawLine(12, 3, 12, 15, p);
                    path.reset(); path.moveTo(7, 10); path.lineTo(12, 15); path.lineTo(17, 10); c.drawPath(path, p);
                    path.reset(); path.moveTo(4, 15); path.lineTo(4, 20); path.lineTo(20, 20); path.lineTo(20, 15); c.drawPath(path, p);
                    break;
                case GEAR:
                    c.drawCircle(12, 12, 4, p);
                    c.drawCircle(12, 12, 7.5f, p);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI / 4 * i;
                        c.drawLine((float) (12 + Math.cos(a) * 7.5), (float) (12 + Math.sin(a) * 7.5),
                                (float) (12 + Math.cos(a) * 10.5), (float) (12 + Math.sin(a) * 10.5), p);
                    }
                    break;
                case SEARCH:
                    c.drawCircle(10, 10, 6.5f, p);
                    c.drawLine(15, 15, 21, 21, p);
                    break;
                case CLOSE:
                    c.drawLine(5, 5, 19, 19, p);
                    c.drawLine(19, 5, 5, 19, p);
                    break;
                case PACK:
                    r.set(3, 7, 21, 20); c.drawRoundRect(r, 2, 2, p);
                    c.drawLine(3, 7, 3, 5, p); c.drawLine(3, 5, 9, 5, p); c.drawLine(9, 5, 11, 7, p);
                    c.drawLine(12, 7, 12, 14, p);
                    break;
                case PUZZLE:
                    r.set(4, 9, 20, 21); c.drawRoundRect(r, 2, 2, p);
                    c.drawCircle(12, 6, 3, p);
                    break;
                case WORLD:
                    c.drawCircle(12, 12, 9, p);
                    r.set(8, 3, 16, 21); c.drawOval(r, p);
                    c.drawLine(3, 12, 21, 12, p);
                    break;
                case BULB:
                    c.drawCircle(12, 10, 6, p);
                    c.drawLine(9, 18, 15, 18, p);
                    c.drawLine(10, 21, 14, 21, p);
                    break;
                case LAYERS:
                    path.reset(); path.moveTo(3, 8); path.lineTo(12, 4); path.lineTo(21, 8); path.lineTo(12, 12); path.close(); c.drawPath(path, p);
                    path.reset(); path.moveTo(3, 12); path.lineTo(12, 16); path.lineTo(21, 12); c.drawPath(path, p);
                    path.reset(); path.moveTo(3, 16); path.lineTo(12, 20); path.lineTo(21, 16); c.drawPath(path, p);
                    break;
                case USER:
                    c.drawCircle(12, 8, 4, p);
                    r.set(4, 14, 20, 30); c.drawArc(r, 180, 180, false, p);
                    break;
                case PAD:
                    r.set(2, 7, 22, 18); c.drawRoundRect(r, 5, 5, p);
                    c.drawLine(5.5f, 12.5f, 9.5f, 12.5f, p); c.drawLine(7.5f, 10.5f, 7.5f, 14.5f, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(15.5f, 11.5f, 1.3f, p); c.drawCircle(18f, 13.5f, 1.3f, p);
                    break;
                case PLAY:
                    p.setStyle(Paint.Style.FILL);
                    path.reset(); path.moveTo(7, 4); path.lineTo(20, 12); path.lineTo(7, 20); path.close(); c.drawPath(path, p);
                    break;
                case LINK:
                    path.reset(); path.moveTo(10, 5); path.lineTo(5, 5); path.lineTo(5, 19); path.lineTo(19, 19); path.lineTo(19, 14); c.drawPath(path, p);
                    c.drawLine(11, 13, 20, 4, p);
                    path.reset(); path.moveTo(14, 4); path.lineTo(20, 4); path.lineTo(20, 10); c.drawPath(path, p);
                    break;
                case PLUS:
                    c.drawLine(12, 4, 12, 20, p);
                    c.drawLine(4, 12, 20, 12, p);
                    break;
                default:
                    break;
            }
            c.restore();
        }
    }
}
