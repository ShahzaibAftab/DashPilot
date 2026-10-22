package com.honda.launcher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Minimal Bluetooth ELM327 reader (classic SPP). Looks for a PAIRED device whose
 * name contains OBD / ELM / LINK. Polls RPM, speed, coolant temp, battery volts,
 * ATF (transmission) temperature, fuel level, and diagnostic trouble codes (DTCs).
 * Runs on its own thread; does nothing if no scanner is paired or in range.
 */
public class ObdManager {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public volatile boolean connected;
    public volatile int rpm = -1, coolant = -999, speed = -1, load = -1;
    public volatile float volts = -1f;
    public volatile int atfTemp = -999;          // ATF / transmission oil temp (°C)
    public volatile float fuelLevel = -1f;       // fuel tank level 0-100 %
    public volatile List<String> dtcCodes = new ArrayList<>();  // active DTC codes
    public volatile boolean dtcScanned;          // true once DTC scan has completed at least once
    public volatile boolean hasFuelSupported;    // true if ECU confirms PID 012F
    public volatile boolean hasAtfSupported;     // true if ECU confirms PID 015C or 01B4
    public volatile boolean pidsDetected;        // true once PID bitmask scan has completed

    private volatile boolean running;
    private volatile int gen;
    private volatile BluetoothSocket sock;

    /** How many main-loop iterations between DTC scans (DTCs change slowly). */
    private static final int DTC_INTERVAL = 20;

    public synchronized void start() {
        final int id = ++gen;
        running = true;
        new Thread(new Runnable() { public void run() { loop(id); } }, "obd").start();
    }

    public synchronized void stop() {
        running = false;
        gen++;
        closeQuietly(sock);
    }

    private boolean alive(int id) { return running && id == gen; }

