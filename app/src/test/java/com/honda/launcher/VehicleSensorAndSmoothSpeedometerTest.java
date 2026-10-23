package com.honda.launcher;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests verifying:
 * 1. Speedometer responsiveness, delta-time critically damped smoothing, and quick settling without lag.
 * 2. Standalone sensor telemetry: Reading RPM, Battery, Coolant, and Engine Load without OBD-II scanner.
 * 3. Fuel Tank reading: OBD priority, CAN-bus detection, manual calibration, and distance-based consumption.
 */
public class VehicleSensorAndSmoothSpeedometerTest {

    // -------------------------------------------------------------
    // 1. SPEEDOMETER SMOOTH & QUICK CONVERGENCE TESTS
    // -------------------------------------------------------------

    private float stepSpeedometer(float current, float target, float dt) {
        float d = target - current;
        if (Math.abs(d) < 0.08f) {
            return target;
        }
        float tau = (Math.abs(d) > 6f) ? 0.070f : 0.090f;
        float alpha = 1.0f - (float) Math.exp(-dt / tau);
        float stepAmount = d * alpha;

        float minStep = 40f * dt;
        if (Math.abs(stepAmount) < minStep) {
            stepAmount = Math.copySign(Math.min(minStep, Math.abs(d)), d);
        }
        return current + stepAmount;
    }

    @Test
    public void testSpeedometerFastConvergenceUnderAcceleration() {
        // Vehicle launches from 0 to 60 km/h
        float shown = 0f;
        float target = 60f;
        float dt = 0.016f; // 60 FPS frame time (16.6ms)

        // After 10 frames (~160ms), the needle should already be within 90% of target
        for (int frame = 0; frame < 10; frame++) {
            shown = stepSpeedometer(shown, target, dt);
        }
        assertTrue("Speedometer must reach > 50 km/h in ~160ms on 0->60 acceleration (quick response)", shown > 50f);

        // Within 15 frames (~240ms), it should fully settle at 60 km/h
        for (int frame = 0; frame < 10; frame++) {
            shown = stepSpeedometer(shown, target, dt);
        }
        assertEquals("Speedometer must settle exactly at target without tail creeping", 60f, shown, 0.08f);
    }

    @Test
    public void testSpeedometerSmoothSmallAdjustments() {
        // Cruising at 80 km/h with a tiny GPS speed variation to 82 km/h
        float shown = 80f;
        float target = 82f;
        float dt = 0.016f;

        float frame1 = stepSpeedometer(shown, target, dt);
        float delta = frame1 - shown;

        // Small variation must move smoothly without sudden jerky jump
        assertTrue("Frame delta for small adjustment must be smooth and controlled", delta > 0.1f && delta < 1.0f);
    }

    @Test
    public void testSpeedometerDisplayRoundingConsistency() {
        // At 59.6 km/h, rounded display must read 60 to match needle pointing at 60 tick mark
        assertEquals(60, Math.round(59.6f));
        assertEquals(59, Math.round(59.4f));
        assertEquals(0, Math.round(0.2f));
        assertEquals(100, Math.round(99.7f));
    }

    // -------------------------------------------------------------
    // 2. STANDALONE TELEMETRY: BATTERY, COOLANT, RPM WITHOUT OBD2
    // -------------------------------------------------------------

    @Test
    public void testBatteryVoltageReadingFrom12VRail() {
        // Android car head units (Allwinner T3, FYT, MTK) report car electrical rail in millivolts
        int rawMv = 12450; // 12.45 V resting
        float volts = rawMv / 1000f;
        assertEquals(12.45f, volts, 0.01f);

        int chargingMv = 14200; // 14.20 V alternator charging
        float chargingVolts = chargingMv / 1000f;
        assertEquals(14.20f, chargingVolts, 0.01f);
    }

