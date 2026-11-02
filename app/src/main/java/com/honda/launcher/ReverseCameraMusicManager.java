package com.honda.launcher;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import java.util.Collections;
import java.util.List;

/**
 * Keeps music playing while the reverse camera is on.
 *
 * Head units silence music in reverse in different ways: muting or lowering the music stream,
 * or taking audio focus so the player pauses. The unit's own "reverse mute" setting can only be
 * changed by a system app, so this class repairs the audio from the outside:
 *
 * 1. A light sampler remembers whether music was playing and at what volume, so the state from
 *    just before reverse is known even when the camera silenced music before we heard about it.
 * 2. A recognised reverse broadcast starts a guard for the first seconds of reverse that undoes
 *    the mute, puts the volume back and resumes a paused player.
 * 3. If no reverse broadcast arrives, the launcher being covered while music plays starts a
 *    narrower guard that only undoes a full mute. It never presses play, because the cover
 *    could equally be the radio or a phone call.
 */
public class ReverseCameraMusicManager {
    private static final String TAG = "ReverseCamMusic";
    public static final String PREF_KEEP_MUSIC_IN_REVERSE = "keep_music_in_reverse";

    static final long SAMPLE_INTERVAL_MS = 1000;
    /** Music counts as "playing before reverse" if it was heard this recently. */
    static final long RECENT_MUSIC_MS = 3000;
    /** Head units apply their reverse mute in the first seconds; after that the user is in control. */
    static final long GUARD_MS = 8000;
    static final long GUARD_TICK_MS = 300;
    static final long RESUME_RETRY_MS = 1500;
    static final int MAX_RESUME_ATTEMPTS = 4;
    /** The launcher and the boot receiver both hear each reverse broadcast. */
    static final long DUPLICATE_SIGNAL_MS = 2000;
    /** Being covered by an app DashPilot itself just opened is not the reverse camera. */
    static final long SELF_LAUNCH_GRACE_MS = 3000;
    static final long REVERSE_OFF_SETTLE_MS = 800;

    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static volatile Context sAppContext;

    // Sampled once a second while not in reverse
    private static volatile int sLastKnownMusicVolume = -1;   // last non-zero music volume
    private static volatile int sSampledVolume = -1;
    private static volatile boolean sSampledMuted;
    private static volatile long sSampledAt;
    private static volatile long sLastMusicActiveTimestamp;

    // Reverse state and the snapshot taken as it began
    private static volatile boolean sReverseActive;
    private static volatile long sReverseStartedAt;
    private static volatile boolean sWasPlayingBeforeReverse;
    private static volatile int sVolumeBeforeReverse = -1;
    private static volatile boolean sMutedBeforeReverse;
    private static volatile String sPlayingSessionPackage;

    // Guard
    private static volatile long sGuardUntil;
    private static volatile boolean sGuardConfirmed;   // started by a reverse broadcast
    private static volatile int sResumeAttempts;
    private static volatile long sLastResumeAttemptAt;
    private static volatile long sLastSelfLaunchAt;

    // Diagnostics for the settings card: the car has no USB port, so there is no logcat
    private static volatile String sLastSignal;
    private static volatile long sLastSignalAt;
    private static volatile int sSoundRestores;
    private static volatile int sPlayPresses;

