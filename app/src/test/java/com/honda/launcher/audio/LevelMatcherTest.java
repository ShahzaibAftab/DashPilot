package com.honda.launcher.audio;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for LevelMatcher: evening out loud and quiet songs without fighting the volume knob.
 */
public class LevelMatcherTest {

    private static void play(LevelMatcher m, float rmsDb, int seconds) {
        for (int i = 0; i < seconds; i++) m.onMeasurement(rmsDb);
    }

    @Test
    public void testQuietSongAfterLoudOnesIsLifted() {
        LevelMatcher m = new LevelMatcher();
        play(m, -12f, 180);           // three minutes of loud music sets the reference
        m.onTrackChanged();
        play(m, -20f, 60);            // a quiet, older recording
        assertTrue("Quiet song lifted", m.getGainDb() > 2f);
        assertTrue("Never more than the cap", m.getGainDb() <= LevelMatcher.MAX_GAIN_DB + 0.001f);
    }

    @Test
    public void testLoudSongAfterQuietOnesIsLowered() {
        LevelMatcher m = new LevelMatcher();
        play(m, -22f, 180);
        m.onTrackChanged();
        play(m, -12f, 60);
        assertTrue(m.getGainDb() < -2f);
        assertTrue(m.getGainDb() >= -LevelMatcher.MAX_GAIN_DB - 0.001f);
    }

    @Test
    public void testFirstSongIsNotCorrected() {
        // Music level swings with the beat; the first reading landing on a peak must not
        // bias the reference (seen on the emulator as -1.9 dB on the very first song)
        LevelMatcher m = new LevelMatcher();
        for (int i = 0; i < 60; i++) m.onMeasurement(i % 2 == 0 ? -10f : -14f);
        assertEquals(0f, m.getGainDb(), 0.6f);
    }

    @Test
    public void testLoudSongAfterLiftedQuietSongIsCutAtOnce() {
        // Seen on the emulator: Car Memory moved from a quiet song (lifted +4 dB) to a loud one
        // without a title change, and the loud song played boosted for several seconds
        LevelMatcher m = new LevelMatcher();
        play(m, -26f, 30);
        play(m, -50f, 40);
        assertTrue(m.getGainDb() > 3.5f);

        m.onMeasurement(-26f);       // the very first second of the loud song
        assertTrue("Lift dropped immediately", m.getGainDb() < 0.5f);
    }

    @Test
    public void testLoudSongCutAtOnceAlsoWithTitleChange() {
        LevelMatcher m = new LevelMatcher();
        play(m, -26f, 30);
        play(m, -50f, 40);
        m.onTrackChanged();          // the player announced the new song first
        m.onMeasurement(-26f);
        assertTrue(m.getGainDb() < 0.5f);
    }

    @Test
    public void testQuieterSongStillLiftedSlowly() {
        LevelMatcher m = new LevelMatcher();
        play(m, -26f, 60);
        m.onMeasurement(-50f);       // first second of a much quieter song
        assertEquals("No sudden lift", 0f, m.getGainDb(), 0.1f);
    }

    @Test
    public void testSilenceIsIgnored() {
        LevelMatcher m = new LevelMatcher();
        play(m, -15f, 60);
        float before = m.getGainDb();
        play(m, -96f, 30);            // gap between songs: digital silence
        assertEquals(before, m.getGainDb(), 0.0001f);
    }

    @Test
    public void testQuietSongAtLowVolumeStillCounts() {
        // Found on the emulator: a -35 dBFS recording with the knob at 11/15 measured about
        // -45 dB and was mistaken for silence, so nothing adapted to it
        LevelMatcher m = new LevelMatcher();
        play(m, -35f, 120);
        m.onTrackChanged();
        play(m, -55f, 60);
        assertTrue("Very quiet song is still measured and lifted", m.getGainDb() > 2f);
    }

    @Test
    public void testNoCorrectionUntilSongSettles() {
        LevelMatcher m = new LevelMatcher();
        play(m, -12f, 180);
        m.onTrackChanged();
        play(m, -20f, LevelMatcher.SETTLE_SAMPLES - 1);
        assertEquals("Waits a few seconds into a new song", 0f, m.getGainDb(), 0.2f);
    }

    @Test
    public void testVolumeChangeIsNotUndone() {
        LevelMatcher m = new LevelMatcher();
        play(m, -20f, 120);
        // User turns the knob up 8 dB: the louder output must not be read as a loud song
        m.onVolumeChanged();
        play(m, -12f, 60);
        assertEquals(0f, m.getGainDb(), 0.3f);
    }

    @Test
    public void testResetClearsGain() {
        LevelMatcher m = new LevelMatcher();
        play(m, -12f, 180);
        m.onTrackChanged();
        play(m, -20f, 60);
        m.reset();
        assertEquals(0f, m.getGainDb(), 0.0001f);
    }
}
