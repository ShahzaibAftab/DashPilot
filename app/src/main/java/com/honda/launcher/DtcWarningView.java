package com.honda.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Warning indicator for OBD-II Diagnostic Trouble Codes (DTCs).
 * When trouble codes are present the MIL (check-engine) icon pulses red
 * and the first few codes are listed below. When clear it shows a green
 * tick. Pass an empty list for "all clear".
 *
 * Performance: engine-block and bolt paths are pre-built once (they use
 * absolute coordinates that never change), so onDraw() only redraws them.
 */
public class DtcWarningView extends View {

    private static final int RED    = 0xFFFF1744;
    private static final int AMBER  = 0xFFFFAB00;
    private static final int GREEN  = 0xFF00E676;

    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF badgeRect = new RectF();

    // Pre-built paths (coordinates are in 100×100 unit space, cx=50, cy=32)
    private final Path enginePath = new Path();
    private final Path boltPath   = new Path();

    private List<String> codes = new ArrayList<>();
    private boolean hasCodes;
    private boolean scanning;

    public DtcWarningView(Context c) {
        super(c);
        textPaint.setTextAlign(Paint.Align.CENTER);
        buildPaths();
    }

    /** Build the engine-block and lightning-bolt paths once. */
    private void buildPaths() {
        float cx = 50, cy = 32;

        // Engine block outline
        enginePath.moveTo(cx - 14, cy - 8);
        enginePath.lineTo(cx - 14, cy - 14);
        enginePath.lineTo(cx - 8, cy - 14);
        enginePath.lineTo(cx - 8, cy - 10);
        enginePath.lineTo(cx - 4, cy - 10);
        enginePath.lineTo(cx - 4, cy - 14);
        enginePath.lineTo(cx + 4, cy - 14);
        enginePath.lineTo(cx + 4, cy - 10);
        enginePath.lineTo(cx + 8, cy - 10);
        enginePath.lineTo(cx + 8, cy - 14);
        enginePath.lineTo(cx + 14, cy - 14);
        enginePath.lineTo(cx + 14, cy - 8);
        enginePath.lineTo(cx + 18, cy - 8);
        enginePath.lineTo(cx + 18, cy + 4);
        enginePath.lineTo(cx + 14, cy + 4);
        enginePath.lineTo(cx + 14, cy + 10);
        enginePath.lineTo(cx - 14, cy + 10);
        enginePath.lineTo(cx - 14, cy + 4);
        enginePath.lineTo(cx - 18, cy + 4);
        enginePath.lineTo(cx - 18, cy);
        enginePath.lineTo(cx - 14, cy);
        enginePath.close();

        // Lightning bolt (MIL indicator)
        boltPath.moveTo(cx - 2, cy - 6);
        boltPath.lineTo(cx + 4, cy - 6);
        boltPath.lineTo(cx,     cy);
        boltPath.lineTo(cx + 3, cy);
        boltPath.lineTo(cx - 3, cy + 8);
        boltPath.lineTo(cx,     cy + 1);
        boltPath.lineTo(cx - 3, cy + 1);
        boltPath.close();
    }

    /** Pass the current list of DTC strings. Empty = all clear. */
    public void setCodes(List<String> dtcCodes) {
        boolean changed = (dtcCodes.size() != codes.size());
        if (!changed) {
            for (int i = 0; i < codes.size(); i++) {
                if (!codes.get(i).equals(dtcCodes.get(i))) { changed = true; break; }
            }
        }
        if (changed) {
            codes = new ArrayList<>(dtcCodes);
            hasCodes = !codes.isEmpty();
            invalidate();
        }
    }

    public void setScanning(boolean s) {
        if (s != scanning) { scanning = s; invalidate(); }
    }

    public boolean hasCodes() { return hasCodes; }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE && (hasCodes || scanning)) {
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float u = Math.min(w, h) / 100f;
        c.save();
        c.translate((w - 100 * u) / 2f, (h - 100 * u) / 2f);
        c.scale(u, u);

        float time = SystemClock.uptimeMillis() / 1000f;
        float cx = 50, cy = 32;

        // ---- Pulsing glow behind icon ----
        if (hasCodes) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(time * 5);
            iconPaint.setStyle(Paint.Style.FILL);
            iconPaint.setColor((RED & 0x00FFFFFF) | ((int) (60 + 80 * pulse) << 24));
            c.drawCircle(cx, cy, 22, iconPaint);
        }

        // ---- Engine block outline ----
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(3f);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        if (hasCodes) {
            float pulse = 0.7f + 0.3f * (float) Math.sin(time * 5);
            iconPaint.setColor((RED & 0x00FFFFFF) | ((int) (180 + 75 * pulse) << 24));
        } else {
            iconPaint.setColor(scanning ? AMBER : GREEN);
        }
        c.drawPath(enginePath, iconPaint);

        // ---- Lightning bolt ----
        iconPaint.setStyle(Paint.Style.FILL);
        c.drawPath(boltPath, iconPaint);

        // ---- Status text ----
        textPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        textPaint.setTextSize(10);

        if (hasCodes) {
            // Badge count
            bgPaint.setColor(RED);
            bgPaint.setStyle(Paint.Style.FILL);
            badgeRect.set(cx + 10, cy - 18, cx + 24, cy - 8);
            c.drawRoundRect(badgeRect, 5, 5, bgPaint);
            textPaint.setColor(0xFFFFFFFF);
            textPaint.setTextSize(8);
            c.drawText(String.valueOf(codes.size()), cx + 17, cy - 11, textPaint);

            // DTC codes list
            textPaint.setTextSize(10);
            textPaint.setColor(RED);
            textPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            int maxShow = Math.min(codes.size(), 3);
            for (int i = 0; i < maxShow; i++) {
                c.drawText(codes.get(i), cx, 58 + i * 12, textPaint);
            }
            if (codes.size() > 3) {
                textPaint.setTextSize(9);
                textPaint.setColor(0xFF9AA5B1);
                c.drawText("+" + (codes.size() - 3) + " more", cx, 58 + 3 * 12, textPaint);
            }
        } else if (scanning) {
            textPaint.setColor(AMBER);
            textPaint.setTextSize(9);
            c.drawText("Scanning", cx, 58, textPaint);
            int dots = ((int) (time * 2)) % 4;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < dots; i++) sb.append('.');
            c.drawText(sb.toString(), cx, 67, textPaint);
        } else {
            textPaint.setColor(GREEN);
            textPaint.setTextSize(9);
            c.drawText("No faults", cx, 58, textPaint);
        }

        // ---- Label ----
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(11);
        textPaint.setColor(0xFFCFD8DC);
        c.drawText("DTC", cx, 95, textPaint);

        c.restore();

        // Keep animating when codes are present or scanning (~12 fps) only when shown
        if ((hasCodes || scanning) && isShown()) postInvalidateDelayed(80);
    }
}
