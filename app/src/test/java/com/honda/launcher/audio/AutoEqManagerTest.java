package com.honda.launcher.audio;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for AutoEqManager dynamic audio tuning logic.
 * Tests:
 * 1. Default settings initialization and copy functionality.
 * 2. Parameter clamping & sanitization (strength, dB values, NaN/Infinity safety).
 * 3. Dynamic genre & track title/artist acoustic evaluation.
 * 4. Live tonal-balance corrections, whole-word genre cues and the car-tuned AUTO curve.
 * 5. Road noise levels, volume-aware loudness and gliding changes.
 */
public class AutoEqManagerTest {

    @Test
    public void testDefaultSettings() {
        AutoEqManager.Settings s = new AutoEqManager.Settings();
        assertEquals(AutoEqManager.Profile.DYNAMIC_AUTO, s.profile);
        assertEquals(50, s.strengthPercent);
        assertEquals(0f, s.bassDb, 0.001f);
        assertEquals(0f, s.midsDb, 0.001f);
        assertEquals(0f, s.trebleDb, 0.001f);
        assertTrue(s.headroomProtection);
        assertTrue(s.speedCompensation);
        assertTrue(s.enabled);

        AutoEqManager.Settings copy = s.copy();
        assertNotSame(s, copy);
        assertEquals(s.profile, copy.profile);
        assertEquals(s.strengthPercent, copy.strengthPercent);
    }

    @Test
    public void testProfileNames() {
        assertEquals("Auto (Live Music)", AutoEqManager.Profile.DYNAMIC_AUTO.displayName);
        assertEquals("Balanced (Studio)", AutoEqManager.Profile.BALANCED.displayName);
        assertEquals("Vocal & Clarity", AutoEqManager.Profile.VOCAL.displayName);
        assertEquals("Bass & Punch", AutoEqManager.Profile.BASS_CONTROL.displayName);
        assertEquals("Rock & Energy", AutoEqManager.Profile.ROCK_POWER.displayName);
        assertEquals("Highway Acoustic", AutoEqManager.Profile.BACKGROUND.displayName);
    }

    @Test
    public void testEvaluateDynamicMusicTarget_GenreKeywords() {
        // EDM / Club / Bass
        AutoEqManager.DynamicTarget edm = AutoEqManager.evaluateDynamicMusicTarget(
                "Midnight Club Remix", "DJ Tiesto", null);
        assertTrue(edm.description.contains("Bass"));
        assertTrue(edm.bassDb > 2.0f);

        // Rock / Metal
        AutoEqManager.DynamicTarget rock = AutoEqManager.evaluateDynamicMusicTarget(
                "Back in Black", "AC/DC", null);
        assertTrue(rock.description.contains("Rock"));
        assertTrue(rock.midDb > 0.5f);

        // Vocal / Acoustic / Coke Studio
        AutoEqManager.DynamicTarget vocal = AutoEqManager.evaluateDynamicMusicTarget(
                "Pasoori (Coke Studio)", "Ali Sethi", null);
        assertTrue(vocal.description.contains("Vocal"));
        assertTrue(vocal.midDb >= 3.0f);

        // Podcast / Speech
        AutoEqManager.DynamicTarget speech = AutoEqManager.evaluateDynamicMusicTarget(
                "Daily News Podcast Episode 42", "Host", null);
        assertTrue(speech.description.contains("Voice") || speech.description.contains("Speech"));
        assertTrue(speech.bassDb < 0f); // Cuts rumble
    }

