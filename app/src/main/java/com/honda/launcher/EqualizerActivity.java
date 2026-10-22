package com.honda.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Visualizer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** 5-ish band equalizer with presets and a live level display. Type R styling. */
public class EqualizerActivity extends Activity {
    private static final int RED = 0xFFE4002B;
    private static final String[] PRESET_NAMES = {"Flat", "Bass boost", "Vocal", "Rock"};
    private static final int[][] PRESETS = {{0, 0, 0, 0, 0}, {8, 5, 0, -1, 1}, {-3, 0, 5, 4, -1}, {5, 2, -2, 3, 5}};

    private final Handler h = new Handler(Looper.getMainLooper());
    private Equalizer eq;
    private int minL, maxL, n;
    private BandView[] bandViews;
    private TextView[] dbLabels;
    private Bars bars;
    private Visualizer viz;
    private byte[] fft;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        final int M = ViewGroup.LayoutParams.MATCH_PARENT, W = ViewGroup.LayoutParams.WRAP_CONTENT;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF08080A);
        root.setPadding(dp(20), dp(12), dp(20), dp(12));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("<  BACK", 20, RED);
        back.setPadding(dp(8), dp(8), dp(16), dp(8));
        back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
        TextView title = text("Equalizer", 28, 0xFFFFFFFF);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        head.addView(back);
        head.addView(title);
        root.addView(head, new LinearLayout.LayoutParams(M, W));

        eq = EqManager.get(this);
        if (eq == null) {
            TextView msg = text("The equalizer is not supported on this unit. It probably does its own sound processing.", 20, 0xFFCFD8DC);
            msg.setPadding(dp(8), dp(24), dp(8), 0);
            root.addView(msg);
            setContentView(root);
            return;
        }

        bars = new Bars(this);
        root.addView(bars, new LinearLayout.LayoutParams(M, dp(64)));

        short[] range = eq.getBandLevelRange();
        minL = range[0]; maxL = range[1]; n = eq.getNumberOfBands();
        bandViews = new BandView[n];
        dbLabels = new TextView[n];

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < n; i++) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            dbLabels[i] = text("", 18, 0xFFFFFFFF);
            bandViews[i] = new BandView(this, i, minL, maxL, eq.getBandLevel((short) i), new BandView.Listener() {
                public void onLevel(int band, int mB) { EqManager.setLevel(EqualizerActivity.this, band, mB); label(band, mB); }
            });
            label(i, eq.getBandLevel((short) i));
            int hz = eq.getCenterFreq((short) i) / 1000;
            TextView f = text(hz >= 1000 ? String.format(Locale.US, "%.1f kHz", hz / 1000f) : hz + " Hz", 16, 0xFFB0BEC5);
            col.addView(dbLabels[i]);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(56), 0, 1f);
            bp.topMargin = dp(4); bp.bottomMargin = dp(4);
            col.addView(bandViews[i], bp);
            col.addView(f);
            row.addView(col, new LinearLayout.LayoutParams(0, M, 1f));
        }
        root.addView(row, new LinearLayout.LayoutParams(M, 0, 1f));

        LinearLayout pre = new LinearLayout(this);
        for (int i = 0; i < PRESET_NAMES.length; i++) {
            final int idx = i;
            TextView t = button(PRESET_NAMES[i]);
            t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { applyPreset(idx); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
            lp.setMargins(dp(4), dp(8), dp(4), 0);
            pre.addView(t, lp);
        }
        root.addView(pre, new LinearLayout.LayoutParams(M, W));

        TextView note = text("If you hear no change, the unit does its own sound processing.", 14, 0xFF78909C);
        note.setPadding(dp(4), dp(8), 0, 0);
        root.addView(note);
        setContentView(root);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        return t;
    }

    private TextView button(String s) {
        TextView t = text(s, 18, 0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, shape(RED));
        sl.addState(new int[]{}, shape(0x1AFFFFFF));
        t.setBackground(sl);
        return t;
    }

    private GradientDrawable shape(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(10));
        return g;
    }

    private void label(int band, int mB) {
        int db = Math.round(mB / 100f);
        dbLabels[band].setText((db > 0 ? "+" : "") + db + " dB");
    }

    private void applyPreset(int idx) {
        for (int i = 0; i < n; i++) {
            int src = n == 1 ? 0 : Math.round(i * 4f / (n - 1));
            int mB = Math.max(minL, Math.min(maxL, PRESETS[idx][src] * 100));
            EqManager.setLevel(this, i, mB);
            bandViews[i].setLevel(mB);
            label(i, mB);
        }
    }

    // ---------------- live level display (needs audio-capture permission) ----------------
    private final Runnable vizTick = new Runnable() {
        @Override public void run() {
            if (viz == null) return;
            try { viz.getFft(fft); bars.setFft(fft); } catch (Throwable ignore) { }
            h.postDelayed(this, 100);
        }
    };

    private void startViz() {
        if (bars == null) return;
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
            return;
        }
        try {
            viz = new Visualizer(0);
            viz.setCaptureSize(Visualizer.getCaptureSizeRange()[0]);
            viz.setEnabled(true);
            fft = new byte[viz.getCaptureSize()];
            h.post(vizTick);
        } catch (Throwable t) { viz = null; }
    }

    private void stopViz() {
        h.removeCallbacks(vizTick);
        if (viz != null) {
            try { viz.setEnabled(false); viz.release(); } catch (Throwable ignore) { }
            viz = null;
        }
    }

    @Override protected void onResume() { super.onResume(); startViz(); }
    @Override protected void onPause() { super.onPause(); stopViz(); }
    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) { startViz(); }

    // ---------------- views ----------------
    static class BandView extends View {
        interface Listener { void onLevel(int band, int mB); }
        private final int band, min, max;
        private int level;
        private final Listener l;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        BandView(Context c, int band, int min, int max, int level, Listener l) {
            super(c);
            this.band = band; this.min = min; this.max = max; this.level = level; this.l = l;
        }

        void setLevel(int v) { level = v; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = w * 0.18f;
            r.set(0, 0, w, h);
            p.setColor(0x14FFFFFF);
            c.drawRoundRect(r, rad, rad, p);
            float fy = h * (1f - (level - min) / (float) (max - min));
            r.set(0, fy, w, h);
            p.setColor(RED);
            c.drawRoundRect(r, rad, rad, p);
            float zy = h * (1f - (0 - min) / (float) (max - min));
            p.setColor(0x66FFFFFF);
            c.drawRect(0, zy - 1, w, zy + 1, p);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                getParent().requestDisallowInterceptTouchEvent(true);
                float f = Math.max(0f, Math.min(1f, 1f - e.getY() / getHeight()));
                level = Math.round(min + f * (max - min));
                l.onLevel(band, level);
                invalidate();
                return true;
            }
            return a == MotionEvent.ACTION_UP;
        }
    }

    static class Bars extends View {
        private static final int N = 28;
        private final float[] lvl = new float[N];
        private final Paint p = new Paint();

        Bars(Context c) { super(c); p.setColor(RED); }

        void setFft(byte[] f) {
            int n = f.length / 2, prev = 1;
            for (int b = 0; b < N; b++) {
                int hi = Math.max(prev + 1, (int) Math.pow(n - 1, (b + 1) / (float) N));
                float m = 0;
                for (int k = prev; k < hi && k < n; k++) m = Math.max(m, (float) Math.hypot(f[2 * k], f[2 * k + 1]));
                prev = hi;
                lvl[b] = Math.max(Math.min(1f, m / 80f), lvl[b] * 0.8f);
            }
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth() / (float) N, h = getHeight();
            for (int b = 0; b < N; b++) {
                float bh = Math.max(h * 0.04f, h * lvl[b]);
                c.drawRect(b * w + 1, h - bh, (b + 1) * w - 1, h, p);
            }
        }
    }
}
