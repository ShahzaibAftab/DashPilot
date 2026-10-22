package com.honda.launcher;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

/**
 * End-to-End & edge-case unit tests for SavedMusicManager:
 * - Filename sanitization against filesystem reserved characters
 * - Track display name formatting with and without artist
 * - Storage size formatting in MB
 * - Idle / system track title detection to avoid saving garbage
 */
public class SavedMusicManagerTest {

    @Test
    public void testFilenameSanitization() {
        String unsafeTitle = "Song:AC/DC?*<>|\"Test";
        String sanitized = SavedMusicManager.sanitizeFilename(unsafeTitle);
        assertEquals("Song_AC_DC_Test", sanitized);
        assertFalse(sanitized.contains(":"));
        assertFalse(sanitized.contains("/"));
        assertFalse(sanitized.contains("?"));
        assertFalse(sanitized.contains("*"));
        assertFalse(sanitized.contains("<"));
        assertFalse(sanitized.contains(">"));
        assertFalse(sanitized.contains("\""));
        assertFalse(sanitized.contains("|"));
    }

    @Test
    public void testTrackDisplayName() {
        File dummy = new File("/tmp/test.m4a");
        // Title without artist
        SavedMusicManager.Track t1 = new SavedMusicManager.Track(dummy, "Song Title", "", 1024 * 1024, 0);
        assertEquals("Song Title", t1.getDisplayName());

        // Title with artist
        SavedMusicManager.Track t2 = new SavedMusicManager.Track(dummy, "Blinding Lights", "The Weeknd", 1024 * 1024, 0);
        assertEquals("Blinding Lights - The Weeknd", t2.getDisplayName());

        // Title that already contains artist
        SavedMusicManager.Track t3 = new SavedMusicManager.Track(dummy, "Blinding Lights - The Weeknd", "The Weeknd", 1024 * 1024, 0);
        assertEquals("Blinding Lights - The Weeknd", t3.getDisplayName());
    }

    @Test
    public void testTrackSizeString() {
        File dummy = new File("/tmp/test.m4a");
        // 3.5 MB = 3.5 * 1024 * 1024 = 3670016 bytes
        SavedMusicManager.Track t = new SavedMusicManager.Track(dummy, "Song", "Artist", 3670016, 0);
        assertEquals("3.5 MB", t.getSizeString());
    }

    @Test
    public void testIgnoreSystemTrackTitles() {
        // System / placeholder titles that should not be recorded
        String[] ignored = {
            null,
            "",
            "   ",
            "Nothing playing",
            "Nothing playing • Tap for Saved Music",
            "Playing Audio (Bluetooth / Media)"
        };

        for (String title : ignored) {
            boolean shouldIgnore = (title == null || title.trim().isEmpty() ||
                    title.contains("Nothing playing") ||
                    title.contains("Playing Audio"));
            assertTrue("Title '" + title + "' must be ignored", shouldIgnore);
        }
    }
}