    @Test
    public void testEvaluateDynamicMusicTarget_LiveBalance() {
        // Thin recording (little bass): AUTO adds bass beyond the neutral starting point
        AutoEqManager.DynamicTarget thin = AutoEqManager.evaluateDynamicMusicTarget(
                "Track 01", "Artist", new SpectrumBalance(-2f, -9f));
        assertTrue(thin.bassDb > 1.8f + 2f);
        assertTrue(thin.description.contains("adding bass"));

        // Bass-heavy recording: AUTO eases the bass instead of boosting it further
        AutoEqManager.DynamicTarget heavy = AutoEqManager.evaluateDynamicMusicTarget(
                "Track 02", "Artist", new SpectrumBalance(14f, -9f));
        assertTrue(heavy.bassDb < 0f);
        assertTrue(heavy.description.contains("heavy bass"));

        // Harsh, bright recording: treble is smoothed
        AutoEqManager.DynamicTarget bright = AutoEqManager.evaluateDynamicMusicTarget(
                "Track 03", "Artist", new SpectrumBalance(6f, 0f));
        assertTrue(bright.trebleDb < 0f);
        assertTrue(bright.description.contains("harsh highs"));

        // Already at the target balance: no correction on top of the neutral curve
        AutoEqManager.DynamicTarget onTarget = AutoEqManager.evaluateDynamicMusicTarget(
                "Track 04", "Artist", new SpectrumBalance(SpectrumBalance.TARGET_BASS_TILT_DB,
                        SpectrumBalance.TARGET_TREBLE_TILT_DB));
        assertEquals(1.8f, onTarget.bassDb, 0.001f);
        assertEquals(1.5f, onTarget.trebleDb, 0.001f);
    }

    @Test
    public void testGenreCuesMatchWholeWordsOnly() {
        String neutral = "Auto: Type R Balanced Stage";
        // "Oliver" contains "live", "Beatles" contains "beat", "Radio Ga Ga" / "voice" were speech cues
        assertEquals(neutral, AutoEqManager.evaluateDynamicMusicTarget("Oliver Twist", "Unknown", null).description);
        assertEquals(neutral, AutoEqManager.evaluateDynamicMusicTarget("Hey Jude", "The Beatles", null).description);
        assertEquals(neutral, AutoEqManager.evaluateDynamicMusicTarget("Radio Ga Ga", "Queen", null).description);
        assertEquals(neutral, AutoEqManager.evaluateDynamicMusicTarget("Teri Voice", "Unknown", null).description);
        // "rapture" is not "rap", "popcorn" is not "pop"
        assertEquals(neutral, AutoEqManager.evaluateDynamicMusicTarget("Rapture", "Popcorn", null).description);

        // Real cues still match, including hyphenated and symbol forms
        assertTrue(AutoEqManager.evaluateDynamicMusicTarget("Hip-Hop Mix", "x", null).description.contains("Club"));
        assertTrue(AutoEqManager.evaluateDynamicMusicTarget("Smooth", "R&B Hits", null).description.contains("Pop"));
    }

    @Test
    public void testCarTunedAutoCurve() {
        // 5-band EQ: 60 Hz gets the bass, 230 Hz is trimmed for cabin boom, 3.6 kHz gets vocal presence
        assertEquals(3f, AutoEqManager.getDynamicBandDb(60, 3f, 2f, 1f), 0.001f);
        assertTrue(AutoEqManager.getDynamicBandDb(230, 3f, 2f, 1f) < 0.5f);
        assertEquals(1f, AutoEqManager.getDynamicBandDb(910, 3f, 2f, 1f), 0.001f);
        assertEquals(2f, AutoEqManager.getDynamicBandDb(3600, 3f, 2f, 1f), 0.001f);
        assertEquals(1f, AutoEqManager.getDynamicBandDb(14000, 3f, 2f, 1f), 0.001f);
    }

    @Test
    public void testVolumeAwareLoudness() {
        float[] quiet = AutoEqManager.loudnessContour(0.1f);
        assertTrue("Low volume adds bass", quiet[0] > 2f);
        assertTrue("Low volume adds treble", quiet[1] > 1f);

        float[] normal = AutoEqManager.loudnessContour(0.6f);
        assertEquals(0f, normal[0], 0.001f);
        assertEquals(0f, normal[1], 0.001f);

        float[] loud = AutoEqManager.loudnessContour(1.0f);
        assertEquals("Full volume eases bass to spare the speakers", -2.5f, loud[0], 0.001f);

        // Out-of-range input is clamped
        assertEquals(3.0f, AutoEqManager.loudnessContour(-1f)[0], 0.001f);
    }

