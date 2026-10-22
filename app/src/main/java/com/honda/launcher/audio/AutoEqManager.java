package com.honda.launcher.audio;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.Equalizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Intelligent AutoEQ & Dynamic Audio Tuning Controller for Android 7.1+ head units.
 *
 * Features:
 * 1. Multi-Profile Auto-Tuning: BALANCED, VOCAL, BASS_CONTROL, ROCK_POWER, BACKGROUND, and DYNAMIC_AUTO.
 * 2. Real-Time Music Analysis: Automatically detects music genre/style from track metadata
 *    and live frequency spectrum (FFT) to auto-adjust bass, vocal presence, and treble sparkle.
 * 3. Road-Noise Speed Adaptation: Compensates for Honda City cabin noise at highway speeds.
 * 4. Digital Headroom & Anti-Clipping: Protects output stage against digital distortion.
 * 5. Universal Session Support: Works seamlessly on both Global Mix (Session 0) and player-specific sessions.
 */
public final class AutoEqManager implements AutoCloseable {
    private static final String TAG = "DashPilotAutoEQ";
    private static final String PREFS = "dashpilot_autoeq";
    private static final int PREF_VERSION = 2;
    private static final int MAX_REQUESTED_BOOST_DB = 6;

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
        public boolean speedCompensation = true;
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
    private float appliedPreampDb = 0f;
    private String statusMessage = "Not attached to an audio session";
    private String currentDetectedMode = "Dynamic (Awaiting audio)";
    private float[] currentAppliedBandLevels = new float[0];
    private short[] lastAppliedMillibels = new short[0];
    private String lastNotifiedMode = "";

    // Live music spectrum energy tracking (smoothed)
    private float smoothedLowEnergy = 0.5f;
    private float smoothedMidEnergy = 0.5f;
    private float smoothedHighEnergy = 0.5f;

    // Vehicle speed compensation
    private float currentVehicleSpeedKmh = 0f;

    // Last known track metadata
    private String lastTrackTitle = "";
    private String lastTrackArtist = "";

