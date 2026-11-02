package com.honda.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/**
 * Fuel tank gauge: draws a fuel-pump icon with an animated liquid fill level
 * and a large percentage readout. Colour transitions from green (full) through
 * amber to red (near empty). A subtle wave animation runs on the liquid surface.
 * Pass Float.NaN for "no data" (shows --).
 *
 * Performance: the LinearGradient shader is cached and only recreated when the
 * displayed fill percentage changes by ≥0.5 %. Wave paths reuse the same Path
 * object. Redraw rate is capped at ~12 fps (80 ms) to be gentle on head-unit GPUs.
 */
public class FuelGaugeView extends View {

    // Colour thresholds
    private static final float CAUTION_PCT = 25f;   // amber below 25 %
    private static final float DANGER_PCT  = 10f;   // red   below 10 %

    // Colours
    private static final int GREEN  = 0xFF00E676;
    private static final int AMBER  = 0xFFFFAB00;
    private static final int RED    = 0xFFFF1744;
    private static final int DIM    = 0xFF1B2230;

    private final Paint bodyPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tankBody    = new RectF();
    private final RectF capRect     = new RectF();
    private final Path wavePath     = new Path();
    private final Path wavePath2    = new Path();   // second wave layer

    private float value = Float.NaN;   // 0-100 %
    private float shown = Float.NaN;   // animated display value
    private boolean animating;

    // Shader cache — avoids allocating a new LinearGradient every frame
    private LinearGradient cachedGradient;
    private float cachedTop = Float.NaN;
    private int cachedColor;

    private final Runnable step = new Runnable() {
        @Override public void run() {
            if (Float.isNaN(value)) { shown = Float.NaN; animating = false; invalidate(); return; }
            if (Float.isNaN(shown)) shown = value;
            float d = value - shown;
            if (Math.abs(d) < 0.15f) { shown = value; animating = false; }
            else { shown += d * 0.18f; postOnAnimation(this); }
            invalidate();
        }
    };

