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
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.honda.launcher.audio.AutoEqManager;

import java.util.Locale;

/**
 * 5-band Equalizer with Intelligent Auto Audio Tuning and live visualizer.
 * Type R styling with real-time music genre and spectrum adaptation.
 */
public class EqualizerActivity extends Activity {
    private static final int RED = 0xFFE4002B;
    private static final int DARK_CARD = 0x1AFFFFFF;
    private static final String[] PRESET_NAMES = {"Flat", "Bass boost", "Vocal", "Rock"};
    private static final int[][] PRESETS = {{0, 0, 0, 0, 0}, {8, 5, 0, -1, 1}, {-3, 0, 5, 4, -1}, {5, 2, -2, 3, 5}};

    private final Handler h = new Handler(Looper.getMainLooper());
    private Equalizer eq;
    private AutoEqManager autoEq;
    private int minL, maxL, n;
    private BandView[] bandViews;
    private TextView[] dbLabels;
    private Bars bars;
    private Visualizer viz;
    private byte[] fft;

    private TextView tAutoStatus;
    private TextView btnStrength;
    private Switch swAutoEq;
    private LinearLayout profileRow;
    private LinearLayout presetsRow;
    private TextView[] profileButtons;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        final int M = ViewGroup.LayoutParams.MATCH_PARENT, W = ViewGroup.LayoutParams.WRAP_CONTENT;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF08080A);
        root.setPadding(dp(16), dp(8), dp(16), dp(8));

        // Top Navigation Header
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("<  BACK", 18, RED);
        back.setPadding(dp(6), dp(6), dp(14), dp(6));
        back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
        TextView title = text("Equalizer & Auto-Tuning", 24, 0xFFFFFFFF);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        head.addView(back);
        head.addView(title);
        root.addView(head, new LinearLayout.LayoutParams(M, W));

        eq = EqManager.get(this);
        if (eq == null) {
            TextView msg = text("The equalizer is not supported on this unit. It probably does its own sound processing.", 18, 0xFFCFD8DC);
            msg.setPadding(dp(8), dp(24), dp(8), 0);
            root.addView(msg);
            setContentView(root);
            return;
        }

        autoEq = AutoEqManager.get(this);

        // Live Audio Spectrum Visualizer
        bars = new Bars(this);
        LinearLayout.LayoutParams barsLp = new LinearLayout.LayoutParams(M, dp(48));
        barsLp.topMargin = dp(2);
        barsLp.bottomMargin = dp(6);
        root.addView(bars, barsLp);

        // Auto Audio Tuning Control Card
        View autoCard = buildAutoTuningCard();
        root.addView(autoCard);

        // EQ Bands (Vertical Sliders)
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
            dbLabels[i] = text("", 16, 0xFFFFFFFF);
            final int bandIndex = i;
            bandViews[i] = new BandView(this, i, minL, maxL, eq.getBandLevel((short) i), new BandView.Listener() {
                public void onLevel(int band, int mB) {
                    if (autoEq != null && autoEq.getSettings().enabled) {
                        autoEq.setEnabled(false);
                        if (swAutoEq != null) swAutoEq.setChecked(false);
                        updateAutoEqUi();
                        Toast.makeText(EqualizerActivity.this, "Switched to Manual EQ", Toast.LENGTH_SHORT).show();
                    }
                    EqManager.setLevel(EqualizerActivity.this, band, mB);
                    label(band, mB);
                }
            });
            label(i, eq.getBandLevel((short) i));
            int hz = eq.getCenterFreq((short) i) / 1000;
            TextView f = text(hz >= 1000 ? String.format(Locale.US, "%.1f kHz", hz / 1000f) : hz + " Hz", 14, 0xFFB0BEC5);
            f.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            col.addView(dbLabels[i]);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(54), 0, 1f);
            bp.topMargin = dp(2); bp.bottomMargin = dp(2);
            col.addView(bandViews[i], bp);
            col.addView(f);
            row.addView(col, new LinearLayout.LayoutParams(0, M, 1f));
        }
        root.addView(row, new LinearLayout.LayoutParams(M, 0, 1f));

        // Bottom Presets Row
        presetsRow = new LinearLayout(this);
        presetsRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < PRESET_NAMES.length; i++) {
            final int idx = i;
            TextView t = button(PRESET_NAMES[i]);
            t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { applyPreset(idx); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            lp.setMargins(dp(4), dp(4), dp(4), 0);
            presetsRow.addView(t, lp);
        }
        root.addView(presetsRow, new LinearLayout.LayoutParams(M, W));

        TextView note = text("AutoEQ dynamically adapts frequency bands according to music genre, track energy & vehicle speed.", 12, 0xFF78909C);
        note.setPadding(dp(4), dp(4), 0, 0);
        root.addView(note);
        setContentView(root);

        // AutoEQ live tuning listener
        autoEq.setOnTuningUpdateListener(new AutoEqManager.OnTuningUpdateListener() {
            @Override public void onTuningUpdated(final AutoEqManager.Status status, final String tuningDesc) {
                h.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                        if (tAutoStatus != null) {
                            tAutoStatus.setText("● " + tuningDesc);
                        }
                        if (autoEq != null && autoEq.getSettings().enabled &&
                            status != null && status.currentBandLevelsDb != null && bandViews != null) {
                            for (int i = 0; i < n && i < status.currentBandLevelsDb.length; i++) {
                                float db = status.currentBandLevelsDb[i];
                                int mB = Math.round(db * 100f);
                                mB = Math.max(minL, Math.min(maxL, mB));
                                bandViews[i].setLevel(mB);
                                label(i, mB);
                            }
                        }
                    }
                });
            }
        });

        updateAutoEqUi();
    }

    private View buildAutoTuningCard() {
        final int M = ViewGroup.LayoutParams.MATCH_PARENT, W = ViewGroup.LayoutParams.WRAP_CONTENT;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(shape(DARK_CARD));
        card.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(M, W);
        clp.bottomMargin = dp(6);
        card.setLayoutParams(clp);

        // Card Top Row
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView tTitle = text("AUTO AUDIO TUNING", 14, 0xFFFFFFFF);
        tTitle.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));

        tAutoStatus = text("● Auto: Initializing music analyzer...", 11, 0xFF00E5FF);
        tAutoStatus.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        info.addView(tTitle);
        info.addView(tAutoStatus);
        topRow.addView(info, new LinearLayout.LayoutParams(0, W, 1f));

        // Tuning Strength button
        btnStrength = button("50% DYN");
        btnStrength.setTextSize(12);
        btnStrength.setPadding(dp(10), dp(4), dp(10), dp(4));
        btnStrength.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cycleStrength();
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(W, dp(34));
        slp.rightMargin = dp(10);
        topRow.addView(btnStrength, slp);

        // Toggle Switch
        swAutoEq = new Switch(this);
        swAutoEq.setChecked(autoEq.getSettings().enabled);
        swAutoEq.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                autoEq.setEnabled(isChecked);
                updateAutoEqUi();
                Toast.makeText(EqualizerActivity.this,
                        isChecked ? "Auto Audio Tuning enabled" : "Manual Equalizer enabled",
                        Toast.LENGTH_SHORT).show();
            }
        });
        topRow.addView(swAutoEq, new LinearLayout.LayoutParams(W, W));
        card.addView(topRow);

        // Profile Selector Buttons Row
        profileRow = new LinearLayout(this);
        profileRow.setOrientation(LinearLayout.HORIZONTAL);
        profileRow.setPadding(0, dp(6), 0, 0);

        final AutoEqManager.Profile[] profiles = {
                AutoEqManager.Profile.DYNAMIC_AUTO,
                AutoEqManager.Profile.BALANCED,
                AutoEqManager.Profile.VOCAL,
                AutoEqManager.Profile.BASS_CONTROL,
                AutoEqManager.Profile.ROCK_POWER,
                AutoEqManager.Profile.BACKGROUND
        };
        final String[] titles = {"AUTO MUSIC", "BALANCED", "VOCAL", "BASS PUNCH", "ROCK", "HIGHWAY"};
        profileButtons = new TextView[profiles.length];

        for (int i = 0; i < profiles.length; i++) {
            final AutoEqManager.Profile p = profiles[i];
            final int idx = i;
            TextView btn = text(titles[i], 11, 0xFFFFFFFF);
            btn.setGravity(Gravity.CENTER);
            btn.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            btn.setPadding(dp(4), dp(4), dp(4), dp(4));
            btn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!autoEq.getSettings().enabled) {
                        autoEq.setEnabled(true);
                        swAutoEq.setChecked(true);
                    }
                    autoEq.setProfile(p);
                    updateAutoEqUi();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(30), 1f);
            lp.setMargins(dp(2), 0, dp(2), 0);
            profileRow.addView(btn, lp);
            profileButtons[i] = btn;
        }
        card.addView(profileRow);

        return card;
    }

    private void updateAutoEqUi() {
        if (autoEq == null) return;
        boolean enabled = autoEq.getSettings().enabled;
        AutoEqManager.Profile curProfile = autoEq.getSettings().profile;
        int strength = autoEq.getSettings().strengthPercent;

        if (btnStrength != null) {
            btnStrength.setText(strength + "% INT");
            btnStrength.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }

        if (tAutoStatus != null) {
            if (enabled) {
                tAutoStatus.setText("● " + autoEq.getStatus().activeTuningMode);
                tAutoStatus.setTextColor(0xFF00E5FF);
            } else {
                tAutoStatus.setText("Manual Mode • Sliders adjust manually");
                tAutoStatus.setTextColor(0x8CFFFFFF);
            }
        }

        if (profileRow != null) {
            profileRow.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }

        if (profileButtons != null) {
            final AutoEqManager.Profile[] profiles = {
                    AutoEqManager.Profile.DYNAMIC_AUTO,
                    AutoEqManager.Profile.BALANCED,
                    AutoEqManager.Profile.VOCAL,
                    AutoEqManager.Profile.BASS_CONTROL,
                    AutoEqManager.Profile.ROCK_POWER,
                    AutoEqManager.Profile.BACKGROUND
            };
            for (int i = 0; i < profileButtons.length && i < profiles.length; i++) {
                boolean active = (enabled && profiles[i] == curProfile);
                profileButtons[i].setBackground(shape(active ? RED : 0x22FFFFFF));
                profileButtons[i].setTextColor(active ? 0xFFFFFFFF : 0xB0BEC5);
            }
        }

        if (presetsRow != null) {
            presetsRow.setAlpha(enabled ? 0.45f : 1.0f);
        }
    }

    private void cycleStrength() {
        if (autoEq == null) return;
        int cur = autoEq.getSettings().strengthPercent;
        int next;
        if (cur < 35) next = 50;
        else if (cur < 60) next = 75;
        else if (cur < 85) next = 100;
        else next = 25;
        autoEq.setStrengthPercent(next);
        updateAutoEqUi();
        Toast.makeText(this, "AutoEQ Intensity: " + next + "%", Toast.LENGTH_SHORT).show();
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        return t;
    }

    private TextView button(String s) {
        TextView t = text(s, 16, 0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, shape(RED));
        sl.addState(new int[]{}, shape(0x1AFFFFFF));
        t.setBackground(sl);
        return t;
    }

    private GradientDrawable shape(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(8));
        return g;
    }

    private void label(int band, int mB) {
        if (dbLabels == null || band < 0 || band >= dbLabels.length || dbLabels[band] == null) return;
        int db = Math.round(mB / 100f);
        dbLabels[band].setText((db > 0 ? "+" : "") + db + " dB");
    }

    private void applyPreset(int idx) {
        if (autoEq != null && autoEq.getSettings().enabled) {
            autoEq.setEnabled(false);
            if (swAutoEq != null) swAutoEq.setChecked(false);
            updateAutoEqUi();
        }
        for (int i = 0; i < n; i++) {
            int src = n == 1 ? 0 : Math.round(i * 4f / (n - 1));
            int mB = Math.max(minL, Math.min(maxL, PRESETS[idx][src] * 100));
            EqManager.setLevel(this, i, mB);
            bandViews[i].setLevel(mB);
            label(i, mB);
        }
        Toast.makeText(this, "Preset applied: " + PRESET_NAMES[idx], Toast.LENGTH_SHORT).show();
    }

    // ---------------- live level display & spectrum analysis ----------------
    private final Runnable vizTick = new Runnable() {
        @Override public void run() {
            if (viz == null) return;
            try {
                viz.getFft(fft);
                bars.setFft(fft);
                if (autoEq != null && autoEq.getSettings().enabled) {
                    autoEq.analyzeSpectrum(fft);
                }
            } catch (Throwable ignore) { }
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

    @Override protected void onResume() {
        super.onResume();
        startViz();
        updateAutoEqUi();
    }

    @Override protected void onPause() {
        super.onPause();
        stopViz();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (autoEq != null) {
            autoEq.setOnTuningUpdateListener(null);
        }
    }

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
            float range = (max > min) ? (float) (max - min) : 1f;
            float fy = h * (1f - (level - min) / range);
            r.set(0, fy, w, h);
            p.setColor(RED);
            c.drawRoundRect(r, rad, rad, p);
            float zy = h * (1f - (0 - min) / range);
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
