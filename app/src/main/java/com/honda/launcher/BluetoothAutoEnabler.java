package com.honda.launcher;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Automatically manages Bluetooth power state on vehicle head units.
 * Ensures Bluetooth turns on automatically when:
 * 1. The vehicle head unit boots up (cold start or quickboot / STR).
 * 2. The car screen turns on (driver turns ignition to ACC / ON or wakes display).
 * 3. The launcher is brought to foreground.
 *
 * Designed specifically for Android 7.1.1 (API 25) Allwinner T3 / Chinese car head units
 * where Bluetooth hardware defaults to OFF on boot or deep sleep wake.
 */
public class BluetoothAutoEnabler {
    private static final String TAG = "BluetoothAutoEnabler";
    public static final String PREF_AUTO_BLUETOOTH = "auto_turn_on_bluetooth";

    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    public static boolean isAutoTurnOnEnabled(Context context) {
        if (context == null) return true;
        SharedPreferences prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_AUTO_BLUETOOTH, true);
    }

    public static void setAutoTurnOnEnabled(Context context, boolean enabled) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        prefs.edit().putBoolean(PREF_AUTO_BLUETOOTH, enabled).apply();
    }

    /**
     * Checks if Bluetooth is turned off, and if so, turns it on automatically.
     * Retries after 1.5s and 3.5s in case the head unit hardware or UART link was powering up.
     */
    public static void enableBluetoothIfNeeded(final Context context) {
        if (context == null) return;
        if (!isAutoTurnOnEnabled(context)) {
            Log.d(TAG, "Auto-turn on Bluetooth is disabled by user settings");
            return;
        }

        BluetoothAdapter adapter = null;
        try {
            adapter = BluetoothAdapter.getDefaultAdapter();
        } catch (Throwable t) {
            Log.w(TAG, "Failed to get default BluetoothAdapter", t);
        }

        if (adapter == null) {
            Log.d(TAG, "No Bluetooth adapter hardware detected on this device");
            return;
        }

        try {
            int state = adapter.getState();
            if (!adapter.isEnabled() || state == BluetoothAdapter.STATE_OFF) {
                Log.i(TAG, "Screen / System wake detected: Auto-enabling Bluetooth (current state: " + state + ")...");
                adapter.enable();

                // Broadcast head-unit specific power intents for vendor MCU / BT modules
                broadcastCarVendorBtWakeIntents(context);

                // Staggered retries: On car head units (Allwinner T3), the BT chip firmware
                // or UART link may take 1-2 seconds after screen wake before accepting enable commands.
                scheduleRetry(context.getApplicationContext(), 1500, 1);
                scheduleRetry(context.getApplicationContext(), 3500, 2);
            } else {
                Log.d(TAG, "Bluetooth is already enabled/turning on (state: " + state + ")");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error checking/enabling Bluetooth", t);
        }
    }

    private static void scheduleRetry(final Context appContext, long delayMs, final int attempt) {
        sHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (appContext == null || !isAutoTurnOnEnabled(appContext)) return;
                try {
                    BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                    if (ad != null && (!ad.isEnabled() || ad.getState() == BluetoothAdapter.STATE_OFF)) {
                        Log.i(TAG, "Bluetooth still off (retry " + attempt + "), re-asserting enable...");
                        ad.enable();
                        broadcastCarVendorBtWakeIntents(appContext);
                    }
                } catch (Throwable ignore) { }
            }
        }, delayMs);
    }

    /**
     * Sends vendor-specific broadcast intents recognized by Allwinner T3, MTK,
     * Sofia, Syu, and GOC head unit Bluetooth daemons.
     */
    public static void broadcastCarVendorBtWakeIntents(Context context) {
        if (context == null) return;
        String[] actions = {
                "com.gocsdk.action.OPEN_BT",
                "com.syu.bt.action.OPEN_BT",
                "com.car.bt.action.POWER_ON",
                "com.microntek.bt.poweron",
                "com.allwinner.action.OPEN_BT"
        };
        for (String action : actions) {
            try {
                context.sendBroadcast(new Intent(action));
            } catch (Throwable ignore) { }
        }
    }
}