    public FuelGaugeView(Context c) {
        super(c);
        textPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    /** Set fuel level 0-100 %, or Float.NaN for no data. */
    public void setValue(float pct) {
        value = pct;
        if (!animating) { animating = true; postOnAnimation(step); }
    }

    public float getValue() { return value; }

    private int levelColor(float pct) {
        if (pct <= DANGER_PCT) return RED;
        if (pct <= CAUTION_PCT) {
            float t = (pct - DANGER_PCT) / (CAUTION_PCT - DANGER_PCT);
            return blendColor(RED, AMBER, t);
        }
        if (pct <= 50f) {
            float t = (pct - CAUTION_PCT) / (50f - CAUTION_PCT);
            return blendColor(AMBER, GREEN, t);
        }
        return GREEN;
    }

    private static int blendColor(int a, int b, float t) {
        int rr = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int gg = (int) (((a >>  8) & 0xFF) + (((b >>  8) & 0xFF) - ((a >>  8) & 0xFF)) * t);
        int bb = (int) (( a        & 0xFF) + (( b        & 0xFF) - ( a        & 0xFF)) * t);
        return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
    }

    /** Return (or lazily create) a cached gradient for the current fill state. */
    private LinearGradient getGradient(float top, float bottom, int col) {
        int colDark = (col & 0x00FFFFFF) | 0x88000000;
        // Recreate only when fill position or colour changed meaningfully
        if (cachedGradient == null || cachedColor != col || Math.abs(top - cachedTop) > 0.5f) {
            cachedGradient = new LinearGradient(0, top, 0, bottom, col, colDark, Shader.TileMode.CLAMP);
            cachedTop = top;
            cachedColor = col;
        }
        return cachedGradient;
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE && !Float.isNaN(shown) && shown > 0.5f) {
            invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(step);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float u = Math.min(w, h) / 100f;
        c.save();
        c.translate((w - 100 * u) / 2f, (h - 100 * u) / 2f);
        c.scale(u, u);

        // ---- tank body (rounded rectangle) ----
        tankBody.set(18, 12, 68, 80);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(3f);
        outlinePaint.setColor(DIM);
        bodyPaint.setStyle(Paint.Style.FILL);
        bodyPaint.setColor(0xFF0D1117);
        c.drawRoundRect(tankBody, 6, 6, bodyPaint);
        c.drawRoundRect(tankBody, 6, 6, outlinePaint);

        // ---- fuel cap (small rectangle on top) ----
        capRect.set(34, 6, 52, 14);
        c.drawRoundRect(capRect, 3, 3, bodyPaint);
        outlinePaint.setColor(DIM);
        c.drawRoundRect(capRect, 3, 3, outlinePaint);

        // ---- nozzle handle on the right ----
        outlinePaint.setStrokeWidth(2.5f);
        outlinePaint.setColor(DIM);
        outlinePaint.setStyle(Paint.Style.STROKE);
        c.drawLine(68, 30, 78, 30, outlinePaint);
        c.drawLine(78, 30, 78, 58, outlinePaint);
        c.drawLine(78, 52, 73, 58, outlinePaint);

        // ---- liquid fill ----
        float pct = Float.isNaN(shown) ? 0f : Math.max(0f, Math.min(100f, shown));
        float fillH = (tankBody.height() - 4f) * (pct / 100f);
        float top = tankBody.bottom - 2f - fillH;

        if (fillH > 0.5f) {
            int col = levelColor(pct);

            // Use cached gradient to avoid GC pressure
            fillPaint.setShader(getGradient(top, tankBody.bottom - 2f, col));

            c.save();
            c.clipRect(tankBody.left + 2f, tankBody.top + 2f,
                       tankBody.right - 2f, tankBody.bottom - 2f);

            // Wave on the surface (step every 2 px instead of 1 to halve path points)
            float time = SystemClock.uptimeMillis() / 1000f;
            float waveAmp = Math.min(2.5f, fillH * 0.08f);
            float left = tankBody.left + 2f, right = tankBody.right - 2f;

            wavePath.reset();
            wavePath.moveTo(left, tankBody.bottom - 2f);
            for (float x = left; x <= right; x += 2f) {
                float y = top + waveAmp * (float) Math.sin((x - left) * 0.3f + time * 3.5f);
                wavePath.lineTo(x, y);
            }
            wavePath.lineTo(right, tankBody.bottom - 2f);
            wavePath.close();
            c.drawPath(wavePath, fillPaint);

            // Second softer wave layer for depth
            wavePaint.setColor((col & 0x00FFFFFF) | 0x33000000);
            wavePaint.setStyle(Paint.Style.FILL);
            wavePath2.reset();
            wavePath2.moveTo(left, tankBody.bottom - 2f);
            for (float x = left; x <= right; x += 2f) {
                float y = top + waveAmp * (float) Math.sin((x - left) * 0.25f + time * 2.8f + 1.5f)
                        + waveAmp * 0.5f;
                wavePath2.lineTo(x, y);
            }
            wavePath2.lineTo(right, tankBody.bottom - 2f);
            wavePath2.close();
            c.drawPath(wavePath2, wavePaint);

            c.restore();

            // Redraw outline on top of fill
            outlinePaint.setColor(col);
            outlinePaint.setStrokeWidth(2f);
            outlinePaint.setStyle(Paint.Style.STROKE);
            c.drawRoundRect(tankBody, 6, 6, outlinePaint);
        }

        // ---- percentage text ----
        textPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        textPaint.setTextSize(18);
        if (Float.isNaN(shown)) {
            textPaint.setColor(0xFF9AA5B1);
            c.drawText("--", 43, 52, textPaint);
        } else {
            textPaint.setColor(levelColor(pct));
            c.drawText(Math.round(pct) + "%", 43, 52, textPaint);
        }

        // ---- label ----
        labelPaint.setTypeface(Typeface.DEFAULT_BOLD);
        labelPaint.setTextSize(11);
        labelPaint.setColor(0xFFCFD8DC);
        c.drawText("FUEL", 43, 93, labelPaint);

        c.restore();

        // Keep animating the wave (~12 fps, gentle on head-unit GPU) only when shown
        if (!Float.isNaN(shown) && shown > 0.5f && isShown()) postInvalidateDelayed(80);
    }
}
