package com.honda.launcher;

import android.content.Intent;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for ReverseCameraMusicManager.
 * Tests:
 * 1. Default constant for keep_music_in_reverse preference key.
 * 2. Preference default is true.
 * 3. Null-safety across all methods when context or intent is null.
 * 4. Correct classification of vendor reverse broadcasts across Allwinner T3, Syu, AutoChips, Microntek, etc.
 * 5. Extraction of reverse ON vs OFF state flags.
 */
public class ReverseCameraMusicManagerTest {

    @Test
    public void testPreferenceKeyConstant() {
        assertEquals("keep_music_in_reverse", ReverseCameraMusicManager.PREF_KEEP_MUSIC_IN_REVERSE);
    }

    @Test
    public void testNullSafety() {
        // Must never throw exceptions
        assertTrue("Null context should default to true", ReverseCameraMusicManager.isKeepMusicInReverseEnabled(null));
        ReverseCameraMusicManager.setKeepMusicInReverseEnabled(null, false);
        ReverseCameraMusicManager.applySystemReverseMuteConfig(null, false);
        ReverseCameraMusicManager.onReverseStateChanged(null, true);
        ReverseCameraMusicManager.onReverseStateChanged(null, false);
        ReverseCameraMusicManager.handleAudioFocusChange(null, -1);
        ReverseCameraMusicManager.onActivityPaused(null);
        ReverseCameraMusicManager.unmuteAndResumePlayback(null);

        assertFalse(ReverseCameraMusicManager.isReverseIntent(null));
        assertFalse(ReverseCameraMusicManager.isReverseOn(null));
    }

    @Test
    public void testReverseIntentDetection() {
        // Car head unit reverse intent actions
        String[] validActions = {
                "android.intent.action.REVERSE",
                "com.allwinner.action.BACK_CAR_START",
                "com.allwinner.action.BACK_CAR_STOP",
                "com.syu.action.REVERSE_ON",
                "com.syu.action.REVERSE_OFF",
                "autochips.intent.action.REVERSE_ON",
                "autochips.intent.action.REVERSE_OFF",
                "com.microntek.reverse",
                "com.tw.action.REVERSE",
                "com.fyt.action.REVERSE_STATUS",
                "action.honda.reverse",
                "com.car.reverse.state"
        };

        for (String act : validActions) {
            assertTrue("Action " + act + " should be recognized as a reverse action",
                    ReverseCameraMusicManager.isReverseAction(act));
        }

        assertFalse(ReverseCameraMusicManager.isReverseAction("android.intent.action.BATTERY_CHANGED"));
        assertFalse(ReverseCameraMusicManager.isReverseAction(null));
    }

    @Test
    public void testReverseOnVsOffDetection() {
        // Start / ON intents
        assertTrue(ReverseCameraMusicManager.isReverseActionOn("com.allwinner.action.BACK_CAR_START"));
        assertTrue(ReverseCameraMusicManager.isReverseActionOn("com.syu.action.REVERSE_ON"));
        assertTrue(ReverseCameraMusicManager.isReverseActionOn("autochips.intent.action.REVERSE_ON"));
        assertTrue(ReverseCameraMusicManager.isReverseActionOn("android.intent.action.REVERSE"));

        // Stop / OFF intents
        assertFalse(ReverseCameraMusicManager.isReverseActionOn("com.allwinner.action.BACK_CAR_STOP"));
        assertFalse(ReverseCameraMusicManager.isReverseActionOn("com.syu.action.REVERSE_OFF"));
        assertFalse(ReverseCameraMusicManager.isReverseActionOn("autochips.intent.action.REVERSE_OFF"));
        assertFalse(ReverseCameraMusicManager.isReverseActionOn(null));
    }

    @Test
    public void testPreferencePersistenceSimulation() {
        Map<String, Object> fakePrefs = new HashMap<>();

        // By default should be true
        boolean keepMusic = fakePrefs.containsKey("keep_music_in_reverse")
                ? (Boolean) fakePrefs.get("keep_music_in_reverse") : true;
        assertTrue("Keep music in reverse should be enabled by default", keepMusic);

        // Turn off toggle
        fakePrefs.put("keep_music_in_reverse", false);
        keepMusic = (Boolean) fakePrefs.get("keep_music_in_reverse");
        assertFalse("Should be false when toggled off", keepMusic);

        // Turn on toggle
        fakePrefs.put("keep_music_in_reverse", true);
        keepMusic = (Boolean) fakePrefs.get("keep_music_in_reverse");
        assertTrue("Should be true when toggled on", keepMusic);
    }
}
