# Honda City 2018 (Pakistan) - car, head unit and launcher notes

Everything known about the car and the head unit, what was decided, and what is still unknown.
**Confirmed** = you told me. **Assumed** = my guess or general knowledge, please correct it if wrong.

## 1. The car
| Item | Value | Status |
|---|---|---|
| Model | Honda City, 2018, Pakistan market | Confirmed |
| Transmission | Automatic. You described it as an "ATF" transmission. | Confirmed that it is automatic |
| CVT or conventional automatic | I believe the automatic City is a CVT (uses CVT fluid, not classic ATF). | **Assumed, please check** |
| Engine | Usually 1.3 or 1.5 i-VTEC for this model (general knowledge) | **Unknown which one** |
| OBD2 | Standard CAN OBD2 on the driver-side port | Assumed |
| Fuel level via OBD2 | Often not exposed on Hondas | Assumed |
| Transmission temperature via OBD2 | Not a standard reading. Honda-specific and may not work with a cheap ELM327. | **Needs a test (section 6)** |

## 2. The head unit
| Item | Value | Status |
|---|---|---|
| Android version | 7.1.1 (you typed "7.1.,1") | Confirmed |
| CPU | Quad-core "T3" (I assume Allwinner T3) | Confirmed name, maker assumed |
| RAM / storage | 1 GB RAM, 16 GB ROM | Confirmed |
| MCU version | `t5.3.19-127-10-c06101-170321` (MCU build dated 21 Mar 2017) | Confirmed |
| System version | `v8.3.2_20180608.133359_ctj3` (build dated 8 Jun 2018, "ctj3" is probably a vendor or board tag) | Confirmed |
| Speed | "Super slow" with the stock launcher | Confirmed |
| USB port / cable | **No USB cable available on the unit**, so no USB stick and no ADB cable | Confirmed |
| Screen size and density | Not known | **Unknown** |
| Stock launcher package | Not known | **Unknown** |
| GPS available to Android apps | Not known | **Unknown** |
| Notification access setting present | Not known | **Unknown** |
| How you install apps today | Not known (Play Store? unit's own store? SD card?) | **Unknown** |

Why a custom ROM or firmware flash is not planned: on these units a separate MCU handles steering wheel buttons, reverse camera, climate and CAN bus. A wrong ROM can break those or brick the unit. The plan is to keep the stock firmware and only replace the launcher.

## 3. OBD2 scanner
- Bluetooth ELM327. **You confirmed it pairs with the head unit.**
- The launcher connects to a paired Bluetooth device whose name contains `OBD`, `ELM` or `LINK`.
- Values read: RPM (010C), speed (010D), coolant temperature (0105), engine load (0104), battery voltage (ATRV).
- Reading about every half second. The gauge panel hides itself when no scanner is connected.

## 4. What you want on the screen
- Speedometer with a racing look, colour reacting to speed and hard acceleration.
- Clock and date.
- OBD-II Gauges with danger zones:
  - Row 1: RPM (010C) + Coolant Temp (0105)
  - Row 2: Battery Voltage (ATRV) + Engine Load (0104)
  - Row 3: ATF Transmission Temp (015C/01B4) + Fuel Tank Level (012F) + DTC Fault Code Warning (Mode 03)
- 0-100 km/h timer under the clock, with an on/off switch on the apps page.
- Bluetooth music player bar at the bottom (previous, play/pause, next, track title). Nothing else in the bottom bar.
- Music equalizer, reached from the apps page.
- Swipe left on the screen for the apps page (Music, Equalizer, YouTube, Browser, Phone/Bluetooth, Maps, Settings, All apps). Swipe right or press Back to return.
- Colour scheme: **Type R** (carbon black, white, racing red). The shift-light dots were removed.
- YouTube via NewPipe (or the browser if NewPipe is missing). Browser: Via Browser. YouTube and Browser are blocked above 5 km/h.

## 5. Danger limits (edit in `MainActivity.java`, where the gauges are created)
| Gauge | Caution | Danger |
|---|---|---|
| RPM | 5000 | 6000 |
| Coolant | 100 C | 108 C |
| Battery | below 12.0 V | below 11.8 V or above 15.2 V |
| Engine load | 85% | 95% |
| ATF Temp | 110 C | 130 C |

These are starting guesses. Please check them against your owner's manual.

## 6. Transmission fluid temperature & Fuel Level Notes
1. ATF Temp tries standard PID `015C` first, with automatic fallback to Honda OBD-II PID `01B4`.
2. Unsupported PIDs are dynamically detected at connection time via PIDs `0100`, `0120`, `0140`, and `01A0` bitmasks to avoid bus lag.
3. Fuel tank level polls standard PID `012F` and renders an animated liquid tank with percentage readout.
4. Active DTCs are scanned via Mode 03. If any trouble code is detected, the check-engine icon flashes red and displays the DTC codes.

## 7. Project layout (`app/src/main/java/com/honda/launcher/`)
| File | Job |
|---|---|
| `MainActivity.java` | Home screen, config block, GPS, timer, apps page, swipe gesture |
| `SpeedometerView.java` | Racing dial. Face is drawn once, then only arc, needle and number per frame |
| `MiniGaugeView.java` | Small ring gauge with danger zone (RPM, Coolant, Battery, ATF) |
| `FuelGaugeView.java` | Custom fuel tank icon with liquid wave animation & percentage |
| `DtcWarningView.java` | Check engine light & OBD-II fault code warning display |
| `AuroraView.java` | Type R background, slow drifting glow, red danger pulse. Set `ANIMATE = false` if the unit stutters |
| `ObdManager.java` | Bluetooth ELM327 reader on background thread with PID detection & slow-polling |
| `EqualizerActivity.java`, `EqManager.java` | Equalizer screen, saved levels re-applied at launch |
| `AppDrawerActivity.java` | Plain list of every installed app |
| `NowPlayingService.java` | Empty. Lets the launcher read the current track |

Settings you may want to change are in the `CONFIG` block at the top of `MainActivity.java`: `BLOCK_WEB_WHILE_MOVING`, `MOVING_KMH`, `PHONE_PACKAGE` (the stock Bluetooth app's package name), and the browser, NewPipe and maps package names.

## 8. Getting the app onto the unit (no USB)
**Option A - build in the cloud, download on the unit (no Android Studio):**
1. Create a free GitHub account and a new repository (works from a phone browser).
2. Upload everything from this folder, including the hidden `.github` folder.
3. GitHub builds the app automatically (Actions tab, "Build APK"). It takes a few minutes.
4. Open `https://github.com/<your-name>/<repo>/releases/latest/download/app-debug.apk` in the unit's browser, download it, and install it from the unit's file manager. Allow "Unknown sources" if asked.

**Option B - Android Studio on a laptop:** open this folder, Build > Build APK(s), then move the APK to the unit by any method it supports.

After installing: press Home and choose **Honda Launcher**. Pick **Just once** first, not **Always**. Never uninstall or disable the stock launcher.

First-run steps: allow Location. Tap the "allow now-playing" text and enable Honda Launcher under notification access. Pair the ELM327 in Android Bluetooth settings first.

## 9. In-car test checklist
Mark each as worked / broke / missing:
- Steering wheel buttons (volume, mode, answer, hang up)
- Reverse camera appears over the launcher and goes back afterwards
- Climate and door-open pop-ups
- Radio and Bluetooth calls via the stock app
- Ignition off then on: launcher returns quickly
- Night dimming with headlights, volume overlay, mute
- GPS speed shows on the speedometer
- OBD2 gauges appear with the engine running
- Bluetooth track title and the three music buttons work
- Swipe left opens apps, swipe right returns, the 0-100 switch hides and shows the timer
- Equalizer: do the sliders change the sound? Do the bouncing bars move while music plays?

## 10. Known limits
- **The code has not been compiled or run on a real unit.** The first build may need a small fix.
- The 0-100 timer is for fun, not racing: GPS speed updates once a second. It uses OBD speed when the scanner is connected, which is better.
- The equalizer may do nothing if the unit processes sound in its own chip. The bouncing bars need audio-capture permission and may be blocked.
- No custom font is bundled. The app uses Android's condensed bold italic font. The web demo used Chakra Petch.
- The animated background costs some speed on 1 GB of RAM. Turn it off with `ANIMATE = false`.

## 11. Ideas not built yet
Trip meter and top speed, speed-limit alert, throttle bar and peak RPM, album art, prayer-time countdown (all offered, you chose only the 0-100 timer for now).

## 12. In-Car Live Testing Feedback & Roadmap (Documented — Pending Implementation)
*Observed during real-world testing on the car head unit:*

1. **Remove Unbuilt App Shortcuts:**
   - Remove hardcoded app placeholders/shortcuts from the swipe-left page that are not built or installed on the unit. Keep only installed apps or functional screens.

2. **Convert Swipe-Left Page to Gauge Control & Settings Panel:**
   - Transform the current swipe-left apps screen into a dedicated Dashboard Customization & Control Hub.
   - Add individual On/Off toggle switches for each gauge on the home screen:
     - RPM Gauge toggle
     - Coolant Temp Gauge toggle
     - Battery Voltage Gauge toggle
     - Engine Load Gauge toggle
     - ATF Transmission Temp Gauge toggle
     - Fuel Tank Level Gauge toggle
     - DTC Fault Code Warning View toggle
   - Persist driver preferences using `SharedPreferences` so gauge visibility is saved across ignition cycles.

3. **Music / Now Playing Detection (Phone Audio):**
   - **Issue:** Music was playing from phone over the head unit audio, but DashPilot displayed "Nothing playing".
   - **Root Cause & Fix:**
     - Chinese Allwinner T3 head units route Bluetooth audio directly through the MCU and vendor BT app (`com.gocsdk`, `com.syu.bt`, etc.) rather than standard Android `MediaSession` notifications.
     - Fix plan: Add BroadcastReceivers for common AVRCP metadata intents (`com.android.music.metachanged`, `com.android.music.playstatechanged`, AVRCP broadcasts) and check for vendor head-unit BT broadcast actions. Verify if Notification Listener permissions are enabled in Android settings.

4. **Fuel Tank Gauge in Reserve / Percentage Missing:**
   - **Issue:** Car is in fuel reserve, but the fuel gauge is not displaying the remaining fuel percentage.
   - **Root Cause & Fix:**
     - Standard OBD2 PID `012F` (Fuel Tank Level) is frequently not implemented by Honda City ECUs or returned as `NO DATA`.
     - When PID `012F` is unsupported, the gauge displays `--%` or remains hidden.
     - Fix plan: Verify Honda-specific fuel PID support, add a fallback reserve indicator (warning the driver when fuel is in reserve), or add a manual tank capacity / reset option in the swipe-left settings panel.

5. **Bluetooth Auto-Pairing & Reconnect Controls:**
   - **Auto-connect on Launch:** Automatically connect to the paired OBD2 ELM327 scanner and trigger Bluetooth auto-connect with the driver's phone as soon as DashPilot starts.
   - **Manual Controls in Swipe-Left Panel:** Add manual "Reconnect OBD-II" and "Reconnect Phone" buttons with live connection status chips (Connected / Disconnected / Scanning).
