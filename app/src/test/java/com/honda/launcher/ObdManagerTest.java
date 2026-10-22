package com.honda.launcher;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.Assert.*;

/**
 * End-to-End & edge-case unit tests for ObdManager:
 * - Protocol parsing and initialization
 * - PID bitmask detection (0100, 0120, 0140, 01A0)
 * - Scanner detachment detection via EOF and missing prompt '>' timeout
 * - Auto-connect toggle and manual connect request lifecycle
 * - DTC Mode 03 diagnostic trouble code parsing and edge cases
 */
public class ObdManagerTest {

    private ObdManager obd;

    @Before
    public void setUp() {
        obd = new ObdManager();
    }

    // ----------------- DTC Mode 03 Tests -----------------

    @Test
    public void testParseDtc_SinglePowertrainCode() throws Exception {
        Method m = ObdManager.class.getDeclaredMethod("parseDtc", String.class);
        m.setAccessible(true);

        // "43 03 00" -> P0300 (Random Misfire)
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) m.invoke(obd, "4303000000");
        assertNotNull(codes);
        assertEquals(1, codes.size());
        assertEquals("P0300", codes.get(0));
    }

    @Test
    public void testParseDtc_MultipleMixedCodes() throws Exception {
        Method m = ObdManager.class.getDeclaredMethod("parseDtc", String.class);
        m.setAccessible(true);

        // 0x0102 = P0102 (Powertrain: bits 15-14 = 00)
        // 0x4100 = C0100 (Chassis: bits 15-14 = 01)
        // 0x8100 = B0100 (Body: bits 15-14 = 10)
        // 0xC100 = U0100 (Network: bits 15-14 = 11)
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) m.invoke(obd, "43010241008100C1000000");
        assertNotNull(codes);
        assertEquals(4, codes.size());
        assertEquals("P0102", codes.get(0));
        assertEquals("C0100", codes.get(1));
        assertEquals("B0100", codes.get(2));
        assertEquals("U0100", codes.get(3));
    }

    @Test
    public void testParseDtc_EdgeCases() throws Exception {
        Method m = ObdManager.class.getDeclaredMethod("parseDtc", String.class);
        m.setAccessible(true);

        // No codes
        @SuppressWarnings("unchecked")
        List<String> empty1 = (List<String>) m.invoke(obd, "430000");
        assertTrue(empty1.isEmpty());

        // NODATA response
        @SuppressWarnings("unchecked")
        List<String> empty2 = (List<String>) m.invoke(obd, "NODATA");
        assertTrue(empty2.isEmpty());

        // CAN ERROR response
        @SuppressWarnings("unchecked")
        List<String> empty3 = (List<String>) m.invoke(obd, "CAN ERROR");
        assertTrue(empty3.isEmpty());

        // Garbage or empty
        @SuppressWarnings("unchecked")
        List<String> empty4 = (List<String>) m.invoke(obd, "");
        assertTrue(empty4.isEmpty());
    }

    // ----------------- Hex Decoding Tests -----------------

    @Test
    public void testHexDecoding() throws Exception {
        Method m = ObdManager.class.getDeclaredMethod("hex", String.class, String.class, int.class);
        m.setAccessible(true);

        // Mode 01 PID 0C (RPM): "410C1A20" -> 0x1A20 = 6688 -> 6688 / 4 = 1672 RPM
        int rawRpm = (Integer) m.invoke(obd, "410C1A20", "410C", 2);
        assertEquals(0x1A20, rawRpm);

        // Mode 01 PID 0D (Speed): "410D3C" -> 0x3C = 60 km/h
        int rawSpeed = (Integer) m.invoke(obd, "410D3C", "410D", 1);
        assertEquals(60, rawSpeed);

        // Mode 01 PID 05 (Coolant): "410555" -> 0x55 = 85 -> 85 - 40 = 45 °C
        int rawCoolant = (Integer) m.invoke(obd, "410555", "4105", 1);
        assertEquals(85, rawCoolant);

        // Prefix not present
        int notFound = (Integer) m.invoke(obd, "NODATA", "410C", 2);
        assertEquals(-1, notFound);

        // Incomplete hex
        int incomplete = (Integer) m.invoke(obd, "410C1", "410C", 2);
        assertEquals(-1, incomplete);
    }

    // ----------------- Detachment & Send Timeout Tests -----------------

    @Test
    public void testSend_NormalPromptResponse() throws Exception {
        Method m = ObdManager.class.getDeclaredMethod("send", InputStream.class, java.io.OutputStream.class, String.class, int.class);
        m.setAccessible(true);

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = new InputStream() {
            private int pos = 0;
            private final byte[] data = "41 0C 1A F8\r\r>".getBytes();
            @Override public int available() {
                // Returns bytes only after send() has flushed command to out
                return (out.size() > 0) ? (data.length - pos) : 0;
            }
            @Override public int read() {
                if (out.size() == 0 || pos >= data.length) return -1;
                return data[pos++] & 0xFF;
            }
        };

        String result = (String) m.invoke(obd, in, out, "010C", 1000);
        assertEquals("410C1AF8", result);
        assertEquals("010C\r", out.toString());
    }

    @Test
    public void testSend_DetachedScannerThrowsIOExceptionOnMissingPrompt() throws Exception {
        Method m = ObdManager.class.getDeclaredMethod("send", InputStream.class, java.io.OutputStream.class, String.class, int.class);
        m.setAccessible(true);

        // Detached scanner loses power: stream has no bytes or no prompt '>'
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]); // EOF/empty
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            m.invoke(obd, in, out, "010C", 100);
            fail("Expected InvocationTargetException wrapping IOException for detached scanner");
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertTrue(e.getCause() instanceof IOException);
            assertTrue(e.getCause().getMessage().contains("No response") || e.getCause().getMessage().contains("OBD"));
        }
    }

    @Test
    public void testSend_DetachedScannerThrowsIOExceptionOnStreamClosed() throws Exception {
        Method m = ObdManager.class.getDeclaredMethod("send", InputStream.class, java.io.OutputStream.class, String.class, int.class);
        m.setAccessible(true);

        // Custom stream that returns -1 (EOF)
        InputStream eofStream = new InputStream() {
            private boolean eof = false;
            @Override public int available() { return eof ? 0 : 1; }
            @Override public int read() { eof = true; return -1; }
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            m.invoke(obd, eofStream, out, "010C", 100);
            fail("Expected IOException on EOF");
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertTrue(e.getCause() instanceof IOException);
            String msg = e.getCause().getMessage().toLowerCase();
            assertTrue(msg.contains("eof") || msg.contains("disconnected") || msg.contains("closed") || msg.contains("no response"));
        }
    }

    // ----------------- Auto-Connect Toggle Lifecycle -----------------

    @Test
    public void testAutoConnectToggleDefaults() {
        assertTrue("Auto-connect must be enabled by default", obd.autoConnect);
        assertFalse("Manual connect must be false initially", obd.manualConnectRequested);
        assertEquals(ObdManager.STATE_DISCONNECTED, obd.state);
        assertFalse(obd.connected);
    }

    @Test
    public void testReconnectNowSetsManualConnectFlag() {
        obd.autoConnect = false;
        obd.reconnectNow();
        assertTrue("reconnectNow must set manualConnectRequested to true", obd.manualConnectRequested);
        obd.stop();
    }

    @Test
    public void testManualDeviceSelection() {
        obd.setManualDevice("00:1D:A5:68:98:8B");
        assertEquals("00:1D:A5:68:98:8B", obd.manualDeviceAddress);
        assertTrue(obd.manualConnectRequested);
        obd.stop();
    }
}
