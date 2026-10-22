package com.honda.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Type R background: carbon-black with slow red/grey glows drifting at ~15 fps.
 * When danger is set, a pulsing red glow is drawn around the screen edge.
 * Drawing stops whenever the launcher is not on screen.
 * If the unit stutters, set ANIMATE = false (static glows, danger pulse still works).
 */
public class AuroraView extends View {
    static final boolean ANIMATE = true;
    public static final int THEME_TYPER = 0;
    public static final int THEME_SAPPHIRE = 1;
    public static final int THEME_ONYX = 2;

    private static final int BASE = 0xFF08080A;
    private static final int[] COL_TYPER = {0xFFE4002B, 0xFF6E0014, 0xFF4A4A52};
    private static final int[] COL_SAPPHIRE = {0xFF00B0FF, 0xFF0D47A1, 0xFF1A237E};
    private static final int[] COL_ONYX = {0xFFFF8F00, 0xFFE65100, 0xFF212121};

    // Heat-tinted ambience palettes: cold blue-grey -> warm bronze -> Type R red -> hot angry crimson
    private static final int[] COL_COLD = {0xFF37474F, 0xFF1E3A5F, 0xFF263238};
    private static final int[] COL_WARM = {0xFF8D6E63, 0xFF5D4037, 0xFF37474F};
    private static final int[] COL_HOT  = {0xFFFF1744, 0xFFFF3D00, 0xFFB71C1C};

    private final Paint[] p = {new Paint(), new Paint(), new Paint()};
    private final Paint glow = new Paint();
    private float w, h, r;
    private boolean running, danger;
    private int theme = THEME_TYPER;

    private boolean heatTinted = true;
    private float coolantTemp = Float.NaN;

    public AuroraView(Context c) { super(c); }

    public void setTheme(int theme) {
        if (this.theme != theme) {
            this.theme = theme;
            updateThemeShaders();
            invalidate();
        }
    }

    public int getTheme() { return theme; }

    public void setDanger(boolean d) { if (d != danger) { danger = d; invalidate(); } }

    public void setHeatTintedEnabled(boolean enabled) {
        if (this.heatTinted != enabled) {
            this.heatTinted = enabled;
            updateThemeShaders();
            invalidate();
        }
    }

    public boolean isHeatTintedEnabled() { return heatTinted; }

    public void setCoolantTemp(float temp) {
        if (Float.isNaN(temp) && Float.isNaN(coolantTemp)) return;
        if (!Float.isNaN(temp) && !Float.isNaN(coolantTemp) && Math.abs(temp - coolantTemp) < 0.5f) return;
        if (Float.compare(temp, coolantTemp) != 0) {
            this.coolantTemp = temp;
            if (heatTinted) {
                updateThemeShaders();
                invalidate();
            }
        }
    }

    public float getCoolantTemp() { return coolantTemp; }

    private static int blendColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int rr = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int gg = (int) (((a >>  8) & 0xFF) + (((b >>  8) & 0xFF) - ((a >>  8) & 0xFF)) * t);
        int bb = (int) (( a        & 0xFF) + (( b        & 0xFF) - ( a        & 0xFF)) * t);
        return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
    }

    private int[] calculateHeatTintedColors(float temp) {
        int[] out = new int[3];
        if (Float.isNaN(temp) || temp <= 45f) {
            System.arraycopy(COL_COLD, 0, out, 0, 3);
        } else if (temp < 65f) {
            float t = (temp - 45f) / 20f;
            for (int i = 0; i < 3; i++) out[i] = blendColor(COL_COLD[i], COL_WARM[i], t);
        } else if (temp < 88f) {
            float t = (temp - 65f) / 23f;
            for (int i = 0; i < 3; i++) out[i] = blendColor(COL_WARM[i], COL_TYPER[i], t);
        } else {
            float t = Math.min(1f, (temp - 88f) / 16f);
            for (int i = 0; i < 3; i++) out[i] = blendColor(COL_TYPER[i], COL_HOT[i], t);
        }
        return out;
    }

    private void updateThemeShaders() {
        if (w <= 0 || h <= 0) return;
        int[] col;
        if (heatTinted && !Float.isNaN(coolantTemp)) {
            col = calculateHeatTintedColors(coolantTemp);
        } else {
            col = (theme == THEME_SAPPHIRE) ? COL_SAPPHIRE : (theme == THEME_ONYX) ? COL_ONYX : COL_TYPER;
        }
        for (int i = 0; i < 3; i++) {
            int c = col[i] & 0x00FFFFFF;
            p[i].setShader(new RadialGradient(0, 0, r, c | 0x66000000, c, Shader.TileMode.CLAMP));
        }
    }

    @Override protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        w = nw; h = nh; r = w * 0.55f;
        updateThemeShaders();
        glow.setShader(new RadialGradient(w / 2f, h / 2f, (float) Math.hypot(w, h) / 2f,
                new int[]{0x00FF1744, 0x00FF1744, 0xFFFF1744}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override protected void onWindowVisibilityChanged(int v) {
        super.onWindowVisibilityChanged(v);
        running = (v == VISIBLE);
        if (running) invalidate();
    }

    private void blob(Canvas c, int i, float x, float y) {
        c.save();
        c.translate(x, y);
        c.drawCircle(0, 0, r, p[i]);
        c.restore();
    }

    @Override protected void onDraw(Canvas c) {
        int baseColor;
        if (heatTinted && !Float.isNaN(coolantTemp)) {
            if (coolantTemp <= 50f) {
                baseColor = 0xFF060B12; // Cool blue-grey dark base
            } else if (coolantTemp >= 95f) {
                baseColor = 0xFF0E0708; // Angry hot dark embers base
            } else {
                baseColor = BASE;
            }
        } else {
            baseColor = (theme == THEME_SAPPHIRE) ? 0xFF060B12 : (theme == THEME_ONYX) ? 0xFF0A0C0E : BASE;
        }
        c.drawColor(baseColor);
        float t = SystemClock.uptimeMillis() / 1000f;
        float m = ANIMATE ? t : 0f;
        blob(c, 0, w * 0.15f + w * 0.25f * (float) Math.sin(m * 0.12f), h * 0.20f + h * 0.20f * (float) Math.cos(m * 0.10f));
        blob(c, 1, w * 0.85f + w * 0.20f * (float) Math.cos(m * 0.09f), h * 0.30f + h * 0.25f * (float) Math.sin(m * 0.11f));
        blob(c, 2, w * 0.50f + w * 0.25f * (float) Math.sin(m * 0.07f + 2f), h * 1.00f + h * 0.20f * (float) Math.cos(m * 0.08f));
        if (danger) {
            glow.setAlpha((int) (110 + 110 * Math.sin(t * 6)));
            c.drawRect(0, 0, w, h, glow);
        }
        if ((ANIMATE || danger) && running) postInvalidateDelayed(danger ? 40 : 66);
    }
}
