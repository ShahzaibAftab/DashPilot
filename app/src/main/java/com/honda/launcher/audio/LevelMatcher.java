package com.honda.launcher.audio;

/**
 * Evens out loud and quiet songs. It compares the current song's level with the long-run
 * average of everything played and works out a correction of up to +/-4 dB, applied slowly so
 * it never pumps. Fed with one output-level measurement per second.
 *
 * The measured level includes the volume knob on most units, so any volume change starts the
 * learning again; otherwise turning the music up would be read as a loud song and undone.
 */
final class LevelMatcher {
    static final float MAX_GAIN_DB = 4f;
    /**
     * Output quieter than this is the gap between songs, not music. Kept low because the
     * measured level already includes the volume knob: a quiet recording at low volume reads
     * -55 dB or less (true silence reads about -96 dB, and a paused player reports no music).
     */
    static final float SILENCE_DB = -70f;
    /** Seconds of a new song to hear before correcting it. */
    static final int SETTLE_SAMPLES = 5;
    /**
     * A level jump this big is a new song even without a title change (Car Memory, Bluetooth
     * without track info). Music within a song rarely swings this far in one second.
     */
    static final float NEW_SONG_JUMP_DB = 10f;

    private static final float SONG_WEIGHT_SETTLING = 0.3f;
    private static final float SONG_WEIGHT = 0.1f;          // ~10 s memory of the current song
    private static final float REFERENCE_WEIGHT = 0.005f;   // ~3 min memory of everything played
    private static final float GAIN_GLIDE = 0.15f;

    private float songDb = Float.NaN;
    private float previousSongDb = Float.NaN;   // to tell whether the next song is louder
    private float referenceDb = Float.NaN;
    private float gainDb;
    private int songSamples;
    private int referenceSamples;

    void onMeasurement(float rmsDb) {
        if (Float.isNaN(rmsDb) || rmsDb < SILENCE_DB) return;

        float lastDb = !Float.isNaN(songDb) ? songDb : previousSongDb;
        boolean louderSong = false;
        if (!Float.isNaN(lastDb) && Math.abs(rmsDb - lastDb) > NEW_SONG_JUMP_DB) {
            louderSong = rmsDb > lastDb;
            if (!Float.isNaN(songDb)) onTrackChanged();   // level jump without a title change
        }

        songDb = Float.isNaN(songDb) ? rmsDb
                : songDb + (rmsDb - songDb) * (songSamples < SETTLE_SAMPLES ? SONG_WEIGHT_SETTLING : SONG_WEIGHT);
        songSamples++;
        // A true running average at first, so one reading on a loud beat cannot skew it,
        // easing into the slow ~3 min average once there is enough history
        referenceSamples++;
        float refWeight = Math.max(REFERENCE_WEIGHT, 1f / referenceSamples);
        referenceDb = Float.isNaN(referenceDb) ? rmsDb : referenceDb + (rmsDb - referenceDb) * refWeight;

        float target = Math.max(-MAX_GAIN_DB, Math.min(MAX_GAIN_DB, referenceDb - songDb));
        // A loud song right after a quiet, lifted one: drop the lift at once instead of
        // gliding, so the loud song never plays boosted. Lifts are still added slowly.
        if (louderSong && target < gainDb) gainDb = target;
        if (songSamples < SETTLE_SAMPLES) return;

        gainDb += (target - gainDb) * GAIN_GLIDE;
    }

    /** A new song started: measure it afresh, keep the long-run reference and current gain. */
    void onTrackChanged() {
        if (!Float.isNaN(songDb)) previousSongDb = songDb;
        songDb = Float.NaN;
        songSamples = 0;
    }

    /** The user changed the volume: relearn both levels; the gain glides back to 0. */
    void onVolumeChanged() {
        songDb = Float.NaN;
        previousSongDb = Float.NaN;   // levels before and after a volume change do not compare
        referenceDb = Float.NaN;
        songSamples = 0;
        referenceSamples = 0;
    }

    void reset() {
        onVolumeChanged();
        gainDb = 0f;
    }

    float getGainDb() {
        return gainDb;
    }
}
