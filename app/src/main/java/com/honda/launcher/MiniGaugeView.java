package com.honda.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.Locale;

/**
 * Small ring gauge with a danger zone. Cyan = normal, amber = caution, red = danger.
 * The number turns red in the danger zone. Pass NaN for "no data" (shows --).
 * Use Float.NaN for any threshold you don't need.
 */
public class MiniGaugeView extends View {
    private final String label, unit;
    private final float min, max, cautionHigh, dangerHigh, cautionLow, dangerLow;
    private final int decimals;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF(12, 6, 88, 82);   // centre (50,44), r = 38
    private float value = Float.NaN;
    private boolean danger, caution;

    public MiniGaugeView(Context c, String label, String unit, float min, float max,
                         float cautionHigh, float dangerHigh, float cautionLow, float dangerLow,
                         int decimals) {
        super(c);
        this.label = label; this.unit = unit; this.min = min; this.max = max;
        this.cautionHigh = cautionHigh; this.dangerHigh = dangerHigh;
        this.cautionLow = cautionLow; this.dangerLow = dangerLow;
        this.decimals = decimals;
        t.setTextAlign(Paint.Align.CENTER);
    }

    /** Returns true if the value is in the danger zone. */
    public boolean setValue(float v) {
        boolean changed = Float.compare(v, value) != 0;
        value = v;
        boolean nan = Float.isNaN(v);
        danger = !nan && ((!Float.isNaN(dangerHigh) && v >= dangerHigh) || (!Float.isNaN(dangerLow) && v < dangerLow));
        caution = !nan && !danger && ((!Float.isNaN(cautionHigh) && v >= cautionHigh) || (!Float.isNaN(cautionLow) && v < cautionLow));
        if (changed) invalidate();
        return danger;
    }

    private float fr(float v) { return Math.max(0f, Math.min(1f, (v - min) / (max - min))); }

    @Override protected void onDraw(Canvas c) {
        float u = Math.min(getWidth(), getHeight()) / 100f;
        c.translate((getWidth() - 100 * u) / 2f, (getHeight() - 100 * u) / 2f);
        c.scale(u, u);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(7);
        p.setColor(0xFF1B2230);
        c.drawArc(rect, 135, 270, false, p);
        p.setColor(0x73FF1744);   // danger zone, dim red
        if (!Float.isNaN(dangerHigh)) c.drawArc(rect, 135 + 270 * fr(dangerHigh), 270 * (1 - fr(dangerHigh)), false, p);
        if (!Float.isNaN(dangerLow)) c.drawArc(rect, 135, 270 * fr(dangerLow), false, p);

        if (!Float.isNaN(value)) {
            float f = fr(value);
            if (f > 0.004f) {
                p.setColor(danger ? 0xFFFF1744 : caution ? 0xFFFFAB00 : 0xFFF2F2F2);
                c.drawArc(rect, 135, 270 * f, false, p);
            }
        }

        t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        t.setTextSize(24);
        t.setColor(danger ? 0xFFFF1744 : 0xFFFFFFFF);
        String s = Float.isNaN(value) ? "--"
                : decimals == 0 ? String.valueOf(Math.round(value))
                : String.format(Locale.US, "%." + decimals + "f", value);
        c.drawText(s, 50, 52, t);

        t.setTypeface(Typeface.DEFAULT);
        t.setTextSize(10);
        t.setColor(0xFF9AA5B1);
        c.drawText(unit, 50, 66, t);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(12);
        t.setColor(0xFFCFD8DC);
        c.drawText(label, 50, 95, t);
    }
}