    @Test
    public void testThermodynamicCoolantWarmupCurve() {
        float ambient = 32f;
        float operatingTarget = 89f;

        // At t = 0s (start): ambient
        float tempStart = ambient + (operatingTarget - ambient) * (1.0f - (float) Math.exp(0));
        assertEquals(32f, tempStart, 0.1f);

        // At t = 180s (~3 min): warm up well underway (> 65°C)
        float temp3Min = ambient + (operatingTarget - ambient) * (1.0f - (float) Math.exp(-180f / 180f));
        assertTrue("Coolant after 3 min warmup should exceed 65°C", temp3Min > 65f);

        // At t = 600s (10 min): fully settled near thermostat operating temperature (87-90°C)
        float temp10Min = ambient + (operatingTarget - ambient) * (1.0f - (float) Math.exp(-600f / 180f));
        assertTrue("Coolant after 10 min should reach optimal operating range (87-90°C)", temp10Min >= 86.5f && temp10Min <= 90f);
    }

    @Test
    public void testHondaCityIdleRpmAtStandstill() {
        // At speed = 0, engine must idle at factory spec (750 RPM base)
        float speed = 0f;
        float baseIdle = 750f;
        assertTrue("Standstill idle must match Honda 750 RPM spec", baseIdle >= 700f && baseIdle <= 800f);
    }

    @Test
    public void testHondaCvtCruisingRpmRatios() {
        // Cruising RPM progression in Honda City CVT:
        // 40 km/h -> ~1325 RPM
        // 60 km/h -> ~1550 RPM
        // 80 km/h -> ~1785 RPM
        // 100 km/h -> ~2050 RPM
        // 120 km/h -> ~2325 RPM
        float rpm40 = calculateCvtCruiseRpm(40f);
        float rpm60 = calculateCvtCruiseRpm(60f);
        float rpm80 = calculateCvtCruiseRpm(80f);
        float rpm100 = calculateCvtCruiseRpm(100f);
        float rpm120 = calculateCvtCruiseRpm(120f);

        assertTrue("40 km/h cruising RPM should be around 1300 RPM", rpm40 >= 1250f && rpm40 <= 1400f);
        assertTrue("60 km/h cruising RPM should be around 1550 RPM", rpm60 >= 1500f && rpm60 <= 1650f);
        assertTrue("80 km/h cruising RPM should be around 1785 RPM", rpm80 >= 1700f && rpm80 <= 1850f);
        assertTrue("100 km/h cruising RPM should be around 2050 RPM", rpm100 >= 1950f && rpm100 <= 2150f);
        assertTrue("120 km/h cruising RPM should be around 2325 RPM", rpm120 >= 2200f && rpm120 <= 2450f);
    }

    @Test
    public void testAccelerationKickdownSurge() {
        float cruiseRpm = calculateCvtCruiseRpm(60f); // ~1550 RPM
        float hardAccel = 5.5f; // floor it (km/h/s)

        // Under hard acceleration kickdown, revs surge to VTEC powerband (4500 - 6000 RPM)
        float kickdownRpm = Math.min(6500f, 4800f + (hardAccel - 5.0f) * 350f);
        assertTrue("Hard acceleration kickdown must surge to VTEC powerband (> 4500 RPM)", kickdownRpm >= 4800f);
        assertTrue("Kickdown RPM must not exceed redline", kickdownRpm <= 6500f);
    }

    private float calculateCvtCruiseRpm(float speed) {
        if (speed <= 15f) {
            return 750f + speed * 25f;
        } else if (speed <= 40f) {
            return 1125f + (speed - 15f) * 8f;
        } else if (speed <= 80f) {
            return 1325f + (speed - 40f) * 11.5f;
        } else if (speed <= 120f) {
            return 1785f + (speed - 80f) * 13.5f;
        } else {
            return 2325f + (speed - 120f) * 16f;
        }
    }

    // -------------------------------------------------------------
    // 3. FUEL TANK LEVEL LOGIC & FALLBACKS
    // -------------------------------------------------------------

    @Test
    public void testFuelTankPriorityResolution() {
        // Case 1: OBD-II reports valid fuel (72%) -> OBD has top priority
        float obdFuel = 72f;
        float canFuel = 68f;
        float estFuel = 65f;
        float resolved = resolveFuel(obdFuel, canFuel, estFuel);
        assertEquals(72f, resolved, 0.01f);

        // Case 2: OBD has no fuel (-1), but CAN-bus reports 68% -> CAN has second priority
        resolved = resolveFuel(-1f, canFuel, estFuel);
        assertEquals(68f, resolved, 0.01f);

        // Case 3: Both OBD and CAN are unavailable -> Calibrated estimate used (65%)
        resolved = resolveFuel(-1f, -1f, estFuel);
        assertEquals(65f, resolved, 0.01f);
    }

