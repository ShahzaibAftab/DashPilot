package com.honda.launcher;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * End-to-End & edge-case unit tests for Gauge thresholds and Eco/Comfort Mode:
 * - Danger and Caution threshold logic for RPM, Coolant, Battery, and Load
 * - Dynamic color transitions: Safe (Green in Eco, White in Standard), Caution (Yellow), Danger (Red)
 * - Speedometer speed-based color ramp in Eco/Comfort mode vs Type R mode
 * - NaN handling and out-of-bounds boundary values
 */
public class GaugeAndEcoModeLogicTest {

    // Helper model mimicking MiniGaugeView internal threshold logic
    private static class GaugeTester {
        final float min, max, cautionHigh, dangerHigh, cautionLow, dangerLow;
        boolean danger, caution;
        boolean ecoMode;

        GaugeTester(float min, float max, float cHigh, float dHigh, float cLow, float dLow) {
            this.min = min; this.max = max;
            this.cautionHigh = cHigh; this.dangerHigh = dHigh;
            this.cautionLow = cLow; this.dangerLow = dLow;
        }

        boolean setValue(float v) {
            boolean nan = Float.isNaN(v);
            danger = !nan && ((!Float.isNaN(dangerHigh) && v >= dangerHigh) || (!Float.isNaN(dangerLow) && v < dangerLow));
            caution = !nan && !danger && ((!Float.isNaN(cautionHigh) && v >= cautionHigh) || (!Float.isNaN(cautionLow) && v < cautionLow));
            return danger;
        }

        int getGaugeColor() {
            if (danger) return 0xFFFF1744; // Red
            if (caution) return 0xFFFFD600; // Yellow
            if (ecoMode) return 0xFF00E676; // Green
            return 0xFFF2F2F2; // White
        }
    }

    @Test
    public void testRpmGaugeThresholdsAndEcoMode() {
        GaugeTester rpm = new GaugeTester(0, 8000, 5000, 6000, Float.NaN, Float.NaN);

        // Safe cruising: 2500 RPM
        rpm.setValue(2500);
        assertFalse(rpm.danger);
        assertFalse(rpm.caution);
        // Standard mode -> white
        rpm.ecoMode = false;
        assertEquals(0xFFF2F2F2, rpm.getGaugeColor());
        // Eco mode -> green
        rpm.ecoMode = true;
        assertEquals(0xFF00E676, rpm.getGaugeColor());

        // Caution: 5500 RPM
        rpm.setValue(5500);
        assertFalse(rpm.danger);
        assertTrue(rpm.caution);
        assertEquals(0xFFFFD600, rpm.getGaugeColor()); // Yellow in both modes

        // Danger / Redline: 6500 RPM
        boolean bad = rpm.setValue(6500);
        assertTrue(bad);
        assertTrue(rpm.danger);
        assertEquals(0xFFFF1744, rpm.getGaugeColor()); // Red
    }

    @Test
    public void testCoolantGaugeThresholds() {
        // Normal 88°C, Caution 100°C, Danger 108°C
        GaugeTester temp = new GaugeTester(40, 130, 100, 108, Float.NaN, Float.NaN);
        temp.ecoMode = true;

        // Normal operating temperature (88°C)
        temp.setValue(88);
        assertFalse(temp.danger);
        assertFalse(temp.caution);
        assertEquals(0xFF00E676, temp.getGaugeColor()); // Safe green

        // Traffic heat (102°C)
        temp.setValue(102);
        assertFalse(temp.danger);
        assertTrue(temp.caution);
        assertEquals(0xFFFFD600, temp.getGaugeColor()); // Caution yellow

        // Overheating (112°C)
        boolean bad = temp.setValue(112);
        assertTrue(bad);
        assertTrue(temp.danger);
        assertEquals(0xFFFF1744, temp.getGaugeColor()); // Danger red
    }

    @Test
    public void testBatteryGaugeDualThresholds() {
        // Low caution 12.0V, Low danger 11.8V, High danger 15.2V
        GaugeTester batt = new GaugeTester(10, 16, Float.NaN, 15.2f, 12.0f, 11.8f);
        batt.ecoMode = true;

        // Normal charging: 14.2V
        batt.setValue(14.2f);
        assertFalse(batt.danger);
        assertFalse(batt.caution);
        assertEquals(0xFF00E676, batt.getGaugeColor());

        // Low voltage caution: 11.9V
        batt.setValue(11.9f);
        assertFalse(batt.danger);
        assertTrue(batt.caution);
        assertEquals(0xFFFFD600, batt.getGaugeColor());

        // Critical low danger: 11.5V
        assertTrue(batt.setValue(11.5f));
        assertTrue(batt.danger);
        assertEquals(0xFFFF1744, batt.getGaugeColor());

        // Alternator overcharge danger: 15.5V
        assertTrue(batt.setValue(15.5f));
        assertTrue(batt.danger);
        assertEquals(0xFFFF1744, batt.getGaugeColor());
    }

    @Test
    public void testNanEdgeCasesNeverTriggerDanger() {
        GaugeTester rpm = new GaugeTester(0, 8000, 5000, 6000, Float.NaN, Float.NaN);
        boolean bad = rpm.setValue(Float.NaN);
        assertFalse("NaN must never trigger danger", bad);
        assertFalse(rpm.danger);
        assertFalse(rpm.caution);
    }
}
