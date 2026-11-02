package com.honda.launcher.audio;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Auto Audio Tuning for Android 7.1+ head units: an equalizer on the global output mix
 * (session 0) that adapts to the music, the volume and the car's speed.
 *
 * 1. Profiles: AUTO (live music) plus fixed Balanced, Vocal, Bass, Rock and Highway curves.
 * 2. AUTO tunes from whole-word genre cues in the song title and, with audio access, from the
 *    song's tonal balance measured live in the background (SpectrumBalance).
 * 3. Volume-aware loudness: more bass and treble at low volume, where ears lose them, and
 *    less bass at high volume to spare the stock speakers.
 * 4. Song level matching: quiet songs lifted and loud ones lowered, up to 4 dB (LevelMatcher).
 * 5. Road-noise compensation by speed (OFF / LOW / HIGH): more bass and overall gain.
 * 6. Every change glides over a second or two instead of jumping.
 * 7. A/B bypass to hear the sound with no DashPilot processing at all.
 *
 * Boosts of the overall level go through LoudnessEnhancer, whose limiter prevents clipping;
 * cuts go through the equalizer. Audio that a hardware Bluetooth module plays directly never
 * reaches Android's mixer, so none of this affects it; the A/B bypass shows whether it does.
 */
public final class AutoEqManager implements AutoCloseable {
    private static final String TAG = "DashPilotAutoEQ";
    private static final String PREFS = "dashpilot_autoeq";
    private static final int PREF_VERSION = 3;
    private static final int MAX_REQUESTED_BOOST_DB = 6;

    public static final int SPEED_COMP_OFF = 0;
    public static final int SPEED_COMP_LOW = 1;
    public static final int SPEED_COMP_HIGH = 2;

    /** Car cabins already boom around 200-300 Hz; AUTO trims it instead of adding to it. */
    static final float BOOM_CUT_DB = 1.0f;
    /** Overall boost allowed through the equalizer alone, when LoudnessEnhancer is missing. */
    private static final float EQ_ONLY_MAX_BOOST_DB = 1.5f;
    private static final long RAMP_TICK_MS = 100;
    /** 2.5 dB per second: a full profile change takes about a second and a half. */
    static final float RAMP_STEP_DB = 0.25f;
    private static final long VISUALIZER_RETRY_MS = 60_000;
    /** The equalizer screen's bars keep the analyzer alive for this long after their last read. */
    private static final long CAPTURE_KEEPALIVE_MS = 5_000;

    private static final String ANALYSIS_ON = "Live analysis on";
    private static final String ANALYSIS_OFF = "Live analysis off";
    private static final String ANALYSIS_NEEDS_PERMISSION = "Live analysis needs audio access (open Equalizer)";
    private static final String ANALYSIS_UNSUPPORTED = "Live analysis not supported on this unit";

    public enum Profile {
        DYNAMIC_AUTO("Auto (Live Music)"),
        BALANCED("Balanced (Studio)"),
        VOCAL("Vocal & Clarity"),
        BASS_CONTROL("Bass & Punch"),
        ROCK_POWER("Rock & Energy"),
        BACKGROUND("Highway Acoustic");

        public final String displayName;
        Profile(String displayName) {
            this.displayName = displayName;
        }
    }

    public static final class Settings {
        public Profile profile = Profile.DYNAMIC_AUTO;
        public int strengthPercent = 50;
        public float bassDb = 0f;
        public float midsDb = 0f;
        public float trebleDb = 0f;
        public boolean headroomProtection = true;
        /** Mirrors speedCompLevel > OFF; kept for older callers. */
        public boolean speedCompensation = true;
        public int speedCompLevel = SPEED_COMP_LOW;
        public boolean levelMatching = true;
        public boolean loudnessCompensation = true;
        public boolean enabled = true;

        public Settings copy() {
            Settings s = new Settings();
            s.profile = profile;
            s.strengthPercent = strengthPercent;
            s.bassDb = bassDb;
            s.midsDb = midsDb;
            s.trebleDb = trebleDb;
            s.headroomProtection = headroomProtection;
            s.speedCompensation = speedCompensation;
            s.speedCompLevel = speedCompLevel;
            s.levelMatching = levelMatching;
            s.loudnessCompensation = loudnessCompensation;
            s.enabled = enabled;
            return s;
        }
    }

    public static final class BandInfo {
        public final short index;
        public final int centerHz;
        public final int minDb;
        public final int maxDb;

        public BandInfo(short index, int centerHz, int minDb, int maxDb) {
            this.index = index;
            this.centerHz = centerHz;
            this.minDb = minDb;
            this.maxDb = maxDb;
        }
    }

    public static final class Status {
        public final boolean attached;
        public final boolean enabled;
        public final boolean hasControl;
        public final String message;
        public final String activeTuningMode;
        public final List<BandInfo> bands;
        public final float appliedPreampDb;
        public final float[] currentBandLevelsDb;

