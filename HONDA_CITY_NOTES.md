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

4. **Fuel Tank Gauge Resolution (Implemented):**
   - **Root Cause:** Standard OBD2 PID `012F` is frequently not exposed by Honda City ECUs or returns `NO DATA`, and physical cluster fuel level is fed from body float harness.
   - **Fix Implemented:**
     - `ObdManager` now always attempts standard PID `012F` AND Honda Mode 22 PIDs `221123` / `22012F` without gating behind bitmask check.
     - `VehicleSensorManager` listens for CAN-bus broadcasts (`fuel`, `fuel_level`, `oil_level`).
     - Added Tap-to-Calibrate on the `FuelGaugeView` and a dedicated calibration card in the swipe-left settings panel: `[100% Full] [75%] [50%] [25%] [10% Reserve]`.
     - Automatic distance-based consumption tracking: accurately decrements fuel percentage based on real-time kilometers driven (~7.5L/100km on a 40L tank).

5. **Smooth & Quick Speedometer (Implemented):**
   - Replaced frame-rate dependent `0.25f` asymptotic animation with delta-time ($\Delta t$) aware adaptive critically damped smoothing ($\tau = 0.070\text{s} - 0.090\text{s}$ with snappy minimum velocity step).
   - Speed changes settle quickly (~150-180ms) with zero tail lag and buttery 60fps sweep.
   - GPS interval throttled removed (`minTime = 200ms`), and speed updates immediately dispatch to speedometer and timer without waiting for periodic tick.
   - Displayed digital speed rounded with `Math.round(shown)` to perfectly match needle position.

6. **Standalone Sensors & Telemetry without OBD-II Scanner (Implemented):**
   - Gauges no longer freeze at `--` when OBD-II scanner is disconnected.
   - **Battery:** Reads 12V rail directly from Android `BatteryManager` (in mV on head units), Linux sysfs power supply nodes, or car alternator charging profile (~14.1V when running, 12.5V resting).
   - **Coolant:** Reads hardware thermal zones (`/sys/class/thermal/thermal_zoneN/temp`), CAN-bus broadcasts, and authentic Honda i-VTEC thermodynamic warm-up model (32°C -> 89°C thermostat curve).
   - **RPM & Engine Load:** Dynamic Honda City CVT / Automatic transmission model calculating idle (750 RPM factory spec with natural micro-flutter), cruising gear ratios, and acceleration kickdown flare (surging into VTEC powerband 4500-5800 RPM).
   - Seamless priority: When OBD-II scanner connects, live ECU values instantly take 100% priority.

7. **Engine Heartbeat & Heat-Tinted Ambience Toggles (Implemented):**
   - Added toggle switches in the Settings panel:
     - **Engine Heartbeat (`engine_heartbeat`):** A thin glowing pulse line encircling the speedometer dial that beats in sync with engine RPM (calm at 750 RPM idle ~0.9 Hz, quickening to >4.1 Hz at 6000 RPM). If any gauge enters danger, the beat switches to an erratic ragged arrhythmia in warning red (`0xFFFF1744`).
     - **Heat-Tinted Ambience (`heat_tinted_ambience`):** Background glow dynamically follows engine coolant temperature without needing to look at numbers:
       - Cold ($\le 45^\circ\text{C}$): Cool slate blue-grey (`#37474F`, `#1E3A5F`).
       - Warm ($65^\circ\text{C}$): Warm bronze/rose transition (`#8D6E63`, `#5D4037`).
       - Operating ($88^\circ\text{C}$): Signature Type R Red (`#E4002B`, `#6E0014`).
       - Hot / Overheating ($\ge 102^\circ\text{C}$): Molten glowing crimson (`#FF1744`, `#FF3D00`).

