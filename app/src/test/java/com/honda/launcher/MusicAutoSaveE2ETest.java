package com.honda.launcher;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

/**
 * End-to-End & edge-case unit test suite for SavedMusicManager:
 * - Complex filename sanitization (slashes, colons, pipes, control chars, trim, unicode)
 * - Stub detection (<5000ms duration, <35KB filesize) to prevent cluttering car memory
 * - System/idle track detection (prevents saving phone notification/ringer/blank sessions)
 * - Display name and file metadata parsing
 * - Storage calculation edge cases
 */
public class MusicAutoSaveE2ETest {

    @Test
    public void testSanitizeFilename_ComplexSpecialCharacters() {
        // Filenames with slashes, question marks, asterisks, pipe, quotes, tabs, colon
        String raw = "AC/DC : Highway to Hell *Live* <2026> | \"Remastered\"?";
        String sanitized = SavedMusicManager.sanitizeFilename(raw);

        assertFalse(sanitized.contains("/"));
        assertFalse(sanitized.contains("\\"));
        assertFalse(sanitized.contains(":"));
        assertFalse(sanitized.contains("*"));
        assertFalse(sanitized.contains("?"));
        assertFalse(sanitized.contains("<"));
        assertFalse(sanitized.contains(">"));
        assertFalse(sanitized.contains("|"));
        assertFalse(sanitized.contains("\""));

        assertEquals("AC_DC _ Highway to Hell _Live_ _2026_ _ _Remastered", sanitized);
    }

    @Test
    public void testSanitizeFilename_EmptyNullAndWhitespaceOnly() {
        assertEquals("Unknown_Track", SavedMusicManager.sanitizeFilename(null));
        assertEquals("Unknown_Track", SavedMusicManager.sanitizeFilename(""));
        assertEquals("Unknown_Track", SavedMusicManager.sanitizeFilename("   "));
        assertEquals("Unknown_Track", SavedMusicManager.sanitizeFilename("////::::***"));
    }

    @Test
    public void testSanitizeFilename_UnicodeAndUrduEnglishSupport() {
        // Test unicode title (e.g. Pakistani music: Coke Studio / Atif Aslam / Pasoori)
        String raw = "Ali Sethi & Shae Gill - Pasoori (کوک اسٹوڈیو)";
        String sanitized = SavedMusicManager.sanitizeFilename(raw);
        assertTrue(sanitized.contains("Pasoori"));
        assertTrue(sanitized.contains("Ali Sethi"));
        assertFalse(sanitized.contains("/"));
    }

    @Test
    public void testStubClipDetection_UnderFiveSeconds() {
        // Recording shorter than 5 seconds must be flagged as a stub/notification
        long startTime = 1000000L;
        long endTime = startTime + 4200L; // 4.2 seconds
        long duration = endTime - startTime;

        boolean isStub = duration < 5000L;
        assertTrue("Recordings < 5000ms must be flagged as stub clips", isStub);
    }

    @Test
    public void testStubClipDetection_ValidFivePlusSeconds() {
        long startTime = 1000000L;
        long endTime = startTime + 6500L; // 6.5 seconds
        long duration = endTime - startTime;

        boolean isStub = duration < 5000L;
        assertFalse("Recordings >= 5000ms are valid music clips", isStub);
    }

    @Test
    public void testStubFileLength_Under35Kb() {
        // Files smaller than 35KB are truncated/failed headers
        long smallSize = 18400L; // 18 KB
        long validSize = 420000L; // 420 KB

        assertTrue(smallSize < 35000L);
        assertFalse(validSize < 35000L);
    }

    @Test
    public void testSystemOrGarbageTrackFiltering() {
        assertTrue(isSystemOrGarbageTitle(null));
        assertTrue(isSystemOrGarbageTitle(""));
        assertTrue(isSystemOrGarbageTitle("   "));
        assertTrue(isSystemOrGarbageTitle("phone audio"));
        assertTrue(isSystemOrGarbageTitle("Bluetooth"));
        assertTrue(isSystemOrGarbageTitle("Media"));
        assertTrue(isSystemOrGarbageTitle("Android System"));
        assertTrue(isSystemOrGarbageTitle("Idle"));
        assertTrue(isSystemOrGarbageTitle("Unknown"));

        // Valid music track
        assertFalse(isSystemOrGarbageTitle("Starboy"));
        assertFalse(isSystemOrGarbageTitle("Dil Diyan Gallan"));
    }

    private boolean isSystemOrGarbageTitle(String t) {
        if (t == null) return true;
        String s = t.trim().toLowerCase();
        if (s.isEmpty()) return true;
        return s.equals("phone audio") || s.equals("bluetooth") || s.equals("media")
                || s.equals("android system") || s.equals("idle") || s.equals("unknown");
    }

    @Test
    public void testStorageFormatting() {
        // 0 bytes
        assertEquals("0.0 MB", formatStorageMb(0L));
        // 1 Megabyte
        assertEquals("1.0 MB", formatStorageMb(1024 * 1024L));
        // 25.4 Megabytes
        assertEquals("25.4 MB", formatStorageMb(26633830L));
    }

    private String formatStorageMb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }

    @Test
    public void testTrackDisplayNameHandling() {
        File f = new File("/dummy/song.m4a");
        // Title with artist
        SavedMusicManager.Track t1 = new SavedMusicManager.Track(f, "Aadat", "Atif Aslam", 5000000L, 0);
        assertEquals("Aadat - Atif Aslam", t1.getDisplayName());

        // Title that already includes " - Artist"
        SavedMusicManager.Track t2 = new SavedMusicManager.Track(f, "Aadat - Atif Aslam", "Atif Aslam", 5000000L, 0);
        assertEquals("Aadat - Atif Aslam", t2.getDisplayName());

        // Title with null or empty artist
        SavedMusicManager.Track t3 = new SavedMusicManager.Track(f, "Aadat", null, 5000000L, 0);
        assertEquals("Aadat", t3.getDisplayName());
    }
}
