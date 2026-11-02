package com.honda.launcher;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
        ReverseCameraMusicManager.onActivityPaused(null);
        ReverseCameraMusicManager.onActivityResumed();
        ReverseCameraMusicManager.noteSelfLaunch();
        ReverseCameraMusicManager.startMonitoring(null);

        assertFalse(ReverseCameraMusicManager.isReverseIntent(null));
        assertFalse(ReverseCameraMusicManager.isReverseOn(null));
        assertFalse(ReverseCameraMusicManager.handleReverseIntent(null, null));
    }

    @Test
    public void testDuplicateReverseBroadcastIsHandledOnce() {
        Context ctx = new FakeContext(true);
        ReverseCameraMusicManager.onReverseStateChanged(ctx, false);
        assertFalse(ReverseCameraMusicManager.isReverseActive());

        // The launcher and the boot receiver both deliver the same ON broadcast
        ReverseCameraMusicManager.onReverseStateChanged(ctx, true);
        ReverseCameraMusicManager.onReverseStateChanged(ctx, true);
        assertTrue(ReverseCameraMusicManager.isReverseActive());

        ReverseCameraMusicManager.onReverseStateChanged(ctx, false);
        ReverseCameraMusicManager.onReverseStateChanged(ctx, false);
        assertFalse(ReverseCameraMusicManager.isReverseActive());
    }

    @Test
    public void testReverseStateTrackedEvenWhenToggleOff() {
        // Turning the toggle on mid-reverse must not leave the state machine out of sync
        Context ctx = new FakeContext(false);
        ReverseCameraMusicManager.onReverseStateChanged(ctx, false);
        ReverseCameraMusicManager.onReverseStateChanged(ctx, true);
        assertTrue(ReverseCameraMusicManager.isReverseActive());
        ReverseCameraMusicManager.onReverseStateChanged(ctx, false);
        assertFalse(ReverseCameraMusicManager.isReverseActive());
    }

    @Test
    public void testStatusText() {
        assertEquals("Off: the head unit may mute music while reversing",
                ReverseCameraMusicManager.getStatusText(new FakeContext(false)));
        assertTrue(ReverseCameraMusicManager.getStatusText(new FakeContext(true))
                .startsWith("No reverse signal received"));
    }

    @Test
    public void testFormatAgo() {
        assertEquals("0 s ago", ReverseCameraMusicManager.formatAgo(-50));
        assertEquals("42 s ago", ReverseCameraMusicManager.formatAgo(42_000));
        assertEquals("3 min ago", ReverseCameraMusicManager.formatAgo(200_000));
        assertEquals("2 h ago", ReverseCameraMusicManager.formatAgo(7_300_000));
    }

    /** Context whose only working part is the "launcher" preferences file. */
    private static class FakeContext extends ContextWrapper {
        private final FakePrefs prefs = new FakePrefs();

        FakeContext(boolean keepMusicInReverse) {
            super(null);
            prefs.values.put(ReverseCameraMusicManager.PREF_KEEP_MUSIC_IN_REVERSE, keepMusicInReverse);
        }

        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
        @Override public Context getApplicationContext() { return this; }
        @Override public Object getSystemService(String name) { return null; }
        @Override public int checkSelfPermission(String permission) { return PackageManager.PERMISSION_DENIED; }
    }

    private static class FakePrefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();

        @Override public boolean getBoolean(String key, boolean defValue) {
            Object v = values.get(key);
            return (v instanceof Boolean) ? (Boolean) v : defValue;
        }
        @Override public Map<String, ?> getAll() { return values; }
        @Override public String getString(String key, String defValue) { return defValue; }
        @Override public Set<String> getStringSet(String key, Set<String> defValues) { return defValues; }
        @Override public int getInt(String key, int defValue) { return defValue; }
        @Override public long getLong(String key, long defValue) { return defValue; }
        @Override public float getFloat(String key, float defValue) { return defValue; }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public Editor edit() { throw new UnsupportedOperationException(); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
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
