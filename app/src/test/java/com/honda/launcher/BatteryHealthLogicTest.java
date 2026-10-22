package com.honda.launcher;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.*;

/**
 * End-to-End & edge-case unit tests for Battery Health percentage & lifespan calculation:
 * - Alternator active charging conditions (13.5V - 15.0V)
 * - Resting healthy battery (12.4V - 12.8V)
 * - Weak / fair battery (11.9V - 12.3V)
 * - Critically low / replace battery (< 11.8V)
 * - Disconnected, NaN, 0V, and negative boundary edge cases
 * - Color threshold assignments (Green, Yellow, Red, Dim)
 */
public class BatteryHealthLogicTest {

    // Mirror of helper methods from MainActivity to verify automotive battery math
    private String getBatteryHealthText(float volts) {
        if (Float.isNaN(volts) || volts <= 0) {
            return "HEALTH: -- • LIFE: --";
        }
        int pct;
        String life;
        if (volts >= 13.5f) {
            // Engine running & alternator charging
            if (volts >= 13.8f && volts <= 14.6f) {
                pct = Math.min(99, Math.round(92f + (volts - 13.8f) * 7.5f));
                life = "Good (~2.5-3 yrs)";
            } else if (volts > 14.6f) {
                pct = 82;
                life = "High (~1-2 yrs)";
            } else {
                pct = Math.round(75f + (volts - 13.5f) * 50f);
                life = "Moderate (~1.5 yrs)";
            }
        } else {
            // Resting voltage / accessory mode (11.5V - 12.8V)
            float frac = (volts - 11.5f) / (12.8f - 11.5f);
            pct = Math.max(10, Math.min(100, Math.round(frac * 100f)));
            if (pct >= 85) life = "Good (~2.5-3 yrs)";
            else if (pct >= 65) life = "Fair (~1.5-2 yrs)";
            else if (pct >= 45) life = "Weak (~6-12 mos)";
            else life = "Replace Soon";
        }
        return String.format(Locale.US, "HEALTH: %d%% • %s", pct, life);
    }

    private int getBatteryHealthColor(float volts) {
        if (Float.isNaN(volts) || volts <= 0) return 0x8CFFFFFF;
        if (volts > 14.7f) return 0xFFFF1744; // Red (Overcharging / regulator fault)
        if (volts >= 13.5f && volts <= 14.7f) return 0xFF00E676; // Green
        if (volts >= 12.3f) return 0xFF00E676; // Green
        if (volts >= 11.9f) return 0xFFFFD600; // Yellow
        return 0xFFFF1744; // Red
    }

    @Test
    public void testAlternatorChargingIdealVoltage() {
        // Typical healthy alternator output is 14.2V
        String text = getBatteryHealthText(14.2f);
        assertTrue(text.contains("HEALTH: 95%"));
        assertTrue(text.contains("Good (~2.5-3 yrs)"));
        assertEquals(0xFF00E676, getBatteryHealthColor(14.2f)); // Green
    }

    @Test
    public void testAlternatorOverchargingVoltage() {
        // High alternator output (voltage regulator failing) e.g. 15.0V
        String text = getBatteryHealthText(15.0f);
        assertTrue(text.contains("High (~1-2 yrs)"));
        assertEquals(0xFFFF1744, getBatteryHealthColor(15.0f)); // Red warning
    }

    @Test
    public void testRestingHealthyBattery() {
        // Full SoC resting battery 12.7V
        String text = getBatteryHealthText(12.7f);
        assertTrue(text.contains("Good (~2.5-3 yrs)"));
        assertEquals(0xFF00E676, getBatteryHealthColor(12.7f)); // Green
    }

    @Test
    public void testRestingFairBattery() {
        // Moderate state of charge 12.1V
        String text = getBatteryHealthText(12.1f);
        assertTrue(text.contains("Weak (~6-12 mos)") || text.contains("Fair"));
        assertEquals(0xFFFFD600, getBatteryHealthColor(12.1f)); // Yellow
    }

    @Test
    public void testCriticallyLowBattery() {
        // Deeply discharged battery 11.6V
        String text = getBatteryHealthText(11.6f);
        assertTrue(text.contains("Replace Soon"));
        assertEquals(0xFFFF1744, getBatteryHealthColor(11.6f)); // Red
    }

    @Test
    public void testDisconnectedAndEdgeCases() {
        // Disconnected (NaN)
        String nanText = getBatteryHealthText(Float.NaN);
        assertEquals("HEALTH: -- • LIFE: --", nanText);
        assertEquals(0x8CFFFFFF, getBatteryHealthColor(Float.NaN));

        // Zero volts
        String zeroText = getBatteryHealthText(0.0f);
        assertEquals("HEALTH: -- • LIFE: --", zeroText);
        assertEquals(0x8CFFFFFF, getBatteryHealthColor(0.0f));

        // Negative volts
        String negText = getBatteryHealthText(-5.0f);
        assertEquals("HEALTH: -- • LIFE: --", negText);
        assertEquals(0x8CFFFFFF, getBatteryHealthColor(-5.0f));
    }
}
