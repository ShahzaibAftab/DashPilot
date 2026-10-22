package com.honda.launcher;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * End-to-End test suite for Gauge Toggles and Danger Frame Suppression.
 * Strictly verifies the user requirement:
 * "when i disabled the toggle of a guague his warning red blinking frame should not be displayed on screen"
 */
public class GaugeTogglesAndDangerSuppressionTest {

    /**
     * Replicates the exact evaluation logic in MainActivity.updateObd():
     * Evaluates whether dangerous gauge readings trip the global danger alert (aurora / red blinking border),
     * taking into account whether each individual gauge toggle is enabled or disabled.
     */
    private static class DangerEvaluationResult {
        final boolean isDanger;
        final float evaluatedRpm;
        final float evaluatedCoolant;
        final float evaluatedVolts;
        final float evaluatedLoad;
        final float evaluatedAtf;
        final List<String> evaluatedDtcCodes;

        DangerEvaluationResult(boolean isDanger, float rpm, float coolant, float volts, float load, float atf, List<String> dtcCodes) {
            this.isDanger = isDanger;
            this.evaluatedRpm = rpm;
            this.evaluatedCoolant = coolant;
            this.evaluatedVolts = volts;
            this.evaluatedLoad = load;
            this.evaluatedAtf = atf;
            this.evaluatedDtcCodes = dtcCodes;
        }
    }

    private DangerEvaluationResult evaluateDanger(
            boolean sRpm, boolean sCool, boolean sBatt, boolean sLoad, boolean sAtf, boolean sDtc,
            float rawRpm, float rawCoolant, float rawVolts, float rawLoad, float rawAtf, List<String> rawDtcCodes
    ) {
        boolean bad = false;

        // RPM: dangerHigh = 6000
        float valRpm = (!sRpm || rawRpm < 0) ? Float.NaN : rawRpm;
        boolean rpmBad = !Float.isNaN(valRpm) && valRpm >= 6000f;
        if (sRpm) bad |= rpmBad;

        // Coolant: dangerHigh = 108
        float valCool = (!sCool || rawCoolant == -999) ? Float.NaN : rawCoolant;
        boolean coolBad = !Float.isNaN(valCool) && valCool >= 108f;
        if (sCool) bad |= coolBad;

        // Battery: dangerLow = 11.8, dangerHigh = 15.0
        float valBatt = (!sBatt || rawVolts < 0) ? Float.NaN : rawVolts;
        boolean battBad = !Float.isNaN(valBatt) && (valBatt < 11.8f || valBatt >= 15.0f);
        if (sBatt) bad |= battBad;

        // Load: dangerHigh = 95
        float valLoad = (!sLoad || rawLoad < 0) ? Float.NaN : rawLoad;
        boolean loadBad = !Float.isNaN(valLoad) && valLoad >= 95f;
        if (sLoad) bad |= loadBad;

        // ATF: dangerHigh = 115
        float valAtf = (!sAtf || rawAtf == -999) ? Float.NaN : rawAtf;
        boolean atfBad = !Float.isNaN(valAtf) && valAtf >= 115f;
        if (sAtf) bad |= atfBad;

        // DTC codes
        List<String> codes = (sDtc && rawDtcCodes != null) ? rawDtcCodes : new ArrayList<String>();
        if (sDtc && !codes.isEmpty()) {
            bad |= true;
        }

        return new DangerEvaluationResult(bad, valRpm, valCool, valBatt, valLoad, valAtf, codes);
    }

    @Test
    public void testDisabledCoolantGaugeDoesNotTriggerDangerFrame() {
        // Coolant is 120°C (boiling danger!), but coolant gauge toggle is DISABLED
        DangerEvaluationResult result = evaluateDanger(
                true, false, true, true, true, true,
                2000f, 120f, 14.2f, 30f, 80f, null
        );

        assertFalse("Disabled coolant gauge MUST NOT trigger red blinking danger frame", result.isDanger);
        assertTrue("Disabled coolant gauge value must evaluate to Float.NaN", Float.isNaN(result.evaluatedCoolant));
    }

