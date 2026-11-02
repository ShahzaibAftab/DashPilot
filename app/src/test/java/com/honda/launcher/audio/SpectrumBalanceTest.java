package com.honda.launcher.audio;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for SpectrumBalance: reading bass / mid / treble balance from a Visualizer FFT
 * using real frequencies, and the corrections it asks for.
 */
public class SpectrumBalanceTest {

    private static final int SAMPLE_RATE_MILLIHZ = 44_100_000;
    private static final int CAPTURE = 1024;   // bin width 43 Hz

    /** Builds an FFT frame with a flat magnitude per band (bins are k * 44100 / 1024 Hz). */
    private static byte[] fft(int bassMag, int midMag, int trebleMag) {
        byte[] f = new byte[CAPTURE];
        double binHz = 44100.0 / CAPTURE;
        for (int k = 1; k < CAPTURE / 2; k++) {
            double hz = k * binHz;
            int mag = hz < 250 ? bassMag : hz < 4000 ? midMag : hz < 16000 ? trebleMag : 0;
            f[2 * k] = (byte) mag;
        }
        return f;
    }

    @Test
    public void testBassHeavyTrackReadsHighBassTilt() {
        SpectrumBalance b = SpectrumBalance.fromFft(fft(100, 6, 2), SAMPLE_RATE_MILLIHZ);
        assertNotNull(b);
        assertTrue("Bass-heavy mix: strong bass tilt", b.bassTiltDb > SpectrumBalance.TARGET_BASS_TILT_DB);
        assertTrue("Asks for less bass", b.bassCorrectionDb() < 0f);
    }

    @Test
    public void testThinTrackAsksForBass() {
        SpectrumBalance b = SpectrumBalance.fromFft(fft(2, 20, 6), SAMPLE_RATE_MILLIHZ);
        assertNotNull(b);
        assertTrue(b.bassTiltDb < 0f);
        assertEquals("Capped boost", SpectrumBalance.MAX_BASS_CORRECTION_DB, b.bassCorrectionDb(), 0.001f);
    }

    @Test
    public void testOldSplitBugFixed() {
        // Energy only between 300 Hz and 3 kHz (vocals) is mids, not bass. The old code counted
        // everything below ~3.3 kHz as "low" and called every song bass heavy.
        byte[] f = new byte[CAPTURE];
        double binHz = 44100.0 / CAPTURE;
        for (int k = 1; k < CAPTURE / 2; k++) {
            double hz = k * binHz;
            if (hz > 300 && hz < 3000) f[2 * k] = 30;
        }
        SpectrumBalance b = SpectrumBalance.fromFft(f, SAMPLE_RATE_MILLIHZ);
        assertNotNull(b);
        assertTrue("No bass energy reads as a big bass deficit", b.bassTiltDb < -20f);
    }

    @Test
    public void testSilenceAndBadInput() {
        assertNull(SpectrumBalance.fromFft(new byte[CAPTURE], SAMPLE_RATE_MILLIHZ));
        assertNull(SpectrumBalance.fromFft(null, SAMPLE_RATE_MILLIHZ));
        assertNull(SpectrumBalance.fromFft(new byte[16], SAMPLE_RATE_MILLIHZ));
        assertNull(SpectrumBalance.fromFft(fft(10, 10, 10), 0));
    }

    @Test
    public void testTrebleCorrectionLimits() {
        // A very dull mix asks for treble, but never more than the hiss-safe maximum
        assertEquals(SpectrumBalance.MAX_TREBLE_BOOST_DB, new SpectrumBalance(6f, -40f).trebleCorrectionDb(), 0.001f);
        assertEquals(-SpectrumBalance.MAX_TREBLE_CUT_DB, new SpectrumBalance(6f, 10f).trebleCorrectionDb(), 0.001f);
    }

    @Test
    public void testBlendSmoothsTowardNewMeasurement() {
        SpectrumBalance a = new SpectrumBalance(0f, -10f);
        SpectrumBalance b = a.blend(new SpectrumBalance(10f, 0f), 0.1f);
        assertEquals(1f, b.bassTiltDb, 0.001f);
        assertEquals(-9f, b.trebleTiltDb, 0.001f);
        assertSame(a, a.blend(null, 0.5f));
    }
}
