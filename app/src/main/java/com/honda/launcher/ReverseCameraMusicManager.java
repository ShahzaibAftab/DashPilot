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
    private static volatile int sLastKnownMusicVolume = -1;
    private static volatile boolean sWasPlayingBeforeReverse = false;
    private static volatile long sLastMusicActiveTimestamp = 0;

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

    public static void recordActiveMusicVolume(Context context) {
        if (context == null) return;
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                int vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (vol > 0) {
                    sLastKnownMusicVolume = vol;
                }
                if (am.isMusicActive()) {
                    sLastMusicActiveTimestamp = SystemClock.elapsedRealtime();
                }
            }
        } catch (Throwable ignore) { }
    }

    /**
     * Clears all pending delayed unmute/recovery callbacks.
     */
    public static void clearPendingCallbacks() {
        sHandler.removeCallbacksAndMessages(null);
    }

    /**
     * Applies system and vendor head-unit settings to prevent muting during reverse camera.
     */
    public static void applySystemReverseMuteConfig(Context context, boolean mute) {
        if (context == null) return;
        int val = mute ? 1 : 0;
        int volVal = mute ? 0 : 100;
        String[] keys = {
                "reverse_mute",
                "canbus_reverse_mute",
                "backcar_mute",
                "sys_reverse_mute",
                "car_reverse_music_mute",
                "mcu_reverse_mute",
                "syu_reverse_mute",
                "allwinner_reverse_mute",
                "mute_in_reverse",
                "back_car_mute",
                "camera_mute",
                "is_backcar_mute",
                "is_reverse_mute",
                "syu_backcar_mute",
                "tw_reverse_mute",
                "fyt_reverse_mute",
                "car_backcar_mute",
                "app_reverse_mute",
                "app_backcar_mute"
        };
        for (String k : keys) {
            try { Settings.System.putInt(context.getContentResolver(), k, val); } catch (Throwable ignore) { }
            try { Settings.Global.putInt(context.getContentResolver(), k, val); } catch (Throwable ignore) { }
            try { Settings.Secure.putInt(context.getContentResolver(), k, val); } catch (Throwable ignore) { }
        }
        String[] volKeys = {
                "backcar_volume",
                "reverse_volume",
                "mcu_reverse_volume",
                "back_car_volume",
                "syu_reverse_volume",
                "fyt_reverse_volume",
                "reverse_media_volume"
        };
        for (String vk : volKeys) {
            try { Settings.System.putInt(context.getContentResolver(), vk, volVal); } catch (Throwable ignore) { }
            try { Settings.Global.putInt(context.getContentResolver(), vk, volVal); } catch (Throwable ignore) { }
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
        sHandler.removeCallbacksAndMessages(null);
        if (!isKeepMusicInReverseEnabled(context)) return;

        if (isReverse) {
            Log.i(TAG, "Reverse camera ON: Preserving music playback...");
            try {
                AudioManager am = (AudioManager) (context != null ? context.getSystemService(Context.AUDIO_SERVICE) : null);
                boolean activeNow = (am != null && am.isMusicActive());
                boolean activeRecently = (SystemClock.elapsedRealtime() - sLastMusicActiveTimestamp < 4000);
                sWasPlayingBeforeReverse = activeNow || activeRecently;
            } catch (Throwable ignore) {
                sWasPlayingBeforeReverse = true;
            }

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
            if (isInteractive && (sReverseActive || sWasPlayingBeforeReverse)) {
                // Screen is still on, meaning an overlay or system window (such as reverse camera) took focus
                long[] delays = {100, 300, 600, 1000, 1600, 2500};
                for (long d : delays) {
                    sHandler.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (isKeepMusicInReverseEnabled(context)) {
                                unmuteAndResumePlayback(context);
                            }
                        }
                    }, d);
                }
            }
        } catch (Throwable ignore) { }
    }

    /**
     * Unmutes the music audio stream and ensures playback is uninterrupted.
     * Never sends toggle/pause commands if music is already playing.
     */
    public static void unmuteAndResumePlayback(Context context) {
        if (context == null) return;
        applySystemReverseMuteConfig(context, false);
        boolean wasPlaying = false;
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                // 1. Unmute music stream using both adjustStreamVolume and setStreamMute
                try { am.setStreamMute(AudioManager.STREAM_MUSIC, false); } catch (Throwable ignore) { }
                try { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0); } catch (Throwable ignore) { }

                // 2. Ensure volume was not pulled to zero
                int vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (vol == 0) {
                    int target = (sLastKnownMusicVolume > 0) ? sLastKnownMusicVolume
                            : Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 2 / 3);
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
                }

                wasPlaying = am.isMusicActive();

                // 3. If music was interrupted/paused by reverse camera, send explicit PLAY
                // but only if music was playing prior to reverse gear engagement
                if (!wasPlaying && (sWasPlayingBeforeReverse || !sReverseActive)) {
                    long now = SystemClock.uptimeMillis();
                    am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
                    am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
                }
            }
        } catch (Throwable ignore) { }

        // Only send play intents if audio stopped playing and was previously active
        if (!wasPlaying && (sWasPlayingBeforeReverse || !sReverseActive)) {
            try {
                Intent mb = new Intent(Intent.ACTION_MEDIA_BUTTON);
                mb.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
                context.sendOrderedBroadcast(mb, null);
                mb.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY));
                context.sendOrderedBroadcast(mb, null);
            } catch (Throwable ignore) { }

            // Broadcast vendor Chinese head-unit explicit PLAY intents (DO NOT broadcast PLAY_PAUSE!)
            String[] playIntents = {
                    "com.syu.bt.action.MUSIC_PLAY",
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
}
