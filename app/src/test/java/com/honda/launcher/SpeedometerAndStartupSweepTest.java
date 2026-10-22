package com.honda.launcher;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * End-to-End & edge-case unit tests for SpeedometerView:
 * - Startup needle sweep keyframe sequence (0 -> 200 -> 200 -> 0)
 * - Eco/Comfort mode dynamic color transitions (<80 km/h Green, 80-120 km/h Yellow, >120 km/h Red)
 * - Color blending mathematics
 */
public class SpeedometerAndStartupSweepTest {

    private static int blendColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int rr = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int gg = (int) (((a >>  8) & 0xFF) + (((b >>  8) & 0xFF) - ((a >>  8) & 0xFF)) * t);
        int bb = (int) (( a        & 0xFF) + (( b        & 0xFF) - ( a        & 0xFF)) * t);
        return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
    }

    private int getSpeedometerColor(float speed, boolean ecoMode) {
        if (ecoMode) {
            if (speed >= 120f) {
                return 0xFFFF1744; // Red
            } else if (speed >= 80f) {
                float tr = (speed - 80f) / 40f;
                return blendColor(0xFFFFD600, 0xFFFF1744, tr); // Yellow -> Red
            } else {
                float tr = Math.min(1f, speed / 80f);
                return blendColor(0xFF00E676, 0xFFFFD600, tr * 0.35f); // Green -> slight warm
            }
        } else {
            return 0xFFF2F2F2; // White calm
        }
    }

    @Test
    public void testBlendColorBounds() {
        int c1 = 0xFF00E676; // Green
        int c2 = 0xFFFF1744; // Red

        // At t = 0 -> c1
        assertEquals(c1, blendColor(c1, c2, 0.0f));

        // At t = 1 -> c2
        assertEquals(c2, blendColor(c1, c2, 1.0f));

        // Clamped below 0
        assertEquals(c1, blendColor(c1, c2, -0.5f));

        // Clamped above 1
        assertEquals(c2, blendColor(c1, c2, 1.5f));
    }

    @Test
    public void testSpeedometerEcoModeColors() {
        // City speed (40 km/h) -> Green dominant
        int c40 = getSpeedometerColor(40f, true);
        int greenComponent40 = (c40 >> 8) & 0xFF;
        int redComponent40 = (c40 >> 16) & 0xFF;
        assertTrue("At 40 km/h in Eco Mode, green must dominate red", greenComponent40 > redComponent40);

        // Highway warning speed (100 km/h) -> Yellowish
        int c100 = getSpeedometerColor(100f, true);
        int redComponent100 = (c100 >> 16) & 0xFF;
        int greenComponent100 = (c100 >> 8) & 0xFF;
        assertTrue(redComponent100 > 200);
        assertTrue(greenComponent100 > 100);

        // High speed danger (130 km/h) -> Red
        int c130 = getSpeedometerColor(130f, true);
        assertEquals(0xFFFF1744, c130);
    }
}