    private void loop(int id) {
        while (alive(id)) {
            try {
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                BluetoothDevice dev = (ad != null && ad.isEnabled()) ? find(ad) : null;
                if (dev == null) { Thread.sleep(8000); continue; }

                ad.cancelDiscovery();
                BluetoothSocket s = dev.createRfcommSocketToServiceRecord(SPP);
                sock = s;
                try {
                    s.connect();
                } catch (IOException e) {
                    closeQuietly(s);
                    s = (BluetoothSocket) dev.getClass().getMethod("createRfcommSocket", int.class)
                            .invoke(dev, 1);
                    sock = s;
                    s.connect();
                }
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();

                send(in, out, "ATZ", 3000);
                send(in, out, "ATE0", 1500);
                send(in, out, "ATL0", 1500);
                send(in, out, "ATS0", 1500);
                send(in, out, "ATH0", 1500);
                send(in, out, "ATSP0", 1500);
                connected = true;

                // ---- Detect supported PIDs once on connect ----
                // PID 0x00 returns bitmask of PIDs 01-20 support
                boolean hasFuel = false, hasAtf5C = false, hasAtfB4 = false;
                String r = send(in, out, "0100", 3000);
                int sup = hex(r, "4100", 4);
                // PID 04=load (bit 28), 05=coolant (bit 27), 0C=RPM (bit 20), 0D=speed (bit 19)
                // All core PIDs are in 01-20 range — already known to work

                // PID 0x20 returns bitmask of PIDs 21-40 support
                r = send(in, out, "0120", 3000);
                sup = hex(r, "4120", 4);
                if (sup >= 0) {
                    // PID 2F = fuel level (bit 17 in the 21-40 bitmask, i.e. PID 0x2F = 47 - 32 = bit 15 from left)
                    hasFuel = (sup & (1 << (32 - (0x2F - 0x20)))) != 0;
                }

                // PID 0x40 returns bitmask of PIDs 41-60 support
                r = send(in, out, "0140", 3000);
                sup = hex(r, "4140", 4);
                if (sup >= 0) {
                    // PID 5C = oil temp (bit position: 5C - 40 = 28 → bit 32-28=4 from right)
                    hasAtf5C = (sup & (1 << (32 - (0x5C - 0x40)))) != 0;
                }

                // If 5C not supported, check extended range for non-standard B4
                if (!hasAtf5C) {
                    // PID 0xA0 returns bitmask of PIDs A1-C0
                    r = send(in, out, "01A0", 3000);
                    sup = hex(r, "41A0", 4);
                    if (sup >= 0) {
                        hasAtfB4 = (sup & (1 << (32 - (0xB4 - 0xA0)))) != 0;
                    }
                }

                hasFuelSupported = hasFuel;
                hasAtfSupported = hasAtf5C || hasAtfB4;
                pidsDetected = true;

                int loopCount = 0;

                while (alive(id)) {
                    // ---- Core fast-poll PIDs (always supported) ----
                    r = send(in, out, "010C", 2000);
                    int v = hex(r, "410C", 2); if (v >= 0) rpm = v / 4;
                    r = send(in, out, "010D", 2000);
                    v = hex(r, "410D", 1); if (v >= 0) speed = v;
                    r = send(in, out, "0105", 2000);
                    v = hex(r, "4105", 1); if (v >= 0) coolant = v - 40;
                    r = send(in, out, "0104", 2000);
                    v = hex(r, "4104", 1); if (v >= 0) load = v * 100 / 255;
                    r = send(in, out, "ATRV", 1500);
                    try { volts = Float.parseFloat(r.replace("ATRV", "").replace("V", "")); }
                    catch (NumberFormatException ignore) { }

                    // ---- Slow-poll PIDs (every 5th cycle ≈ 2.5 sec) ----
                    if (loopCount % 5 == 0) {
                        // Fuel level
                        if (hasFuel) {
                            r = send(in, out, "012F", 2000);
                            v = hex(r, "412F", 1);
                            if (v >= 0) fuelLevel = v * 100f / 255f;
                        }

                        // ATF / transmission oil temp
                        if (hasAtf5C) {
                            r = send(in, out, "015C", 2000);
                            v = hex(r, "415C", 1);
                            if (v >= 0) atfTemp = v - 40;
                        } else if (hasAtfB4) {
                            r = send(in, out, "01B4", 2000);
                            v = hex(r, "41B4", 1);
                            if (v >= 0) atfTemp = v - 40;
                        }
                    }

                    // ---- DTC scan (every 20th cycle ≈ 10 sec) ----
                    if (loopCount % DTC_INTERVAL == 0) {
                        r = send(in, out, "03", 4000);
                        dtcCodes = parseDtc(r);
                        dtcScanned = true;
                    }

                    loopCount++;
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // connection lost or failed: fall through and retry
            } finally {
                connected = false;
                pidsDetected = false;
                hasFuelSupported = false;
                hasAtfSupported = false;
                rpm = -1; coolant = -999; speed = -1; load = -1; volts = -1f;
                atfTemp = -999; fuelLevel = -1f;
                dtcCodes = new ArrayList<>(); dtcScanned = false;
                closeQuietly(sock);
            }
            try { Thread.sleep(4000); } catch (InterruptedException e) { return; }
        }
    }

    // ---- DTC parsing ----

    /**
     * Parse Mode 03 response into human-readable DTC strings like "P0300".
     * Response format: 43 XX YY XX YY ... where each pair of bytes = one code.
     * A code of 0000 means "no more codes".
     */
    private List<String> parseDtc(String raw) {
        List<String> result = new ArrayList<>();
        // Remove header "43" and any "NODATA" / "SEARCHING"
        if (raw.contains("NODATA") || raw.contains("ERROR") || !raw.contains("43")) {
            return result;
        }
        int idx = raw.indexOf("43");
        if (idx < 0) return result;
        String data = raw.substring(idx + 2);

        // Each DTC is 4 hex characters (2 bytes)
        for (int i = 0; i + 3 < data.length(); i += 4) {
            String chunk = data.substring(i, i + 4);
            if (chunk.equals("0000")) continue;
            try {
                int code = Integer.parseInt(chunk, 16);
                if (code == 0) continue;

                // First 2 bits = letter, next 2 bits = first digit, remaining 12 bits = last 3 digits
                char letter;
                switch ((code >> 14) & 0x03) {
                    case 0: letter = 'P'; break;   // Powertrain
                    case 1: letter = 'C'; break;   // Chassis
                    case 2: letter = 'B'; break;   // Body
                    default: letter = 'U'; break;   // Network
                }
                int digit1 = (code >> 12) & 0x03;
                int rest = code & 0x0FFF;
                result.add(String.format(Locale.US, "%c%d%03X", letter, digit1, rest));
            } catch (NumberFormatException ignore) { }
        }
        return result;
    }

    private BluetoothDevice find(BluetoothAdapter ad) {
        for (BluetoothDevice d : ad.getBondedDevices()) {
            String n = d.getName();
            if (n == null) continue;
            n = n.toUpperCase(Locale.US);
            if (n.contains("OBD") || n.contains("ELM") || n.contains("LINK")
                    || n.contains("VGATE") || n.contains("VIEFLO") || n.contains("KONNWEI")
                    || n.contains("SCAN") || n.contains("DIAG")) return d;
        }
        return null;
    }

    private String send(InputStream in, OutputStream out, String cmd, int timeoutMs) throws Exception {
        while (in.available() > 0) in.read();
        out.write((cmd + "\r").getBytes());
        out.flush();
        StringBuilder sb = new StringBuilder();
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (in.available() > 0) {
                int b = in.read();
                if (b == '>') break;
                sb.append((char) b);
            } else {
                Thread.sleep(10);
            }
        }
        return sb.toString().replace("\r", "").replace("\n", "").replace(" ", "").toUpperCase(Locale.US);
    }

    private int hex(String r, String prefix, int bytes) {
        int i = r.indexOf(prefix);
        if (i < 0) return -1;
        int s = i + prefix.length();
        if (r.length() < s + bytes * 2) return -1;
        try { return Integer.parseInt(r.substring(s, s + bytes * 2), 16); }
        catch (NumberFormatException e) { return -1; }
    }

    private void closeQuietly(BluetoothSocket s) {
        if (s != null) try { s.close(); } catch (IOException ignore) { }
    }
}

