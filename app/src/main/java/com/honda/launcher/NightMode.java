package com.honda.launcher;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Night mode: dims the dashboard after dark. AUTO follows today's sunset and sunrise at the
 * car's last GPS position, worked out offline with the standard sunrise equation
 * (Almanac for Computers / NOAA), accurate to a few minutes.
 */
final class NightMode {
    static final int AUTO = 0;
    static final int ON = 1;
    static final int OFF = 2;

    static final String PREF_MODE = "night_mode";
    static final String PREF_BRIGHTNESS = "night_brightness";
    static final int DEFAULT_BRIGHTNESS = 35;
    static final int MIN_BRIGHTNESS = 10;

    /** AUTO uses this until the GPS has had a first fix: Lahore, as the car is in Pakistan. */
    static final double FALLBACK_LAT = 31.52;
    static final double FALLBACK_LON = 74.36;

    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 24 * HOUR_MS;
    /** Sun centre 50' below the horizon: refraction plus the sun's radius. */
    private static final double ZENITH = 90.833;

    static boolean isNight(int mode, double lat, double lon, long nowMs, TimeZone tz) {
        if (mode == ON) return true;
        if (mode == OFF) return false;
        long[] sun = sunriseSunset(lat, lon, nowMs, tz);
        if (sun == null) return false;   // polar day or night; never the case in Pakistan
        return nowMs < sun[0] || nowMs >= sun[1];
    }

    /** Opacity (0-1) of the dark layer over the dashboard for a night brightness percentage. */
    static float dimAlpha(int brightnessPct) {
        int p = Math.max(MIN_BRIGHTNESS, Math.min(100, brightnessPct));
        return (100 - p) / 100f * 0.5f;
    }

    /**
     * Sunrise and sunset on the local calendar day that contains {@code dayMs}.
     *
     * @return {sunriseMs, sunsetMs}, or null when the sun does not rise or set that day
     */
    static long[] sunriseSunset(double lat, double lon, long dayMs, TimeZone tz) {
        Calendar local = Calendar.getInstance(tz);
        local.setTimeInMillis(dayMs);
        int dayOfYear = local.get(Calendar.DAY_OF_YEAR);

        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
        long utcMidnight = utc.getTimeInMillis();

        double lngHour = lon / 15.0;
        Double rise = sunUtcHours(dayOfYear, lat, lngHour, true);
        Double set = sunUtcHours(dayOfYear, lat, lngHour, false);
        if (rise == null || set == null) return null;

        // UT hours wrap at midnight UTC; put sunrise before and sunset after local solar noon
        long solarNoon = utcMidnight + (long) ((12 - lngHour) * HOUR_MS);
        long riseMs = utcMidnight + (long) (rise * HOUR_MS);
        long setMs = utcMidnight + (long) (set * HOUR_MS);
        while (riseMs > solarNoon) riseMs -= DAY_MS;
        while (riseMs <= solarNoon - DAY_MS) riseMs += DAY_MS;
        while (setMs < solarNoon) setMs += DAY_MS;
        while (setMs >= solarNoon + DAY_MS) setMs -= DAY_MS;
        return new long[]{riseMs, setMs};
    }

    /** Time of sunrise or sunset in UT hours (0-24), or null if it does not happen. */
    private static Double sunUtcHours(int dayOfYear, double lat, double lngHour, boolean rising) {
        double t = dayOfYear + ((rising ? 6 : 18) - lngHour) / 24.0;
        double meanAnomaly = 0.9856 * t - 3.289;
        double trueLong = norm360(meanAnomaly + 1.916 * sinDeg(meanAnomaly)
                + 0.020 * sinDeg(2 * meanAnomaly) + 282.634);

        double ra = norm360(Math.toDegrees(Math.atan(0.91764 * Math.tan(Math.toRadians(trueLong)))));
        ra += Math.floor(trueLong / 90) * 90 - Math.floor(ra / 90) * 90;   // same quadrant as trueLong
        ra /= 15;

        double sinDec = 0.39782 * sinDeg(trueLong);
        double cosDec = Math.cos(Math.asin(sinDec));
        double cosH = (cosDeg(ZENITH) - sinDec * sinDeg(lat)) / (cosDec * cosDeg(lat));
        if (cosH > 1 || cosH < -1) return null;

        double hourAngle = Math.toDegrees(Math.acos(cosH));
        if (rising) hourAngle = 360 - hourAngle;
        double localMeanTime = hourAngle / 15 + ra - 0.06571 * t - 6.622;
        double ut = localMeanTime - lngHour;
        return ((ut % 24) + 24) % 24;
    }

    private static double norm360(double deg) {
        return ((deg % 360) + 360) % 360;
    }

    private static double sinDeg(double deg) { return Math.sin(Math.toRadians(deg)); }
    private static double cosDeg(double deg) { return Math.cos(Math.toRadians(deg)); }
}