    @Test
    public void testRoadNoiseLevels() {
        assertEquals(0f, AutoEqManager.speedGainDb(100f, AutoEqManager.SPEED_COMP_OFF), 0.001f);
        assertEquals(0f, AutoEqManager.speedGainDb(25f, AutoEqManager.SPEED_COMP_HIGH), 0.001f);
        assertEquals(2f, AutoEqManager.speedGainDb(120f, AutoEqManager.SPEED_COMP_LOW), 0.001f);
        assertEquals(4f, AutoEqManager.speedGainDb(150f, AutoEqManager.SPEED_COMP_HIGH), 0.001f);
        assertEquals(1f, AutoEqManager.speedGainDb(75f, AutoEqManager.SPEED_COMP_LOW), 0.001f);

        assertEquals(2.0f, AutoEqManager.speedBassDb(120f, AutoEqManager.SPEED_COMP_LOW), 0.01f);
        assertEquals(2.8f, AutoEqManager.speedBassDb(120f, AutoEqManager.SPEED_COMP_HIGH), 0.01f);
        assertEquals(0f, AutoEqManager.speedBassDb(120f, AutoEqManager.SPEED_COMP_OFF), 0.001f);
    }

    @Test
    public void testChangesGlideInSteps() {
        assertEquals(0.25f, AutoEqManager.stepToward(0f, 3f, AutoEqManager.RAMP_STEP_DB), 0.0001f);
        assertEquals(-0.25f, AutoEqManager.stepToward(0f, -3f, AutoEqManager.RAMP_STEP_DB), 0.0001f);
        assertEquals("Lands exactly on the target", 3f, AutoEqManager.stepToward(2.9f, 3f, AutoEqManager.RAMP_STEP_DB), 0.0001f);

        // A 3 dB profile change takes 12 steps of 100 ms
        float v = 0f;
        int steps = 0;
        while (v != 3f) { v = AutoEqManager.stepToward(v, 3f, AutoEqManager.RAMP_STEP_DB); steps++; }
        assertEquals(12, steps);
    }

    @Test
    public void testSpeedCompLevelSanitized() {
        AutoEqManager.Settings s = new AutoEqManager.Settings();
        assertEquals(AutoEqManager.SPEED_COMP_LOW, s.speedCompLevel);
        assertTrue(s.levelMatching);
        assertTrue(s.loudnessCompensation);

        s.speedCompLevel = 7;
        AutoEqManager.Settings clean = AutoEqManager.sanitize(s);
        assertEquals(AutoEqManager.SPEED_COMP_HIGH, clean.speedCompLevel);
        assertTrue(clean.speedCompensation);

        s.speedCompLevel = AutoEqManager.SPEED_COMP_OFF;
        assertFalse("The legacy flag follows the level", AutoEqManager.sanitize(s).speedCompensation);
    }

    @Test
    public void testNullSafety() {
        AutoEqManager.DynamicTarget dt = AutoEqManager.evaluateDynamicMusicTarget(null, null, null);
        assertNotNull(dt);
        assertNotNull(dt.description);

        AutoEqManager.Settings nullSettings = AutoEqManager.sanitize(null);
        assertNotNull(nullSettings);
        assertEquals(AutoEqManager.Profile.DYNAMIC_AUTO, nullSettings.profile);
    }

    @Test
    public void testSanitizationClamping() {
        AutoEqManager.Settings s = new AutoEqManager.Settings();
        s.strengthPercent = 150;
        s.bassDb = 12.0f;
        s.midsDb = -15.0f;
        s.trebleDb = Float.NaN;
        s.profile = null;

        AutoEqManager.Settings sanitized = AutoEqManager.sanitize(s);
        assertEquals(100, sanitized.strengthPercent);
        assertEquals(6.0f, sanitized.bassDb, 0.001f);
        assertEquals(-6.0f, sanitized.midsDb, 0.001f);
        assertEquals(0.0f, sanitized.trebleDb, 0.001f);
        assertEquals(AutoEqManager.Profile.DYNAMIC_AUTO, sanitized.profile);

        // Test negative strength & infinite dB
        s.strengthPercent = -20;
        s.bassDb = Float.POSITIVE_INFINITY;
        s.midsDb = Float.NEGATIVE_INFINITY;
        sanitized = AutoEqManager.sanitize(s);
        assertEquals(0, sanitized.strengthPercent);
        assertEquals(0.0f, sanitized.bassDb, 0.001f);
        assertEquals(0.0f, sanitized.midsDb, 0.001f);
    }