        Status(boolean attached, boolean enabled, boolean hasControl,
               String message, String activeTuningMode, List<BandInfo> bands,
               float appliedPreampDb, float[] currentBandLevelsDb) {
            this.attached = attached;
            this.enabled = enabled;
            this.hasControl = hasControl;
            this.message = message;
            this.activeTuningMode = activeTuningMode;
            this.bands = Collections.unmodifiableList(new ArrayList<>(bands));
            this.appliedPreampDb = appliedPreampDb;
            this.currentBandLevelsDb = currentBandLevelsDb != null ? currentBandLevelsDb.clone() : new float[0];
        }
    }

    public interface OnTuningUpdateListener {
        void onTuningUpdated(Status status, String tuningDescription);
    }

    private static volatile AutoEqManager sInstance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private Settings settings;
    private Equalizer equalizer;
    private int audioSessionId = AudioEffect.ERROR_BAD_VALUE;
    private final List<BandInfo> detectedBands = new ArrayList<>();
    private int bandMinMb, bandMaxMb;
    private float appliedPreampDb = 0f;
    private String statusMessage = "Not attached to an audio session";
    private String currentDetectedMode = "Dynamic (Awaiting audio)";
    private String lastNotifiedMode = "";

    // Gliding: targets are computed on every change, the ramp walks the applied levels to them
    private float[] targetBandDb = new float[0];
    private float[] currentAppliedBandLevels = new float[0];
    private short[] lastAppliedMillibels = new short[0];
    private boolean bandsDirty = true;
    private boolean rampPosted;

    // Overall gain boost through LoudnessEnhancer (its limiter prevents clipping)
    private LoudnessEnhancer loudness;
    private boolean loudnessUnavailable;
    private float targetGainDb, currentGainDb;

    // Live analysis of the output mix, polled once a second by onTick()
    private Visualizer visualizer;
    private byte[] fftBuffer = new byte[0];
    private int samplingRateMilliHz;
    private long lastVisualizerFailMs;
    private long lastCaptureRequestMs;
    private String analysisState = ANALYSIS_OFF;
    private SpectrumBalance balance;
    private int balanceSamples;
    private final LevelMatcher levelMatcher = new LevelMatcher();

    private int lastVolumeIndex = -1;
    private float volumeFraction = 0.5f;
    private float currentVehicleSpeedKmh = 0f;
    private boolean bypass;
    private float lastLoudnessBassDb, lastSpeedGainDb;

    // Last known track metadata
    private String lastTrackTitle = "";
    private String lastTrackArtist = "";

    private OnTuningUpdateListener tuningListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable rampStep = new Runnable() {
        @Override public void run() {
            synchronized (AutoEqManager.this) {
                rampPosted = false;
                boolean moving;
                try {
                    moving = stepRampLocked();
                } catch (RuntimeException e) {
                    Log.w(TAG, "EQ ramp step failed", e);
                    moving = false;
                }
                if (moving) {
                    rampPosted = true;
                    mainHandler.postDelayed(this, RAMP_TICK_MS);
                } else {
                    notifyListenerLocked(true);   // let the equalizer screen show the settled levels
                }
            }
        }
    };

    public static AutoEqManager get(Context context) {
        if (sInstance == null) {
            synchronized (AutoEqManager.class) {
                if (sInstance == null) {
                    sInstance = new AutoEqManager(context);
                }
            }
        }
        return sInstance;
    }

    /**
     * Returns the active hardware Equalizer instance (e.g. Session 0), or null if unavailable.
     */
    public synchronized Equalizer getEqualizer() {
        if (equalizer == null && audioSessionId == 0) {
            attachToAudioSession(0);
        }
        return equalizer;
    }

    public AutoEqManager(Context context) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.settings = loadSettings();