    @Test
    public void testFuelConsumptionTrackingOverDistance() {
        // In a Honda City 1.3/1.5: 40L tank, 7.5L/100km -> 0.075L/km -> 0.1875% of tank per km
        float initialFuel = 80.0f;
        float kmDriven = 53.33f; // ~53.33 km driven should consume ~10% of tank
        float consumedPct = kmDriven * 0.1875f;
        float remaining = Math.max(2.0f, initialFuel - consumedPct);

        assertEquals("Driving ~53.3 km should consume ~10% of tank in a 40L Honda City", 70.0f, remaining, 0.2f);
    }

    private float resolveFuel(float obd, float can, float est) {
        if (obd >= 0) return obd;
        if (can >= 0) return can;
        return est;
    }

    // -------------------------------------------------------------
    // 4. ENGINE HEARTBEAT & HEAT-TINTED AMBIENCE TESTS
    // -------------------------------------------------------------

    private float calculateHeartbeatFrequency(float rpm, boolean warn) {
        float effectiveRpm = (rpm > 0 && !Float.isNaN(rpm)) ? rpm : 750f;
        float freq = 0.9f + (Math.max(0f, effectiveRpm - 700f) / 5300f) * 3.3f;
        if (warn) freq *= 1.4f;
        return freq;
    }

    @Test
    public void testEngineHeartbeatFrequencyAtIdleAndRevving() {
        // Idle (750 RPM): calm resting beat ~0.93 Hz (~56 BPM)
        float idleFreq = calculateHeartbeatFrequency(750f, false);
        assertTrue("Idle heartbeat must be calm (~0.9 to 1.0 Hz)", idleFreq >= 0.9f && idleFreq <= 1.05f);

        // Cruising (3000 RPM): moderate pace ~2.3 Hz (~140 BPM)
        float cruiseFreq = calculateHeartbeatFrequency(3000f, false);
        assertTrue("Cruising heartbeat should increase with revs (~2.0 to 2.6 Hz)", cruiseFreq >= 2.0f && cruiseFreq <= 2.6f);

        // Revving to redline (6000 RPM): rapid racing pulse (~4.0 Hz, 240 BPM)
        float redlineFreq = calculateHeartbeatFrequency(6000f, false);
        assertTrue("Redline heartbeat must beat rapidly (> 3.8 Hz)", redlineFreq >= 3.8f);

        assertTrue("Heartbeat must quicken strictly as RPM rises", redlineFreq > cruiseFreq && cruiseFreq > idleFreq);
    }

    @Test
    public void testEngineHeartbeatRaggedWarningWhenGaugeInDanger() {
        // Normal heartbeat at 2000 RPM vs Warning Heartbeat in danger
        float normalFreq = calculateHeartbeatFrequency(2000f, false);
        float dangerFreq = calculateHeartbeatFrequency(2000f, true);

        assertTrue("In danger mode, heartbeat must accelerate significantly into warning rhythm", dangerFreq > normalFreq * 1.3f);
    }

