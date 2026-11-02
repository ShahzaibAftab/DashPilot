package com.honda.launcher;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.Assert.*;

/**
 * Unit tests for NightMode: offline sunrise/sunset maths and the AUTO / ON / OFF decision.
 */
public class NightModeTest {

    private static final TimeZone PKT = TimeZone.getTimeZone("Asia/Karachi");
    private static final long FIVE_MIN = 5 * 60_000L;

    private static long at(TimeZone tz, int y, int month, int d, int h, int min) {
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(y, month - 1, d, h, min, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void testLondonWinterSolstice() {
        TimeZone london = TimeZone.getTimeZone("Europe/London");
        long[] sun = NightMode.sunriseSunset(51.5074, -0.1278, at(london, 2024, 12, 21, 12, 0), london);
        assertNotNull(sun);
        assertEquals("Sunrise ~08:04", at(london, 2024, 12, 21, 8, 4), sun[0], FIVE_MIN);
        assertEquals("Sunset ~15:53", at(london, 2024, 12, 21, 15, 53), sun[1], FIVE_MIN);
    }

    @Test
    public void testNewYorkSummerSolsticeSunsetAfterUtcMidnight() {
        // Sunset is 00:31 UTC the next day: must still count as this local day's sunset
        TimeZone ny = TimeZone.getTimeZone("America/New_York");
        long[] sun = NightMode.sunriseSunset(40.7128, -74.0060, at(ny, 2024, 6, 21, 12, 0), ny);
        assertNotNull(sun);
        assertEquals("Sunrise ~05:25", at(ny, 2024, 6, 21, 5, 25), sun[0], FIVE_MIN);
        assertEquals("Sunset ~20:31", at(ny, 2024, 6, 21, 20, 31), sun[1], FIVE_MIN);
    }

    @Test
    public void testSunriseBeforeSunsetOnSameLocalDay() {
        long[] sun = NightMode.sunriseSunset(NightMode.FALLBACK_LAT, NightMode.FALLBACK_LON,
                at(PKT, 2026, 11, 2, 9, 0), PKT);
        assertNotNull(sun);
        assertTrue(sun[0] < sun[1]);
        assertTrue(sun[0] > at(PKT, 2026, 11, 2, 0, 0));
        assertTrue(sun[1] < at(PKT, 2026, 11, 3, 0, 0));
    }

    @Test
    public void testAutoInLahore() {
        double lat = NightMode.FALLBACK_LAT, lon = NightMode.FALLBACK_LON;
        assertFalse("Noon is day", NightMode.isNight(NightMode.AUTO, lat, lon, at(PKT, 2026, 11, 2, 12, 0), PKT));
        assertTrue("23:00 is night", NightMode.isNight(NightMode.AUTO, lat, lon, at(PKT, 2026, 11, 2, 23, 0), PKT));
        assertTrue("03:00 is night", NightMode.isNight(NightMode.AUTO, lat, lon, at(PKT, 2026, 11, 2, 3, 0), PKT));
        // Lahore sunset is after 19:00 in June but before 17:30 in November
        assertFalse(NightMode.isNight(NightMode.AUTO, lat, lon, at(PKT, 2026, 6, 21, 18, 45), PKT));
        assertTrue(NightMode.isNight(NightMode.AUTO, lat, lon, at(PKT, 2026, 11, 2, 18, 45), PKT));
    }

    @Test
    public void testOnAndOffIgnoreTheSun() {
        long noon = at(PKT, 2026, 11, 2, 12, 0);
        long midnight = at(PKT, 2026, 11, 2, 23, 59);
        assertTrue(NightMode.isNight(NightMode.ON, 31.5, 74.3, noon, PKT));
        assertFalse(NightMode.isNight(NightMode.OFF, 31.5, 74.3, midnight, PKT));
    }

    @Test
    public void testDimAlpha() {
        assertEquals("Full brightness: no dark layer", 0f, NightMode.dimAlpha(100), 0.001f);
        assertEquals(0.325f, NightMode.dimAlpha(35), 0.001f);
        assertEquals("Clamped to the minimum brightness", NightMode.dimAlpha(10), NightMode.dimAlpha(0), 0.001f);
        assertTrue(NightMode.dimAlpha(10) <= 0.5f);
    }
}