        // Default to attaching to system mix session 0 on car head units
        attachToAudioSession(0);
    }

    public void setOnTuningUpdateListener(OnTuningUpdateListener listener) {
        this.tuningListener = listener;
    }

    public synchronized Settings getSettings() {
        return settings.copy();
    }

    public synchronized void updateSettings(Settings newSettings) {
        if (newSettings == null) throw new IllegalArgumentException("settings must not be null");
        settings = sanitize(newSettings);
        persistSettings(settings);
        applySettingsLocked();
        notifyListenerLocked(true);
    }

    public synchronized void setProfile(Profile profile) {
        if (profile == null) profile = Profile.DYNAMIC_AUTO;
        settings.profile = profile;
        persistSettings(settings);
        applySettingsLocked();
        notifyListenerLocked(true);
    }

    public synchronized void setStrengthPercent(int strengthPercent) {
        settings.strengthPercent = Math.max(0, Math.min(100, strengthPercent));
        persistSettings(settings);
        applySettingsLocked();
        notifyListenerLocked(true);
    }

    public synchronized void setEnabled(boolean enabled) {
        settings.enabled = enabled;
        persistSettings(settings);
        applySettingsLocked();
        notifyListenerLocked(true);
    }

    public synchronized void setSpeedCompLevel(int level) {
        settings.speedCompLevel = level;
        settings = sanitize(settings);
        persistSettings(settings);
        applySettingsLocked();
        notifyListenerLocked(true);
    }

    public synchronized void setLevelMatching(boolean on) {
        settings.levelMatching = on;
        if (!on) levelMatcher.reset();
        persistSettings(settings);
        applySettingsLocked();
        notifyListenerLocked(true);
    }

    public synchronized void setLoudnessCompensation(boolean on) {
        settings.loudnessCompensation = on;
        persistSettings(settings);
        applySettingsLocked();
        notifyListenerLocked(true);
    }

    /**
     * A/B comparison: true turns off every DashPilot effect so the original sound can be heard.
     * Not saved; the equalizer screen ends it automatically.
     */
    public synchronized void setBypass(boolean on) {
        if (bypass == on) return;
        bypass = on;
        applyEnableStateLocked();
        notifyListenerLocked(true);
    }

    public synchronized boolean isBypassed() {
        return bypass;
    }

    /**
     * Attaches to an Android audio session ID.
     * Session ID 0 is accepted as the global output mix on Android 7.1.
     */
    public synchronized Status attachToAudioSession(int sessionId) {
        if (equalizer != null && audioSessionId == sessionId) {
            return getStatusLocked();
        }

        releaseEffectLocked();
        if (sessionId < 0) {
            statusMessage = "Invalid audio session ID; effect detached";
            return getStatusLocked();
        }

        audioSessionId = sessionId;
        try {
            equalizer = new Equalizer(0, sessionId);
            detectBandsLocked();
            if (detectedBands.isEmpty()) {
                statusMessage = "Equalizer created but no usable EQ bands reported";
                releaseEffectLocked();
                return getStatusLocked();
            }
            statusMessage = "Attached to audio session " + sessionId;
            applySettingsLocked();
            applyEnableStateLocked();
            return getStatusLocked();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not attach Equalizer to session " + sessionId, e);
            statusMessage = "Equalizer unavailable: " + e.getClass().getSimpleName();
            releaseEffectLocked();
            return getStatusLocked();
        }
    }

    /**
     * Called about once a second while the screen is on: follows the volume knob and, with
     * audio access, measures the song's level and tonal balance.
     */
    public synchronized void onTick() {
        boolean changed = false;
        try {
            AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;

            int index = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (index != lastVolumeIndex) {
                if (lastVolumeIndex >= 0) levelMatcher.onVolumeChanged();
                lastVolumeIndex = index;
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                volumeFraction = (max > 0) ? index / (float) max : 0.5f;
                changed = true;
            }

            boolean wantAnalysis = settings.enabled && !bypass
                    && (settings.profile == Profile.DYNAMIC_AUTO || settings.levelMatching);
            if (!wantAnalysis) {
                if (SystemClock.elapsedRealtime() - lastCaptureRequestMs > CAPTURE_KEEPALIVE_MS) {
                    releaseVisualizerLocked();
                }
                analysisState = ANALYSIS_OFF;
            } else if (ensureVisualizerLocked()) {
                analysisState = ANALYSIS_ON;
                if (am.isMusicActive()) changed |= analyzeOutputLocked();
            }
        } catch (Throwable t) {
            Log.w(TAG, "AutoEQ tick failed", t);
        }
        if (changed && settings.enabled) {
            applySettingsLocked();
            notifyListenerLocked(false);
        }
    }

    /** Screen off (ignition off): release the analyzer until the next onTick(). */
    public synchronized void stopAnalysis() {
        releaseVisualizerLocked();
    }

    /** Live FFT for the equalizer screen's bars. False without audio access. */
    public synchronized boolean captureFft(byte[] out) {
        lastCaptureRequestMs = SystemClock.elapsedRealtime();
        if (out == null || !ensureVisualizerLocked() || out.length != fftBuffer.length) return false;
        try {
            return visualizer.getFft(out) == Visualizer.SUCCESS;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** FFT size captureFft() expects, or 0 when the analyzer is unavailable. */
    public synchronized int getCaptureSize() {
        lastCaptureRequestMs = SystemClock.elapsedRealtime();
        return ensureVisualizerLocked() ? fftBuffer.length : 0;
    }

    /** One line describing what the automatic tuning is doing right now. */
    public synchronized String getLiveSummary() {
        if (bypass) return "A/B: ORIGINAL sound, all DashPilot tuning off";
        if (!settings.enabled) return "Manual EQ: automatic adjustments off";
        StringBuilder sb = new StringBuilder(analysisState);
        if (settings.levelMatching) {
            sb.append(String.format(Locale.US, "  •  Song level %+.1f dB", levelMatcher.getGainDb()));
        }
        if (settings.speedCompLevel > SPEED_COMP_OFF) {
            sb.append(String.format(Locale.US, "  •  Road noise %+.1f dB", lastSpeedGainDb));
        }
        if (settings.loudnessCompensation) {
            sb.append(String.format(Locale.US, "  •  Volume bass %+.1f dB", lastLoudnessBassDb));
        }
        if (loudnessUnavailable) sb.append("  •  boosts limited on this unit");
        return sb.toString();
    }

    /** Measures one second of output. Returns true when there was new data to tune from. */
    private boolean analyzeOutputLocked() {
        Visualizer.MeasurementPeakRms m = new Visualizer.MeasurementPeakRms();
        if (visualizer.getMeasurementPeakRms(m) != Visualizer.SUCCESS) return false;
        float rmsDb = m.mRms / 100f;
        if (rmsDb < LevelMatcher.SILENCE_DB) return false;

        boolean changed = false;
        if (settings.levelMatching) {
            levelMatcher.onMeasurement(rmsDb);
            changed = true;
        }
        if (settings.profile == Profile.DYNAMIC_AUTO && visualizer.getFft(fftBuffer) == Visualizer.SUCCESS) {
            SpectrumBalance b = SpectrumBalance.fromFft(fftBuffer, samplingRateMilliHz);
            if (b != null) {
                // Learn a new song quickly, then settle into a ~10 s average
                balance = (balance == null) ? b : balance.blend(b, balanceSamples < 5 ? 0.3f : 0.1f);
                balanceSamples++;
                changed = true;
            }
        }
        return changed;
    }

    private boolean ensureVisualizerLocked() {
        if (visualizer != null) return true;
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            analysisState = ANALYSIS_NEEDS_PERMISSION;
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastVisualizerFailMs > 0 && now - lastVisualizerFailMs < VISUALIZER_RETRY_MS) return false;
        try {
            Visualizer v = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange();
            int size = Math.max(range[0], Math.min(range[1], 1024));
            v.setCaptureSize(size);
            v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            v.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS);
            v.setEnabled(true);
            visualizer = v;
            fftBuffer = new byte[v.getCaptureSize()];
            samplingRateMilliHz = v.getSamplingRate();
            analysisState = ANALYSIS_ON;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Visualizer unavailable on session 0", t);
            lastVisualizerFailMs = now;
            analysisState = ANALYSIS_UNSUPPORTED;
            releaseVisualizerLocked();
            return false;
        }
    }

    private void releaseVisualizerLocked() {
        if (visualizer != null) {
            try { visualizer.setEnabled(false); } catch (Throwable ignored) { }
            try { visualizer.release(); } catch (Throwable ignored) { }
            visualizer = null;
        }
    }

    /**
     * Updates track metadata to inform AutoEQ of song genre and vocal styling.
     */
    public synchronized void updateTrackMetadata(String title, String artist) {
        String cleanTitle = title != null ? title.trim() : "";
        String cleanArtist = artist != null ? artist.trim() : "";
        if (!cleanTitle.equalsIgnoreCase(lastTrackTitle) || !cleanArtist.equalsIgnoreCase(lastTrackArtist)) {
            this.lastTrackTitle = cleanTitle;
            this.lastTrackArtist = cleanArtist;
            levelMatcher.onTrackChanged();
            balanceSamples = 0;   // relearn the new song's balance quickly
            if (settings.enabled) {
                applySettingsLocked();
                notifyListenerLocked(true);
            }
        }
    }

    /**
     * Adapts acoustic low-end compensation according to car speed.
     */
    public synchronized void setVehicleSpeed(float speedKmh) {
        if (Float.isNaN(speedKmh) || Float.isInfinite(speedKmh) || speedKmh < 0f) {
            speedKmh = 0f;
        } else if (speedKmh > 250f) {
            speedKmh = 250f;
        }
        if (Math.abs(currentVehicleSpeedKmh - speedKmh) > 5f) {
            this.currentVehicleSpeedKmh = speedKmh;
            if (settings.enabled && settings.speedCompLevel > SPEED_COMP_OFF) {
                applySettingsLocked();
                notifyListenerLocked(false);
            }
        }
    }

    public synchronized Status refresh() {
        if (equalizer == null) return getStatusLocked();
        try {
            detectBandsLocked();
            applySettingsLocked();
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to refresh equalizer", e);
            statusMessage = "Unable to refresh effect";
        }
        return getStatusLocked();
    }

    public synchronized Status getStatus() {
        return getStatusLocked();
    }

    @Override
    public synchronized void close() {
        releaseEffectLocked();
        releaseVisualizerLocked();
        audioSessionId = AudioEffect.ERROR_BAD_VALUE;
        statusMessage = "Detached";
    }

    private void detectBandsLocked() {
        detectedBands.clear();
        if (equalizer == null) return;
        try {
            short count = equalizer.getNumberOfBands();
            short[] range = equalizer.getBandLevelRange(); // millibels (100 mB = 1 dB)
            bandMinMb = range[0];
            bandMaxMb = range[1];
            int minDb = (int) Math.ceil(range[0] / 100.0);
            int maxDb = (int) Math.floor(range[1] / 100.0);
            for (short i = 0; i < count; i++) {
                int centerMilliHz = equalizer.getCenterFreq(i);
                int centerHz = Math.max(1, centerMilliHz / 1000);
                detectedBands.add(new BandInfo(i, centerHz, minDb, maxDb));
            }
            currentAppliedBandLevels = new float[detectedBands.size()];
            targetBandDb = new float[detectedBands.size()];
            lastAppliedMillibels = new short[detectedBands.size()];
            bandsDirty = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Error detecting EQ bands", e);
        }
    }

    private void applySettingsLocked() {
        if (equalizer == null || detectedBands.isEmpty()) {
            appliedPreampDb = 0f;
            return;
        }

        try {
            if (!settings.enabled) {
                cancelRampLocked();
                restoreManualEqLevelsLocked();
                targetGainDb = 0f;
                currentGainDb = 0f;
                setLoudnessGainLocked(0f);
                appliedPreampDb = 0f;
                currentDetectedMode = "Manual EQ Active";
                return;
            }

            int nb = detectedBands.size();
            float[] requestedDb = new float[nb];
            float maxPositiveDb = 0f;
            float strength = clamp(settings.strengthPercent, 0, 100) / 100f;

            Profile activeProfile = settings.profile;
            String modeName = activeProfile.displayName;

            // If DYNAMIC_AUTO, determine the curve from the title and the live tonal balance
            float dynBass = 0f, dynMid = 0f, dynTreble = 0f;
            if (activeProfile == Profile.DYNAMIC_AUTO) {
                DynamicTarget dt = evaluateDynamicMusicTarget(lastTrackTitle, lastTrackArtist, balance);
                dynBass = dt.bassDb;
                dynMid = dt.midDb;
                dynTreble = dt.trebleDb;
                modeName = dt.description;
            }
            currentDetectedMode = modeName;

            int speedLevel = settings.speedCompLevel;
            float speedBassBoost = speedBassDb(currentVehicleSpeedKmh, speedLevel);
            float[] loud = settings.loudnessCompensation ? loudnessContour(volumeFraction) : new float[]{0f, 0f};
            lastLoudnessBassDb = loud[0];

            for (int i = 0; i < nb; i++) {
                BandInfo band = detectedBands.get(i);
                float profileDb;
                if (activeProfile == Profile.DYNAMIC_AUTO) {
                    profileDb = getDynamicBandDb(band.centerHz, dynBass, dynMid, dynTreble);
                } else {
                    profileDb = profileAdjustmentDb(activeProfile, band.centerHz);
                }
                if (band.centerHz < 160) {
                    profileDb += speedBassBoost;
                }

                float manualDb = manualAdjustmentDb(band.centerHz, settings);
                float target = (profileDb + manualDb) * strength;
                // Loudness corrects for hearing at the current volume, so "strength" does not scale it
                target += loudnessBandDb(band.centerHz, loud[0], loud[1]);
                target = clamp(target, -12f, MAX_REQUESTED_BOOST_DB);
                requestedDb[i] = target;
                if (target > maxPositiveDb) maxPositiveDb = target;
            }

            // Headroom protection: subtle soft-knee compression only when boost is high (> +3.5 dB)
            // Never crush mids or drop overall volume at normal listening levels!
            float compensationDb = 0f;
            if (settings.headroomProtection && maxPositiveDb > 3.5f) {
                compensationDb = (maxPositiveDb - 3.5f) * 0.40f;
            }
            appliedPreampDb = -compensationDb;

            // Overall level: song matching plus road noise. Boosts go through LoudnessEnhancer,
            // whose limiter prevents clipping; cuts (and small boosts without it) through the EQ.
            lastSpeedGainDb = speedGainDb(currentVehicleSpeedKmh, speedLevel);
            float totalGainDb = (settings.levelMatching ? levelMatcher.getGainDb() : 0f) + lastSpeedGainDb;
            float eqOffsetDb;
            if (totalGainDb > 0f && ensureLoudnessLocked()) {
                targetGainDb = totalGainDb;
                eqOffsetDb = 0f;
            } else {
                targetGainDb = 0f;
                eqOffsetDb = Math.min(totalGainDb, EQ_ONLY_MAX_BOOST_DB);
            }

            boolean needsRamp = Math.abs(targetGainDb - currentGainDb) > 0.001f;
            for (int i = 0; i < nb; i++) {
                targetBandDb[i] = requestedDb[i] - compensationDb + eqOffsetDb;
                if (Math.abs(targetBandDb[i] - currentAppliedBandLevels[i]) > 0.001f) needsRamp = true;
            }
            if (needsRamp || bandsDirty) startRampLocked();

            applyEnableStateLocked();
            statusMessage = "Auto-tuned for " + modeName;
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not apply AutoEQ settings", e);
            statusMessage = "Effect error; settings preserved";
        }
    }

    private void startRampLocked() {
        if (!rampPosted) {
            rampPosted = true;
            mainHandler.post(rampStep);
        }
    }

    private void cancelRampLocked() {
        mainHandler.removeCallbacks(rampStep);
        rampPosted = false;
    }

    /** Moves applied levels one step toward their targets. Returns true while still moving. */
    private boolean stepRampLocked() {
        if (equalizer == null || !settings.enabled || targetBandDb.length != detectedBands.size()) return false;
        boolean moving = false;
        for (int i = 0; i < detectedBands.size(); i++) {
            float next = stepToward(currentAppliedBandLevels[i], targetBandDb[i], RAMP_STEP_DB);
            currentAppliedBandLevels[i] = next;
            if (next != targetBandDb[i]) moving = true;
            writeBandLocked(i, next);
        }
        bandsDirty = false;

        float g = stepToward(currentGainDb, targetGainDb, RAMP_STEP_DB);
        if (g != currentGainDb) {
            currentGainDb = g;
            setLoudnessGainLocked(g);
        }
        if (g != targetGainDb) moving = true;
        return moving;
    }

    private void writeBandLocked(int i, float db) {
        int mB = Math.max(bandMinMb, Math.min(bandMaxMb, Math.round(db * 100f)));
        if (!bandsDirty && Math.abs(lastAppliedMillibels[i] - mB) < 10) return;
        equalizer.setBandLevel(detectedBands.get(i).index, (short) mB);
        lastAppliedMillibels[i] = (short) mB;
    }

    static float stepToward(float current, float target, float maxStep) {
        float d = target - current;
        if (Math.abs(d) <= maxStep) return target;
        return current + Math.copySign(maxStep, d);
    }

    private boolean ensureLoudnessLocked() {
        if (loudness != null) return true;
        if (loudnessUnavailable || audioSessionId < 0) return false;
        try {
            loudness = new LoudnessEnhancer(audioSessionId);
            loudness.setTargetGain(0);
            loudness.setEnabled(false);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "LoudnessEnhancer unavailable; overall boosts limited to the EQ", t);
            loudnessUnavailable = true;
            loudness = null;
            return false;
        }
    }

    private void setLoudnessGainLocked(float db) {
        if (loudness == null) return;
        try {
            loudness.setTargetGain(Math.round(Math.max(0f, db) * 100f));
            loudness.setEnabled(!bypass && settings.enabled && db > 0.05f);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not set loudness gain", e);
        }
    }

    /** Equalizer on unless bypassed (manual mode uses it too); loudness only while boosting. */
    private void applyEnableStateLocked() {
        try {
            if (equalizer != null) equalizer.setEnabled(!bypass);
        } catch (RuntimeException ignored) { }
        try {
            if (loudness != null) loudness.setEnabled(!bypass && settings.enabled && currentGainDb > 0.05f);
        } catch (RuntimeException ignored) { }
    }

    public static class DynamicTarget {
        public final float bassDb;
        public final float midDb;
        public final float trebleDb;
        public final String description;

        public DynamicTarget(float bassDb, float midDb, float trebleDb, String description) {
            this.bassDb = bassDb;
            this.midDb = midDb;
            this.trebleDb = trebleDb;
            this.description = description;
        }
    }

    /**
     * Works out the AUTO curve: a starting point from genre words in the title or artist
     * (whole words only, so "Oliver" is not "live" and "Beatles" is not "beat"), then a nudge
     * toward the target tonal balance from the live measurement, when there is one.
     */
    static DynamicTarget evaluateDynamicMusicTarget(String title, String artist, SpectrumBalance live) {
        String words = toWords((title != null ? title : "") + " " + (artist != null ? artist : ""));

        float bass, mid, treble;
        String description;
        if (hasWord(words, "bass", "remix", "club", "dance", "edm", "hip hop", "hiphop", "rap", "trap",
                "techno", "dubstep", "drill", "dj", "reggaeton", "drum and bass", "dnb")) {
            bass = 3.2f; mid = 0.0f; treble = 2.0f; description = "Auto: Club Bass & Punch";
        } else if (hasWord(words, "rock", "metal", "punk", "grunge", "guitar", "ac/dc", "alternative", "drums")) {
            bass = 2.4f; mid = 1.4f; treble = 2.2f; description = "Auto: Rock Energy & Crisp Mids";
        } else if (hasWord(words, "acoustic", "vocal", "coke studio", "unplugged", "ghazal", "piano", "sufi",
                "qawwali", "violin", "classical", "orchestra", "jazz", "blues", "desi", "bollywood", "punjabi",
                "hindi", "urdu", "arijit", "atif", "rahat", "nusrat", "abida")) {
            bass = 1.8f; mid = 3.2f; treble = 1.6f; description = "Auto: Vocal & Melodic Stage";
        } else if (hasWord(words, "podcast", "interview", "audiobook", "episode", "lecture", "sermon", "bayan",
                "talk show")) {
            bass = -2.0f; mid = 4.0f; treble = 0.5f; description = "Auto: Voice & Speech Clarity";
        } else if (hasWord(words, "pop", "r&b", "soul", "synth", "synthwave", "disco", "funk")) {
            bass = 2.2f; mid = 1.2f; treble = 1.8f; description = "Auto: Modern Pop & Vocal Balance";
        } else {
            bass = 1.8f; mid = 1.2f; treble = 1.5f;
            description = (live != null) ? "Auto: Live Music Balance" : "Auto: Type R Balanced Stage";
        }

        if (live != null) {
            float bassFix = live.bassCorrectionDb();
            float trebleFix = live.trebleCorrectionDb();
            bass += bassFix;
            treble += trebleFix;
            if (bassFix > 0.8f) description += " • thin mix, adding bass";
            else if (bassFix < -0.8f) description += " • heavy bass, easing it";
            if (trebleFix > 0.8f) description += " • dull mix, adding clarity";
            else if (trebleFix < -0.8f) description += " • harsh highs, smoothing";
        }
        return new DynamicTarget(bass, mid, treble, description);
    }

    /** Lowercase words separated by single spaces, padded so " word " matches whole words. */
    private static String toWords(String s) {
        StringBuilder sb = new StringBuilder(" ");
        boolean space = true;
        for (char c : s.toLowerCase(Locale.US).toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '&' || c == '/') {
                sb.append(c);
                space = false;
            } else if (!space) {
                sb.append(' ');
                space = true;
            }
        }
        if (!space) sb.append(' ');
        return sb.toString();
    }

    private static boolean hasWord(String words, String... keys) {
        for (String k : keys) {
            if (words.contains(" " + k + " ")) return true;
        }
        return false;
    }

    /**
     * Car-tuned AUTO curve over the equalizer's bands. On a typical 5-band EQ (60, 230, 910 Hz,
     * 3.6, 14 kHz): deep bass at 60 Hz, a trim of the 230 Hz cabin boom, half the mid lift on
     * the body band, full lift at vocal presence, and the treble on the air band.
     */
    static float getDynamicBandDb(int hz, float bass, float mid, float treble) {
        if (hz < 120) return bass;
        if (hz < 350) return bass * 0.3f - BOOM_CUT_DB;
        if (hz < 1500) return mid * 0.5f;
        if (hz < 6000) return mid;
        return treble;
    }

    /**
     * Volume-aware loudness, {bassDb, trebleDb}. Ears lose bass and treble at low volume, so
     * add up to +3 / +1.5 dB below half volume; above three quarters, take up to 2.5 dB of
     * bass out so the stock door speakers do not distort.
     */
    public static float[] loudnessContour(float volumeFraction) {
        float v = clamp(volumeFraction, 0f, 1f);
        if (v < 0.5f) {
            float f = (0.5f - v) / 0.5f;
            return new float[]{3.0f * f, 1.5f * f};
        }
        if (v > 0.75f) {
            float f = (v - 0.75f) / 0.25f;
            return new float[]{-2.5f * f, 0f};
        }
        return new float[]{0f, 0f};
    }

    static float loudnessBandDb(int hz, float bassDb, float trebleDb) {
        if (hz < 120) return bassDb;
        if (hz < 350) return bassDb * 0.3f;
        if (hz >= 6000) return trebleDb;
        return 0f;
    }

    public static float calculateSpeedBassBoost(float speedKmh, boolean speedCompEnabled) {
        if (!speedCompEnabled || speedKmh <= 30f) return 0f;
        return Math.min(2.2f, (speedKmh - 30f) / 45f);
    }

    /** Road-noise bass lift: up to 2.2 dB on LOW, about 3 dB on HIGH. */
    public static float speedBassDb(float speedKmh, int level) {
        if (level <= SPEED_COMP_OFF) return 0f;
        float b = calculateSpeedBassBoost(speedKmh, true);
        return (level >= SPEED_COMP_HIGH) ? b * 1.4f : b;
    }

    /**
     * Road-noise overall lift, like factory speed-sensitive volume: from 30 km/h up to
     * 120 km/h it rises to +2 dB on LOW or +4 dB on HIGH.
     */
    public static float speedGainDb(float speedKmh, int level) {
        if (level <= SPEED_COMP_OFF || speedKmh <= 30f) return 0f;
        float f = Math.min(1f, (speedKmh - 30f) / 90f);
        return f * ((level >= SPEED_COMP_HIGH) ? 4f : 2f);
    }

    public static float profileAdjustmentDb(Profile profile, int hz) {
        if (profile == null) profile = Profile.BALANCED;
        switch (profile) {
            case VOCAL:
                if (hz >= 1000 && hz <= 4000) return 3.2f;
                if (hz >= 150 && hz < 400) return -1.0f;
                if (hz < 150) return 0.5f;
                return 1.2f;
            case BASS_CONTROL:
                if (hz < 160) return 4.0f;
                if (hz >= 250 && hz < 800) return -1.0f;
                if (hz >= 2500 && hz <= 8000) return 1.8f;
                return 0.5f;
            case ROCK_POWER:
                if (hz < 160) return 3.0f;
                if (hz >= 400 && hz < 1200) return -1.5f;
                if (hz >= 1500 && hz <= 6000) return 2.8f;
                return 2.0f;
            case BACKGROUND:
                if (hz < 100) return 2.2f; // Compensate for road rumble
                if (hz >= 2000 && hz <= 5000) return 1.5f;
                return 0.5f;
            case BALANCED:
            default:
                if (hz < 120) return 1.5f;
                if (hz > 5000) return 1.2f;
                return 0f;
        }
    }

    private float manualAdjustmentDb(int hz, Settings s) {
        if (hz < 250) return s.bassDb;
        if (hz < 4000) return s.midsDb;
        return s.trebleDb;
    }

    private void restoreManualEqLevelsLocked() {
        if (equalizer == null || detectedBands.isEmpty()) return;
        try {
            SharedPreferences manualPrefs = appContext.getSharedPreferences("eq", Context.MODE_PRIVATE);
            for (int i = 0; i < detectedBands.size(); i++) {
                BandInfo band = detectedBands.get(i);
                int mB = manualPrefs.getInt("b" + band.index, 0);
                mB = Math.max(bandMinMb, Math.min(bandMaxMb, mB));
                equalizer.setBandLevel(band.index, (short) mB);
                lastAppliedMillibels[i] = (short) mB;
                currentAppliedBandLevels[i] = mB / 100f;
                targetBandDb[i] = mB / 100f;
            }
            bandsDirty = false;
            applyEnableStateLocked();
            statusMessage = "Manual EQ Active";
        } catch (RuntimeException e) {
            Log.w(TAG, "Error restoring manual EQ levels", e);
        }
    }

    private void notifyListenerLocked(boolean force) {
        final OnTuningUpdateListener listener = this.tuningListener;
        if (listener != null) {
            final Status st = getStatusLocked();
            final String mode = currentDetectedMode;
            if (force || !mode.equals(lastNotifiedMode)) {
                lastNotifiedMode = mode;
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        OnTuningUpdateListener l = tuningListener;
                        if (l != null) {
                            l.onTuningUpdated(st, mode);
                        }
                    }
                });
            }
        }
    }

    private Settings loadSettings() {
        Settings s = new Settings();
        try {
            s.profile = Profile.valueOf(prefs.getString("profile", Profile.DYNAMIC_AUTO.name()));
        } catch (IllegalArgumentException ignored) {
            s.profile = Profile.DYNAMIC_AUTO;
        }
        s.strengthPercent = prefs.getInt("strength", 50);
        s.bassDb = prefs.getFloat("bassDb", 0f);
        s.midsDb = prefs.getFloat("midsDb", 0f);
        s.trebleDb = prefs.getFloat("trebleDb", 0f);
        s.headroomProtection = prefs.getBoolean("headroom", true);
        // Before version 3 road-noise compensation was a plain on/off
        s.speedCompLevel = prefs.getInt("speed_comp_level",
                prefs.getBoolean("speed_comp", true) ? SPEED_COMP_LOW : SPEED_COMP_OFF);
        s.levelMatching = prefs.getBoolean("level_match", true);
        s.loudnessCompensation = prefs.getBoolean("loudness_comp", true);
        s.enabled = prefs.getBoolean("enabled", true);
        return sanitize(s);
    }

    private void persistSettings(Settings s) {
        prefs.edit()
                .putInt("version", PREF_VERSION)
                .putString("profile", s.profile.name())
                .putInt("strength", s.strengthPercent)
                .putFloat("bassDb", s.bassDb)
                .putFloat("midsDb", s.midsDb)
                .putFloat("trebleDb", s.trebleDb)
                .putBoolean("headroom", s.headroomProtection)
                .putBoolean("speed_comp", s.speedCompensation)
                .putInt("speed_comp_level", s.speedCompLevel)
                .putBoolean("level_match", s.levelMatching)
                .putBoolean("loudness_comp", s.loudnessCompensation)
                .putBoolean("enabled", s.enabled)
                .apply();
    }

    public static Settings sanitize(Settings input) {
        if (input == null) return new Settings();
        Settings s = input.copy();
        if (s.profile == null) s.profile = Profile.DYNAMIC_AUTO;
        s.strengthPercent = Math.round(clamp(s.strengthPercent, 0, 100));
        s.bassDb = clampFinite(s.bassDb, -6f, 6f);
        s.midsDb = clampFinite(s.midsDb, -6f, 6f);
        s.trebleDb = clampFinite(s.trebleDb, -6f, 6f);
        s.speedCompLevel = Math.max(SPEED_COMP_OFF, Math.min(SPEED_COMP_HIGH, s.speedCompLevel));
        s.speedCompensation = s.speedCompLevel > SPEED_COMP_OFF;
        return s;
    }

    private Status getStatusLocked() {
        boolean attached = equalizer != null;
        boolean hasControl = false;
        boolean enabled = false;
        if (equalizer != null) {
            try {
                hasControl = equalizer.hasControl();
                enabled = equalizer.getEnabled();
            } catch (RuntimeException ignored) { }
        }
        return new Status(attached, enabled, hasControl, statusMessage, currentDetectedMode,
                new ArrayList<>(detectedBands), appliedPreampDb, currentAppliedBandLevels);
    }

    private void releaseEffectLocked() {
        cancelRampLocked();
        if (equalizer != null) {
            try { equalizer.setEnabled(false); } catch (RuntimeException ignored) { }
            try { equalizer.release(); } catch (RuntimeException ignored) { }
        }
        equalizer = null;
        if (loudness != null) {
            try { loudness.setEnabled(false); } catch (RuntimeException ignored) { }
            try { loudness.release(); } catch (RuntimeException ignored) { }
        }
        loudness = null;
        currentGainDb = 0f;
        targetGainDb = 0f;
        detectedBands.clear();
        appliedPreampDb = 0f;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float clampFinite(float v, float min, float max) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return 0f;
        return clamp(v, min, max);
    }
}
