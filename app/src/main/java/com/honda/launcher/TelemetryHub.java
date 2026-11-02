package com.honda.launcher;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Keeps the OBD-II connection, GPS speed and fuel-used tracking running while another app
 * (Maps, YouTube) is on screen. TelemetryService keeps the process alive in the background;
 * MainActivity only reads from here.
 *
 * Everything pauses while the screen is off (ignition off): polling a sleeping ECU keeps the
 * car's CAN bus awake and drains the battery.
 */
final class TelemetryHub implements LocationListener {
    static final int GPS_OK = 0;
    static final int GPS_SEARCHING = 1;
    static final int GPS_DISABLED = 2;
    static final int GPS_NO_PERMISSION = 3;

    /** GPS speed older than this is treated as lost (tunnel, parking garage). */
    static final long GPS_STALE_MS = 5000;
    private static final long LOOP_MS = 1000;

    /** Honda City: 40 L tank at ~7.5 L/100 km uses ~0.1875 % of the tank per km. */
    static final float FUEL_PCT_PER_KM = 0.1875f;
    /** Fuel used is saved in steps of this size, not every second, to spare the flash storage. */
    private static final float FUEL_SAVE_STEP_PCT = 0.05f;

    private static TelemetryHub sInstance;

    static synchronized TelemetryHub get(Context context) {
        if (sInstance == null) sInstance = new TelemetryHub(context);
        return sInstance;
    }

    final ObdManager obd = new ObdManager();

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final GpsSpeedFilter gpsFilter = new GpsSpeedFilter();
    private Location lastLocation;
    private long lastLocationSavedAt;
    private volatile float gpsSpeed;
    private volatile long lastGpsAt;

    private boolean started;
    private boolean screenOn = true;
    private long lastLoopAt;
    private int loops;
    private float pendingFuelPct;

    private TelemetryHub(Context context) {
        Context a = context.getApplicationContext();
        app = (a != null) ? a : context;
        prefs = app.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    /** Starts telemetry, or makes sure it is still running. Safe to call repeatedly. */
    void start() {
        if (!started) {
            started = true;
            String savedMac = prefs.getString("obd_mac", null);
            if (savedMac != null && !savedMac.isEmpty()) obd.manualDeviceAddress = savedMac;
            obd.autoConnect = prefs.getBoolean("auto_obd_connect", true);

            try {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                screenOn = (pm == null) || pm.isInteractive();
            } catch (Throwable ignore) { }

            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            try { app.registerReceiver(screenReceiver, f); } catch (Throwable ignore) { }
            h.post(loop);
        }
        if (screenOn) {
            obd.start();   // no-op while its worker thread is alive
            startGps();
        }
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                screenOn = false;
                obd.stop();
                stopGps();
                saveFuel();
            } else {
                screenOn = true;
                obd.start();
                startGps();
            }
        }
    };

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            float s = currentSpeed();
            if (lastLoopAt > 0 && s > 1.0f) {
                float dtSec = (now - lastLoopAt) / 1000f;
                if (dtSec > 0 && dtSec < 5f) trackFuelConsumption((s / 3600f) * dtSec);
            }
            lastLoopAt = now;

            if (loops % 2 == 0) {
                try { com.honda.launcher.audio.AutoEqManager.get(app).setVehicleSpeed(s); } catch (Throwable ignore) { }
            }
            loops++;
            h.postDelayed(this, LOOP_MS);
        }
    };

    // ---------------------------- Speed ----------------------------

    /** Live speed in km/h: OBD wheel speed if the scanner gives one, else fresh GPS, else 0. */
    float currentSpeed() {
        return pickSpeed(obd.connected, obd.speed, isGpsFresh(), gpsSpeed);
    }

    boolean isObdSpeed() {
        return obd.connected && obd.speed >= 0;
    }

    static float pickSpeed(boolean obdConnected, int obdSpeed, boolean gpsFresh, float gpsSpeedKmh) {
        if (obdConnected && obdSpeed >= 0) return obdSpeed;
        if (gpsFresh) return gpsSpeedKmh;
        return 0f;
    }

    private boolean isGpsFresh() {
        return lastGpsAt > 0 && SystemClock.elapsedRealtime() - lastGpsAt < GPS_STALE_MS;
    }

    // ---------------------------- GPS ----------------------------

    boolean hasLocationPermission() {
        return app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    int getGpsStatus() {
        if (!hasLocationPermission()) return GPS_NO_PERMISSION;
        try {
            LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return GPS_DISABLED;
        } catch (Throwable ignore) { }
        return isGpsFresh() ? GPS_OK : GPS_SEARCHING;
    }

    void startGps() {
        if (!hasLocationPermission()) return;
        try {
            LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                // Strictly use GPS_PROVIDER; never register NETWORK_PROVIDER for speedometer
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200, 0, this, Looper.getMainLooper());
            }
        } catch (Exception ignore) { }
    }

    private void stopGps() {
        try {
            LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) lm.removeUpdates(this);
        } catch (Exception ignore) { }
    }

    @Override public void onLocationChanged(Location l) {
        if (l == null) return;
        // Strictly ignore non-GPS providers to eliminate cell-tower / wifi coordinate jumping
        if (l.getProvider() != null && !LocationManager.GPS_PROVIDER.equals(l.getProvider())) {
            return;
        }
        // Poor-accuracy fixes are kept on purpose: head-unit GPS often reports 30-60 m,
        // and dropping those fixes left the needle at 0 while driving.
        float distMeters = (lastLocation != null) ? l.distanceTo(lastLocation) : 0f;
        boolean updated = gpsFilter.onFix(l.hasSpeed(), l.getSpeed(), l.hasAccuracy(), l.getAccuracy(),
                l.getTime(), distMeters);
        lastLocation = l;
        saveLocationForNightMode(l);
        if (!updated) return;   // no usable speed in this fix: keep the last reading

        gpsSpeed = gpsFilter.getSpeedKmh();
        lastGpsAt = SystemClock.elapsedRealtime();
    }
    /** Night mode AUTO needs the local sunset; a rough position, saved every 10 min, is plenty. */
    private void saveLocationForNightMode(Location l) {
        long now = SystemClock.elapsedRealtime();
        if (lastLocationSavedAt != 0 && now - lastLocationSavedAt < 10 * 60_000L) return;
        lastLocationSavedAt = now;
        prefs.edit()
                .putFloat("last_lat", (float) l.getLatitude())
                .putFloat("last_lon", (float) l.getLongitude())
                .apply();
    }

    @Override public void onStatusChanged(String p, int s, Bundle e) { }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { }

    // ---------------------------- Fuel ----------------------------

    private void trackFuelConsumption(float kmDriven) {
        if (kmDriven <= 0) return;
        pendingFuelPct += kmDriven * FUEL_PCT_PER_KM;
        if (pendingFuelPct >= FUEL_SAVE_STEP_PCT) saveFuel();
    }

    private void saveFuel() {
        if (pendingFuelPct <= 0) return;
        float currentCal = prefs.getFloat("calibrated_fuel_level", 65.0f);
        float newFuel = Math.max(2.0f, currentCal - pendingFuelPct);
        pendingFuelPct = 0;
        prefs.edit().putFloat("calibrated_fuel_level", newFuel).apply();
    }
}
