package com.honda.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Handles device boot and system power events (cold boot, quickboot/fastboot, ACC ON).
 * Automatically turns Bluetooth on when the head unit system boots up.
 */
public class BootAndPowerReceiver extends BroadcastReceiver {
    private static final String TAG = "BootAndPowerReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || context == null) return;
        String action = intent.getAction();
        Log.i(TAG, "Received system boot/power event: " + action);

        if (ReverseCameraMusicManager.isReverseIntent(intent)) {
            boolean isRev = ReverseCameraMusicManager.isReverseOn(intent);
            ReverseCameraMusicManager.onReverseStateChanged(context, isRev);
            return;
        }

        BluetoothAutoEnabler.enableBluetoothIfNeeded(context);
    }
}
