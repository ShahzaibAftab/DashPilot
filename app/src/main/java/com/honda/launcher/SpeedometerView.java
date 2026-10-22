package com.honda.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/**
 * Racing-style gauge: 0-200 km/h dial with ticks, red zone, glowing progress arc,
 * tapered needle, italic digital speed and a 12-light shift bar.
 * The dial face (ticks, numbers, red zone) is drawn once into a bitmap, so each frame
 * only draws the arc, needle and number. Drawn in a 300x300 unit space, scaled to fit.
 */
public class SpeedometerView extends View {
    private static final float MAX = 200f;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF(32, 32, 268, 268);   // r = 118
    private final Path needle = new Path();
    private Bitmap face;
    private float u, ox, oy, shown, target;
    private boolean animating, warn;
    private float lastS, accel;       // accel in km/h per second
    private long lastT;
    private final float[] hsv = {0f, 1f, 1f};

    private final Runnable step = new Runnable() {
        @Override public void run() {
            float d = target - shown;
            if (Math.abs(d) < 0.05f) { shown = target; animating = false; }
            else { shown += d * 0.25f; postOnAnimation(this); }
            invalidate();
        }
    };

    public SpeedometerView(Context c) {
        super(c);
        txt.setTextAlign(Paint.Align.CENTER);
        needle.moveTo(168, 145); needle.lineTo(262, 150); needle.lineTo(168, 155); needle.close();
    }

    private static int ramp(float t) {
        final int[] st = {0xFFF2F2F2, 0xFFFF6B5E, 0xFFFF1F2D};   // white -> salmon -> red
        float x = t * 2f;
        int i = Math.min((int) x, 1);
        float f = x - i;
        int a = st[i], b = st[i + 1];
        return Color.rgb(
                (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * f),
                (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * f),
                (int) ((a & 255) + ((b & 255) - (a & 255)) * f));
    }

    /** Turns the big number red (used when any side gauge is in its danger zone). */
    public void setWarn(boolean w) { if (w != warn) { warn = w; invalidate(); } }

    public void setSpeed(float s) {
        long now = SystemClock.uptimeMillis();
        if (now - lastT >= 500) {
            if (lastT != 0) accel = accel * 0.5f + 0.5f * ((s - lastS) / ((now - lastT) / 1000f));
            lastS = s; lastT = now;
        }
        target = s;
        if (!animating) { animating = true; postOnAnimation(step); }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w <= 0 || h <= 0) return;
        u = Math.min(w, h) / 282f;                  // dial spans y = 18..300 of the 300-unit space
        ox = (w - 300 * u) / 2f;
        oy = (h - 282 * u) / 2f - 18 * u;
        if (face != null) face.recycle();
        face = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(face);
        c.translate(ox, oy);
        c.scale(u, u);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(8);
        p.setColor(0xFF1B2230);
        c.drawArc(rect, 135, 270, false, p);
        p.setColor(0xFFFF1744);
        c.drawArc(rect, 135 + 216, 54, false, p);

        txt.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        txt.setTextSize(13);
        p.setStrokeCap(Paint.Cap.BUTT);
        for (int i = 0; i <= 40; i++) {
            double a = Math.toRadians(135 + 270.0 * i / 40);
            float co = (float) Math.cos(a), si = (float) Math.sin(a);
            boolean major = i % 4 == 0, hot = i >= 32;
            float r2 = major ? 92 : 100;
            p.setStrokeWidth(major ? 3 : 1.5f);
            p.setColor(hot ? 0xFFFF5252 : 0xFFCFD8DC);
            c.drawLine(150 + 108 * co, 150 + 108 * si, 150 + r2 * co, 150 + r2 * si, p);
            if (major) {
                txt.setColor(hot ? 0xFFFF5252 : 0xFFE0E6EA);
                c.drawText(String.valueOf(i * 5), 150 + 76 * co, 150 + 76 * si + 5, txt);
            }
        }
    }

    @Override protected void onDraw(Canvas c) {
        if (face == null) return;
        c.drawBitmap(face, 0, 0, null);
        c.translate(ox, oy);
        c.scale(u, u);

        float f = Math.min(shown, MAX) / MAX;
        // Type R colour: white when calm, towards red with speed and hard acceleration
        float push = Math.max(0f, Math.min(accel / 15f, 1f));
        float t = shown > 160 ? 1f : Math.min(1f, shown / 170f * 0.65f + push * 0.5f);
        int col = ramp(t);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        if (f > 0.003f) {
            p.setStrokeWidth(16);
            p.setColor((col & 0x00FFFFFF) | 0x38000000);   // soft glow
            c.drawArc(rect, 135, 270 * f, false, p);
            p.setStrokeWidth(6);
            p.setColor(col);
            c.drawArc(rect, 135, 270 * f, false, p);
        }

        c.save();
        c.rotate(135 + 270 * f, 150, 150);
        p.setStyle(Paint.Style.FILL);
        p.setColor(col);
        c.drawPath(needle, p);
        c.restore();

        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF0B0F16);
        c.drawCircle(150, 150, 13, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3);
        p.setColor(0xFFFF3D00);
        c.drawCircle(150, 150, 13, p);

        txt.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        txt.setColor(warn ? 0xFFFF1744 : 0xFFFFFFFF);
        txt.setTextSize(62);
        c.drawText(String.valueOf((int) shown), 150, 262, txt);
        txt.setColor(0xFF9AA5B1);
        txt.setTextSize(14);
        c.drawText("km/h", 150, 285, txt);

    }
}
