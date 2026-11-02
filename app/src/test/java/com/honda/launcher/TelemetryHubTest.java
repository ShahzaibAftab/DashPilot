package com.honda.launcher;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for TelemetryHub, which keeps speed, OBD and fuel tracking running in the background.
 */
public class TelemetryHubTest {

    @Test
    public void testObdSpeedWinsWhenScannerGivesOne() {
        assertEquals(72f, TelemetryHub.pickSpeed(true, 72, true, 70f), 0.001f);
        // OBD reporting 0 km/h while parked is a real reading, not "missing"
        assertEquals(0f, TelemetryHub.pickSpeed(true, 0, true, 3f), 0.001f);
    }

    @Test
    public void testGpsUsedWhenScannerHasNoSpeed() {
        // Not connected, or connected but speed PID not answered yet (-1)
        assertEquals(55f, TelemetryHub.pickSpeed(false, -1, true, 55f), 0.001f);
        assertEquals(55f, TelemetryHub.pickSpeed(true, -1, true, 55f), 0.001f);
    }

    @Test
    public void testStaleGpsReadsZero() {
        assertEquals(0f, TelemetryHub.pickSpeed(false, -1, false, 55f), 0.001f);
    }

    @Test
    public void testFuelRateMatchesHondaCityTank() {
        // 40 L tank at 7.5 L/100 km: 100 km uses 7.5 L = 18.75 % of the tank
        assertEquals(18.75f, 100f * TelemetryHub.FUEL_PCT_PER_KM, 0.001f);
    }
}