    @Test
    public void testSpeedCompensationCurve() {
        // At <= 30 km/h: 0 dB boost
        assertEquals(0f, AutoEqManager.calculateSpeedBassBoost(0f, true), 0.001f);
        assertEquals(0f, AutoEqManager.calculateSpeedBassBoost(30f, true), 0.001f);

        // 75 km/h: (75 - 30) / 45 = 1.0 dB
        assertEquals(1.0f, AutoEqManager.calculateSpeedBassBoost(75f, true), 0.01f);

        // 120 km/h: (120 - 30) / 45 = 2.0 dB
        assertEquals(2.0f, AutoEqManager.calculateSpeedBassBoost(120f, true), 0.01f);

        // > 130 km/h: clamped to max 2.2 dB
        assertEquals(2.2f, AutoEqManager.calculateSpeedBassBoost(160f, true), 0.01f);
        assertEquals(2.2f, AutoEqManager.calculateSpeedBassBoost(250f, true), 0.01f);

        // Negative speed safe
        assertEquals(0f, AutoEqManager.calculateSpeedBassBoost(-40f, true), 0.001f);

        // Disabled speed compensation
        assertEquals(0f, AutoEqManager.calculateSpeedBassBoost(100f, false), 0.001f);
    }

    @Test
    public void testProfileAdjustmentsAcrossFrequencies() {
        // VOCAL profile: boosts 1000 - 4000 Hz, cuts lower-mid muddiness
        assertEquals(3.2f, AutoEqManager.profileAdjustmentDb(AutoEqManager.Profile.VOCAL, 2000), 0.01f);
        assertEquals(-1.0f, AutoEqManager.profileAdjustmentDb(AutoEqManager.Profile.VOCAL, 250), 0.01f);

        // BASS_CONTROL profile: boosts sub-bass (<160 Hz)
        assertEquals(4.0f, AutoEqManager.profileAdjustmentDb(AutoEqManager.Profile.BASS_CONTROL, 80), 0.01f);
        assertEquals(1.8f, AutoEqManager.profileAdjustmentDb(AutoEqManager.Profile.BASS_CONTROL, 4000), 0.01f);

        // ROCK_POWER profile: punchy low-end and crisp treble
        assertEquals(3.0f, AutoEqManager.profileAdjustmentDb(AutoEqManager.Profile.ROCK_POWER, 100), 0.01f);
        assertEquals(2.8f, AutoEqManager.profileAdjustmentDb(AutoEqManager.Profile.ROCK_POWER, 3000), 0.01f);

        // BACKGROUND profile: road noise compensation
        assertEquals(2.2f, AutoEqManager.profileAdjustmentDb(AutoEqManager.Profile.BACKGROUND, 80), 0.01f);

        // Null profile safe fallback
        assertEquals(0f, AutoEqManager.profileAdjustmentDb(null, 1000), 0.01f);
    }

    @Test
    public void testSpecialCharactersAndUnicodeMetadata() {
        // Emojis and remix keywords
        AutoEqManager.DynamicTarget edmEmoji = AutoEqManager.evaluateDynamicMusicTarget(
                "🔥 Ultra Bass Remix 🎧", "DJ Snake", null);
        assertTrue(edmEmoji.description.contains("Bass") || edmEmoji.description.contains("Club"));

        // Urdu / Coke Studio
        AutoEqManager.DynamicTarget cokeStudio = AutoEqManager.evaluateDynamicMusicTarget(
                "کوک اسٹوڈیو (Coke Studio)", "Abida Parveen", null);
        assertTrue(cokeStudio.description.contains("Vocal"));

        // Mixed case with leading/trailing spaces
        AutoEqManager.DynamicTarget rockSpaced = AutoEqManager.evaluateDynamicMusicTarget(
                "   THUNDERSTRUCK   ", "   ac/dc   ", null);
        assertTrue(rockSpaced.description.contains("Rock"));
    }
}