    public static boolean isKeepMusicInReverseEnabled(Context context) {
        if (context == null) return true;
        SharedPreferences prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_KEEP_MUSIC_IN_REVERSE, true);
    }

    public static void setKeepMusicInReverseEnabled(Context context, boolean enabled) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        prefs.edit().putBoolean(PREF_KEEP_MUSIC_IN_REVERSE, enabled).apply();
        if (!enabled) stopGuard();
        applySystemReverseMuteConfig(context, !enabled);
    }

    /** Starts the background sampler. Safe to call more than once. */
    public static void startMonitoring(Context context) {
        if (context == null) return;
        attach(context);
        sHandler.removeCallbacks(sSampler);
        sHandler.post(sSampler);
    }

    private static void attach(Context context) {
        if (sAppContext == null) {
            Context app = context.getApplicationContext();
            sAppContext = (app != null) ? app : context;
        }
    }

    private static AudioManager audio(Context context) {
        return (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    private static final Runnable sSampler = new Runnable() {
        @Override public void run() {
            Context c = sAppContext;
            if (c != null && !sReverseActive && !isGuarding() && isKeepMusicInReverseEnabled(c)) {
                sample(c);
            }
            sHandler.postDelayed(this, SAMPLE_INTERVAL_MS);
        }
    };

    private static void sample(Context context) {
        try {
            AudioManager am = audio(context);
            if (am == null) return;
            int vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            sSampledVolume = vol;
            sSampledMuted = am.isStreamMute(AudioManager.STREAM_MUSIC);
            sSampledAt = SystemClock.elapsedRealtime();
            if (vol > 0) sLastKnownMusicVolume = vol;
            if (am.isMusicActive()) sLastMusicActiveTimestamp = sSampledAt;
        } catch (Throwable ignore) { }
    }

    /** Records how the music sounded just before the camera came on. */
    private static void snapshotBeforeReverse(Context context, AudioManager am) {
        long now = SystemClock.elapsedRealtime();
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean curMuted = am.isStreamMute(AudioManager.STREAM_MUSIC);

        // The camera may already have silenced music by the time we hear about it,
        // so trust the sample from a moment ago when there is a fresh one.
        boolean fresh = sSampledAt > 0 && now - sSampledAt <= 2 * SAMPLE_INTERVAL_MS;
        if (fresh) {
            sVolumeBeforeReverse = Math.max(cur, sSampledVolume);
            sMutedBeforeReverse = sSampledMuted && curMuted;   // the user had muted it themselves
        } else {
            sVolumeBeforeReverse = (cur > 0) ? cur : sLastKnownMusicVolume;
            sMutedBeforeReverse = false;
        }

        sPlayingSessionPackage = findPlayingSessionPackage(context);
        sWasPlayingBeforeReverse = am.isMusicActive()
                || (sLastMusicActiveTimestamp > 0 && now - sLastMusicActiveTimestamp < RECENT_MUSIC_MS)
                || sPlayingSessionPackage != null;
        sResumeAttempts = 0;
        sLastResumeAttemptAt = 0;
    }

    private static boolean isGuarding() {
        return sGuardUntil > SystemClock.elapsedRealtime();
    }

    private static void startGuard(boolean confirmed) {
        sGuardConfirmed = confirmed;
        sGuardUntil = SystemClock.elapsedRealtime() + GUARD_MS;
        sHandler.removeCallbacks(sGuardTick);
        sHandler.post(sGuardTick);
    }

    private static void stopGuard() {
        sGuardUntil = 0;
        sHandler.removeCallbacks(sGuardTick);
    }

    private static final Runnable sGuardTick = new Runnable() {
        @Override public void run() {
            Context c = sAppContext;
            if (c == null || !isGuarding() || !isKeepMusicInReverseEnabled(c)) return;
            boolean confirmed = sGuardConfirmed;
            restoreMusic(c, confirmed, confirmed);
            sHandler.postDelayed(this, GUARD_TICK_MS);
        }
    };

    private static final Runnable sReverseOffCheck = new Runnable() {
        @Override public void run() {
            Context c = sAppContext;
            if (c == null || !isKeepMusicInReverseEnabled(c)) return;
            // One more chance to resume, even if the guard used up its attempts
            sResumeAttempts = 0;
            sLastResumeAttemptAt = 0;
            restoreMusic(c, false, true);
        }
    };

    /**
     * Undoes what the head unit did to the music when the camera came on. A mute is always
     * undone (unless the user had muted before reverse); a lowered volume only when
     * {@code restoreLoweredVolume}, otherwise only a drop all the way to zero.
     */
    private static void restoreMusic(Context context, boolean restoreLoweredVolume, boolean mayResume) {
        try {
            AudioManager am = audio(context);
            if (am == null) return;
            if (am.getMode() != AudioManager.MODE_NORMAL) return;   // phone call: leave audio alone

            boolean restored = false;
            if (!sMutedBeforeReverse && am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
                restored = true;
            }
            int vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int target = sVolumeBeforeReverse;
            if (target > 0 && vol < target && (restoreLoweredVolume || vol == 0)) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
                restored = true;
            }
            if (restored) sSoundRestores++;

            long now = SystemClock.elapsedRealtime();
            if (mayResume && sWasPlayingBeforeReverse && !am.isMusicActive()
                    && sResumeAttempts < MAX_RESUME_ATTEMPTS
                    && now - sLastResumeAttemptAt >= RESUME_RETRY_MS) {
                sResumeAttempts++;
                sLastResumeAttemptAt = now;
                sPlayPresses++;
                resumePlayback(context, am);
            }
        } catch (Throwable ignore) { }
    }

    /** Presses PLAY, never PLAY_PAUSE, so it cannot toggle music off. */
    private static void resumePlayback(Context context, AudioManager am) {
        Log.i(TAG, "Music stopped during reverse: resuming playback");
        if (resumeMediaSession(context)) return;

        try {
            long now = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
        } catch (Throwable ignore) { }

        try {
            Intent mb = new Intent(Intent.ACTION_MEDIA_BUTTON);
            mb.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
            context.sendOrderedBroadcast(mb, null);
            mb.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY));
            context.sendOrderedBroadcast(mb, null);
        } catch (Throwable ignore) { }

        // Vendor head-unit Bluetooth music PLAY intents
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

    /**
     * Resumes the media session that was playing before reverse, which targets the right
     * player directly. Needs notification access; returns false without it.
     */
    private static boolean resumeMediaSession(Context context) {
        String pkg = sPlayingSessionPackage;
        if (pkg == null) return false;
        for (MediaController c : activeSessions(context)) {
            if (pkg.equals(c.getPackageName())) {
                try {
                    PlaybackState st = c.getPlaybackState();
                    if (st == null || st.getState() != PlaybackState.STATE_PLAYING) {
                        c.getTransportControls().play();
                    }
                    return true;
                } catch (Throwable ignore) { }
            }
        }
        return false;
    }

    private static String findPlayingSessionPackage(Context context) {
        for (MediaController c : activeSessions(context)) {
            try {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) return c.getPackageName();
            } catch (Throwable ignore) { }
        }
        return null;
    }

    private static List<MediaController> activeSessions(Context context) {
        try {
            MediaSessionManager msm = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm != null) {
                List<MediaController> l = msm.getActiveSessions(new ComponentName(context, NowPlayingService.class));
                if (l != null) return l;
            }
        } catch (Throwable ignore) { }   // SecurityException without notification access
        return Collections.emptyList();
    }

    /**
     * Applies system and vendor head-unit settings to prevent muting during reverse camera.
     * These are private settings that only a system app may write. On a normal install every
     * write fails (there are about 70 of them), so they are skipped unless we hold the rights.
     */
    public static void applySystemReverseMuteConfig(Context context, boolean mute) {
        if (context == null) return;
        boolean system = canWriteSystemSettings(context);
        boolean secure = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        if (!system && !secure) return;

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
            if (system) {
                try { Settings.System.putInt(context.getContentResolver(), k, val); } catch (Throwable ignore) { }
            }
            if (secure) {
                try { Settings.Global.putInt(context.getContentResolver(), k, val); } catch (Throwable ignore) { }
                try { Settings.Secure.putInt(context.getContentResolver(), k, val); } catch (Throwable ignore) { }
            }
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
            if (system) {
                try { Settings.System.putInt(context.getContentResolver(), vk, volVal); } catch (Throwable ignore) { }
            }
            if (secure) {
                try { Settings.Global.putInt(context.getContentResolver(), vk, volVal); } catch (Throwable ignore) { }
            }
        }
    }

    private static boolean canWriteSystemSettings(Context context) {
        try {
            return Settings.System.canWrite(context);
        } catch (Throwable t) {
            return false;
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
     * Handles a broadcast if it is a reverse gear event.
     *
     * @return true if the intent was a reverse event (handled or not)
     */
    public static boolean handleReverseIntent(Context context, Intent intent) {
        if (!isReverseIntent(intent)) return false;
        boolean on = isReverseOn(intent);
        sLastSignal = intent.getAction() + (on ? " (ON)" : " (OFF)");
        sLastSignalAt = SystemClock.elapsedRealtime();
        onReverseStateChanged(context, on);
        return true;
    }

    /**
     * Called when reverse gear or backcar camera status changes.
     */
    public static void onReverseStateChanged(Context context, boolean isReverse) {
        if (context == null) return;
        attach(context);
        long now = SystemClock.elapsedRealtime();
        if (isReverse) {
            // A repeat well after the first is a new reverse whose OFF signal we never got
            if (sReverseActive && now - sReverseStartedAt < DUPLICATE_SIGNAL_MS) return;
            sReverseActive = true;
            sReverseStartedAt = now;
        } else {
            if (!sReverseActive) return;
            sReverseActive = false;
        }
        sHandler.removeCallbacks(sReverseOffCheck);
        if (!isKeepMusicInReverseEnabled(context)) return;

        if (isReverse) {
            Log.i(TAG, "Reverse camera ON: keeping music playing");
            // A guard started by the launcher being covered already holds the real "before" state
            if (!isGuarding()) {
                try {
                    AudioManager am = audio(context);
                    if (am != null) snapshotBeforeReverse(context, am);
                } catch (Throwable ignore) { }
            }
            applySystemReverseMuteConfig(context, false);
            startGuard(true);
        } else {
            Log.i(TAG, "Reverse camera OFF: normal driving mode resumed");
            stopGuard();
            // One last pass once the head unit has handed audio back
            sHandler.postDelayed(sReverseOffCheck, REVERSE_OFF_SETTLE_MS);
        }
    }

    public static boolean isReverseActive() {
        return sReverseActive;
    }

    /**
     * Called when the launcher is paused. With the screen still on and music playing, that is
     * most often the reverse camera covering it, so watch for a mute even without a broadcast.
     */
    public static void onActivityPaused(Context context) {
        if (context == null || !isKeepMusicInReverseEnabled(context)) return;
        attach(context);
        if (sReverseActive || isGuarding()) return;
        if (SystemClock.elapsedRealtime() - sLastSelfLaunchAt < SELF_LAUNCH_GRACE_MS) return;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null || !pm.isInteractive()) return;
            AudioManager am = audio(context);
            if (am == null || am.getMode() != AudioManager.MODE_NORMAL) return;
            snapshotBeforeReverse(context, am);
            if (sWasPlayingBeforeReverse) startGuard(false);
        } catch (Throwable ignore) { }
    }

    /** Called when the launcher is back in front: a cover-only guard has nothing left to protect. */
    public static void onActivityResumed() {
        if (!sReverseActive) stopGuard();
    }

    /** DashPilot is about to open an activity itself, so the pause that follows is not the camera. */
    public static void noteSelfLaunch() {
        sLastSelfLaunchAt = SystemClock.elapsedRealtime();
    }

    /** One-line status for the settings card, so the user can see what the head unit sends. */
    public static String getStatusText(Context context) {
        if (!isKeepMusicInReverseEnabled(context)) {
            return "Off: the head unit may mute music while reversing";
        }
        StringBuilder sb = new StringBuilder();
        String signal = sLastSignal;
        if (signal == null) {
            sb.append("No reverse signal received from the head unit yet");
        } else {
            sb.append("Last reverse signal: ").append(signal).append(", ")
              .append(formatAgo(SystemClock.elapsedRealtime() - sLastSignalAt));
        }
        if (sSoundRestores > 0 || sPlayPresses > 0) {
            sb.append(" • sound restored ×").append(sSoundRestores)
              .append(", play pressed ×").append(sPlayPresses);
        }
        return sb.toString();
    }

    static String formatAgo(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + " s ago";
        if (s < 3600) return (s / 60) + " min ago";
        return (s / 3600) + " h ago";
    }
}
