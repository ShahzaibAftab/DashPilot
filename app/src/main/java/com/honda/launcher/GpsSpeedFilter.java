package com.honda.launcher;

/**
 * Turns raw GPS fixes into a steady speedometer reading when no OBD-II scanner is connected.
 *
 * Doppler speed from the GPS chip (Location.getSpeed) stays accurate even when the position
 * fix is poor, which is normal for head-unit GPS antennas, so it is used whatever the reported
 * accuracy. Speed is only derived from the distance between fixes when the chip reports none.
 * A fix that yields no usable speed leaves the reading unchanged instead of dropping it to 0;
 * the caller zeroes the needle once fixes stop arriving altogether.
 */
final class GpsSpeedFilter {
    static final float MAX_KMH = 260f;
    /** Below this the car is treated as standing still (stationary GPS jitter). */
    static final float ZERO_SNAP_KMH = 1.8f;
    /** Stationary jitter grows with a weak fix, so the snap widens when accuracy is poor. */
    static final float ZERO_SNAP_WEAK_FIX_KMH = 5f;
    static final float WEAK_FIX_ACCURACY_M = 30f;
    /** Distance-derived speed needs both fixes at least this good, or position noise swamps it. */
    static final float DERIVED_MAX_ACCURACY_M = 50f;

    private float speedKmh;
    private boolean hasPrev;
    private long prevTimeMs;
    private boolean prevHasAccuracy;
    private float prevAccuracyM;

    /**
     * Feeds one GPS fix.
     *
     * @param distanceFromPrevM distance to the previous fix in metres; ignored when there is none
     * @return true if the fix updated the reading, false if it carried no usable speed
     */
    boolean onFix(boolean hasSpeed, float speedMps, boolean hasAccuracy, float accuracyM,
                  long timeMs, float distanceFromPrevM) {
        float raw = Float.NaN;
        if (hasSpeed) {
            raw = speedMps * 3.6f;
        } else if (hasPrev) {
            long dt = timeMs - prevTimeMs;
            if (dt >= 400 && dt <= 5000
                    && hasAccuracy && accuracyM <= DERIVED_MAX_ACCURACY_M
                    && prevHasAccuracy && prevAccuracyM <= DERIVED_MAX_ACCURACY_M) {
                raw = (distanceFromPrevM / (dt / 1000f)) * 3.6f;
            }
        }
        hasPrev = true;
        prevTimeMs = timeMs;
        prevHasAccuracy = hasAccuracy;
        prevAccuracyM = accuracyM;

        if (Float.isNaN(raw) || raw < 0f || raw > MAX_KMH) return false;

        float snap = (hasAccuracy && accuracyM > WEAK_FIX_ACCURACY_M) ? ZERO_SNAP_WEAK_FIX_KMH : ZERO_SNAP_KMH;
        if (raw < snap) {
            speedKmh = 0f;
        } else if (speedKmh <= 0f) {
            speedKmh = raw;
        } else {
            // Smooth with exponential moving average to eliminate sudden jumps
            speedKmh = (speedKmh * 0.40f) + (raw * 0.60f);
        }
        return true;
    }

    float getSpeedKmh() { return speedKmh; }
}
