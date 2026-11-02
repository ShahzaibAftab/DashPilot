package com.honda.launcher.audio;

/**
 * A song's tonal balance measured from the Visualizer FFT: how strong the bass (20-250 Hz) and
 * the treble (4-16 kHz) are compared with the mids (250 Hz-4 kHz), in dB per octave. Equal
 * energy per octave (pink noise) reads 0 for both; typical modern music has a few dB more
 * bass per octave than mids and around 9 dB less treble.
 *
 * Auto tuning nudges each song part of the way toward a target balance, so a thin recording
 * gets more bass and a bass-heavy one gets less, instead of every song getting the same boost.
 */
final class SpectrumBalance {
    static final float BASS_LOW_HZ = 20f;
    static final float BASS_HIGH_HZ = 250f;
    static final float MID_HIGH_HZ = 4000f;
    static final float TREBLE_HIGH_HZ = 16000f;

    /** Full, punchy bass and clear highs without harshness. */
    static final float TARGET_BASS_TILT_DB = 6f;
    static final float TARGET_TREBLE_TILT_DB = -9f;
    /** Correct only part of the difference: the aim is a nudge, not a rewrite of the mix. */
    static final float CORRECTION_SHARE = 0.4f;
    static final float MAX_BASS_CORRECTION_DB = 2.5f;
    /** Less room for treble lift: on low-bitrate Bluetooth audio it mostly adds hiss. */
    static final float MAX_TREBLE_BOOST_DB = 2f;
    static final float MAX_TREBLE_CUT_DB = 2.5f;

    private static final double BASS_OCTAVES = Math.log(BASS_HIGH_HZ / BASS_LOW_HZ) / Math.log(2);
    private static final double MID_OCTAVES = Math.log(MID_HIGH_HZ / BASS_HIGH_HZ) / Math.log(2);
    private static final double TREBLE_OCTAVES = Math.log(TREBLE_HIGH_HZ / MID_HIGH_HZ) / Math.log(2);
    /** Floor so an empty band reads as very weak instead of minus infinity. */
    private static final double POWER_FLOOR = 1e-3;

    final float bassTiltDb;
    final float trebleTiltDb;

    SpectrumBalance(float bassTiltDb, float trebleTiltDb) {
        this.bassTiltDb = bassTiltDb;
        this.trebleTiltDb = trebleTiltDb;
    }

    /**
     * Measures one Visualizer FFT frame. Layout: [0] DC, [1] Nyquist, then real/imaginary pairs
     * for bins 1..n/2-1, where bin k is at k * sampleRate / captureSize Hz.
     *
     * @return null when the frame is too short or the mids are silent
     */
    static SpectrumBalance fromFft(byte[] fft, int samplingRateMilliHz) {
        if (fft == null || fft.length < 64 || samplingRateMilliHz <= 0) return null;
        int captureSize = fft.length;
        double binHz = (samplingRateMilliHz / 1000.0) / captureSize;

        double bass = 0, mid = 0, treble = 0;
        int midBins = 0;
        for (int k = 1; k < captureSize / 2; k++) {
            double hz = k * binHz;
            if (hz >= TREBLE_HIGH_HZ) break;
            double re = fft[2 * k], im = fft[2 * k + 1];
            double power = re * re + im * im;
            if (hz < BASS_LOW_HZ) continue;
            if (hz < BASS_HIGH_HZ) {
                bass += power;
            } else if (hz < MID_HIGH_HZ) {
                mid += power;
                midBins++;
            } else {
                treble += power;
            }
        }
        if (midBins == 0 || mid / midBins < 1.0) return null;   // silence or a near-empty frame

        double bassDb = perOctaveDb(bass, BASS_OCTAVES);
        double midDb = perOctaveDb(mid, MID_OCTAVES);
        double trebleDb = perOctaveDb(treble, TREBLE_OCTAVES);
        return new SpectrumBalance((float) (bassDb - midDb), (float) (trebleDb - midDb));
    }

    private static double perOctaveDb(double power, double octaves) {
        return 10 * Math.log10(power / octaves + POWER_FLOOR);
    }

    /** Smooths toward a new measurement; weight 0..1 is how much the new one counts. */
    SpectrumBalance blend(SpectrumBalance next, float weight) {
        if (next == null) return this;
        float w = Math.max(0f, Math.min(1f, weight));
        return new SpectrumBalance(bassTiltDb + (next.bassTiltDb - bassTiltDb) * w,
                trebleTiltDb + (next.trebleTiltDb - trebleTiltDb) * w);
    }

    /** Bass change (dB) that moves this song toward the target balance. */
    float bassCorrectionDb() {
        float c = (TARGET_BASS_TILT_DB - bassTiltDb) * CORRECTION_SHARE;
        return Math.max(-MAX_BASS_CORRECTION_DB, Math.min(MAX_BASS_CORRECTION_DB, c));
    }

    /** Treble change (dB) that moves this song toward the target balance. */
    float trebleCorrectionDb() {
        float c = (TARGET_TREBLE_TILT_DB - trebleTiltDb) * CORRECTION_SHARE;
        return Math.max(-MAX_TREBLE_CUT_DB, Math.min(MAX_TREBLE_BOOST_DB, c));
    }
}