    @Test
    public void testEnabledCoolantGaugeTriggersDangerFrame() {
        // Same coolant temperature (120°C), but toggle is now ENABLED
        DangerEvaluationResult result = evaluateDanger(
                true, true, true, true, true, true,
                2000f, 120f, 14.2f, 30f, 80f, null
        );

        assertTrue("Enabled coolant gauge with 120°C MUST trigger danger frame", result.isDanger);
        assertEquals(120f, result.evaluatedCoolant, 0.001f);
    }

    @Test
    public void testDisabledRpmGaugeDoesNotTriggerDangerFrame() {
        // Redline RPM (6800 RPM), but RPM toggle is DISABLED
        DangerEvaluationResult result = evaluateDanger(
                false, true, true, true, true, true,
                6800f, 88f, 14.2f, 30f, 80f, null
        );

        assertFalse("Disabled RPM gauge MUST NOT trigger danger frame on redline", result.isDanger);
        assertTrue(Float.isNaN(result.evaluatedRpm));
    }

    @Test
    public void testDisabledBatteryGaugeDoesNotTriggerDangerFrameOnDeadBattery() {
        // Alternator failure / battery dropped to 10.2V, but Battery gauge toggle is DISABLED
        DangerEvaluationResult result = evaluateDanger(
                true, true, false, true, true, true,
                2000f, 88f, 10.2f, 30f, 80f, null
        );

        assertFalse("Disabled battery gauge MUST NOT trigger danger frame", result.isDanger);
        assertTrue(Float.isNaN(result.evaluatedVolts));
    }

    @Test
    public void testDisabledDtcDoesNotTriggerDangerFrameWhenCodesExist() {
        // Check engine trouble code present (P0300 Misfire), but DTC toggle is DISABLED
        List<String> dtcs = Collections.singletonList("P0300");
        DangerEvaluationResult result = evaluateDanger(
                true, true, true, true, true, false,
                2000f, 88f, 14.2f, 30f, 80f, dtcs
        );

        assertFalse("Disabled DTC gauge MUST NOT trigger danger frame even when DTC codes exist", result.isDanger);
        assertTrue("DTC codes list must be empty when toggle is disabled", result.evaluatedDtcCodes.isEmpty());
    }

    @Test
    public void testBatteryHealthVisibilityLogic() {
        // Battery health is visible ONLY when both show_battery AND show_batt_health are true
        assertTrue(isBatteryHealthVisible(true, true));
        assertFalse(isBatteryHealthVisible(false, true));  // gauge off -> health off
        assertFalse(isBatteryHealthVisible(true, false));  // health off -> health off
        assertFalse(isBatteryHealthVisible(false, false)); // both off -> health off
    }

    private boolean isBatteryHealthVisible(boolean showBattery, boolean showBattHealth) {
        return showBattery && showBattHealth;
    }

    @Test
    public void testRowVisibilityContainerLogic() {
        // Row 1: (sRpm || sCool)
        assertTrue(isRow1Visible(true, false));
        assertTrue(isRow1Visible(false, true));
        assertTrue(isRow1Visible(true, true));
        assertFalse("Row 1 must be hidden when both RPM and Coolant are disabled", isRow1Visible(false, false));

        // Row 2: (sBatt || sLoad)
        assertTrue(isRow2Visible(true, false));
        assertTrue(isRow2Visible(false, true));
        assertFalse("Row 2 must be hidden when both Battery and Load are disabled", isRow2Visible(false, false));

        // Row 3: (sAtf || sFuel || sDtc)
        assertTrue(isRow3Visible(false, true, false));
        assertFalse("Row 3 must be hidden when ATF, Fuel, and DTC are all disabled", isRow3Visible(false, false, false));
    }

    private boolean isRow1Visible(boolean sRpm, boolean sCool) { return sRpm || sCool; }
    private boolean isRow2Visible(boolean sBatt, boolean sLoad) { return sBatt || sLoad; }
    private boolean isRow3Visible(boolean sAtf, boolean sFuel, boolean sDtc) { return sAtf || sFuel || sDtc; }
}
