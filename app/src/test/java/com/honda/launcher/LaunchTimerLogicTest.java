package com.honda.launcher;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * End-to-End & edge-case unit tests for the 0-100 km/h Launch Timer state machine:
 * - Idle -> Armed -> Running -> Done transitions
 * - Linear interpolation calculation for exact 100 km/h timestamp
 * - Best time record persistence logic
 * - Stop & re-arm behavior
 */
public class LaunchTimerLogicTest {

    private static final int T_IDLE = 0, T_ARMED = 1, T_RUN = 2, T_DONE = 3;

    private static class TimerTester {
        int tState = T_IDLE;
        long tStart, lastT;
        float lastV, tBest = -1f, tRes;

        void timerStep(float v, long now) {
            if (tState == T_IDLE && v <= 1f) {
                tState = T_ARMED;
            } else if (tState == T_ARMED && v > 2f) {
                tStart = now;
                tState = T_RUN;
            } else if (tState == T_RUN && v >= 100f) {
                tState = T_DONE;
                float dt = (now - tStart) / 1000f;
                if (v > lastV && lastT > tStart) {
                    float frac = (100f - lastV) / (v - lastV);
                    dt = (lastT - tStart) / 1000f + frac * (now - lastT) / 1000f;
                }
                tRes = dt;
                if (tBest < 0 || dt < tBest) {
                    tBest = dt;
                }
            } else if (tState == T_DONE && v <= 1f) {
                tState = T_ARMED;
            }
            lastV = v;
            lastT = now;
        }
    }

    @Test
    public void testFullLaunchCycle() {
        TimerTester t = new TimerTester();
        long time = 10000;

        // Car stopped (0 km/h) -> Arms
        t.timerStep(0f, time);
        assertEquals(T_ARMED, t.tState);

        // Launches (> 2 km/h)
        time += 200;
        t.timerStep(15f, time);
        assertEquals(T_RUN, t.tState);
        assertEquals(time, t.tStart);

        // Accelerating
        time += 1000; t.timerStep(40f, time);
        time += 1000; t.timerStep(70f, time);
        time += 1000; t.timerStep(90f, time); // 90 km/h at time 13200
        time += 1000; t.timerStep(110f, time); // 110 km/h at time 14200 -> hits 100 km/h!

        assertEquals(T_DONE, t.tState);
        // Between 90 and 110: 100 is exactly halfway (frac = 0.5)
        // Expected dt = (13200 - 10200)/1000 + 0.5 * (1000)/1000 = 3.0 + 0.5 = 3.5s
        assertEquals(3.5f, t.tRes, 0.01f);
        assertEquals(3.5f, t.tBest, 0.01f);

        // Slows down to a stop -> Re-arms
        time += 2000; t.timerStep(0f, time);
        assertEquals(T_ARMED, t.tState);
        // Best record is preserved
        assertEquals(3.5f, t.tBest, 0.01f);
    }

    @Test
    public void testAbortedLaunchDoesNotTriggerDone() {
        TimerTester t = new TimerTester();
        long time = 10000;

        t.timerStep(0f, time);
        assertEquals(T_ARMED, t.tState);

        time += 500;
        t.timerStep(30f, time);
        assertEquals(T_RUN, t.tState);

        // Aborted launch: car brakes to 0 without reaching 100
        time += 1000;
        t.timerStep(10f, time);
        assertEquals(T_RUN, t.tState);

        time += 1000;
        t.timerStep(0f, time);
        // Does not trigger DONE, stays in RUN until stopped
        assertEquals(-1f, t.tBest, 0.01f);
    }
}
