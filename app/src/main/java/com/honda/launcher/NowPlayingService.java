package com.honda.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.service.notification.NotificationListenerService;

/**
 * Long-running background notification listener.
 * Also monitors screen wake & car power events to guarantee Bluetooth auto-turn-on
 * even when another app (Maps, Radio, etc.) was active before screen turn-off.
 */
public class NowPlayingService extends NotificationListenerService {

    private BroadcastReceiver screenReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String act = intent.getAction();
                if (ReverseCameraMusicManager.isReverseIntent(intent)) {
                    boolean isRev = ReverseCameraMusicManager.isReverseOn(intent);
                    ReverseCameraMusicManager.onReverseStateChanged(context, isRev);
                    return;
                }
                if (Intent.ACTION_SCREEN_ON.equals(act) ||
                    Intent.ACTION_USER_PRESENT.equals(act) ||
                    Intent.ACTION_POWER_CONNECTED.equals(act) ||
                    "autochips.intent.action.ACC_ON".equals(act) ||
                    "com.syu.action.ACC_ON".equals(act) ||
                    "com.allwinner.action.SCREEN_ON".equals(act) ||
                    "com.microntek.screen.on".equals(act)) {
                    BluetoothAutoEnabler.enableBluetoothIfNeeded(context);
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction("autochips.intent.action.ACC_ON");
        filter.addAction("com.syu.action.ACC_ON");
        filter.addAction("com.allwinner.action.SCREEN_ON");
        filter.addAction("com.microntek.screen.on");
        filter.addAction("android.intent.action.REVERSE");
        filter.addAction("com.allwinner.action.BACK_CAR_START");
        filter.addAction("com.allwinner.action.BACK_CAR_STOP");
        filter.addAction("com.syu.action.REVERSE_ON");
        filter.addAction("com.syu.action.REVERSE_OFF");
        filter.addAction("autochips.intent.action.REVERSE_ON");
        filter.addAction("autochips.intent.action.REVERSE_OFF");
        filter.addAction("com.microntek.reverse");
        filter.addAction("com.tw.action.REVERSE");
        filter.addAction("com.fyt.action.REVERSE_STATUS");
        filter.addAction("action.honda.reverse");
        filter.addAction("com.car.reverse.state");
        try {
            registerReceiver(screenReceiver, filter);
        } catch (Throwable ignore) { }

        // Also ensure Bluetooth is on if service was just launched
        BluetoothAutoEnabler.enableBluetoothIfNeeded(this);
    }

    @Override
    public void onDestroy() {
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Throwable ignore) { }
            screenReceiver = null;
        }
        super.onDestroy();
    }
}
