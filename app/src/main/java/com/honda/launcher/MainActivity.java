package com.honda.launcher;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
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

import java.lang.reflect.Method;
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

    public static final int THEME_TYPER = 0;
    public static final int THEME_SAPPHIRE = 1;
    public static final int THEME_ONYX = 2;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFmt24 = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat timeFmt12 = new SimpleDateFormat("h:mm a", Locale.getDefault());
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEE, d MMM", Locale.getDefault());

    private TextView clock, date, nowPlaying, tmVal, tmBest, battHealthText;
    private View tmBox, tmAccent;
    private LinearLayout musicBar;
    private final TextView[] themeButtons = new TextView[3];
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
    private TextView obdStatusText, phoneStatusText, devHeaderBtn, btnPhoneAction;
    private LinearLayout pairedDevicesContainer;
    private boolean devicesListExpanded = false;
    private boolean wasObdConnected = false;
    private BluetoothDevice lastConnectedPhone = null;
    private String btTrack = null, btArtist = null;

    private volatile float gpsSpeed;
    private volatile long lastGps;
    private float curSpeed, downX, downY;
    private int ticks;

    // 0-100 timer
    private int tState;
    private long tStart, lastT;
    private float lastV, tBest = -1f, tRes;

    // Bluetooth music receiver for AVRCP metadata from phone & device connection events
    private final BroadcastReceiver btMusicReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String act = intent.getAction();
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(act) ||
                "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED".equals(act) ||
                "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED".equals(act) ||
                "com.gocsdk.action.DEVICE_CONNECTED".equals(act)) {
                try {
                    BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (dev != null && !isObdCandidate(dev.getName())) {
                        lastConnectedPhone = dev;
                        prefs.edit().putString("phone_mac", dev.getAddress()).apply();
                    }
                } catch (Throwable ignore) { }
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(act) ||
                       "com.gocsdk.action.DEVICE_DISCONNECTED".equals(act)) {
                try {
                    BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (dev != null) {
                        if (lastConnectedPhone != null && dev.getAddress().equalsIgnoreCase(lastConnectedPhone.getAddress())) {
                            lastConnectedPhone = null;
                        }
                        obd.onDeviceDisconnected(dev.getAddress());
                    }
                } catch (Throwable ignore) { }
            }

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
            updateObdStatusCard();
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
        obd.autoConnect = prefs.getBoolean("auto_obd_connect", true);

        buildUi();
        applyGaugeToggles();
        EqManager.get(this);   // re-applies saved equalizer levels

        // Startup needle sweep (0 to 200 km/h and back like a sports car cluster)
        if (speedo != null) {
            speedo.postDelayed(new Runnable() {
                @Override public void run() {
                    speedo.startNeedleSweep();
                }
            }, 300);
        }

        if (Build.VERSION.SDK_INT >= 23 && !hasLoc()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        startGps();
        obd.autoConnect = prefs.getBoolean("auto_obd_connect", true);
        obd.start();
        h.post(tick);

        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction("com.android.music.metachanged");
        f.addAction("com.android.music.playstatechanged");
        f.addAction("com.android.music.playbackcomplete");
        f.addAction("com.htc.music.metachanged");
        f.addAction("com.real.IMP.metachanged");
        f.addAction("com.sonyericsson.music.metachanged");
        f.addAction("com.samsung.sec.android.MusicPlayer.metachanged");
        f.addAction("android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED");
        f.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        f.addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED");
        f.addAction("android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED");
        f.addAction("android.bluetooth.headset-client.profile.action.CONNECTION_STATE_CHANGED");
        f.addAction("com.gocsdk.action.DEVICE_CONNECTED");
        f.addAction("com.gocsdk.action.DEVICE_DISCONNECTED");
        f.addAction("com.gocsdk.action.BT_A2DP_INFO");
        f.addAction("com.syu.bt.action.A2DP_METADATA");
        f.addAction("com.syu.bt.action.CONNECT_STATUS");
        f.addAction("com.car.bt.a2dp.metachanged");
        f.addAction("com.car.bt.action.STATE");
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
        boolean is24h = (prefs == null) || prefs.getBoolean("time_24h", true);
        clock.setText((is24h ? timeFmt24 : timeFmt12).format(d));
        date.setText(dateFmt.format(d));
    }

    private void updateObd() {
        updateObdStatusCard();

        if (!obd.connected) {
            wasObdConnected = false;
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
            if (battHealthText != null && battHealthText.getVisibility() == View.VISIBLE) {
                battHealthText.setText("HEALTH: -- • LIFE: --");
                battHealthText.setTextColor(0x8CFFFFFF);
            }
            updateGaugeBadges();
            return;
        }

        if (!wasObdConnected) {
            wasObdConnected = true;
            // Just connected to OBD2! Automatically hide available devices list under dropdown icon
            devicesListExpanded = false;
            updateDevicesDropdownUi();
        }

        boolean sRpm  = prefs.getBoolean("show_rpm", true);
        boolean sCool = prefs.getBoolean("show_coolant", true);
        boolean sBatt = prefs.getBoolean("show_battery", true);
        boolean sLoad = prefs.getBoolean("show_load", true);
        boolean sAtf  = prefs.getBoolean("show_atf", true);
        boolean sFuel = prefs.getBoolean("show_fuel", true);
        boolean sDtc  = prefs.getBoolean("show_dtc", true);

        boolean bad = false;

        boolean rpmBad = gRpm.setValue((!sRpm || obd.rpm < 0) ? Float.NaN : obd.rpm);
        if (sRpm) bad |= rpmBad;

        boolean coolBad = gTemp.setValue((!sCool || obd.coolant == -999) ? Float.NaN : obd.coolant);
        if (sCool) bad |= coolBad;

        boolean battBad = gBatt.setValue((!sBatt || obd.volts < 0) ? Float.NaN : obd.volts);
        if (sBatt) bad |= battBad;
        if (battHealthText != null && battHealthText.getVisibility() == View.VISIBLE) {
            battHealthText.setText(getBatteryHealthText(obd.volts));
            battHealthText.setTextColor(getBatteryHealthColor(obd.volts));
        }

        boolean loadBad = gLoad.setValue((!sLoad || obd.load < 0) ? Float.NaN : obd.load);
        if (sLoad) bad |= loadBad;

        boolean atfBad = gAtf.setValue((!sAtf || obd.atfTemp == -999) ? Float.NaN : obd.atfTemp);
        if (sAtf) bad |= atfBad;

        // Fuel gauge
        fuelGauge.setValue((!sFuel || obd.fuelLevel < 0) ? Float.NaN : obd.fuelLevel);

        // DTC warning
        if (sDtc) {
            if (obd.dtcScanned) {
                dtcWarning.setScanning(false);
                dtcWarning.setCodes(obd.dtcCodes);
                bad |= dtcWarning.hasCodes();
            } else {
                dtcWarning.setScanning(true);
            }
        } else {
            dtcWarning.setScanning(false);
            dtcWarning.setCodes(new ArrayList<String>());
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
            BluetoothDevice connectedPhone = getConnectedPhoneDevice();
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            boolean musicActive = (am != null && am.isMusicActive());
            int a2dpState = -1, a2dpSink = -1, hfClient = -1;
            try {
                BluetoothAdapter bad = BluetoothAdapter.getDefaultAdapter();
                if (bad != null && bad.isEnabled()) {
                    a2dpState = bad.getProfileConnectionState(BluetoothProfile.A2DP);
                    a2dpSink = bad.getProfileConnectionState(11); // A2DP_SINK
                    hfClient = bad.getProfileConnectionState(16); // HEADSET_CLIENT
                }
            } catch (Throwable ignore) { }

            boolean connected = (connectedPhone != null)
                    || (a2dpSink == BluetoothProfile.STATE_CONNECTED)
                    || (hfClient == BluetoothProfile.STATE_CONNECTED)
                    || (a2dpState == BluetoothProfile.STATE_CONNECTED)
                    || musicActive
                    || (btTrack != null && !btTrack.isEmpty());

            boolean connecting = (a2dpState == BluetoothProfile.STATE_CONNECTING)
                    || (a2dpSink == BluetoothProfile.STATE_CONNECTING);

            if (connected) {
                String phoneName = (connectedPhone != null && connectedPhone.getName() != null && !connectedPhone.getName().isEmpty())
                        ? connectedPhone.getName() : "Mobile Device";
                phoneStatusText.setText("● Mobile Phone: CONNECTED (" + phoneName + ")");
                phoneStatusText.setTextColor(GREEN);
                if (btnPhoneAction != null) {
                    btnPhoneAction.setText("PLAY BT MUSIC");
                }
            } else if (connecting) {
                phoneStatusText.setText("◐ Mobile Phone: CONNECTING...");
                phoneStatusText.setTextColor(AMBER);
                if (btnPhoneAction != null) {
                    btnPhoneAction.setText("CONNECTING...");
                }
            } else {
                phoneStatusText.setText("○ Mobile Phone: READY / STANDBY (Tap to connect)");
                phoneStatusText.setTextColor(0xFFCFD8DC);
                if (btnPhoneAction != null) {
                    btnPhoneAction.setText("CONNECT PHONE");
                }
            }
        }

        updateDevicesDropdownUi();
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

    private String getBatteryHealthText(float volts) {
        if (Float.isNaN(volts) || volts <= 0) {
            return "HEALTH: -- • LIFE: --";
        }
        int pct;
        String life;
        if (volts >= 13.5f) {
            // Engine running & alternator charging
            if (volts >= 13.8f && volts <= 14.6f) {
                pct = Math.min(99, Math.round(92f + (volts - 13.8f) * 7.5f));
                life = "Good (~2.5-3 yrs)";
            } else if (volts > 14.6f) {
                pct = 82;
                life = "High (~1-2 yrs)";
            } else {
                pct = Math.round(75f + (volts - 13.5f) * 50f);
                life = "Moderate (~1.5 yrs)";
            }
        } else {
            // Resting voltage / accessory mode (11.5V - 12.8V)
            float frac = (volts - 11.5f) / (12.8f - 11.5f);
            pct = Math.max(10, Math.min(100, Math.round(frac * 100f)));
            if (pct >= 85) life = "Good (~2.5-3 yrs)";
            else if (pct >= 65) life = "Fair (~1.5-2 yrs)";
            else if (pct >= 45) life = "Weak (~6-12 mos)";
            else life = "Replace Soon";
        }
        return String.format(Locale.US, "HEALTH: %d%% • %s", pct, life);
    }

    private int getBatteryHealthColor(float volts) {
        if (Float.isNaN(volts) || volts <= 0) return 0x8CFFFFFF;
        if (volts > 14.7f) return 0xFFFF1744; // Red (Overcharging / regulator fault)
        if (volts >= 13.5f && volts <= 14.7f) return 0xFF00E676; // Green
        if (volts >= 12.3f) return 0xFF00E676; // Green
        if (volts >= 11.9f) return 0xFFFFD600; // Yellow
        return 0xFFFF1744; // Red
    }

    private void applyGaugeToggles() {
        boolean sRpm = prefs.getBoolean("show_rpm", true);
        boolean sCool = prefs.getBoolean("show_coolant", true);
        boolean sBatt = prefs.getBoolean("show_battery", true);
        boolean sBattHealth = prefs.getBoolean("show_batt_health", true);
        boolean sLoad = prefs.getBoolean("show_load", true);
        boolean sAtf = prefs.getBoolean("show_atf", true);
        boolean sFuel = prefs.getBoolean("show_fuel", true);
        boolean sDtc = prefs.getBoolean("show_dtc", true);
        boolean sTm = prefs.getBoolean("timer_on", true);
        boolean sMusic = prefs.getBoolean("show_music_bar", true);
        boolean eco = prefs.getBoolean("eco_mode", false);

        if (speedo != null) speedo.setEcoMode(eco);
        if (gRpm != null) { gRpm.setEcoMode(eco); gRpm.setVisibility(sRpm ? View.VISIBLE : View.GONE); }
        if (gTemp != null) { gTemp.setEcoMode(eco); gTemp.setVisibility(sCool ? View.VISIBLE : View.GONE); }
        if (row1 != null) row1.setVisibility((sRpm || sCool) ? View.VISIBLE : View.GONE);

        if (gBatt != null) { gBatt.setEcoMode(eco); gBatt.setVisibility(sBatt ? View.VISIBLE : View.GONE); }
        if (battHealthText != null) battHealthText.setVisibility((sBatt && sBattHealth) ? View.VISIBLE : View.GONE);
        if (gLoad != null) { gLoad.setEcoMode(eco); gLoad.setVisibility(sLoad ? View.VISIBLE : View.GONE); }
        if (row2 != null) row2.setVisibility((sBatt || sLoad) ? View.VISIBLE : View.GONE);

        if (gAtf != null) { gAtf.setEcoMode(eco); gAtf.setVisibility(sAtf ? View.VISIBLE : View.GONE); }
        if (fuelGauge != null) fuelGauge.setVisibility(sFuel ? View.VISIBLE : View.GONE);
        if (dtcWarning != null) dtcWarning.setVisibility(sDtc ? View.VISIBLE : View.GONE);
        if (row3 != null) row3.setVisibility((sAtf || sFuel || sDtc) ? View.VISIBLE : View.GONE);

        if (tmBox != null) tmBox.setVisibility(sTm ? View.VISIBLE : View.GONE);
        if (musicBar != null) musicBar.setVisibility(sMusic ? View.VISIBLE : View.GONE);

        int curTheme = prefs.getInt("ui_theme", THEME_SAPPHIRE);
        applyTheme(curTheme);

        updateClock();

        // Immediately update OBD status and clear danger if disabled!
        updateObd();
    }

    private void applyTheme(int theme) {
        prefs.edit().putInt("ui_theme", theme).apply();
        if (speedo != null) speedo.setTheme(theme);
        if (gRpm != null) gRpm.setTheme(theme);
        if (gTemp != null) gTemp.setTheme(theme);
        if (gBatt != null) gBatt.setTheme(theme);
        if (gLoad != null) gLoad.setTheme(theme);
        if (gAtf != null) gAtf.setTheme(theme);
        if (aurora != null) aurora.setTheme(theme);
        if (tmAccent != null) {
            int accentCol = (theme == THEME_SAPPHIRE) ? 0xFF00E5FF : (theme == THEME_ONYX) ? 0xFFFFB300 : RED;
            tmAccent.setBackgroundColor(accentCol);
        }
        updateThemeButtonsUi(theme);
    }

    private void updateThemeButtonsUi(int activeTheme) {
        if (themeButtons[0] == null) return;
        final int[] themes = {THEME_TYPER, THEME_SAPPHIRE, THEME_ONYX};
        for (int i = 0; i < 3; i++) {
            TextView btn = themeButtons[i];
            if (btn == null) continue;
            boolean active = (themes[i] == activeTheme);
            if (active) {
                int activeBg = (activeTheme == THEME_SAPPHIRE) ? 0x4400E5FF : (activeTheme == THEME_ONYX) ? 0x44FFA000 : 0x44E4002B;
                int activeBorder = (activeTheme == THEME_SAPPHIRE) ? 0xFF00E5FF : (activeTheme == THEME_ONYX) ? 0xFFFFA000 : 0xFFFF1744;
                GradientDrawable g = new GradientDrawable();
                g.setColor(activeBg);
                g.setCornerRadius(dp(6));
                g.setStroke(dp(2), activeBorder);
                btn.setBackground(g);
                btn.setTextColor(0xFFFFFFFF);
            } else {
                btn.setBackground(shape(0x22FFFFFF));
                btn.setTextColor(0x8CFFFFFF);
            }
        }
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
        SavedMusicManager sm = SavedMusicManager.get(this);
        boolean autoSave = prefs.getBoolean("auto_save_music", true);

        // 0. If local saved music is playing from car memory
        if (sm.isPlaying()) {
            SavedMusicManager.Track cur = sm.getCurrentlyPlaying();
            String title = (cur != null) ? cur.getDisplayName() : "Car Memory Music";
            nowPlaying.setText("▶ " + title);
            return;
        }

        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        boolean isMusicActive = (am != null && am.isMusicActive());

        // 1. Try local MediaSession (Spotify, YouTube, media player running on unit)
        String activeTitle = null, activeArtist = null;
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
                        activeTitle = t.trim();
                        activeArtist = (a != null) ? a.trim() : "";
                    }
                }
            }
        } catch (SecurityException e) {
            needsAccess = true;
            controller = null;
        } catch (Throwable ignore) { }

        // 2. If Bluetooth track info was received from phone
        if (activeTitle == null && btTrack != null && !btTrack.isEmpty()) {
            activeTitle = btTrack.trim();
            activeArtist = (btArtist != null) ? btArtist.trim() : "";
        }

        if (activeTitle != null) {
            String display = (activeArtist != null && !activeArtist.isEmpty())
                    ? activeTitle + " - " + activeArtist : activeTitle;

            // Auto-save logic
            if (autoSave) {
                sm.onTrackPlaying(activeTitle, activeArtist, true);
                if (sm.isRecording()) {
                    nowPlaying.setText("● SAVING • " + display);
                    return;
                }
            }
            nowPlaying.setText(display);
            return;
        }

        // 3. Audio actively playing through car speakers (Chrome, Bluetooth stream, etc.)
        if (isMusicActive) {
            if (autoSave) {
                sm.onTrackPlaying("Bluetooth Audio Track", "", true);
                if (sm.isRecording()) {
                    nowPlaying.setText("● SAVING • Playing Audio (Bluetooth / Media)");
                    return;
                }
            }
            nowPlaying.setText("Playing Audio (Bluetooth / Media)");
            return;
        }

        // Not playing: stop any ongoing recording
        sm.stopRecording();

        // 4. Truly idle
        if (needsAccess) {
            nowPlaying.setText("Tap to allow notification access");
        } else {
            nowPlaying.setText("Nothing playing • Tap for Saved Music");
        }
    }

    private void transport(int what) {
        SavedMusicManager sm = SavedMusicManager.get(this);
        if (sm.isPlaying()) {
            if (what == 0) sm.togglePlayPause();
            else if (what > 0) sm.playNext();
            else sm.playPrevious();
            updateNowPlaying();
            return;
        }

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
                if (what == 0) {
                    am.setStreamMute(AudioManager.STREAM_MUSIC, false);
                    am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                }
                long now = SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
            }
        } catch (Throwable ignore) { }

        try {
            Intent mb = new Intent(Intent.ACTION_MEDIA_BUTTON);
            mb.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            sendOrderedBroadcast(mb, null);
            mb.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_UP, keyCode));
            sendOrderedBroadcast(mb, null);
        } catch (Throwable ignore) { }

        try {
            String act = (what == 0) ? "PLAY_PAUSE" : (what > 0) ? "NEXT" : "PREV";
            sendBroadcast(new Intent("com.syu.bt.action." + act));
            sendBroadcast(new Intent("com.gocsdk.action.MUSIC_" + act));
            sendBroadcast(new Intent("com.allwinner.bluetooth.action." + (what == 0 ? "PLAY" : act)));
            sendBroadcast(new Intent("com.car.bt.action.MUSIC_" + (what == 0 ? "PLAY" : act)));
            sendBroadcast(new Intent("com.ts.bt.action." + act));
        } catch (Throwable ignore) { }

        if (what == 0) {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null && !am.isMusicActive() && btTrack == null) {
                openCarBluetoothMusic();
            }
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

    private boolean openCarBluetooth() {
        // 1. Try known Chinese car head unit Bluetooth packages
        for (String p : PKG_BT) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(p);
                if (i != null) { startActivity(i); return true; }
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
                        if (i != null) { startActivity(i); return true; }
                    }
                }
            }
        } catch (Throwable ignore) {}

        // 3. Fallback: Android Bluetooth settings
        try {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            return true;
        } catch (Throwable ignore) {}

        // 4. Fallback: Android System settings
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
            return true;
        } catch (Throwable ignore) {}

        toast("Bluetooth is managed directly in DashPilot below.");
        return false;
    }

    private boolean openCarBluetoothMusic() {
        // 1. Search installed activities for dedicated Bluetooth Music / A2DP audio player
        try {
            PackageManager pm = getPackageManager();
            List<ResolveInfo> list = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN), 0);
            if (list != null) {
                for (ResolveInfo ri : list) {
                    String pkg = ri.activityInfo.packageName.toLowerCase(Locale.US);
                    String cls = ri.activityInfo.name.toLowerCase(Locale.US);
                    if ((pkg.contains("bt") || pkg.contains("blue") || cls.contains("bt") || cls.contains("blue"))
                            && (cls.contains("music") || cls.contains("a2dp") || cls.contains("audio") || cls.contains("media"))) {
                        Intent i = new Intent();
                        i.setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                        return true;
                    }
                }
            }
        } catch (Throwable ignore) { }

        // 2. Direct component fallbacks for Allwinner T3 / Chinese head units
        String[][] btMusicComponents = {
                {"com.syu.bt", "com.syu.bt.act.ActBtMusic"},
                {"com.allwinner.bluetooth", "com.allwinner.bluetooth.MusicActivity"},
                {"com.allwinner.bluetooth", "com.allwinner.bluetooth.A2dpActivity"},
                {"com.car.bt", "com.car.bt.MusicActivity"},
                {"com.gocsdk", "com.gocsdk.MusicActivity"},
                {"com.ts.bt", "com.ts.bt.BtMusicActivity"},
                {"com.forfan.bt", "com.forfan.bt.MusicActivity"},
                {"cn.manstep.phonemirrorbox", "cn.manstep.phonemirrorbox.MusicActivity"}
        };
        for (String[] cmp : btMusicComponents) {
            try {
                Intent i = new Intent();
                i.setClassName(cmp[0], cmp[1]);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Throwable ignore) { }
        }

        return openCarBluetooth();
    }

    private void playPhoneMusic() {
        toast("Routing Bluetooth Audio to Car Speakers...");

        // 1. Unmute music stream and set volume high
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setStreamMute(AudioManager.STREAM_MUSIC, false);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (cur < max * 0.7f) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, (int) (max * 0.85f), 0);
                }
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            }
        } catch (Throwable ignore) { }

        // 2. Dispatch Media Play key event to phone
        try {
            long now = SystemClock.uptimeMillis();
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
            }
        } catch (Throwable ignore) { }

        // 3. Broadcast ordered media button
        try {
            Intent mb = new Intent(Intent.ACTION_MEDIA_BUTTON);
            mb.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
            sendOrderedBroadcast(mb, null);
            mb.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY));
            sendOrderedBroadcast(mb, null);
        } catch (Throwable ignore) { }

        // 4. Send vendor Chinese head unit AVRCP & Audio Source switch commands
        String[] playIntents = {
                "com.syu.bt.action.PLAY_PAUSE",
                "com.syu.bt.action.MUSIC_PLAY",
                "com.syu.ms.action.AUDIO_SOURCE",
                "com.gocsdk.action.MUSIC_PLAY",
                "com.allwinner.bluetooth.action.PLAY",
                "com.allwinner.action.SWITCH_AUDIO_SOURCE",
                "com.car.bt.action.MUSIC_PLAY",
                "com.ts.bt.action.PLAY",
                "com.forfan.action.BT_MUSIC"
        };
        for (String act : playIntents) {
            try {
                Intent i = new Intent(act);
                if (act.contains("AUDIO_SOURCE")) {
                    i.putExtra("source", "bluetooth");
                    i.putExtra("source_id", 2);
                }
                sendBroadcast(i);
            } catch (Throwable ignore) { }
        }

        // 5. Open Bluetooth Music Activity to physically switch MCU hardware audio relay
        openCarBluetoothMusic();
    }

    private boolean isDeviceConnected(BluetoothDevice device) {
        if (device == null) return false;
        try {
            Method m = device.getClass().getMethod("isConnected");
            m.setAccessible(true);
            Object res = m.invoke(device);
            if (res instanceof Boolean && (Boolean) res) return true;
        } catch (Throwable ignore) { }

        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad != null && ad.isEnabled()) {
                int a2dpSink = ad.getProfileConnectionState(11);
                int hfClient = ad.getProfileConnectionState(16);
                int a2dp = ad.getProfileConnectionState(BluetoothProfile.A2DP);
                int hfp = ad.getProfileConnectionState(BluetoothProfile.HEADSET);
                if (a2dpSink == 2 || hfClient == 2 || a2dp == 2 || hfp == 2) {
                    String activeObd = (obd.manualDeviceAddress != null) ? obd.manualDeviceAddress : "";
                    if (!device.getAddress().equalsIgnoreCase(activeObd) && !isObdCandidate(device.getName())) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignore) { }

        return false;
    }

    private BluetoothDevice getConnectedPhoneDevice() {
        BluetoothAdapter ad = null;
        try { ad = BluetoothAdapter.getDefaultAdapter(); } catch (Throwable ignore) { }
        if (ad == null || !ad.isEnabled()) return null;

        Set<BluetoothDevice> bonded = null;
        try { bonded = ad.getBondedDevices(); } catch (Throwable ignore) { }
        if (bonded == null || bonded.isEmpty()) return null;

        String activeObdMac = (obd.manualDeviceAddress != null) ? obd.manualDeviceAddress : "";

        // 1. Check if any non-OBD bonded device is connected via isConnected()
        for (BluetoothDevice d : bonded) {
            String mac = d.getAddress();
            String name = (d.getName() != null) ? d.getName() : "";
            if (!mac.equalsIgnoreCase(activeObdMac) && !isObdCandidate(name)) {
                if (isDeviceConnected(d)) {
                    return d;
                }
            }
        }

        // 2. Check last connected phone if still in bonded list
        if (lastConnectedPhone != null) {
            for (BluetoothDevice d : bonded) {
                if (d.getAddress().equalsIgnoreCase(lastConnectedPhone.getAddress())) {
                    return d;
                }
            }
        }

        // 3. Check profile connection states
        int a2dpSink = -1, a2dp = -1, hfClient = -1, hfp = -1;
        try { a2dpSink = ad.getProfileConnectionState(11); } catch (Throwable ignore) { }
        try { hfClient = ad.getProfileConnectionState(16); } catch (Throwable ignore) { }
        try { a2dp = ad.getProfileConnectionState(BluetoothProfile.A2DP); } catch (Throwable ignore) { }
        try { hfp = ad.getProfileConnectionState(BluetoothProfile.HEADSET); } catch (Throwable ignore) { }

        if (a2dpSink == 2 || hfClient == 2 || a2dp == 2 || hfp == 2) {
            String savedPhoneMac = prefs.getString("phone_mac", "");
            for (BluetoothDevice d : bonded) {
                if (!savedPhoneMac.isEmpty() && savedPhoneMac.equalsIgnoreCase(d.getAddress())) return d;
            }
            for (BluetoothDevice d : bonded) {
                if (!d.getAddress().equalsIgnoreCase(activeObdMac) && !isObdCandidate(d.getName())) {
                    return d;
                }
            }
        }

        return null;
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
            updateDevicesDropdownUi();
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
            updateDevicesDropdownUi();
            return;
        }

        Set<BluetoothDevice> bonded = null;
        try { bonded = ad.getBondedDevices(); } catch (Throwable ignore) { }

        if (bonded == null || bonded.isEmpty()) {
            TextView t = label(13, 0x8CFFFFFF);
            t.setText("No paired Bluetooth devices found.\nPair your OBD-II adapter or Mobile Phone in your car's Bluetooth menu first.");
            t.setPadding(dp(8), dp(6), dp(8), dp(6));
            pairedDevicesContainer.addView(t);
            updateDevicesDropdownUi();
            return;
        }

        final String activeMac = (obd.manualDeviceAddress != null) ? obd.manualDeviceAddress : "";
        final String savedPhoneMac = prefs.getString("phone_mac", "");

        for (final BluetoothDevice d : bonded) {
            final String name = (d.getName() != null && !d.getName().isEmpty()) ? d.getName() : "Unknown Device";
            final String mac = d.getAddress() != null ? d.getAddress() : "";
            final boolean isLikelyObd = isObdCandidate(name);
            final boolean isSelectedObd = !activeMac.isEmpty()
                    ? mac.equalsIgnoreCase(activeMac)
                    : (obd.connected && name.equalsIgnoreCase(obd.connectedDeviceName));
            final boolean isLikelyPhone = !isLikelyObd && !isSelectedObd;
            final boolean isSavedPhone = !mac.isEmpty() && mac.equalsIgnoreCase(savedPhoneMac);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(shape(isSelectedObd ? 0x3300E676 : 0x1AFFFFFF));
            card.setPadding(dp(12), dp(8), dp(12), dp(8));

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = dp(6);
            card.setLayoutParams(clp);

            // Left: Device icon, name and MAC
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);

            String icon = isLikelyObd || isSelectedObd ? "🚗 " : "📱 ";
            TextView tName = label(15, 0xFFFFFFFF);
            tName.setText(icon + name);
            tName.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));

            TextView tMac = label(12, isSelectedObd ? GREEN : 0x8CFFFFFF);
            String macSubtitle = mac;
            if (isSelectedObd) macSubtitle += "  •  ACTIVE OBD2 SENSOR";
            else if (isSavedPhone) macSubtitle += "  •  SAVED PHONE";
            tMac.setText(macSubtitle);

            info.addView(tName);
            info.addView(tMac);
            card.addView(info, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // Right: Actions
            if (isSelectedObd) {
                TextView activeBadge = label(12, GREEN);
                activeBadge.setText(" ACTIVE OBD SENSOR ✓ ");
                activeBadge.setTypeface(Typeface.DEFAULT_BOLD);
                activeBadge.setBackground(badgeShape(0x3300E676));
                activeBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
                card.addView(activeBadge);
            } else if (isLikelyPhone) {
                final boolean isConnected = isDeviceConnected(d);
                card.setClickable(true);
                card.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (isConnected) playPhoneMusic();
                        else connectPhoneAudio(d);
                    }
                });

                LinearLayout actRow = new LinearLayout(this);
                actRow.setOrientation(LinearLayout.HORIZONTAL);
                actRow.setGravity(Gravity.CENTER_VERTICAL);

                if (isConnected) {
                    TextView connBadge = label(12, GREEN);
                    connBadge.setText(" CONNECTED ✓ ");
                    connBadge.setTypeface(Typeface.DEFAULT_BOLD);
                    connBadge.setBackground(badgeShape(0x3300E676));
                    connBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
                    actRow.addView(connBadge);

                    TextView btnPlay = button("PLAY SOUND", 12, new Runnable() {
                        public void run() {
                            playPhoneMusic();
                        }
                    });
                    btnPlay.setPadding(dp(10), dp(6), dp(10), dp(6));
                    LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    plp.leftMargin = dp(6);
                    actRow.addView(btnPlay, plp);
                } else {
                    TextView btnPhone = button("CONNECT PHONE", 12, new Runnable() {
                        public void run() {
                            connectPhoneAudio(d);
                        }
                    });
                    btnPhone.setPadding(dp(10), dp(6), dp(10), dp(6));
                    actRow.addView(btnPhone);
                }

                TextView btnUse = button("SET OBD", 11, new Runnable() {
                    public void run() {
                        prefs.edit().putString("obd_mac", mac).apply();
                        obd.setManualDevice(mac);
                        toast("Set " + name + " as OBD sensor. Connecting...");
                        refreshPairedDevicesList();
                        updateObdStatusCard();
                    }
                });
                btnUse.setPadding(dp(8), dp(4), dp(8), dp(4));
                LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ulp.leftMargin = dp(6);
                actRow.addView(btnUse, ulp);

                card.addView(actRow);
            } else {
                // OBD candidate device
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

        updateDevicesDropdownUi();
    }

    private void updateDevicesDropdownUi() {
        if (devHeaderBtn == null || pairedDevicesContainer == null) return;

        BluetoothAdapter ad = null;
        try { ad = BluetoothAdapter.getDefaultAdapter(); } catch (Throwable ignore) { }
        int count = 0;
        if (ad != null && ad.isEnabled()) {
            try {
                Set<BluetoothDevice> bonded = ad.getBondedDevices();
                if (bonded != null) count = bonded.size();
            } catch (Throwable ignore) { }
        }

        String countStr = count > 0 ? " [" + count + " PAIRED]" : "";

        if (devicesListExpanded) {
            devHeaderBtn.setText("▼  AVAILABLE BLUETOOTH DEVICES" + countStr + "  (TAP TO COLLAPSE)");
            devHeaderBtn.setTextColor(0xFFFFFFFF);
            pairedDevicesContainer.setVisibility(View.VISIBLE);
        } else {
            if (obd.connected) {
                devHeaderBtn.setText("▶  AVAILABLE BLUETOOTH DEVICES" + countStr + "  (OBD CONNECTED • TAP TO EXPAND)");
                devHeaderBtn.setTextColor(0xFFCFD8DC);
            } else {
                devHeaderBtn.setText("▶  AVAILABLE BLUETOOTH DEVICES" + countStr + "  (TAP TO EXPAND)");
                devHeaderBtn.setTextColor(0xFFCFD8DC);
            }
            pairedDevicesContainer.setVisibility(View.GONE);
        }
    }

    private void connectPhoneAudio(final BluetoothDevice device) {
        if (device == null) {
            toast("No device selected");
            return;
        }
        final String name = (device.getName() != null && !device.getName().isEmpty())
                ? device.getName() : device.getAddress();
        toast("Connecting to " + name + " for Audio/Calls...");

        final BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad != null && ad.isEnabled()) {
            // 1. Connect A2DP (Media / Music streaming)
            try {
                ad.getProfileProxy(getApplicationContext(), new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        try {
                            Method m = proxy.getClass().getMethod("connect", BluetoothDevice.class);
                            m.setAccessible(true);
                            m.invoke(proxy, device);
                        } catch (Throwable ignore) { }
                        try { ad.closeProfileProxy(profile, proxy); } catch (Throwable ignore) { }
                    }
                    @Override public void onServiceDisconnected(int profile) { }
                }, BluetoothProfile.A2DP);
            } catch (Throwable ignore) { }

            // 2. Connect HEADSET (Phone calls)
            try {
                ad.getProfileProxy(getApplicationContext(), new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        try {
                            Method m = proxy.getClass().getMethod("connect", BluetoothDevice.class);
                            m.setAccessible(true);
                            m.invoke(proxy, device);
                        } catch (Throwable ignore) { }
                        try { ad.closeProfileProxy(profile, proxy); } catch (Throwable ignore) { }
                    }
                    @Override public void onServiceDisconnected(int profile) { }
                }, BluetoothProfile.HEADSET);
            } catch (Throwable ignore) { }
        }

        // 3. Broadcast Chinese head unit connection intents (Allwinner T3 / GOCSDK / Syu)
        String[] actions = {
                "com.gocsdk.action.CONNECT_DEVICE",
                "com.syu.bt.action.CONNECT",
                "net.easyconn.bt.connect",
                "com.car.bt.action.CONNECT",
                "android.bluetooth.device.action.CONNECT"
        };
        for (String act : actions) {
            try {
                Intent i = new Intent(act);
                i.putExtra("mac", device.getAddress());
                i.putExtra("address", device.getAddress());
                i.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
                sendBroadcast(i);
            } catch (Throwable ignore) { }
        }

        prefs.edit().putString("phone_mac", device.getAddress()).apply();
        h.postDelayed(new Runnable() {
            public void run() {
                updateObdStatusCard();
                refreshPairedDevicesList();
            }
        }, 1500);
    }

    private void connectFirstPairedPhone() {
        BluetoothAdapter ad = null;
        try { ad = BluetoothAdapter.getDefaultAdapter(); } catch (Throwable ignore) { }
        if (ad == null || !ad.isEnabled()) {
            toast("Bluetooth is OFF. Please enable Bluetooth.");
            return;
        }

        Set<BluetoothDevice> bonded = null;
        try { bonded = ad.getBondedDevices(); } catch (Throwable ignore) { }
        if (bonded == null || bonded.isEmpty()) {
            toast("No paired phone found. Opening Bluetooth pairing...");
            openCarBluetooth();
            return;
        }

        // 1. Try saved phone mac if available
        String savedPhoneMac = prefs.getString("phone_mac", null);
        if (savedPhoneMac != null && !savedPhoneMac.isEmpty()) {
            for (BluetoothDevice d : bonded) {
                if (savedPhoneMac.equalsIgnoreCase(d.getAddress())) {
                    connectPhoneAudio(d);
                    return;
                }
            }
        }

        // 2. Find first non-OBD device
        String activeObdMac = (obd.manualDeviceAddress != null) ? obd.manualDeviceAddress : "";
        for (BluetoothDevice d : bonded) {
            String mac = d.getAddress();
            String name = (d.getName() != null) ? d.getName() : "";
            if (!mac.equalsIgnoreCase(activeObdMac) && !isObdCandidate(name)) {
                connectPhoneAudio(d);
                return;
            }
        }

        // 3. Fallback: first device that is not the active OBD adapter
        for (BluetoothDevice d : bonded) {
            String mac = d.getAddress();
            if (!mac.equalsIgnoreCase(activeObdMac)) {
                connectPhoneAudio(d);
                return;
            }
        }

        toast("No paired phone found. Opening Bluetooth pairing...");
        openCarBluetooth();
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
        list.addView(gaugeCard("BATTERY HEALTH & LIFE", "Estimated battery health % & remaining lifespan below gauge", "show_batt_health", null));
        list.addView(gaugeCard("ENGINE LOAD", "Real-time engine capacity load (PID 0104)", "show_load", null));
        list.addView(gaugeCard("ATF TRANSMISSION TEMP", "Automatic / CVT transmission fluid temp", "show_atf", bAtf));
        badgeAtf = bAtf[0];
        list.addView(gaugeCard("FUEL TANK LEVEL", "Liquid animated tank & remaining percentage", "show_fuel", bFuel));
        badgeFuel = bFuel[0];
        list.addView(gaugeCard("DTC TROUBLE WARNING", "Check engine warning & diagnostic codes (Mode 03)", "show_dtc", null));
        list.addView(gaugeCard("0–100 KM/H LAUNCH TIMER", "Automatic launch acceleration timer", "timer_on", null));

        TextView tModesHeader = label(16, 0xFFFFFFFF);
        tModesHeader.setText("DISPLAY & THEME MODES");
        tModesHeader.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        tModesHeader.setPadding(dp(2), dp(14), 0, dp(8));
        list.addView(tModesHeader);

        list.addView(buildThemeSelectorCard());
        list.addView(gaugeCard("SHOW MUSIC CONTROLS", "Display or hide the bottom music playback bar on home screen", "show_music_bar", null));
        list.addView(gaugeCard("24-HOUR CLOCK FORMAT", "Display time in 24-hour (15:00) vs 12-hour (3:00 PM) format", "time_24h", null));
        list.addView(gaugeCard("ECO / COMFORT MODE", "Dynamic color feedback: Green (safe/eco), Yellow (warning), Red (danger)", "eco_mode", null));
        list.addView(gaugeCard("AUTO-SAVE BLUETOOTH MUSIC", "Automatically record & save songs played from phone into car internal memory", "auto_save_music", null));

        sv.addView(list);
        root.addView(sv, lp(M, 0, 1f));
        return root;
    }

    private View buildThemeSelectorCard() {
        final int M = ViewGroup.LayoutParams.MATCH_PARENT, W = ViewGroup.LayoutParams.WRAP_CONTENT;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(shape(0x22FFFFFF));
        card.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(M, W);
        clp.bottomMargin = dp(12);
        card.setLayoutParams(clp);

        TextView title = label(16, 0xFFFFFFFF);
        title.setText("DASHBOARD DESIGN THEME");
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        card.addView(title);

        TextView sub = label(12, 0x8CFFFFFF);
        sub.setText("Select your active instrument cluster aesthetic");
        sub.setPadding(0, dp(2), 0, dp(10));
        card.addView(sub);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        final int currentTheme = prefs.getInt("ui_theme", THEME_SAPPHIRE);

        final String[] names = {"TYPE R RED", "ROYAL SAPPHIRE", "NOCTURNE ONYX"};
        final int[] themes = {THEME_TYPER, THEME_SAPPHIRE, THEME_ONYX};

        for (int i = 0; i < 3; i++) {
            final int t = themes[i];
            TextView btn = button(names[i], 12, new Runnable() {
                public void run() {
                    applyTheme(t);
                }
            });
            btn.setPadding(dp(8), dp(10), dp(8), dp(10));
            themeButtons[i] = btn;
            LinearLayout.LayoutParams blp = lp(0, W, 1f);
            if (i > 0) blp.leftMargin = dp(8);
            row.addView(btn, blp);
        }

        updateThemeButtonsUi(currentTheme);
        card.addView(row);
        return card;
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

        // Control Buttons: CONNECT OBD | CONNECT PHONE | PLAY BT MUSIC | CAR BT APP
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(8), 0, dp(8));

        TextView btnConnectObd = button("CONNECT OBD", 13, new Runnable() {
            public void run() {
                obd.reconnectNow();
                toast("Connecting to OBD-II...");
                updateObdStatusCard();
            }
        });
        btnConnectObd.setPadding(dp(6), dp(6), dp(6), dp(6));

        TextView btnConnectPhone = button("CONNECT PHONE", 13, new Runnable() {
            public void run() {
                connectFirstPairedPhone();
            }
        });
        btnConnectPhone.setPadding(dp(6), dp(6), dp(6), dp(6));

        TextView btnPlayMusic = button("PLAY BT MUSIC", 13, new Runnable() {
            public void run() {
                playPhoneMusic();
            }
        });
        btnPlayMusic.setPadding(dp(6), dp(6), dp(6), dp(6));

        TextView btnCarBt = button("CAR BT APP", 13, new Runnable() {
            public void run() {
                openCarBluetooth();
            }
        });
        btnCarBt.setPadding(dp(6), dp(6), dp(6), dp(6));

        LinearLayout.LayoutParams blp1 = new LinearLayout.LayoutParams(0, W, 1f);
        blp1.rightMargin = dp(6);
        btnRow.addView(btnConnectObd, blp1);

        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(0, W, 1f);
        blp2.rightMargin = dp(6);
        btnRow.addView(btnConnectPhone, blp2);

        LinearLayout.LayoutParams blp3 = new LinearLayout.LayoutParams(0, W, 1f);
        blp3.rightMargin = dp(6);
        btnRow.addView(btnPlayMusic, blp3);

        btnRow.addView(btnCarBt, lp(0, W, 1f));
        card.addView(btnRow);

        // Auto-connect OBD-II Toggle Row
        LinearLayout autoRow = new LinearLayout(this);
        autoRow.setOrientation(LinearLayout.HORIZONTAL);
        autoRow.setGravity(Gravity.CENTER_VERTICAL);
        autoRow.setBackground(shape(0x14FFFFFF));
        autoRow.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout autoInfo = new LinearLayout(this);
        autoInfo.setOrientation(LinearLayout.VERTICAL);

        TextView tAutoTitle = label(14, 0xFFFFFFFF);
        tAutoTitle.setText("AUTO-CONNECT OBD-II");
        tAutoTitle.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));

        final TextView tAutoSub = label(11, 0x8CFFFFFF);
        boolean autoEnabled = prefs.getBoolean("auto_obd_connect", true);
        tAutoSub.setText(autoEnabled ? "Automatically reconnects to OBD-II scanner in background" : "Manual mode: Tap [CONNECT OBD] when scanner is plugged in");

        autoInfo.addView(tAutoTitle);
        autoInfo.addView(tAutoSub);
        autoRow.addView(autoInfo, lp(0, W, 1f));

        Switch swAutoObd = new Switch(this);
        swAutoObd.setChecked(autoEnabled);
        swAutoObd.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("auto_obd_connect", isChecked).apply();
                obd.autoConnect = isChecked;
                tAutoSub.setText(isChecked ? "Automatically reconnects to OBD-II scanner in background" : "Manual mode: Tap [CONNECT OBD] when scanner is plugged in");
                if (isChecked) {
                    obd.reconnectNow();
                    toast("Auto-connect OBD enabled");
                } else {
                    toast("Auto-connect OBD disabled (manual connect only)");
                    updateObdStatusCard();
                }
            }
        });
        autoRow.addView(swAutoObd, new LinearLayout.LayoutParams(W, W));

        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(M, W);
        arlp.topMargin = dp(2);
        arlp.bottomMargin = dp(8);
        card.addView(autoRow, arlp);

        // Phone Audio & Calls Section Card (Directly clickable!)
        LinearLayout phoneCard = new LinearLayout(this);
        phoneCard.setOrientation(LinearLayout.HORIZONTAL);
        phoneCard.setGravity(Gravity.CENTER_VERTICAL);
        phoneCard.setBackground(shape(0x1AFFFFFF));
        phoneCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        phoneCard.setClickable(true);
        phoneCard.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (getConnectedPhoneDevice() != null) {
                    playPhoneMusic();
                } else {
                    connectFirstPairedPhone();
                }
            }
        });

        LinearLayout phoneInfo = new LinearLayout(this);
        phoneInfo.setOrientation(LinearLayout.VERTICAL);

        phoneStatusText = label(14, 0xFFCFD8DC);
        phoneStatusText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        phoneInfo.addView(phoneStatusText);

        TextView phoneHint = label(12, 0x8CFFFFFF);
        phoneHint.setText("Audio Active • Tap [PLAY BT MUSIC] to stream sound through car speakers");
        phoneHint.setPadding(0, dp(2), 0, 0);
        phoneInfo.addView(phoneHint);

        phoneCard.addView(phoneInfo, lp(0, W, 1f));

        btnPhoneAction = button("PLAY BT MUSIC", 13, new Runnable() {
            public void run() {
                if (getConnectedPhoneDevice() != null) {
                    playPhoneMusic();
                } else {
                    connectFirstPairedPhone();
                }
            }
        });
        btnPhoneAction.setPadding(dp(14), dp(8), dp(14), dp(8));
        phoneCard.addView(btnPhoneAction);

        LinearLayout.LayoutParams pclp = new LinearLayout.LayoutParams(M, W);
        pclp.topMargin = dp(4);
        pclp.bottomMargin = dp(8);
        card.addView(phoneCard, pclp);

        // Car Memory Music section row in Bluetooth Hub
        LinearLayout musicMemoryRow = new LinearLayout(this);
        musicMemoryRow.setOrientation(LinearLayout.HORIZONTAL);
        musicMemoryRow.setGravity(Gravity.CENTER_VERTICAL);
        musicMemoryRow.setBackground(shape(0x14FFFFFF));
        musicMemoryRow.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout musicInfo = new LinearLayout(this);
        musicInfo.setOrientation(LinearLayout.VERTICAL);

        TextView tMusicTitle = label(14, 0xFFFFFFFF);
        tMusicTitle.setText("AUTO-SAVE BLUETOOTH MUSIC");
        tMusicTitle.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));

        final TextView tMusicSub = label(11, 0x8CFFFFFF);
        boolean autoSaveMusic = prefs.getBoolean("auto_save_music", true);
        tMusicSub.setText(autoSaveMusic ? "Auto-records songs played from phone into car internal memory" : "Auto-save disabled • Songs will not be stored in car storage");

        musicInfo.addView(tMusicTitle);
        musicInfo.addView(tMusicSub);
        musicMemoryRow.addView(musicInfo, lp(0, W, 1f));

        TextView btnOpenLibrary = button("CAR MEMORY", 12, new Runnable() {
            public void run() {
                SavedMusicManager.get(MainActivity.this).showLibraryDialog(MainActivity.this, new Runnable() {
                    public void run() { updateNowPlaying(); }
                });
            }
        });
        btnOpenLibrary.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(W, W);
        olp.rightMargin = dp(8);
        musicMemoryRow.addView(btnOpenLibrary, olp);

        Switch swMusicSave = new Switch(this);
        swMusicSave.setChecked(autoSaveMusic);
        swMusicSave.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("auto_save_music", isChecked).apply();
                tMusicSub.setText(isChecked ? "Auto-records songs played from phone into car internal memory" : "Auto-save disabled • Songs will not be stored in car storage");
                toast(isChecked ? "Auto-save music to car memory enabled" : "Auto-save music disabled");
                updateNowPlaying();
            }
        });
        musicMemoryRow.addView(swMusicSave, new LinearLayout.LayoutParams(W, W));

        LinearLayout.LayoutParams mrlp = new LinearLayout.LayoutParams(M, W);
        mrlp.bottomMargin = dp(10);
        card.addView(musicMemoryRow, mrlp);

        // Dropdown Header for Available Devices
        devHeaderBtn = button("", 13, new Runnable() {
            public void run() {
                devicesListExpanded = !devicesListExpanded;
                updateDevicesDropdownUi();
            }
        });
        devHeaderBtn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        devHeaderBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        card.addView(devHeaderBtn);

        pairedDevicesContainer = new LinearLayout(this);
        pairedDevicesContainer.setOrientation(LinearLayout.VERTICAL);
        pairedDevicesContainer.setPadding(0, dp(6), 0, dp(2));
        card.addView(pairedDevicesContainer);

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
                "BLUETOOTH PHONE", "BT MUSIC (A2DP)",
                "CAR SETTINGS", "CAR RADIO (FM/AM)",
                "LOCAL MUSIC", "NAVIGATION / MAPS",
                "EQUALIZER / DSP", "ALL INSTALLED APPS"
        };
        Runnable[] actions = {
                new Runnable() { public void run() { openCarBluetooth(); } },
                new Runnable() { public void run() { playPhoneMusic(); } },
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
        tmAccent = accent;
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

        LinearLayout battCol = new LinearLayout(this);
        battCol.setOrientation(LinearLayout.VERTICAL);
        battCol.setGravity(Gravity.CENTER_HORIZONTAL);
        battCol.addView(gBatt, lp(M, 0, 1f));

        battHealthText = label(10, 0xFF00E676);
        battHealthText.setGravity(Gravity.CENTER);
        battHealthText.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        battHealthText.setText("HEALTH: -- • LIFE: --");
        battHealthText.setPadding(0, 0, 0, dp(2));
        battCol.addView(battHealthText, new LinearLayout.LayoutParams(M, W));

        row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(battCol, lp(0, M, 1f));
        row2.addView(gLoad,   lp(0, M, 1f));

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
        musicBar = bar;
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
                } else {
                    SavedMusicManager.get(MainActivity.this).showLibraryDialog(MainActivity.this, new Runnable() {
                        public void run() { updateNowPlaying(); }
                    });
                }
            }
        });
        bar.addView(nowPlaying, lp(0, M, 1f));

        TextView btnSavedMusic = button("CAR MEMORY", 12, new Runnable() {
            public void run() {
                SavedMusicManager.get(MainActivity.this).showLibraryDialog(MainActivity.this, new Runnable() {
                    public void run() { updateNowPlaying(); }
                });
            }
        });
        btnSavedMusic.setPadding(dp(8), dp(4), dp(8), dp(4));
        bar.addView(btnSavedMusic, barLp());

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
