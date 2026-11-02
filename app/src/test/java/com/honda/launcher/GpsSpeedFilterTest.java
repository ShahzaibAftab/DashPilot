package com.honda.launcher;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for GpsSpeedFilter, the GPS speed source used when no OBD-II scanner is connected.
 * Covers the head-unit GPS cases that used to leave the speedometer at 0 while driving:
 * poor-accuracy fixes and fixes that carry no Doppler speed.
 */
public class GpsSpeedFilterTest {

    private static final float KMH_60_MPS = 60f / 3.6f;

    @Test
    public void testPoorAccuracyDopplerSpeedIsUsed() {
        GpsSpeedFilter f = new GpsSpeedFilter();
        // 45 m accuracy is typical for a head-unit antenna; the Doppler speed is still good
        assertTrue(f.onFix(true, KMH_60_MPS, true, 45f, 1000, 0f));
        assertEquals(60f, f.getSpeedKmh(), 0.01f);
    }

    @Test
    public void testFixWithoutSpeedKeepsLastReading() {
        GpsSpeedFilter f = new GpsSpeedFilter();
        f.onFix(true, KMH_60_MPS, true, 10f, 1000, 0f);

        // No Doppler speed and accuracy too poor to derive one: reading must not drop to 0
        assertFalse(f.onFix(false, 0f, true, 80f, 2000, 17f));
        assertEquals(60f, f.getSpeedKmh(), 0.01f);

        // No accuracy reported at all: same
        assertFalse(f.onFix(false, 0f, false, 0f, 3000, 17f));
        assertEquals(60f, f.getSpeedKmh(), 0.01f);
    }

    @Test
    public void testSpeedDerivedFromDistanceWhenChipReportsNone() {
        GpsSpeedFilter f = new GpsSpeedFilter();
        assertFalse("First fix has nothing to measure against", f.onFix(false, 0f, true, 40f, 1000, 0f));
        // 25 m in 1.5 s = 60 km/h
        assertTrue(f.onFix(false, 0f, true, 40f, 2500, 25f));
        assertEquals(60f, f.getSpeedKmh(), 0.01f);
    }

    @Test
    public void testDerivedSpeedRejectsBadTimeGaps() {
        GpsSpeedFilter f = new GpsSpeedFilter();
        f.onFix(false, 0f, true, 10f, 1000, 0f);
        assertFalse("Fixes 100 ms apart are too close", f.onFix(false, 0f, true, 10f, 1100, 2f));
        assertFalse("Fixes 9 s apart are too far", f.onFix(false, 0f, true, 10f, 10_100, 150f));
    }

    @Test
    public void testZeroSnapWidensOnWeakFix() {
        GpsSpeedFilter f = new GpsSpeedFilter();
        // 4 km/h jitter while parked with a weak fix reads as standstill
        assertTrue(f.onFix(true, 4f / 3.6f, true, 50f, 1000, 0f));
        assertEquals(0f, f.getSpeedKmh(), 0.001f);

        // The same 4 km/h with a good fix is real crawling movement
        GpsSpeedFilter good = new GpsSpeedFilter();
        assertTrue(good.onFix(true, 4f / 3.6f, true, 8f, 1000, 0f));
        assertEquals(4f, good.getSpeedKmh(), 0.01f);

        // Below 1.8 km/h always snaps to 0
        GpsSpeedFilter crawl = new GpsSpeedFilter();
        crawl.onFix(true, 1.2f / 3.6f, true, 5f, 1000, 0f);
        assertEquals(0f, crawl.getSpeedKmh(), 0.001f);
    }

    @Test
    public void testAbsurdSpeedIgnored() {
        GpsSpeedFilter f = new GpsSpeedFilter();
        f.onFix(true, KMH_60_MPS, true, 10f, 1000, 0f);
        assertFalse(f.onFix(true, 300f / 3.6f, true, 10f, 2000, 0f));
        assertEquals(60f, f.getSpeedKmh(), 0.01f);
    }

    @Test
    public void testSmoothingAndStop() {
        GpsSpeedFilter f = new GpsSpeedFilter();
        f.onFix(true, 30f / 3.6f, true, 10f, 1000, 0f);
        assertEquals(30f, f.getSpeedKmh(), 0.01f);

        f.onFix(true, 40f / 3.6f, true, 10f, 2000, 0f);
        assertEquals(36f, f.getSpeedKmh(), 0.01f);   // 30 * 0.4 + 40 * 0.6

        f.onFix(true, 0f, true, 10f, 3000, 0f);
        assertEquals("Coming to a stop snaps straight to 0", 0f, f.getSpeedKmh(), 0.001f);
    }
}