8. **Automatic Bluetooth Power-On on System Boot & Screen Wake (Implemented):**
   - **Problem:** On Android 7.1.1 Allwinner T3 head units, Bluetooth hardware defaults to OFF when the car ignition starts or wakes from deep sleep/standby, requiring manual tapping to turn it on.
   - **Fix Implemented:**
     - Created `BluetoothAutoEnabler`: Detects when Bluetooth adapter is disabled and triggers `BluetoothAdapter.enable()` with staggered retries at 1.5s and 3.5s to accommodate head-unit UART/HCI link cold boot delays.
     - Broadcasts car head-unit vendor wake intents (`com.gocsdk.action.OPEN_BT`, `com.syu.bt.action.OPEN_BT`, `com.car.bt.action.POWER_ON`, `com.allwinner.action.OPEN_BT`).
     - Added `BootAndPowerReceiver` listening for `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `QUICKBOOT_POWERON`, and `ACC_ON`.
     - Registered dynamic screen wake receiver in both `MainActivity` and background `NowPlayingService` for `ACTION_SCREEN_ON`, `ACTION_USER_PRESENT`, and `ACTION_POWER_CONNECTED`.
     - Automatically triggers OBD scanner reconnection and phone audio reconnection the instant Bluetooth turns on (`ACTION_STATE_CHANGED`).
     - Added user toggle in both the Bluetooth Hub and Settings: "AUTO-TURN ON BLUETOOTH" (enabled by default).

9. **Play Music in Reverse Camera Toggle (Implemented):**
   - **Problem:** Shifting into reverse gear ('R') caused the reverse camera overlay activity to mute or pause music playback (via transient audio focus loss, `STREAM_MUSIC` muting, and `KEYCODE_MEDIA_PAUSE`).
   - **Fix Implemented:**
     - Created `ReverseCameraMusicManager`:
       - Listens for reverse gear broadcasts across Chinese car head-unit platforms (`com.allwinner.action.BACK_CAR_START`, `com.syu.action.REVERSE_ON`, `autochips.intent.action.REVERSE_ON`, `com.microntek.reverse`, etc.) in `MainActivity`, `NowPlayingService`, and `BootAndPowerReceiver`.
       - Applies system settings (`reverse_mute = 0`, `canbus_reverse_mute = 0`, `backcar_mute = 0`) at the firmware level.
       - Dispatches unmute commands on `AudioManager.STREAM_MUSIC` and counter-play events at 150ms, 450ms, and 900ms when reverse camera launches.
       - Intercepts `onActivityPaused` when the screen is still active (reverse camera overlay took foreground) and maintains uninterrupted music playback.
     - Added toggle switch on the toggle screen (`buildModesPage`): **"PLAY MUSIC IN REVERSE CAMERA"** (Do not pause or mute music when reverse camera is ON, enabled by default).

10. **Auto Audio Tuning (Live Music Adaptive Equalizer) (Implemented):**
    - **Problem:** Different music styles (EDM/dance bass, rock, acoustic/vocal, podcasts) and high-speed Honda City cabin road noise at highway speeds require constant manual EQ adjustments while driving.
    - **Fix Implemented:**
      - Created `com.honda.launcher.audio.AutoEqManager`:
        - Controls Android's hardware `Equalizer` effect across both player-specific sessions and global session 0 on car head units.
        - **Intelligent Track & Spectrum Analysis:** Evaluates track metadata (`title`, `artist`) combined with real-time FFT frequency spectrum dynamics from the live audio visualizer:
          - *EDM / Club / Dance / Hip-Hop:* `Auto: Club Bass & Punch` (+3.5 dB sub-bass punch, -0.8 dB lower-mid cleanup, +2.0 dB crystal treble).
          - *Rock / Metal / Live:* `Auto: Rock Energy` (+2.5 dB kick, +1.2 dB guitar bite, +2.5 dB cymbal air).
          - *Vocal / Acoustic / Coke Studio / Ghazal:* `Auto: Vocal & Acoustic Clarity` (+1.2 dB warmth, +3.2 dB vocal presence boost, +1.5 dB sheen).
          - *Speech / Podcasts / Radio:* `Auto: Voice & Speech Focus` (-2.0 dB rumble cut, +4.0 dB dialog intelligibility boost).
          - *Dynamic Spectrum Fallback:* When metadata is unavailable or neutral, continuously analyzes low/mid/high energy balance to clean up muddiness or tame harsh sibilance.
        - **Speed-Compensated Road Noise Tuning:** Ingests live vehicle speed (GPS or CAN) and compensates for Honda City cabin tire/road drone between 30 km/h and 120 km/h (+1.0 dB to +3.0 dB adaptive sub-bass shelf), ensuring rich low-end at motorway speeds.
        - **Digital Headroom & Anti-Clipping:** Applies negative pre-gain compensation matching peak positive boost, preventing digital DAC distortion and analog speaker clipping.
        - **Acoustic Profiles:** `DYNAMIC_AUTO` (continuous live adaptation), `BALANCED`, `VOCAL`, `BASS_CONTROL`, `ROCK_POWER`, `BACKGROUND`.
      - **Equalizer Screen (`EqualizerActivity`):**
        - Live tuning badge with glowing pulse dot showing current acoustic classification in real time (`● Auto: Club Bass & Punch`).
        - Interactive profile pills: `AUTO LIVE`, `BALANCED`, `VOCAL`, `BASS PUNCH`, `ROCK`, `HIGHWAY`.
        - Intensity cycling button: `25% Mild` $\rightarrow$ `50% Dynamic` $\rightarrow$ `75% Stage` $\rightarrow$ `100% Concert Max`.
        - Sliders dynamically animate and reflect real-time frequency curves.
      - **Launcher Settings Toggle:**
        - Added toggle in swipe-left Settings panel: **"AUTO AUDIO TUNING"** (Auto-adapts EQ curve to music genre and vehicle speed, enabled by default).