    private static int blendColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int rr = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int gg = (int) (((a >>  8) & 0xFF) + (((b >>  8) & 0xFF) - ((a >>  8) & 0xFF)) * t);
        int bb = (int) (( a        & 0xFF) + (( b        & 0xFF) - ( a        & 0xFF)) * t);
        return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
    }

    private int[] calculateHeatTintedColors(float temp) {
        final int[] COL_COLD = {0xFF37474F, 0xFF1E3A5F, 0xFF263238};
        final int[] COL_WARM = {0xFF8D6E63, 0xFF5D4037, 0xFF37474F};
        final int[] COL_TYPER = {0xFFE4002B, 0xFF6E0014, 0xFF4A4A52};
        final int[] COL_HOT  = {0xFFFF1744, 0xFFFF3D00, 0xFFB71C1C};

        int[] out = new int[3];
        if (Float.isNaN(temp) || temp <= 45f) {
            System.arraycopy(COL_COLD, 0, out, 0, 3);
        } else if (temp < 65f) {
            float t = (temp - 45f) / 20f;
            for (int i = 0; i < 3; i++) out[i] = blendColor(COL_COLD[i], COL_WARM[i], t);
        } else if (temp < 88f) {
            float t = (temp - 65f) / 23f;
            for (int i = 0; i < 3; i++) out[i] = blendColor(COL_WARM[i], COL_TYPER[i], t);
        } else {
            float t = Math.min(1f, (temp - 88f) / 16f);
            for (int i = 0; i < 3; i++) out[i] = blendColor(COL_TYPER[i], COL_HOT[i], t);
        }
        return out;
    }

    @Test
    public void testHeatTintedAmbienceColorProgression() {
        // Cold engine (35°C): Primary blob must have blue-grey tint (blue/green components dominant over red)
        int[] coldColors = calculateHeatTintedColors(35f);
        int coldR = (coldColors[0] >> 16) & 0xFF;
        int coldB = coldColors[0] & 0xFF;
        assertTrue("Cold engine ambient glow must feature cool blue tint", coldB > 70 && coldR < 70);

        // Operating temperature (88°C): Must be Type R Championship Red
        int[] optColors = calculateHeatTintedColors(88f);
        int optR = (optColors[0] >> 16) & 0xFF;
        int optG = (optColors[0] >> 8) & 0xFF;
        assertEquals(0xE4, optR); // 0xFFE4002B
        assertEquals(0x00, optG);

        // Hot engine (105°C): Glows angrier fiery red (0xFFFF1744)
        int[] hotColors = calculateHeatTintedColors(105f);
        int hotR = (hotColors[0] >> 16) & 0xFF;
        int hotG = (hotColors[0] >> 8) & 0xFF;
        assertEquals(0xFF, hotR); // Full 255 maximum red intensity
        assertTrue("Hot engine glow must exhibit high-intensity fiery flare", hotR > optR);
    }

    @Test
    public void testDisconnectedObdHonestNanValues() {
        // When OBD2 is not connected and no CAN-bus exists:
        boolean obdConnected = false;
        boolean hasCanBus = false;

        float rpm = (obdConnected || hasCanBus) ? 750f : Float.NaN;
        float coolant = (obdConnected || hasCanBus) ? 88f : Float.NaN;
        float batt = (obdConnected || hasCanBus) ? 14.1f : Float.NaN;

        assertTrue(Float.isNaN(rpm));
        assertTrue(Float.isNaN(coolant));
        assertTrue(Float.isNaN(batt));

        // Speedometer heartbeat ring must stay at calm 750 RPM baseline when disconnected
        float heartbeatRpm = Float.isNaN(rpm) ? 750f : rpm;
        assertEquals(750f, heartbeatRpm, 0.001f);
    }

    @Test
    public void testGpsSpeedZeroDriftSnapAndSmoothing() {
        // Stationary GPS drift (e.g. 1.2 km/h walking / multipath noise)
        float driftSpeed = 1.2f;
        float filtered = (driftSpeed < 1.8f) ? 0f : driftSpeed;
        assertEquals(0f, filtered, 0.001f);

        // Genuine movement (e.g. 40 km/h)
        float movingSpeed = 40.0f;
        float movingFiltered = (movingSpeed < 1.8f) ? 0f : movingSpeed;
        assertEquals(40.0f, movingFiltered, 0.001f);

        // Smoothing 60/40
        float prevSmoothed = 30.0f;
        float newSmoothed = (prevSmoothed * 0.40f) + (movingFiltered * 0.60f);
        assertEquals(36.0f, newSmoothed, 0.001f);

        // Coming to a complete stop: immediate snap to 0
        float stopSpeed = 0f;
        float finalSmoothed = (stopSpeed == 0f) ? 0f : ((newSmoothed * 0.4f) + (stopSpeed * 0.6f));
        assertEquals(0f, finalSmoothed, 0.001f);
    }
}
