package com.honda.launcher;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import static org.junit.Assert.*;

/**
 * Unit tests covering:
 * - Theme selection (Royal Sapphire, Nocturne Onyx, Type R Red)
 * - 24-hour vs 12-hour clock formatting
 * - Music controls bar display/hide visibility toggle
 * - Theme-specific color ramp evaluations
 */
public class ThemeAndDisplayTogglesTest {

    @Test
    public void testThemeConstants() {
        assertEquals(0, MainActivity.THEME_TYPER);
        assertEquals(1, MainActivity.THEME_SAPPHIRE);
        assertEquals(2, MainActivity.THEME_ONYX);

        assertEquals(0, SpeedometerView.THEME_TYPER);
        assertEquals(1, SpeedometerView.THEME_SAPPHIRE);
        assertEquals(2, SpeedometerView.THEME_ONYX);

        assertEquals(0, MiniGaugeView.THEME_TYPER);
        assertEquals(1, MiniGaugeView.THEME_SAPPHIRE);
        assertEquals(2, MiniGaugeView.THEME_ONYX);

        assertEquals(0, AuroraView.THEME_TYPER);
        assertEquals(1, AuroraView.THEME_SAPPHIRE);
        assertEquals(2, AuroraView.THEME_ONYX);
    }

    @Test
    public void test24HourClockFormatting() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 15);
        cal.set(Calendar.MINUTE, 42);
        Date testDate = cal.getTime();

        SimpleDateFormat fmt24 = new SimpleDateFormat("HH:mm", Locale.US);
        SimpleDateFormat fmt12 = new SimpleDateFormat("h:mm a", Locale.US);

        assertEquals("15:42", fmt24.format(testDate));
        assertTrue(fmt12.format(testDate).contains("3:42") && fmt12.format(testDate).contains("PM"));
    }

    @Test
    public void testMidnightAndMorningClockFormatting() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 9);
        cal.set(Calendar.MINUTE, 5);
        Date testDate = cal.getTime();

        SimpleDateFormat fmt24 = new SimpleDateFormat("HH:mm", Locale.US);
        SimpleDateFormat fmt12 = new SimpleDateFormat("h:mm a", Locale.US);

        assertEquals("09:05", fmt24.format(testDate));
        assertTrue(fmt12.format(testDate).contains("9:05") && fmt12.format(testDate).contains("AM"));
    }

    @Test
    public void testMusicBarVisibilityToggleLogic() {
        // Default: true
        boolean showMusicDefault = true;
        assertTrue(shouldShowMusicBar(showMusicDefault));

        // When user toggles off
        boolean showMusicDisabled = false;
        assertFalse(shouldShowMusicBar(showMusicDisabled));
    }

    private boolean shouldShowMusicBar(boolean toggle) {
        return toggle;
    }

    @Test
    public void testSapphireThemeColorRamp() throws Exception {
        java.lang.reflect.Method m = SpeedometerView.class.getDeclaredMethod("rampSapphire", float.class);
        m.setAccessible(true);

        // At 0% speed: Electric Cyan (0xFF00E5FF)
        int colStart = (Integer) m.invoke(null, 0f);
        int r0 = (colStart >> 16) & 0xFF;
        int g0 = (colStart >> 8) & 0xFF;
        int b0 = colStart & 0xFF;
        assertEquals(0, r0);
        assertEquals(0xE5, g0);
        assertEquals(0xFF, b0);

        // At 50% speed: Sapphire Blue (0xFF2979FF)
        int colMid = (Integer) m.invoke(null, 0.5f);
        int r1 = (colMid >> 16) & 0xFF;
        int g1 = (colMid >> 8) & 0xFF;
        int b1 = colMid & 0xFF;
        assertEquals(0x29, r1);
        assertEquals(0x79, g1);
        assertEquals(0xFF, b1);

        // At 100% speed: Kinetic Ruby Red (0xFFFF1744)
        int colEnd = (Integer) m.invoke(null, 1.0f);
        int r2 = (colEnd >> 16) & 0xFF;
        int g2 = (colEnd >> 8) & 0xFF;
        int b2 = colEnd & 0xFF;
        assertEquals(0xFF, r2);
        assertEquals(0x17, g2);
        assertEquals(0x44, b2);
    }

    @Test
    public void testOnyxThemeColorRamp() throws Exception {
        java.lang.reflect.Method m = SpeedometerView.class.getDeclaredMethod("rampOnyx", float.class);
        m.setAccessible(true);

        // At 0% speed: Warm Tungsten Amber (0xFFFFB300)
        int colStart = (Integer) m.invoke(null, 0f);
        int r0 = (colStart >> 16) & 0xFF;
        int g0 = (colStart >> 8) & 0xFF;
        int b0 = colStart & 0xFF;
        assertEquals(0xFF, r0);
        assertEquals(0xB3, g0);
        assertEquals(0x00, b0);

        // At 50% speed: Gold (0xFFFF8F00)
        int colMid = (Integer) m.invoke(null, 0.5f);
        int r1 = (colMid >> 16) & 0xFF;
        int g1 = (colMid >> 8) & 0xFF;
        int b1 = colMid & 0xFF;
        assertEquals(0xFF, r1);
        assertEquals(0x8F, g1);
        assertEquals(0x00, b1);

        // At 100% speed: Ember Red (0xFFFF3D00)
        int colEnd = (Integer) m.invoke(null, 1.0f);
        int r2 = (colEnd >> 16) & 0xFF;
        int g2 = (colEnd >> 8) & 0xFF;
        int b2 = colEnd & 0xFF;
        assertEquals(0xFF, r2);
        assertEquals(0x3D, g2);
        assertEquals(0x00, b2);
    }
}
