package com.honda.launcher;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * Foreground service that keeps DashPilot's process alive while another app is on screen, so
 * the OBD-II connection, GPS speed and fuel tracking in TelemetryHub keep running. Without it
 * the 1 GB head unit kills the launcher in the background, e.g. while Maps is navigating.
 */
public class TelemetryService extends Service {
    private static final int NOTIFICATION_ID = 7;

    static void start(Context context) {
        try {
            context.startService(new Intent(context, TelemetryService.class));
        } catch (Throwable ignore) { }
    }

    @Override public void onCreate() {
        super.onCreate();
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Throwable ignore) { }   // still works as a plain background service on Android 7
        TelemetryHub.get(this).start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        TelemetryHub.get(this).start();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // The adaptive launcher icon only exists on Android 8+, so use the vector foreground
        return new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("DashPilot")
                .setContentText("Reading speed and OBD-II in the background")
                .setPriority(Notification.PRIORITY_MIN)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }
}
