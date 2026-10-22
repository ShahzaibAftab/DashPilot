package com.honda.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

/**
 * Manages audio focus, volume, and playback continuity during vehicle reverse camera operations.
 * Prevents music from pausing or muting when shifting into reverse gear ('R').
 */
public class ReverseCameraMusicManager {
    private static final String TAG = "ReverseCamMusic";
    public static final String PREF_KEEP_MUSIC_IN_REVERSE = "keep_music_in_reverse";

    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static volatile boolean sReverseActive = false;

    public static boolean isKeepMusicInReverseEnabled(Context context) {
        if (context == null) return true;
        SharedPreferences prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_KEEP_MUSIC_IN_REVERSE, true);
    }

    public static void setKeepMusicInReverseEnabled(Context context, boolean enabled) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        prefs.edit().putBoolean(PREF_KEEP_MUSIC_IN_REVERSE, enabled).apply();
        applySystemReverseMuteConfig(context, !enabled);
    }

    /**
     * Applies system and vendor head-unit settings to prevent muting during reverse camera.
     */
    public static void applySystemReverseMuteConfig(Context context, boolean mute) {
        if (context == null) return;
        int val = mute ? 1 : 0;
        String[] keys = {
                "reverse_mute",
                "canbus_reverse_mute",
                "backcar_mute",
                "sys_reverse_mute",
                "car_reverse_music_mute",
                "mcu_reverse_mute",
                "syu_reverse_mute",
                "allwinner_reverse_mute"
        };
        for (String k : keys) {
            try {
                Settings.System.putInt(context.getContentResolver(), k, val);
            } catch (Throwable ignore) { }
            try {
                Settings.Global.putInt(context.getContentResolver(), k, val);
            } catch (Throwable ignore) { }
        }
    }

    /**
     * Inspects whether an intent action represents a car reverse gear or backcar event.
     */
    public static boolean isReverseAction(String act) {
        if (act == null) return false;
        return act.equals("android.intent.action.REVERSE") ||
               act.equals("com.allwinner.action.BACK_CAR_START") ||
               act.equals("com.allwinner.action.BACK_CAR_STOP") ||
               act.equals("com.syu.action.REVERSE_ON") ||
               act.equals("com.syu.action.REVERSE_OFF") ||
               act.equals("autochips.intent.action.REVERSE_ON") ||
               act.equals("autochips.intent.action.REVERSE_OFF") ||
               act.equals("com.microntek.reverse") ||
               act.equals("com.tw.action.REVERSE") ||
               act.equals("com.fyt.action.REVERSE_STATUS") ||
               act.equals("action.honda.reverse") ||
               act.equals("com.car.reverse.state");
    }

    public static boolean isReverseIntent(Intent intent) {
        if (intent == null) return false;
        return isReverseAction(intent.getAction());
    }

    /**
     * Checks if the reverse action indicates reverse is turning ON vs OFF.
     */
    public static boolean isReverseActionOn(String act) {
        if (act == null) return false;
        return !act.contains("STOP") && !act.contains("OFF");
    }

    /**
     * Checks if the reverse intent indicates reverse is turning ON vs OFF.
     */
    public static boolean isReverseOn(Intent intent) {
        if (intent == null) return false;
        String act = intent.getAction();
        if (act != null && !isReverseActionOn(act)) {
            return false;
        }
        try {
            if (intent.hasExtra("reverse")) {
                Object rev = intent.getExtras().get("reverse");
                if (rev instanceof Boolean) return (Boolean) rev;
                if (rev instanceof Number) return ((Number) rev).intValue() != 0;
            }
            if (intent.hasExtra("state")) {
                Object st = intent.getExtras().get("state");
                if (st instanceof Boolean) return (Boolean) st;
                if (st instanceof Number) return revToInt(st) != 0;
            }
            if (intent.hasExtra("status")) {
                Object st = intent.getExtras().get("status");
                if (st instanceof Boolean) return (Boolean) st;
                if (st instanceof Number) return revToInt(st) != 0;
            }
        } catch (Throwable ignore) { }
        return true;
    }

    private static int revToInt(Object obj) {
        if (obj instanceof Number) return ((Number) obj).intValue();
        return 1;
    }

    /**
     * Called when reverse gear or backcar camera status changes.
     */
    public static void onReverseStateChanged(final Context context, boolean isReverse) {
        sReverseActive = isReverse;
        if (!isKeepMusicInReverseEnabled(context)) return;

        if (isReverse) {
            Log.i(TAG, "Reverse camera ON: Preserving music playback...");
            applySystemReverseMuteConfig(context, false);
            unmuteAndResumePlayback(context);

            // Staggered recovery to defeat camera overlay activity pausing audio after launching
            sHandler.postDelayed(new Runnable() {
                @Override public void run() { unmuteAndResumePlayback(context); }
            }, 150);
            sHandler.postDelayed(new Runnable() {
                @Override public void run() { unmuteAndResumePlayback(context); }
            }, 450);
            sHandler.postDelayed(new Runnable() {
                @Override public void run() { unmuteAndResumePlayback(context); }
            }, 900);
            sHandler.postDelayed(new Runnable() {
                @Override public void run() { unmuteAndResumePlayback(context); }
            }, 1500);
        } else {
            Log.i(TAG, "Reverse camera OFF: Normal driving mode resumed");
        }
    }

    public static boolean isReverseActive() {
        return sReverseActive;
    }

    /**
     * Handles audio focus losses. If reverse camera is active or caused the loss,
     * keeps stream unmuted and resumes music playback.
     */
    public static void handleAudioFocusChange(final Context context, int focusChange) {
        if (context == null || !isKeepMusicInReverseEnabled(context)) return;

        if (sReverseActive || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            Log.d(TAG, "Audio focus transient loss intercepted (reverse active: " + sReverseActive + ")");
            sHandler.postDelayed(new Runnable() {
                @Override public void run() {
                    unmuteAndResumePlayback(context);
                }
            }, 100);
        }
    }

    /**
     * Called when MainActivity is paused while screen is still interactive (e.g. Reverse camera popped up).
     */
    public static void onActivityPaused(final Context context) {
        if (context == null || !isKeepMusicInReverseEnabled(context)) return;

        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            boolean isInteractive = (pm != null && pm.isInteractive());
            if (isInteractive) {
                // Screen is still on, meaning an overlay or system window (such as reverse camera) took focus
                sHandler.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (isKeepMusicInReverseEnabled(context)) {
                            unmuteAndResumePlayback(context);
                        }
                    }
                }, 200);
                sHandler.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (isKeepMusicInReverseEnabled(context)) {
                            unmuteAndResumePlayback(context);
                        }
                    }
                }, 600);
            }
        } catch (Throwable ignore) { }
    }

    /**
     * Unmutes the music audio stream, requests audio focus, and dispatches media play commands.
     */
    public static void unmuteAndResumePlayback(Context context) {
        if (context == null) return;
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                // 1. Unmute music stream
                am.setStreamMute(AudioManager.STREAM_MUSIC, false);

                // 2. Request audio focus with GAIN
                am.requestAudioFocus(new AudioManager.OnAudioFocusChangeListener() {
                    @Override public void onAudioFocusChange(int focusChange) {
                        Log.d(TAG, "OnAudioFocusChange during reverse: " + focusChange);
                    }
                }, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);

                // 3. Re-dispatch Play key events to counteract any PAUSE sent by camera overlay
                long now = SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
            }
        } catch (Throwable ignore) { }

        // Broadcast media button play intent
        try {
            Intent mb = new Intent(Intent.ACTION_MEDIA_BUTTON);
            mb.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
            context.sendOrderedBroadcast(mb, null);
            mb.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY));
            context.sendOrderedBroadcast(mb, null);
        } catch (Throwable ignore) { }

        // Broadcast vendor Chinese head-unit music play intents
        String[] playIntents = {
                "com.syu.bt.action.MUSIC_PLAY",
                "com.syu.bt.action.PLAY_PAUSE",
                "com.gocsdk.action.MUSIC_PLAY",
                "com.allwinner.bluetooth.action.PLAY",
                "com.car.bt.action.MUSIC_PLAY",
                "com.ts.bt.action.PLAY",
                "com.forfan.action.BT_MUSIC"
        };
        for (String act : playIntents) {
            try {
                context.sendBroadcast(new Intent(act));
            } catch (Throwable ignore) { }
        }
    }
}
