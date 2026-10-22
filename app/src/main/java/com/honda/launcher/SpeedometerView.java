package com.honda.launcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.Keyframe;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
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
import android.view.animation.AccelerateDecelerateInterpolator;

/**
 * Racing-style gauge: 0-200 km/h dial with ticks, red zone, glowing progress arc,
 * tapered needle, italic digital speed, and startup needle sweep (0 to 200 km/h and back).
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
    private boolean isSweeping = false;
    private ValueAnimator sweepAnim;

    private final Runnable step = new Runnable() {
        @Override public void run() {
            if (isSweeping) return;
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
        setClickable(true);
        setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                startNeedleSweep();
            }
        });
    }

    /**
     * Startup needle sweep: needle sweeps 0 to 200 km/h and back with a luminous glow,
     * like a real sports car cluster (Honda Type R / STI / Porsche).
     */
    public void startNeedleSweep() {
        if (sweepAnim != null) {
            sweepAnim.cancel();
        }
        isSweeping = true;
        // Keyframes:
        // 0.00: 0 km/h
        // 0.45: 200 km/h (accelerates and sweeps to redline in ~720ms)
        // 0.55: 200 km/h (holds at redline peak for ~160ms with full glow)
        // 1.00: 0 km/h (smoothly returns back to rest in ~720ms)
        Keyframe k0 = Keyframe.ofFloat(0f, 0f);
        Keyframe k1 = Keyframe.ofFloat(0.45f, MAX);
        Keyframe k2 = Keyframe.ofFloat(0.55f, MAX);
        Keyframe k3 = Keyframe.ofFloat(1.0f, 0f);
        PropertyValuesHolder pvh = PropertyValuesHolder.ofKeyframe("speed", k0, k1, k2, k3);
        sweepAnim = ValueAnimator.ofPropertyValuesHolder(pvh);
        sweepAnim.setDuration(1600);
        sweepAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        sweepAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator animation) {
                shown = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        sweepAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                isSweeping = false;
                shown = target;
                if (!animating && Math.abs(target - shown) > 0.05f) {
                    animating = true;
                    postOnAnimation(step);
                }
                invalidate();
            }
        });
        sweepAnim.start();
    }

    public static final int THEME_TYPER = 0;
    public static final int THEME_SAPPHIRE = 1;
    public static final int THEME_ONYX = 2;

    private boolean ecoMode = false;
    private int theme = THEME_TYPER;

    public void setEcoMode(boolean eco) {
        if (this.ecoMode != eco) {
            this.ecoMode = eco;
            invalidate();
        }
    }

    public void setTheme(int theme) {
        if (this.theme != theme) {
            this.theme = theme;
            if (getWidth() > 0 && getHeight() > 0) {
                onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
            }
            invalidate();
        }
    }

    public int getTheme() { return theme; }

    private static int blendColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int rr = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int gg = (int) (((a >>  8) & 0xFF) + (((b >>  8) & 0xFF) - ((a >>  8) & 0xFF)) * t);
        int bb = (int) (( a        & 0xFF) + (( b        & 0xFF) - ( a        & 0xFF)) * t);
        return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
    }

    private static int ramp(float t) {
        final int[] st = {0xFFF2F2F2, 0xFFFF6B5E, 0xFFFF1F2D};   // white -> salmon -> red
        float x = t * 2f;
        int i = Math.min((int) x, 1);
        float f = x - i;
        return blendColor(st[i], st[i + 1], f);
    }

    private static int rampSapphire(float t) {
        final int[] st = {0xFF00E5FF, 0xFF2979FF, 0xFFFF1744};   // cyan -> sapphire blue -> kinetic ruby
        float x = t * 2f;
        int i = Math.min((int) x, 1);
        float f = x - i;
        return blendColor(st[i], st[i + 1], f);
    }

    private static int rampOnyx(float t) {
        final int[] st = {0xFFFFB300, 0xFFFF8F00, 0xFFFF3D00};   // warm amber -> gold -> ember
        float x = t * 2f;
        int i = Math.min((int) x, 1);
        float f = x - i;
        return blendColor(st[i], st[i + 1], f);
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
        if (isSweeping) return;
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
        int trackColor = (theme == THEME_SAPPHIRE) ? 0xFF0D1B2A : (theme == THEME_ONYX) ? 0xFF14171C : 0xFF1B2230;
        p.setColor(trackColor);
        c.drawArc(rect, 135, 270, false, p);
        int hotZoneColor = (theme == THEME_ONYX) ? 0xFFFF8F00 : 0xFFFF1744;
        p.setColor(hotZoneColor);
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
            int tickColor;
            if (hot) {
                tickColor = (theme == THEME_ONYX) ? 0xFFFF6D00 : 0xFFFF5252;
            } else if (theme == THEME_SAPPHIRE) {
                tickColor = major ? 0xFF00E5FF : 0x8000E5FF;
            } else if (theme == THEME_ONYX) {
                tickColor = major ? 0xFFFFB300 : 0x80FFB300;
            } else {
                tickColor = 0xFFCFD8DC;
            }
            p.setColor(tickColor);
            c.drawLine(150 + 108 * co, 150 + 108 * si, 150 + r2 * co, 150 + r2 * si, p);
            if (major) {
                int txtColor;
                if (hot) {
                    txtColor = (theme == THEME_ONYX) ? 0xFFFF8F00 : 0xFFFF5252;
                } else if (theme == THEME_SAPPHIRE) {
                    txtColor = 0xFF80D8FF;
                } else if (theme == THEME_ONYX) {
                    txtColor = 0xFFFFD54F;
                } else {
                    txtColor = 0xFFE0E6EA;
                }
                txt.setColor(txtColor);
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
        int col;
        if (ecoMode && !isSweeping) {
            // Eco / Comfort Mode:
            // 0 - 80 km/h: Honda Eco Green (Safe / cruising)
            // 80 - 120 km/h: Warning Yellow
            // > 120 km/h: Danger Red
            if (shown >= 120f) {
                col = 0xFFFF1744; // Red
            } else if (shown >= 80f) {
                float tr = (shown - 80f) / 40f;
                col = blendColor(0xFFFFD600, 0xFFFF1744, tr); // Yellow -> Red
            } else {
                float tr = Math.min(1f, shown / 80f);
                col = blendColor(0xFF00E676, 0xFFFFD600, tr * 0.35f); // Green -> slight warm
            }
        } else {
            float push = Math.max(0f, Math.min(accel / 15f, 1f));
            float t = isSweeping
                    ? Math.min(1f, (shown / MAX) * 1.15f)
                    : (shown > 160 ? 1f : Math.min(1f, shown / 170f * 0.65f + push * 0.5f));
            if (theme == THEME_SAPPHIRE) {
                col = rampSapphire(t);
            } else if (theme == THEME_ONYX) {
                col = rampOnyx(t);
            } else {
                col = ramp(t);
            }
        }

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        if (f > 0.003f) {
            // Enhanced multi-layer glow: outer halo + intense mid + crisp core
            p.setStrokeWidth(24);
            p.setColor((col & 0x00FFFFFF) | 0x22000000);   // wide sports atmospheric halo
            c.drawArc(rect, 135, 270 * f, false, p);

            p.setStrokeWidth(12);
            p.setColor((col & 0x00FFFFFF) | 0x4D000000);   // intense core glow
            c.drawArc(rect, 135, 270 * f, false, p);

            p.setStrokeWidth(6);
            p.setColor(col);                               // solid core arc
            c.drawArc(rect, 135, 270 * f, false, p);
        }

        c.save();
        c.rotate(135 + 270 * f, 150, 150);
        // Needle luminous glow
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4);
        p.setColor((col & 0x00FFFFFF) | 0x44000000);
        c.drawPath(needle, p);

        // Needle body
        p.setStyle(Paint.Style.FILL);
        p.setColor(col);
        c.drawPath(needle, p);
        c.restore();

        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF0B0F16);
        c.drawCircle(150, 150, 13, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3);
        int capBorder = (theme == THEME_SAPPHIRE) ? 0xFF00E5FF : (theme == THEME_ONYX) ? 0xFFFFA000 : 0xFFFF3D00;
        p.setColor(capBorder);
        c.drawCircle(150, 150, 13, p);

        txt.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        if (warn) {
            txt.setColor(0xFFFF1744);
        } else if (ecoMode && shown > 0.5f && !isSweeping) {
            txt.setColor(col);
        } else {
            txt.setColor(0xFFFFFFFF);
        }
        txt.setTextSize(62);
        c.drawText(String.valueOf((int) shown), 150, 262, txt);
        txt.setColor((theme == THEME_SAPPHIRE) ? 0xFF80D8FF : (theme == THEME_ONYX) ? 0xFFFFD54F : 0xFF9AA5B1);
        txt.setTextSize(14);
        c.drawText("km/h", 150, 285, txt);
    }
}
