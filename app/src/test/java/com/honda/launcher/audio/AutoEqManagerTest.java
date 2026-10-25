package com.honda.launcher.audio;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for AutoEqManager dynamic audio tuning logic.
 * Tests:
 * 1. Default settings initialization and copy functionality.
 * 2. Parameter clamping & sanitization (strength, dB values, NaN/Infinity safety).
 * 3. Dynamic genre & track title/artist acoustic evaluation.
 * 4. Live spectrum energy balance logic (low vs mid vs high dominance).
 * 5. Road noise speed compensation calculations.
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
                "Midnight Club Remix", "DJ Tiesto", 0.5f, 0.5f, 0.5f, 0f);
        assertTrue(edm.description.contains("Bass"));
        assertTrue(edm.bassDb > 2.0f);

        // Rock / Metal
        AutoEqManager.DynamicTarget rock = AutoEqManager.evaluateDynamicMusicTarget(
                "Back in Black", "AC/DC", 0.5f, 0.5f, 0.5f, 0f);
        assertTrue(rock.description.contains("Rock"));
        assertTrue(rock.midDb > 0.5f);

        // Vocal / Acoustic / Coke Studio
        AutoEqManager.DynamicTarget vocal = AutoEqManager.evaluateDynamicMusicTarget(
                "Pasoori (Coke Studio)", "Ali Sethi", 0.5f, 0.5f, 0.5f, 0f);
        assertTrue(vocal.description.contains("Vocal"));
        assertTrue(vocal.midDb >= 3.0f);

        // Podcast / Speech
        AutoEqManager.DynamicTarget speech = AutoEqManager.evaluateDynamicMusicTarget(
                "Daily News Podcast Episode 42", "Host", 0.5f, 0.5f, 0.5f, 0f);
        assertTrue(speech.description.contains("Voice") || speech.description.contains("Speech"));
        assertTrue(speech.bassDb < 0f); // Cuts rumble
    }

    @Test
    public void testEvaluateDynamicMusicTarget_SpectrumFallback() {
        // High bass energy in live FFT
        AutoEqManager.DynamicTarget heavyBass = AutoEqManager.evaluateDynamicMusicTarget(
                "Unknown Song", "Unknown Artist", 1.8f, 0.4f, 0.4f, 0f);
        assertTrue(heavyBass.description.contains("Balance") || heavyBass.description.contains("Deep"));

        // Mid-dominant acoustic energy
        AutoEqManager.DynamicTarget vocalHeavy = AutoEqManager.evaluateDynamicMusicTarget(
                "Track 01", "Artist", 0.2f, 1.2f, 0.3f, 0f);
        assertTrue(vocalHeavy.description.contains("Vocal") || vocalHeavy.description.contains("Warm"));

        // Treble-dominant bright track
        AutoEqManager.DynamicTarget bright = AutoEqManager.evaluateDynamicMusicTarget(
                "Audio", "Soloist", 0.3f, 0.4f, 1.3f, 0f);
        assertTrue(bright.description.contains("Smooth") || bright.description.contains("Fidelity"));
    }

    @Test
    public void testNullSafety() {
        AutoEqManager.DynamicTarget dt = AutoEqManager.evaluateDynamicMusicTarget(
                null, null, 0f, 0f, 0f, 0f);
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
                "🔥 Ultra Bass Remix 🎧", "DJ Snake", 0.5f, 0.5f, 0.5f, 0f);
        assertTrue(edmEmoji.description.contains("Bass") || edmEmoji.description.contains("Club"));

        // Urdu / Coke Studio
        AutoEqManager.DynamicTarget cokeStudio = AutoEqManager.evaluateDynamicMusicTarget(
                "کوک اسٹوڈیو (Coke Studio)", "Abida Parveen", 0.5f, 0.5f, 0.5f, 0f);
        assertTrue(cokeStudio.description.contains("Vocal"));

        // Mixed case with leading/trailing spaces
        AutoEqManager.DynamicTarget rockSpaced = AutoEqManager.evaluateDynamicMusicTarget(
                "   THUNDERSTRUCK   ", "   ac/dc   ", 0.5f, 0.5f, 0.5f, 0f);
        assertTrue(rockSpaced.description.contains("Rock"));
    }
}
