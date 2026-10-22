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
    private static final int BASE = 0xFF08080A;
    private static final int[] COL = {0xFFE4002B, 0xFF6E0014, 0xFF4A4A52};

    private final Paint[] p = {new Paint(), new Paint(), new Paint()};
    private final Paint glow = new Paint();
    private float w, h, r;
    private boolean running, danger;

    public AuroraView(Context c) { super(c); }

    public void setDanger(boolean d) { if (d != danger) { danger = d; invalidate(); } }

    @Override protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        w = nw; h = nh; r = w * 0.55f;
        for (int i = 0; i < 3; i++) {
            int c = COL[i] & 0x00FFFFFF;
            p[i].setShader(new RadialGradient(0, 0, r, c | 0x66000000, c, Shader.TileMode.CLAMP));
        }
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
        c.drawColor(BASE);
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
