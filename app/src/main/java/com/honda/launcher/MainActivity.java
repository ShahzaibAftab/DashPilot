package com.honda.launcher;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
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
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity implements LocationListener {

    // ===================== CONFIG =====================
    static final int RED = 0xFFE4002B;
    static final int GREEN = 0xFF00E676;
    static final int AMBER = 0xFFFFB300;
    static final int GREY = 0xFF90A4AE;

    // Common Chinese Android head-unit package names
    static final String[] PKG_BT = {
            "com.gocsdk", "com.syu.bt", "com.car.bt", "com.ts.bt", "com.forfan.bt", "com.allwinner.bluetooth",
            "cn.manstep.phonemirrorbox", "com.zjinnova.zlink"
    };
    static final String[] PKG_SETTINGS = {
            "com.syu.canbus", "com.car.settings", "com.ts.carsettings", "com.allwinner.carsettings"
    };
    static final String[] PKG_RADIO = {
            "com.syu.radio", "com.car.radio", "com.ts.radio", "com.forfan.radio", "com.allwinner.radio", "com.android.fmradio"
    };
    static final String[] PKG_MUSIC = {
            "com.syu.music", "com.car.music", "com.ts.music", "com.android.music"
    };
    static final String[] PKG_VIDEO = {
            "com.syu.video", "com.car.video", "com.ts.video", "com.android.gallery3d"
    };
    static final String[] PKG_MAPS = {
            "com.google.android.apps.maps", "com.google.android.apps.mapslite", "net.osmand.plus", "net.osmand",
            "com.waze", "com.autonavi.amapauto"
    };
    static final String[] PKG_EQ = {
            "com.syu.eq", "com.car.eq", "com.ts.eq", "com.forfan.eq"
    };
    // ===================================================

    private static final int T_IDLE = 0, T_ARMED = 1, T_RUN = 2, T_DONE = 3;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEE, d MMM", Locale.getDefault());

    private TextView clock, date, nowPlaying, tmVal, tmBest;
    private View tmBox;
    private SpeedometerView speedo;
    private AuroraView aurora;
    private LinearLayout obdPanel, row1, row2, row3;
    private LinearLayout pageGauges, pageApps;
    private FrameLayout wrap;
    private MiniGaugeView gRpm, gTemp, gBatt, gLoad, gAtf;
    private FuelGaugeView fuelGauge;
    private DtcWarningView dtcWarning;
    private final ObdManager obd = new ObdManager();
    private MediaController controller;
    private SharedPreferences prefs;
    private boolean needsAccess;
    private int curPage = 0; // 0 = Home, 1 = Gauges, 2 = Native Apps

    // Dynamic badges for unsupported/supported detection
    private TextView badgeAtf, badgeFuel;

    // In-launcher Bluetooth and OBD status card views
    private TextView obdStatusText, phoneStatusText;
    private LinearLayout pairedDevicesContainer;
    private String btTrack = null, btArtist = null;

    private volatile float gpsSpeed;
    private volatile long lastGps;
    private float curSpeed, downX, downY;
    private int ticks;

    // 0-100 timer
    private int tState;
    private long tStart, lastT;
    private float lastV, tBest = -1f, tRes;

    // Bluetooth music receiver for AVRCP metadata from phone
    private final BroadcastReceiver btMusicReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String track = intent.getStringExtra("track");
            if (track == null) track = intent.getStringExtra("title");
            if (track == null) track = intent.getStringExtra("song");
            if (track == null) track = intent.getStringExtra("name");
            if (track == null) track = intent.getStringExtra("song_name");

            String artist = intent.getStringExtra("artist");
            if (artist == null) artist = intent.getStringExtra("singer");

            if (track != null && !track.trim().isEmpty()) {
                btTrack = track.trim();
                btArtist = (artist != null && !artist.trim().isEmpty()) ? artist.trim() : "";
                String label = btArtist.isEmpty() ? btTrack : btTrack + " - " + btArtist;
                nowPlaying.setText(label);
            }
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);

        // Load manually selected OBD-II device if saved
        String savedMac = prefs.getString("obd_mac", null);
        if (savedMac != null && !savedMac.isEmpty()) {
            obd.manualDeviceAddress = savedMac;
        }

        buildUi();
        applyGaugeToggles();
        EqManager.get(this);   // re-applies saved equalizer levels
        if (Build.VERSION.SDK_INT >= 23 && !hasLoc()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        startGps();
        obd.start();
        h.post(tick);

        IntentFilter f = new IntentFilter();
        f.addAction("com.android.music.metachanged");
        f.addAction("com.android.music.playstatechanged");
        f.addAction("com.android.music.playbackcomplete");
        f.addAction("com.htc.music.metachanged");
        f.addAction("com.real.IMP.metachanged");
        f.addAction("com.sonyericsson.music.metachanged");
        f.addAction("com.samsung.sec.android.MusicPlayer.metachanged");
        f.addAction("android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED");
        f.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        f.addAction("com.gocsdk.action.BT_A2DP_INFO");
        f.addAction("com.syu.bt.action.A2DP_METADATA");
        f.addAction("com.car.bt.a2dp.metachanged");
        f.addAction("com.my.bt.music.info");
        try { registerReceiver(btMusicReceiver, f); } catch (Exception ignore) { }
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
        obd.stop();
        try { ((LocationManager) getSystemService(LOCATION_SERVICE)).removeUpdates(this); }
        catch (Exception ignore) { }
        try { unregisterReceiver(btMusicReceiver); }
        catch (Exception ignore) { }
    }

    @Override protected void onNewIntent(Intent i) {   // Home pressed while we are open
        super.onNewIntent(i);
        if (curPage != 0) goToPage(0, false);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) { startGps(); }

    @Override public void onBackPressed() {
        if (curPage == 2) goToPage(1, true);
        else if (curPage == 1) goToPage(0, true);
    }

    /**
     * Swipe gestures:
     * Home (0) -> swipe left -> Gauges Page (1)
     * Gauges Page (1) -> swipe left -> Native Apps (2)
     * Native Apps (2) -> swipe right -> Gauges Page (1)
     * Gauges Page (1) -> swipe right -> Home (0)
     */
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            downX = e.getRawX(); downY = e.getRawY();
        } else if (a == MotionEvent.ACTION_UP) {
            float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
            if (Math.abs(dx) > dp(100) && Math.abs(dy) < Math.abs(dx) * 0.7f) {
                if (dx < 0) { // swipe left
                    if (curPage == 0) goToPage(1, true);
                    else if (curPage == 1) goToPage(2, true);
                } else if (dx > 0) { // swipe right
                    if (curPage == 2) goToPage(1, true);
                    else if (curPage == 1) goToPage(0, true);
                }
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
            if (ticks % 6 == 0) updateNowPlaying();
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
        updateObdStatusCard();

        if (!obd.connected) {
            // Keep gauges visible in sleek standby mode instead of an empty black void!
            gRpm.setValue(Float.NaN);
            gTemp.setValue(Float.NaN);
            gBatt.setValue(Float.NaN);
            gLoad.setValue(Float.NaN);
            gAtf.setValue(Float.NaN);
            fuelGauge.setValue(Float.NaN);
            dtcWarning.setScanning(false);
            dtcWarning.setCodes(new ArrayList<String>());
            speedo.setWarn(false);
            aurora.setDanger(false);
            updateGaugeBadges();
            return;
        }

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

        speedo.setWarn(bad);
        aurora.setDanger(bad);

        // Update PID support badges if on gauge page
        updateGaugeBadges();
    }

    private void updateObdStatusCard() {
        if (obdStatusText != null) {
            if (obd.state == ObdManager.STATE_CONNECTED) {
                String dev = (obd.connectedDeviceName != null && !obd.connectedDeviceName.isEmpty())
                        ? obd.connectedDeviceName : "ELM327";
                obdStatusText.setText("● OBD-II: CONNECTED (" + dev + ")");
                obdStatusText.setTextColor(GREEN);
            } else if (obd.state == ObdManager.STATE_CONNECTING) {
                obdStatusText.setText("◐ OBD-II: " + obd.statusMessage);
                obdStatusText.setTextColor(AMBER);
            } else {
                obdStatusText.setText("○ OBD-II: " + obd.statusMessage);
                obdStatusText.setTextColor(0xFFCFD8DC);
            }
        }

        if (phoneStatusText != null) {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            boolean musicActive = (am != null && am.isMusicActive());
            if (musicActive || (btTrack != null && !btTrack.isEmpty())) {
                phoneStatusText.setText("● Mobile Phone: AUDIO CONNECTED (A2DP)");
                phoneStatusText.setTextColor(GREEN);
            } else {
                phoneStatusText.setText("○ Mobile Phone: READY / STANDBY (A2DP)");
                phoneStatusText.setTextColor(0xFFCFD8DC);
            }
        }
    }

    private void updateGaugeBadges() {
        if (badgeAtf != null) {
            if (obd.connected && obd.pidsDetected && !obd.hasAtfSupported) {
                badgeAtf.setVisibility(View.VISIBLE);
                badgeAtf.setText(" NOT SUPPORTED BY ECU ");
                badgeAtf.setTextColor(AMBER);
                badgeAtf.setBackground(badgeShape(0x33FFB300));
            } else {
                badgeAtf.setVisibility(View.GONE);
            }
        }
        if (badgeFuel != null) {
            if (obd.connected && obd.pidsDetected && !obd.hasFuelSupported) {
                badgeFuel.setVisibility(View.VISIBLE);
                badgeFuel.setText(" NOT SUPPORTED BY ECU ");
                badgeFuel.setTextColor(AMBER);
                badgeFuel.setBackground(badgeShape(0x33FFB300));
            } else {
                badgeFuel.setVisibility(View.GONE);
            }
        }
    }

    private void applyGaugeToggles() {
        boolean sRpm = prefs.getBoolean("show_rpm", true);
        boolean sCool = prefs.getBoolean("show_coolant", true);
        boolean sBatt = prefs.getBoolean("show_battery", true);
        boolean sLoad = prefs.getBoolean("show_load", true);
        boolean sAtf = prefs.getBoolean("show_atf", true);
        boolean sFuel = prefs.getBoolean("show_fuel", true);
        boolean sDtc = prefs.getBoolean("show_dtc", true);
        boolean sTm = prefs.getBoolean("timer_on", true);

        if (gRpm != null) gRpm.setVisibility(sRpm ? View.VISIBLE : View.GONE);
        if (gTemp != null) gTemp.setVisibility(sCool ? View.VISIBLE : View.GONE);
        if (row1 != null) row1.setVisibility((sRpm || sCool) ? View.VISIBLE : View.GONE);

        if (gBatt != null) gBatt.setVisibility(sBatt ? View.VISIBLE : View.GONE);
        if (gLoad != null) gLoad.setVisibility(sLoad ? View.VISIBLE : View.GONE);
        if (row2 != null) row2.setVisibility((sBatt || sLoad) ? View.VISIBLE : View.GONE);

        if (gAtf != null) gAtf.setVisibility(sAtf ? View.VISIBLE : View.GONE);
        if (fuelGauge != null) fuelGauge.setVisibility(sFuel ? View.VISIBLE : View.GONE);
        if (dtcWarning != null) dtcWarning.setVisibility(sDtc ? View.VISIBLE : View.GONE);
        if (row3 != null) row3.setVisibility((sAtf || sFuel || sDtc) ? View.VISIBLE : View.GONE);

        if (tmBox != null) tmBox.setVisibility(sTm ? View.VISIBLE : View.GONE);
    }

    // ---------------------------- 0-100 timer ----------------------------
    private final Runnable timerTick = new Runnable() {
        @Override public void run() {
            if (tState == T_RUN) { showTimer(SystemClock.elapsedRealtime()); h.postDelayed(this, 50); }
        }
    };

    private void timerStep(float v) {
        long now = SystemClock.elapsedRealtime();
        if (tState == T_IDLE && v <= 1f) {
            tState = T_ARMED;
        } else if (tState == T_ARMED && v > 2f) {
            tStart = now;
            tState = T_RUN;
            h.post(timerTick);
        } else if (tState == T_RUN && v >= 100f) {
            tState = T_DONE;
            float dt = (now - tStart) / 1000f;
            if (v > lastV && lastT > tStart) {
                float frac = (100f - lastV) / (v - lastV);
                dt = (lastT - tStart) / 1000f + frac * (now - lastT) / 1000f;
            }
            tRes = dt;
            if (tBest < 0 || dt < tBest) { tBest = dt; prefs.edit().putFloat("best", tBest).apply(); }
        } else if (tState == T_DONE && v <= 1f) {
            tState = T_ARMED;
        }
        lastV = v; lastT = now;
        showTimer(now);
    }

    private void showTimer(long now) {
        if (tBest < 0) tBest = prefs.getFloat("best", -1f);
        tmBest.setText(tBest > 0 ? String.format(Locale.US, "Best: %.2fs", tBest) : "Best: --");
        if (tState == T_IDLE)  tmVal.setText("Ready");
        else if (tState == T_ARMED) tmVal.setText("Launch");
        else if (tState == T_RUN)   tmVal.setText(String.format(Locale.US, "%.1fs", (now - tStart) / 1000f));
        else                        tmVal.setText(String.format(Locale.US, "%.2fs", tRes));
    }

    // ---------------------------- now playing ----------------------------
    private void updateNowPlaying() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        boolean isMusicActive = (am != null && am.isMusicActive());

        // 1. Try local MediaSession (Spotify, YouTube, media player running on unit)
        try {
            MediaSessionManager m = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> l = m.getActiveSessions(new ComponentName(this, NowPlayingService.class));
            needsAccess = false;
            controller = null;
            if (l != null) {
                for (MediaController c : l) {
                    PlaybackState st = c.getPlaybackState();
                    if (st != null && st.getState() == PlaybackState.STATE_PLAYING) { controller = c; break; }
                }
                if (controller == null && !l.isEmpty()) controller = l.get(0);
            }
            if (controller != null) {
                MediaMetadata md = controller.getMetadata();
                if (md != null) {
                    String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                    String a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                    if (t != null && !t.trim().isEmpty()) {
                        nowPlaying.setText(a != null && !a.trim().isEmpty() ? t + " - " + a : t);
                        return;
                    }
                }
            }
        } catch (SecurityException e) {
            needsAccess = true;
            controller = null;
        } catch (Throwable ignore) { }

        // 2. If Bluetooth track info was received from phone
        if (btTrack != null && !btTrack.isEmpty()) {
            String display = (btArtist != null && !btArtist.isEmpty()) ? btTrack + " - " + btArtist : btTrack;
            nowPlaying.setText(display);
            return;
        }

        // 3. Audio actively playing through car speakers (Chrome, Bluetooth stream, etc.)
        if (isMusicActive) {
            nowPlaying.setText("Playing Audio (Bluetooth / Media)");
            return;
        }

        // 4. Truly idle
        if (needsAccess) {
            nowPlaying.setText("Tap to allow notification access");
        } else {
            nowPlaying.setText("Nothing playing");
        }
    }

    private void transport(int what) {
        if (controller != null) {
            try {
                MediaController.TransportControls t = controller.getTransportControls();
                if (what < 0) t.skipToPrevious();
                else if (what > 0) t.skipToNext();
                else {
                    PlaybackState st = controller.getPlaybackState();
                    if (st != null && st.getState() == PlaybackState.STATE_PLAYING) t.pause(); else t.play();
                }
                return;
            } catch (Throwable ignore) { }
        }

        // Global media key dispatch for active Bluetooth audio or background players
        int keyCode = (what < 0) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    : (what > 0) ? KeyEvent.KEYCODE_MEDIA_NEXT
                    : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                long now = SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
            }
        } catch (Throwable ignore) { }
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

    private boolean launchFirst(String... pkgs) {
        for (String p : pkgs) {
            if (p == null || p.isEmpty()) continue;
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(p);
                if (i != null) { startActivity(i); return true; }
            } catch (Throwable ignore) { }
        }
        return false;
    }

    private void openCarBluetooth() {
        // 1. Try known Chinese car head unit Bluetooth packages
        for (String p : PKG_BT) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(p);
                if (i != null) { startActivity(i); return; }
            } catch (Throwable ignore) {}
        }

        // 2. Search installed packages for any bluetooth or phone app
        try {
            PackageManager pm = getPackageManager();
            List<ResolveInfo> list = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            if (list != null) {
                for (ResolveInfo ri : list) {
                    String pkg = ri.activityInfo.packageName.toLowerCase(Locale.US);
                    String cls = ri.activityInfo.name.toLowerCase(Locale.US);
                    if (pkg.contains("blue") || pkg.contains("bt") || cls.contains("blue") || cls.contains("bt") || pkg.contains("phone")) {
                        Intent i = pm.getLaunchIntentForPackage(ri.activityInfo.packageName);
                        if (i != null) { startActivity(i); return; }
                    }
                }
            }
        } catch (Throwable ignore) {}

        // 3. Fallback: Android Bluetooth settings
        try {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            return;
        } catch (Throwable ignore) {}

        // 4. Fallback: Android System settings
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
            return;
        } catch (Throwable ignore) {}

        toast("Bluetooth is managed directly in DashPilot below.");
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---------------------------- Multi-page Navigation ----------------------------
    private int pageWidth() {
        return wrap != null && wrap.getWidth() > 0 ? wrap.getWidth() : getResources().getDisplayMetrics().widthPixels;
    }

    private void goToPage(int target, boolean animate) {
        if (target == curPage) return;
        int prev = curPage;
        curPage = target;
        int w = pageWidth();

        if (target == 0) { // Home
            if (animate) {
                if (pageGauges.getVisibility() == View.VISIBLE) {
                    pageGauges.animate().translationX(w).setDuration(220).withEndAction(new Runnable() {
                        public void run() { pageGauges.setVisibility(View.GONE); }
                    });
                }
                if (pageApps.getVisibility() == View.VISIBLE) {
                    pageApps.animate().translationX(w).setDuration(220).withEndAction(new Runnable() {
                        public void run() { pageApps.setVisibility(View.GONE); }
                    });
                }
            } else {
                pageGauges.setVisibility(View.GONE);
                pageApps.setVisibility(View.GONE);
            }
        } else if (target == 1) { // Page 1: Gauges Settings & In-Launcher Bluetooth
            pageGauges.setVisibility(View.VISIBLE);
            updateGaugeBadges();
            updateObdStatusCard();
            refreshPairedDevicesList();
            if (animate) {
                float startX = (prev == 0) ? w : -w;
                pageGauges.setTranslationX(startX);
                pageGauges.animate().translationX(0).setDuration(220);
                if (pageApps.getVisibility() == View.VISIBLE) {
                    pageApps.animate().translationX(w).setDuration(220).withEndAction(new Runnable() {
                        public void run() { pageApps.setVisibility(View.GONE); }
                    });
                }
            } else {
                pageGauges.setTranslationX(0);
                pageApps.setVisibility(View.GONE);
            }
        } else if (target == 2) { // Page 2: Native Apps
            pageApps.setVisibility(View.VISIBLE);
            if (animate) {
                pageApps.setTranslationX(w);
                pageApps.animate().translationX(0).setDuration(220);
                if (pageGauges.getVisibility() == View.VISIBLE) {
                    pageGauges.animate().translationX(-w).setDuration(220).withEndAction(new Runnable() {
                        public void run() { pageGauges.setVisibility(View.GONE); }
                    });
                }
            } else {
                pageApps.setTranslationX(0);
                pageGauges.setVisibility(View.GONE);
            }
        }
    }

    // ---------------------------- In-Launcher Bluetooth Device Manager ----------------------------
    private void refreshPairedDevicesList() {
        if (pairedDevicesContainer == null) return;
        pairedDevicesContainer.removeAllViews();

        BluetoothAdapter ad = null;
        try { ad = BluetoothAdapter.getDefaultAdapter(); } catch (Throwable ignore) { }

        if (ad == null) {
            TextView t = label(13, 0x8CFFFFFF);
            t.setText("Bluetooth hardware not detected.");
            t.setPadding(dp(8), dp(4), dp(8), dp(4));
            pairedDevicesContainer.addView(t);
            return;
        }

        if (!ad.isEnabled()) {
            TextView t = label(13, AMBER);
            t.setText("Bluetooth is OFF. Tap below to enable.");
            t.setPadding(dp(8), dp(4), dp(8), dp(4));
            pairedDevicesContainer.addView(t);

            TextView btnEnable = button("ENABLE BLUETOOTH", 13, new Runnable() {
                public void run() {
                    try {
                        BluetoothAdapter.getDefaultAdapter().enable();
                        toast("Enabling Bluetooth...");
                        h.postDelayed(new Runnable() { public void run() { refreshPairedDevicesList(); updateObdStatusCard(); } }, 1500);
                    } catch (Throwable ignore) { }
                }
            });
            btnEnable.setPadding(dp(12), dp(6), dp(12), dp(6));
            pairedDevicesContainer.addView(btnEnable);
            return;
        }

        Set<BluetoothDevice> bonded = null;
        try { bonded = ad.getBondedDevices(); } catch (Throwable ignore) { }

        if (bonded == null || bonded.isEmpty()) {
            TextView t = label(13, 0x8CFFFFFF);
            t.setText("No paired Bluetooth devices found.\nPair your OBD-II adapter or Mobile Phone in your car's Bluetooth menu first.");
            t.setPadding(dp(8), dp(6), dp(8), dp(6));
            pairedDevicesContainer.addView(t);
            return;
        }

        final String activeMac = (obd.manualDeviceAddress != null) ? obd.manualDeviceAddress : "";

        for (final BluetoothDevice d : bonded) {
            final String name = (d.getName() != null && !d.getName().isEmpty()) ? d.getName() : "Unknown Device";
            final String mac = d.getAddress() != null ? d.getAddress() : "";
            final boolean isSelected = !activeMac.isEmpty() ? mac.equalsIgnoreCase(activeMac) : isObdCandidate(name);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(shape(isSelected ? 0x3300E676 : 0x1AFFFFFF));
            card.setPadding(dp(12), dp(8), dp(12), dp(8));

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = dp(6);
            card.setLayoutParams(clp);

            // Left: Device icon, name and MAC
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);

            String icon = isObdCandidate(name) ? "🚗 " : "📱 ";
            TextView tName = label(15, 0xFFFFFFFF);
            tName.setText(icon + name);
            tName.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));

            TextView tMac = label(12, isSelected ? GREEN : 0x8CFFFFFF);
            tMac.setText(mac + (isSelected ? "  •  ACTIVE OBD2 SENSOR" : ""));

            info.addView(tName);
            info.addView(tMac);
            card.addView(info, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // Right: Select / Status action
            if (isSelected) {
                TextView activeBadge = label(12, GREEN);
                activeBadge.setText(" SELECTED OBD ✓ ");
                activeBadge.setTypeface(Typeface.DEFAULT_BOLD);
                activeBadge.setBackground(badgeShape(0x3300E676));
                activeBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
                card.addView(activeBadge);
            } else {
                TextView btnUse = button("SET AS OBD", 13, new Runnable() {
                    public void run() {
                        prefs.edit().putString("obd_mac", mac).apply();
                        obd.setManualDevice(mac);
                        toast("Set " + name + " as OBD sensor. Connecting...");
                        refreshPairedDevicesList();
                        updateObdStatusCard();
                    }
                });
                btnUse.setPadding(dp(10), dp(6), dp(10), dp(6));
                card.addView(btnUse);
            }

            pairedDevicesContainer.addView(card);
        }
    }

    private boolean isObdCandidate(String name) {
        if (name == null) return false;
        String u = name.toUpperCase(Locale.US);
        return u.contains("OBD") || u.contains("ELM") || u.contains("LINK")
                || u.contains("VGATE") || u.contains("VIEFLO") || u.contains("KONNWEI")
                || u.contains("SCAN") || u.contains("DIAG");
    }

    // ---------------------------- Page 1: Gauge Toggles & In-Launcher Bluetooth ----------------------------
    private LinearLayout buildGaugesPage() {
        final int M = ViewGroup.LayoutParams.MATCH_PARENT, W = ViewGroup.LayoutParams.WRAP_CONTENT;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xF7060608);
        root.setPadding(dp(20), dp(12), dp(20), dp(12));
        root.setClickable(true);

        // Header bar
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setOrientation(LinearLayout.HORIZONTAL);

        TextView back = button("<  HOME", 16, new Runnable() { public void run() { goToPage(0, true); } });
        back.setPadding(dp(16), dp(8), dp(16), dp(8));

        TextView title = label(24, 0xFFFFFFFF);
        title.setText("GAUGE & BLUETOOTH SETTINGS");
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        title.setPadding(dp(16), 0, 0, 0);

        TextView next = button("NATIVE APPS  >", 16, new Runnable() { public void run() { goToPage(2, true); } });
        next.setPadding(dp(16), dp(8), dp(16), dp(8));

        head.addView(back, new LinearLayout.LayoutParams(W, W));
        head.addView(title, lp(0, W, 1f));
        head.addView(next, new LinearLayout.LayoutParams(W, W));
        root.addView(head, new LinearLayout.LayoutParams(M, W));

        // Subtitle hint
        TextView hint = label(12, 0x8CFFFFFF);
        hint.setText("Manage OBD-II & Phone Bluetooth directly below • Toggle which gauges appear on your home screen");
        hint.setPadding(dp(4), dp(4), 0, dp(10));
        root.addView(hint);

        // Scrollable cards list
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        // Top: Dedicated In-Launcher Bluetooth & OBD-II Management Card
        list.addView(buildBluetoothHubCard());

        TextView tGaugesHeader = label(16, 0xFFFFFFFF);
        tGaugesHeader.setText("DASHBOARD GAUGES SHOW / HIDE");
        tGaugesHeader.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        tGaugesHeader.setPadding(dp(2), dp(8), 0, dp(8));
        list.addView(tGaugesHeader);

        TextView[] bAtf = new TextView[1];
        TextView[] bFuel = new TextView[1];

        list.addView(gaugeCard("ENGINE RPM GAUGE", "0–8000 RPM dial (PID 010C)", "show_rpm", null));
        list.addView(gaugeCard("COOLANT TEMPERATURE", "Engine block temperature (PID 0105)", "show_coolant", null));
        list.addView(gaugeCard("BATTERY VOLTAGE", "Alternator & battery health (ATRV)", "show_battery", null));
        list.addView(gaugeCard("ENGINE LOAD", "Real-time engine capacity load (PID 0104)", "show_load", null));
        list.addView(gaugeCard("ATF TRANSMISSION TEMP", "Automatic / CVT transmission fluid temp", "show_atf", bAtf));
        badgeAtf = bAtf[0];
        list.addView(gaugeCard("FUEL TANK LEVEL", "Liquid animated tank & remaining percentage", "show_fuel", bFuel));
        badgeFuel = bFuel[0];
        list.addView(gaugeCard("DTC TROUBLE WARNING", "Check engine warning & diagnostic codes (Mode 03)", "show_dtc", null));
        list.addView(gaugeCard("0–100 KM/H LAUNCH TIMER", "Automatic launch acceleration timer", "timer_on", null));

        sv.addView(list);
        root.addView(sv, lp(M, 0, 1f));
        return root;
    }

    private View buildBluetoothHubCard() {
        final int M = ViewGroup.LayoutParams.MATCH_PARENT, W = ViewGroup.LayoutParams.WRAP_CONTENT;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(shape(0x22FFFFFF));
        card.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(M, W);
        clp.bottomMargin = dp(14);
        card.setLayoutParams(clp);

        // OBD2 Status Line
        obdStatusText = label(16, 0xFFCFD8DC);
        obdStatusText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        card.addView(obdStatusText);

        // Control Buttons
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(8), 0, dp(8));

        TextView btnConnect = button("CONNECT / RETRY", 13, new Runnable() {
            public void run() {
                obd.reconnectNow();
                toast("Connecting to OBD-II...");
                updateObdStatusCard();
            }
        });
        btnConnect.setPadding(dp(12), dp(6), dp(12), dp(6));

        TextView btnRefresh = button("REFRESH DEVICES", 13, new Runnable() {
            public void run() {
                refreshPairedDevicesList();
                toast("Refreshed Bluetooth device list.");
            }
        });
        btnRefresh.setPadding(dp(12), dp(6), dp(12), dp(6));

        TextView btnCarBt = button("CAR BLUETOOTH APP", 13, new Runnable() {
            public void run() {
                openCarBluetooth();
            }
        });
        btnCarBt.setPadding(dp(12), dp(6), dp(12), dp(6));

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, W, 1f);
        blp.rightMargin = dp(6);
        btnRow.addView(btnConnect, blp);

        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(0, W, 1f);
        blp2.rightMargin = dp(6);
        btnRow.addView(btnRefresh, blp2);

        btnRow.addView(btnCarBt, lp(0, W, 1f));
        card.addView(btnRow);

        // In-line Paired Devices Container
        TextView devHeader = label(13, 0xCCFFFFFF);
        devHeader.setText("PAIRED BLUETOOTH DEVICES (tap 'SET AS OBD' to connect):");
        devHeader.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        devHeader.setPadding(0, dp(4), 0, dp(4));
        card.addView(devHeader);

        pairedDevicesContainer = new LinearLayout(this);
        pairedDevicesContainer.setOrientation(LinearLayout.VERTICAL);
        card.addView(pairedDevicesContainer);

        // Divider
        View div = new View(this);
        div.setBackgroundColor(0x2AFFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(M, dp(1));
        dlp.topMargin = dp(8); dlp.bottomMargin = dp(8);
        card.addView(div, dlp);

        // Phone Audio Status
        phoneStatusText = label(14, 0xFFCFD8DC);
        phoneStatusText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        card.addView(phoneStatusText);

        TextView note = label(12, 0x8CFFFFFF);
        note.setText("Dual Bluetooth: Mobile connects for Music/Calls, OBD-II connects for Telemetry at the same time.");
        note.setPadding(0, dp(2), 0, 0);
        card.addView(note);

        updateObdStatusCard();
        refreshPairedDevicesList();
        return card;
    }

    private View gaugeCard(String title, String subtitle, final String prefKey, TextView[] outBadge) {
        final int M = ViewGroup.LayoutParams.MATCH_PARENT, W = ViewGroup.LayoutParams.WRAP_CONTENT;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(shape(0x1AFFFFFF));
        card.setPadding(dp(16), dp(10), dp(16), dp(10));

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(M, W);
        clp.bottomMargin = dp(8);
        card.setLayoutParams(clp);

        // Left info
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView tTitle = label(16, 0xFFFFFFFF);
        tTitle.setText(title);
        tTitle.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));

        TextView tSub = label(12, 0x8CFFFFFF);
        tSub.setText(subtitle);

        col.addView(tTitle);
        col.addView(tSub);

        // Only add badge view if this sensor can be unsupported
        if (outBadge != null) {
            TextView tBadge = label(11, AMBER);
            tBadge.setTypeface(Typeface.DEFAULT_BOLD);
            tBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
            tBadge.setVisibility(View.GONE); // Hidden by default, only shown if unsupported
            outBadge[0] = tBadge;

            LinearLayout badgeRow = new LinearLayout(this);
            badgeRow.setOrientation(LinearLayout.HORIZONTAL);
            badgeRow.setPadding(0, dp(4), 0, 0);
            badgeRow.addView(tBadge);
            col.addView(badgeRow);
        }

        card.addView(col, lp(0, W, 1f));

        // Right switch
        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(prefKey, true));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(prefKey, isChecked).apply();
                applyGaugeToggles();
            }
        });

        card.addView(sw, new LinearLayout.LayoutParams(W, W));
        return card;
    }

    // ---------------------------- Page 2: Native Car Apps ----------------------------
    private LinearLayout buildAppsPage() {
        final int M = ViewGroup.LayoutParams.MATCH_PARENT, W = ViewGroup.LayoutParams.WRAP_CONTENT;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xF7060608);
        root.setPadding(dp(20), dp(12), dp(20), dp(12));
        root.setClickable(true);

        // Header bar
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setOrientation(LinearLayout.HORIZONTAL);

        TextView back = button("<  GAUGES", 16, new Runnable() { public void run() { goToPage(1, true); } });
        back.setPadding(dp(16), dp(8), dp(16), dp(8));

        TextView title = label(24, 0xFFFFFFFF);
        title.setText("NATIVE CAR APPS");
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        title.setPadding(dp(16), 0, 0, 0);

        TextView home = button("HOME  X", 16, new Runnable() { public void run() { goToPage(0, true); } });
        home.setPadding(dp(16), dp(8), dp(16), dp(8));

        head.addView(back, new LinearLayout.LayoutParams(W, W));
        head.addView(title, lp(0, W, 1f));
        head.addView(home, new LinearLayout.LayoutParams(W, W));
        root.addView(head, new LinearLayout.LayoutParams(M, W));

        TextView hint = label(12, 0x8CFFFFFF);
        hint.setText("Direct links to your car head-unit's native applications and features");
        hint.setPadding(dp(4), dp(4), 0, dp(14));
        root.addView(hint);

        // Native app items
        String[] labels = {
                "BLUETOOTH / PHONE", "CAR SETTINGS",
                "CAR RADIO (FM/AM)", "MUSIC PLAYER",
                "VIDEO PLAYER", "NAVIGATION / MAPS",
                "EQUALIZER / DSP", "ALL INSTALLED APPS"
        };
        Runnable[] actions = {
                new Runnable() { public void run() { openCarBluetooth(); } },
                new Runnable() { public void run() {
                    if (!launchFirst(PKG_SETTINGS)) {
                        try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                        catch (Throwable t) { toast("Settings app not accessible"); }
                    }
                } },
                new Runnable() { public void run() { if (!launchFirst(PKG_RADIO)) toast("Car radio app not found"); } },
                new Runnable() { public void run() {
                    if (!launchFirst(PKG_MUSIC)) {
                        try { startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)); }
                        catch (Throwable e) { toast("No music app found"); }
                    }
                } },
                new Runnable() { public void run() { if (!launchFirst(PKG_VIDEO)) toast("Video player not found"); } },
                new Runnable() { public void run() { if (!launchFirst(PKG_MAPS)) toast("No maps app installed"); } },
                new Runnable() { public void run() {
                    if (!launchFirst(PKG_EQ)) {
                        try { startActivity(new Intent(MainActivity.this, EqualizerActivity.class)); }
                        catch (Throwable ignore) { }
                    }
                } },
                new Runnable() { public void run() {
                    try { startActivity(new Intent(MainActivity.this, AppDrawerActivity.class)); }
                    catch (Throwable ignore) { }
                } }
        };

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);

        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 4; c++) {
                int idx = r * 4 + c;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, M, 1f);
                lp.setMargins(dp(6), dp(6), dp(6), dp(6));
                row.addView(button(labels[idx], 18, actions[idx]), lp);
            }
            grid.addView(row, lp(M, 0, 1f));
        }

        root.addView(grid, lp(M, 0, 1f));
        return root;
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

    private GradientDrawable badgeShape(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(4));
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

    // ---------------------------- Build UI ----------------------------
    private void buildUi() {
        final int M = ViewGroup.LayoutParams.MATCH_PARENT, W = ViewGroup.LayoutParams.WRAP_CONTENT;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(8), dp(14), dp(8));

        // Top section: clock + timer | speedometer | gauges
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
        tmBox = tm;
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(W, W);
        tlp.topMargin = dp(14);
        left.addView(tm, tlp);
        top.addView(left, lp(0, M, 1f));

        speedo = new SpeedometerView(this);
        top.addView(speedo, lp(0, M, 1.4f));

        // Gauges: RPM, Temp, Batt, Load, ATF, Fuel, DTC
        obdPanel = new LinearLayout(this);
        obdPanel.setOrientation(LinearLayout.VERTICAL);
        obdPanel.setGravity(Gravity.CENTER_VERTICAL);
        gRpm  = new MiniGaugeView(this, "RPM", "rpm", 0, 8000, 5000, 6000, Float.NaN, Float.NaN, 0);
        gTemp = new MiniGaugeView(this, "COOLANT", "\u00B0C", 40, 130, 100, 108, Float.NaN, Float.NaN, 0);
        gBatt = new MiniGaugeView(this, "BATTERY", "V", 10, 16, Float.NaN, 15.2f, 12.0f, 11.8f, 1);
        gLoad = new MiniGaugeView(this, "LOAD", "%", 0, 100, 85, 95, Float.NaN, Float.NaN, 0);
        gAtf  = new MiniGaugeView(this, "ATF TEMP", "\u00B0C", 20, 150, 110, 130, Float.NaN, Float.NaN, 0);

        row1 = row(gRpm, gTemp);
        row2 = row(gBatt, gLoad);

        // Third row: ATF temp gauge + fuel gauge + DTC warning
        fuelGauge  = new FuelGaugeView(this);
        dtcWarning = new DtcWarningView(this);
        row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.addView(gAtf,        lp(0, M, 1f));
        row3.addView(fuelGauge,   lp(0, M, 1f));
        row3.addView(dtcWarning,  lp(0, M, 1f));

        obdPanel.addView(row1, lp(M, 0, 1f));
        obdPanel.addView(row2, lp(M, 0, 1f));
        obdPanel.addView(row3, lp(M, 0, 1f));

        top.addView(obdPanel, lp(0, M, 1.3f));

        // Bluetooth music bar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        nowPlaying = label(18, 0xCCFFFFFF);
        nowPlaying.setSingleLine(true);
        nowPlaying.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowPlaying.setText("Nothing playing");
        nowPlaying.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (needsAccess) {
                    try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
                    catch (Throwable ignore) { }
                }
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

        // Faint swipe hint on the right edge: tap or swipe left for gauge settings
        FrameLayout edge = new FrameLayout(this);
        View pill = new View(this);
        GradientDrawable pg = new GradientDrawable();
        pg.setColor(0x59FFFFFF);
        pg.setCornerRadius(dp(2));
        pill.setBackground(pg);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(4), dp(80), Gravity.END | Gravity.CENTER_VERTICAL);
        plp.rightMargin = dp(4);
        edge.addView(pill, plp);
        edge.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { goToPage(1, true); } });
        wrap.addView(edge, new FrameLayout.LayoutParams(dp(28), dp(160), Gravity.END | Gravity.CENTER_VERTICAL));

        // Page 1: Gauge Toggles & In-Launcher Bluetooth
        pageGauges = buildGaugesPage();
        pageGauges.setVisibility(View.GONE);
        wrap.addView(pageGauges, new FrameLayout.LayoutParams(M, M));

        // Page 2: Native Car Apps
        pageApps = buildAppsPage();
        pageApps.setVisibility(View.GONE);
        wrap.addView(pageApps, new FrameLayout.LayoutParams(M, M));

        setContentView(wrap);
    }
}