    private OnTuningUpdateListener tuningListener;

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
            equalizer.setEnabled(settings.enabled);
            detectBandsLocked();
            if (detectedBands.isEmpty()) {
                statusMessage = "Equalizer created but no usable EQ bands reported";
                releaseEffectLocked();
                return getStatusLocked();
            }
            statusMessage = "Attached to audio session " + sessionId;
            applySettingsLocked();
            return getStatusLocked();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not attach Equalizer to session " + sessionId, e);
            statusMessage = "Equalizer unavailable: " + e.getClass().getSimpleName();
            releaseEffectLocked();
            return getStatusLocked();
        }
    }

    /**
     * Analyzes live FFT spectrum data from Android Visualizer to adapt EQ in real-time.
     */
    public synchronized void analyzeSpectrum(byte[] fft) {
        if (!settings.enabled || settings.profile != Profile.DYNAMIC_AUTO || fft == null || fft.length < 16) {
            return;
        }

        int n = fft.length / 2;
        float lowSum = 0f, midSum = 0f, highSum = 0f;
        int lowCount = 0, midCount = 0, highCount = 0;

        for (int k = 1; k < n; k++) {
            float mag = (float) Math.hypot(fft[2 * k], fft[2 * k + 1]);
            if (k < n * 0.15f) {
                lowSum += mag;
                lowCount++;
            } else if (k < n * 0.60f) {
                midSum += mag;
                midCount++;
            } else {
                highSum += mag;
                highCount++;
            }
        }

        float avgLow = lowCount > 0 ? (lowSum / lowCount) : 0f;
        float avgMid = midCount > 0 ? (midSum / midCount) : 0f;
        float avgHigh = highCount > 0 ? (highSum / highCount) : 0f;

        // Smooth with exponential moving average to prevent acoustic pumping
        smoothedLowEnergy = smoothedLowEnergy * 0.85f + avgLow * 0.15f;
        smoothedMidEnergy = smoothedMidEnergy * 0.85f + avgMid * 0.15f;
        smoothedHighEnergy = smoothedHighEnergy * 0.85f + avgHigh * 0.15f;

        // Re-tune if spectrum dynamics have changed significantly
        applySettingsLocked();
        notifyListenerLocked(false);
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
            if (settings.enabled && settings.speedCompensation) {
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
        audioSessionId = AudioEffect.ERROR_BAD_VALUE;
        statusMessage = "Detached";
    }

    private void detectBandsLocked() {
        detectedBands.clear();
        if (equalizer == null) return;
        try {
            short count = equalizer.getNumberOfBands();
            short[] range = equalizer.getBandLevelRange(); // millibels (100 mB = 1 dB)
            int minDb = (int) Math.ceil(range[0] / 100.0);
            int maxDb = (int) Math.floor(range[1] / 100.0);
            for (short i = 0; i < count; i++) {
                int centerMilliHz = equalizer.getCenterFreq(i);
                int centerHz = Math.max(1, centerMilliHz / 1000);
                detectedBands.add(new BandInfo(i, centerHz, minDb, maxDb));
            }
            currentAppliedBandLevels = new float[detectedBands.size()];
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
                restoreManualEqLevelsLocked();
                appliedPreampDb = 0f;
                currentDetectedMode = "Manual EQ Active";
                return;
            }

            float[] requestedDb = new float[detectedBands.size()];
            float maxPositiveDb = 0f;
            float strength = clamp(settings.strengthPercent, 0, 100) / 100f;

            Profile activeProfile = settings.profile;
            String modeName = activeProfile.displayName;

            // If DYNAMIC_AUTO, dynamically determine acoustic curve from metadata & spectrum
            float dynBass = 0f, dynMid = 0f, dynTreble = 0f;
            if (activeProfile == Profile.DYNAMIC_AUTO) {
                DynamicTarget dt = evaluateDynamicMusicTarget(lastTrackTitle, lastTrackArtist,
                        smoothedLowEnergy, smoothedMidEnergy, smoothedHighEnergy, currentVehicleSpeedKmh);
                dynBass = dt.bassDb;
                dynMid = dt.midDb;
                dynTreble = dt.trebleDb;
                modeName = dt.description;
            }

            currentDetectedMode = modeName;

            // Compute speed-dependent cabin noise compensation
            float speedBassBoost = calculateSpeedBassBoost(currentVehicleSpeedKmh, settings.speedCompensation);

            for (int i = 0; i < detectedBands.size(); i++) {
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
                target = clamp(target, -12f, MAX_REQUESTED_BOOST_DB);
                requestedDb[i] = target;
                if (target > maxPositiveDb) maxPositiveDb = target;
            }

            // Headroom protection: attenuate all bands by max positive boost to prevent digital clipping
            float compensationDb = settings.headroomProtection ? maxPositiveDb : 0f;
            appliedPreampDb = -compensationDb;

            short[] range = equalizer.getBandLevelRange();
            int minMb = range[0];
            int maxMb = range[1];

            if (currentAppliedBandLevels.length != detectedBands.size()) {
                currentAppliedBandLevels = new float[detectedBands.size()];
            }

            boolean levelsChanged = (lastAppliedMillibels.length != detectedBands.size());
            if (lastAppliedMillibels.length != detectedBands.size()) {
                lastAppliedMillibels = new short[detectedBands.size()];
            }

            for (int i = 0; i < detectedBands.size(); i++) {
                BandInfo band = detectedBands.get(i);
                float finalDb = requestedDb[i] - compensationDb;
                currentAppliedBandLevels[i] = finalDb;
                int millibels = Math.round(finalDb * 100f);
                millibels = Math.max(minMb, Math.min(maxMb, millibels));
                if (levelsChanged || Math.abs(lastAppliedMillibels[i] - millibels) >= 15) {
                    equalizer.setBandLevel(band.index, (short) millibels);
                    lastAppliedMillibels[i] = (short) millibels;
                    levelsChanged = true;
                }
            }

            equalizer.setEnabled(true);
            statusMessage = "Auto-tuned for " + modeName;
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not apply AutoEQ settings", e);
            statusMessage = "Effect error; settings preserved";
        }
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
     * Intelligently classifies track and frequency character to formulate target EQ curves.
     */
    public static DynamicTarget evaluateDynamicMusicTarget(String title, String artist,
                                                           float lowE, float midE, float highE,
                                                           float speedKmh) {
        String combined = ((title != null ? title : "") + " " + (artist != null ? artist : "")).toLowerCase(Locale.US);

        // 1. Check title/artist genre cues
        if (combined.contains("bass") || combined.contains("remix") || combined.contains("club") ||
            combined.contains("dance") || combined.contains("edm") || combined.contains("hip hop") ||
            combined.contains("rap") || combined.contains("beat")) {
            return new DynamicTarget(3.5f, -0.8f, 2.0f, "Auto: Club Bass & Punch");
        }

        if (combined.contains("rock") || combined.contains("metal") || combined.contains("guitar") ||
            combined.contains("ac/dc") || combined.contains("live") || combined.contains("punk")) {
            return new DynamicTarget(2.5f, 1.2f, 2.5f, "Auto: Rock Energy");
        }

        if (combined.contains("acoustic") || combined.contains("vocal") || combined.contains("coke studio") ||
            combined.contains("unplugged") || combined.contains("ghazal") || combined.contains("piano") ||
            combined.contains("sufi") || combined.contains("qawwali")) {
            return new DynamicTarget(1.2f, 3.2f, 1.5f, "Auto: Vocal & Acoustic Clarity");
        }

        if (combined.contains("podcast") || combined.contains("interview") || combined.contains("talk") ||
            combined.contains("news") || combined.contains("radio")) {
            return new DynamicTarget(-2.0f, 4.0f, 0.5f, "Auto: Voice & Speech Focus");
        }

        // 2. Fall back to live spectrum energy dynamics
        if (lowE > 1.4f * (midE + 0.1f)) {
            // Already bass-heavy; clean up lower-mid muddiness and sparkle highs
            return new DynamicTarget(1.0f, 0.5f, 2.0f, "Auto: Deep Spectrum Balance");
        } else if (midE > 1.3f * (lowE + 0.1f)) {
            // Vocal/mid dominant; warm up sub-bass and lift high sheen
            return new DynamicTarget(2.5f, 1.0f, 1.8f, "Auto: Warm Vocal Stage");
        } else if (highE > 1.3f * (midE + 0.1f)) {
            // Bright track; anchor low-end and tame harsh sibilance
            return new DynamicTarget(2.2f, 0.5f, -0.5f, "Auto: Smooth High-Fidelity");
        }

        return new DynamicTarget(1.8f, 1.2f, 1.5f, "Auto: Type R Balanced Stage");
    }

    private static float getDynamicBandDb(int hz, float bass, float mid, float treble) {
        if (hz < 250) return bass;
        if (hz < 3500) return mid;
        return treble;
    }

    public static float calculateSpeedBassBoost(float speedKmh, boolean speedCompEnabled) {
        if (!speedCompEnabled || speedKmh <= 30f) return 0f;
        return Math.min(2.2f, (speedKmh - 30f) / 45f);
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
            short[] range = equalizer.getBandLevelRange();
            int minMb = range[0];
            int maxMb = range[1];
            if (lastAppliedMillibels.length != detectedBands.size()) {
                lastAppliedMillibels = new short[detectedBands.size()];
            }
            if (currentAppliedBandLevels.length != detectedBands.size()) {
                currentAppliedBandLevels = new float[detectedBands.size()];
            }
            for (int i = 0; i < detectedBands.size(); i++) {
                BandInfo band = detectedBands.get(i);
                int mB = manualPrefs.getInt("b" + band.index, 0);
                mB = Math.max(minMb, Math.min(maxMb, mB));
                equalizer.setBandLevel(band.index, (short) mB);
                lastAppliedMillibels[i] = (short) mB;
                currentAppliedBandLevels[i] = mB / 100f;
            }
            equalizer.setEnabled(true);
            statusMessage = "Manual EQ Active";
        } catch (RuntimeException e) {
            Log.w(TAG, "Error restoring manual EQ levels", e);
        }
    }

    private void notifyListenerLocked() {
        notifyListenerLocked(false);
    }

    private void notifyListenerLocked(boolean force) {
        if (tuningListener != null) {
            final Status st = getStatusLocked();
            final String mode = currentDetectedMode;
            if (force || !mode.equals(lastNotifiedMode)) {
                lastNotifiedMode = mode;
                tuningListener.onTuningUpdated(st, mode);
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
        s.speedCompensation = prefs.getBoolean("speed_comp", true);
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
        if (equalizer != null) {
            try { equalizer.setEnabled(false); } catch (RuntimeException ignored) { }
            try { equalizer.release(); } catch (RuntimeException ignored) { }
        }
        equalizer = null;
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
