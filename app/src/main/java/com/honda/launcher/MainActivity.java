package com.honda.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements LocationListener {

    // ===================== CONFIG: edit these =====================
    /** Block YouTube and Browser while the car is moving. Set false to allow. */
    static final boolean BLOCK_WEB_WHILE_MOVING = true;
    static final int MOVING_KMH = 5;
    /** Package of the stock car Bluetooth/phone app. Leave "" to open Bluetooth settings. */
    static final String PHONE_PACKAGE = "";
    static final String NEWPIPE = "org.schabi.newpipe";
    static final String[] BROWSERS = {"mark.via.gp", "mark.via"};
    static final String[] MAPS = {"net.osmand.plus", "net.osmand",
            "com.google.android.apps.mapslite", "com.google.android.apps.maps"};
    static final int RED = 0xFFE4002B;
    // ==============================================================

    private static final int T_READY = 0, T_RUN = 1, T_DONE = 2;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEE, d MMM", Locale.getDefault());

    private TextView clock, date, nowPlaying, tmVal, tmBest;
    private View tmBox;
    private SpeedometerView speedo;
    private AuroraView aurora;
    private LinearLayout obdPanel, page;
    private FrameLayout wrap;
    private MiniGaugeView gRpm, gTemp, gBatt, gLoad, gAtf;
    private FuelGaugeView fuelGauge;
    private DtcWarningView dtcWarning;
    private final ObdManager obd = new ObdManager();
    private MediaController controller;
    private SharedPreferences prefs;
    private boolean needsAccess, pageOpen;

    private volatile float gpsSpeed;
    private volatile long lastGps;
    private float curSpeed, downX, downY;
    private int ticks;

    // 0-100 timer
    private int tState;
    private long tStart, lastT;
    private float lastV, tBest = -1f, tRes;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        buildUi();
        EqManager.get(this);   // re-applies the saved equalizer levels
        if (Build.VERSION.SDK_INT >= 23 && !hasLoc()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        startGps();
        obd.start();
        h.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
        obd.stop();
        try { ((LocationManager) getSystemService(LOCATION_SERVICE)).removeUpdates(this); }
        catch (Exception ignore) { }
    }

    @Override protected void onNewIntent(Intent i) {   // Home pressed while we are open
        super.onNewIntent(i);
        if (pageOpen) closePage(false);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) { startGps(); }

    @Override public void onBackPressed() { if (pageOpen) closePage(true); }

    /** Swipe left opens the apps page, swipe right closes it. Touches are not consumed. */
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) { downX = e.getRawX(); downY = e.getRawY(); }
        else if (a == MotionEvent.ACTION_UP) {
            float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
            if (Math.abs(dx) > dp(120) && Math.abs(dy) < Math.abs(dx) * 0.7f) {
                if (dx < 0 && !pageOpen) openPage();
                else if (dx > 0 && pageOpen) closePage(true);
            }
        }
        return super.dispatchTouchEvent(e);
    }

    // ---------------------------- loop ----------------------------
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean fresh = SystemClock.elapsedRealtime() - lastGps < 5000;
            float s = fresh ? gpsSpeed : (obd.speed >= 0 ? obd.speed : 0);
            curSpeed = s;
            speedo.setSpeed(s);
            // timer prefers OBD speed (updates faster than GPS)
            timerStep(obd.connected && obd.speed >= 0 ? obd.speed : s);
            if (ticks % 2 == 0) updateObd();
            if (ticks % 4 == 0) updateClock();
            if (ticks % 8 == 0) updateNowPlaying();
            ticks++;
            h.postDelayed(this, 250);
        }
    };

    private void updateClock() {
        Date d = new Date();
        clock.setText(timeFmt.format(d));
        date.setText(dateFmt.format(d));
    }

    private void updateObd() {
        if (!obd.connected) {
            obdPanel.setVisibility(View.INVISIBLE);
            speedo.setWarn(false);
            aurora.setDanger(false);
            dtcWarning.setScanning(false);
            dtcWarning.setCodes(new ArrayList<String>());
            fuelGauge.setValue(Float.NaN);
            return;
        }
        obdPanel.setVisibility(View.VISIBLE);
        boolean bad = gRpm.setValue(obd.rpm < 0 ? Float.NaN : obd.rpm);
        bad |= gTemp.setValue(obd.coolant == -999 ? Float.NaN : obd.coolant);
        bad |= gBatt.setValue(obd.volts < 0 ? Float.NaN : obd.volts);
        bad |= gLoad.setValue(obd.load < 0 ? Float.NaN : obd.load);
        bad |= gAtf.setValue(obd.atfTemp == -999 ? Float.NaN : obd.atfTemp);

        // Fuel gauge
        fuelGauge.setValue(obd.fuelLevel < 0 ? Float.NaN : obd.fuelLevel);

        // DTC warning
        if (obd.dtcScanned) {
            dtcWarning.setScanning(false);
            dtcWarning.setCodes(obd.dtcCodes);
            bad |= dtcWarning.hasCodes();
        } else {
            dtcWarning.setScanning(true);
        }

        speedo.setWarn(bad);      // any gauge in danger: big speed number turns red
        aurora.setDanger(bad);    // and the screen edge pulses red
    }

    // ---------------------------- 0-100 timer ----------------------------
    private final Runnable timerTick = new Runnable() {
        @Override public void run() {
            if (tState == T_RUN) { showTimer(SystemClock.elapsedRealtime()); h.postDelayed(this, 50); }
        }
    };

    private void timerStep(float v) {
        long now = SystemClock.elapsedRealtime();
        if (tState == T_READY && v >= 1f) {
            tStart = (lastT > 0 && lastV < 1f && v > lastV)
                    ? lastT + (long) ((1f - lastV) / (v - lastV) * (now - lastT)) : now;
            tState = T_RUN;
            h.post(timerTick);
        } else if (tState == T_RUN) {
            if (v < 0.5f) tState = T_READY;
            else if (v >= 100f) {
                long cross = (lastV < 100f && v > lastV)
                        ? lastT + (long) ((100f - lastV) / (v - lastV) * (now - lastT)) : now;
                tRes = (cross - tStart) / 1000f;
                tState = T_DONE;
                if (tBest < 0 || tRes < tBest) tBest = tRes;
            }
        } else if (tState == T_DONE && v < 0.5f) {
            tState = T_READY;
        }
        lastV = v; lastT = now;
        showTimer(now);
    }

    private void showTimer(long now) {
        if (tmBox.getVisibility() != View.VISIBLE) return;
        tmVal.setText(tState == T_READY ? "Ready"
                : String.format(Locale.US, "%.2f s", tState == T_RUN ? (now - tStart) / 1000f : tRes));
        tmBest.setText(tBest < 0 ? "" : String.format(Locale.US, "Best %.2f s", tBest));
    }

    // ---------------------------- now playing ----------------------------
    private void updateNowPlaying() {
        try {
            MediaSessionManager m = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> l = m.getActiveSessions(new ComponentName(this, NowPlayingService.class));
            needsAccess = false;
            controller = null;
            for (MediaController c : l) {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) { controller = c; break; }
            }
            if (controller == null && !l.isEmpty()) controller = l.get(0);
            if (controller == null) { nowPlaying.setText("Nothing playing"); return; }
            MediaMetadata md = controller.getMetadata();
            String t = null;
            if (md != null) {
                t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                String a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (a != null) t = (t == null) ? a : t + " - " + a;
            }
            nowPlaying.setText(t == null ? "Playing" : t);
        } catch (SecurityException e) {
            needsAccess = true;
            controller = null;
            nowPlaying.setText("Tap here to allow now-playing (notification access)");
        }
    }

    private void transport(int what) {
        if (controller == null) return;
        MediaController.TransportControls t = controller.getTransportControls();
        if (what < 0) t.skipToPrevious();
        else if (what > 0) t.skipToNext();
        else {
            PlaybackState st = controller.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) t.pause(); else t.play();
        }
    }

    // ---------------------------- GPS ----------------------------
    private boolean hasLoc() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startGps() {
        if (!hasLoc()) return;
        try {
            ((LocationManager) getSystemService(LOCATION_SERVICE))
                    .requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
        } catch (Exception ignore) { }
    }

    @Override public void onLocationChanged(Location l) {
        if (l.hasSpeed()) { gpsSpeed = l.getSpeed() * 3.6f; lastGps = SystemClock.elapsedRealtime(); }
    }
    @Override public void onStatusChanged(String p, int s, Bundle e) { }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { }

    // ---------------------------- actions ----------------------------
    private boolean launchFirst(String... pkgs) {
        for (String p : pkgs) {
            if (p == null || p.isEmpty()) continue;
            Intent i = getPackageManager().getLaunchIntentForPackage(p);
            if (i != null) { startActivity(i); return true; }
        }
        return false;
    }

    private void openUrl(String url) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        for (String p : BROWSERS) {
            if (getPackageManager().getLaunchIntentForPackage(p) != null) { i.setPackage(p); break; }
        }
        try { startActivity(i); } catch (Exception e) { toast("No browser installed"); }
    }

    private boolean blocked() {
        if (BLOCK_WEB_WHILE_MOVING && curSpeed > MOVING_KMH) { toast("Disabled while driving"); return true; }
        return false;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void onMusic() {
        try { startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)); }
        catch (Exception e) { toast("No music app found"); }
    }
    private void onYouTube() { if (blocked()) return; if (!launchFirst(NEWPIPE)) openUrl("https://m.youtube.com"); }
    private void onBrowser() { if (blocked()) return; if (!launchFirst(BROWSERS)) openUrl("https://www.google.com"); }
    private void onPhone() { if (!launchFirst(PHONE_PACKAGE)) startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
    private void onMaps() { if (!launchFirst(MAPS)) toast("No maps app installed"); }

    // ---------------------------- apps page ----------------------------
    private int pageWidth() { return wrap.getWidth() > 0 ? wrap.getWidth() : getResources().getDisplayMetrics().widthPixels; }

    private void openPage() {
        pageOpen = true;
        page.setVisibility(View.VISIBLE);
        page.setTranslationX(pageWidth());
        page.animate().translationX(0).setDuration(200);
    }

    private void closePage(boolean animate) {
        pageOpen = false;
        page.animate().cancel();
        if (animate) {
            page.animate().translationX(pageWidth()).setDuration(200).withEndAction(new Runnable() {
                public void run() { if (!pageOpen) page.setVisibility(View.GONE); }
            });
        } else {
            page.setVisibility(View.GONE);
        }
    }

    private LinearLayout buildPage() {
        final int M = ViewGroup.LayoutParams.MATCH_PARENT, W = ViewGroup.LayoutParams.WRAP_CONTENT;
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(0xF7060608);
        p.setPadding(dp(24), dp(14), dp(24), dp(14));
        p.setClickable(true);   // swallow taps so the home screen underneath is not pressed

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = button("<  HOME", 18, new Runnable() { public void run() { closePage(true); } });
        back.setPadding(dp(18), dp(8), dp(18), dp(8));
        TextView title = label(28, 0xFFFFFFFF);
        title.setText("Apps");
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        title.setPadding(dp(16), 0, 0, 0);
        head.addView(back, new LinearLayout.LayoutParams(W, W));
        head.addView(title);
        p.addView(head, new LinearLayout.LayoutParams(M, W));

        String[] names = {"Music", "Equalizer", "YouTube", "Browser", "Phone / Bluetooth", "Maps", "Settings", "All apps"};
        Runnable[] acts = {
                new Runnable() { public void run() { onMusic(); } },
                new Runnable() { public void run() { startActivity(new Intent(MainActivity.this, EqualizerActivity.class)); } },
                new Runnable() { public void run() { onYouTube(); } },
                new Runnable() { public void run() { onBrowser(); } },
                new Runnable() { public void run() { onPhone(); } },
                new Runnable() { public void run() { onMaps(); } },
                new Runnable() { public void run() { startActivity(new Intent(Settings.ACTION_SETTINGS)); } },
                new Runnable() { public void run() { startActivity(new Intent(MainActivity.this, AppDrawerActivity.class)); } }};
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int c = 0; c < 4; c++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, M, 1f);
                lp.setMargins(dp(5), dp(5), dp(5), dp(5));
                row.addView(button(names[r * 4 + c], 20, acts[r * 4 + c]), lp);
            }
            grid.addView(row, new LinearLayout.LayoutParams(M, 0, 1f));
        }
        p.addView(grid, new LinearLayout.LayoutParams(M, 0, 1f));

        Switch sw = new Switch(this);
        sw.setText("0-100 km/h timer on the home screen");
        sw.setTextSize(20);
        sw.setTextColor(0xFFFFFFFF);
        sw.setChecked(prefs.getBoolean("timer_on", true));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.edit().putBoolean("timer_on", on).apply();
                tmBox.setVisibility(on ? View.VISIBLE : View.GONE);
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(W, W);
        slp.topMargin = dp(8);
        p.addView(sw, slp);
        return p;
    }

    // ---------------------------- UI helpers ----------------------------
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private LinearLayout.LayoutParams lp(int w, int hh, float weight) {
        return new LinearLayout.LayoutParams(w, hh, weight);
    }

    private GradientDrawable shape(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(12));
        return g;
    }

    private TextView button(String label, int textSp, final Runnable r) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(textSp);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, shape(RED));
        sl.addState(new int[]{}, shape(0x1AFFFFFF));
        t.setBackground(sl);
        t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { r.run(); } });
        return t;
    }

    private TextView label(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout row(View a, View b) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.addView(a, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        r.addView(b, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return r;
    }

    private TextView barBtn(String s, final int what) {
        return button(s, 16, new Runnable() { public void run() { transport(what); } });
    }

    private LinearLayout.LayoutParams barLp() {
        LinearLayout.LayoutParams p = lp(dp(150), ViewGroup.LayoutParams.MATCH_PARENT, 0f);
        p.setMargins(dp(8), dp(4), 0, dp(4));
        return p;
    }

    private void buildUi() {
        final int M = ViewGroup.LayoutParams.MATCH_PARENT, W = ViewGroup.LayoutParams.WRAP_CONTENT;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(8), dp(14), dp(8));

        // top: clock + timer | speedometer | gauges
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(top, lp(M, 0, 1f));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        clock = label(60, 0xFFFFFFFF);
        clock.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        date = label(18, 0x8CFFFFFF);
        left.addView(clock);
        left.addView(date);

        LinearLayout tm = new LinearLayout(this);
        tm.setOrientation(LinearLayout.HORIZONTAL);
        View accent = new View(this);
        accent.setBackgroundColor(RED);
        tm.addView(accent, new LinearLayout.LayoutParams(dp(3), M));
        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        tcol.setPadding(dp(10), 0, 0, 0);
        TextView tlab = label(13, 0x8CFFFFFF);
        tlab.setText("0-100 km/h");
        tmVal = label(30, 0xFFFFFFFF);
        tmVal.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        tmVal.setText("Ready");
        tmBest = label(13, 0x8CFFFFFF);
        tcol.addView(tlab);
        tcol.addView(tmVal);
        tcol.addView(tmBest);
        tm.addView(tcol);
        tm.setVisibility(prefs.getBoolean("timer_on", true) ? View.VISIBLE : View.GONE);
        tmBox = tm;
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(W, W);
        tlp.topMargin = dp(14);
        left.addView(tm, tlp);
        top.addView(left, lp(0, M, 1f));

        speedo = new SpeedometerView(this);
        top.addView(speedo, lp(0, M, 1.4f));

        // gauges: name, unit, min, max, cautionHigh, dangerHigh, cautionLow, dangerLow, decimals
        obdPanel = new LinearLayout(this);
        obdPanel.setOrientation(LinearLayout.VERTICAL);
        obdPanel.setGravity(Gravity.CENTER_VERTICAL);
        obdPanel.setVisibility(View.INVISIBLE);
        gRpm  = new MiniGaugeView(this, "RPM", "rpm", 0, 8000, 5000, 6000, Float.NaN, Float.NaN, 0);
        gTemp = new MiniGaugeView(this, "COOLANT", "\u00B0C", 40, 130, 100, 108, Float.NaN, Float.NaN, 0);
        gBatt = new MiniGaugeView(this, "BATTERY", "V", 10, 16, Float.NaN, 15.2f, 12.0f, 11.8f, 1);
        gLoad = new MiniGaugeView(this, "LOAD", "%", 0, 100, 85, 95, Float.NaN, Float.NaN, 0);
        gAtf  = new MiniGaugeView(this, "ATF TEMP", "\u00B0C", 20, 150, 110, 130, Float.NaN, Float.NaN, 0);
        obdPanel.addView(row(gRpm, gTemp), lp(M, 0, 1f));
        obdPanel.addView(row(gBatt, gLoad), lp(M, 0, 1f));

        // Third row: ATF temp gauge + fuel gauge + DTC warning
        fuelGauge  = new FuelGaugeView(this);
        dtcWarning = new DtcWarningView(this);
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.addView(gAtf,        lp(0, M, 1f));
        row3.addView(fuelGauge,   lp(0, M, 1f));
        row3.addView(dtcWarning,  lp(0, M, 1f));
        obdPanel.addView(row3, lp(M, 0, 1f));

        top.addView(obdPanel, lp(0, M, 1.3f));

        // bluetooth music bar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        nowPlaying = label(18, 0xCCFFFFFF);
        nowPlaying.setSingleLine(true);
        nowPlaying.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowPlaying.setText("Nothing playing");
        nowPlaying.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (needsAccess) startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            }
        });
        bar.addView(nowPlaying, lp(0, M, 1f));
        bar.addView(barBtn("PREV", -1), barLp());
        bar.addView(barBtn("PLAY / PAUSE", 0), barLp());
        bar.addView(barBtn("NEXT", 1), barLp());
        root.addView(bar, lp(M, dp(56), 0f));

        wrap = new FrameLayout(this);
        aurora = new AuroraView(this);
        wrap.addView(aurora);
        wrap.addView(root);

        // faint hint on the right edge: swipe left (or tap) for apps
        FrameLayout edge = new FrameLayout(this);
        View pill = new View(this);
        GradientDrawable pg = new GradientDrawable();
        pg.setColor(0x59FFFFFF);
        pg.setCornerRadius(dp(2));
        pill.setBackground(pg);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(4), dp(80), Gravity.END | Gravity.CENTER_VERTICAL);
        plp.rightMargin = dp(4);
        edge.addView(pill, plp);
        edge.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openPage(); } });
        wrap.addView(edge, new FrameLayout.LayoutParams(dp(28), dp(160), Gravity.END | Gravity.CENTER_VERTICAL));

        page = buildPage();
        page.setVisibility(View.GONE);
        wrap.addView(page, new FrameLayout.LayoutParams(M, M));

        setContentView(wrap);
    }
}
