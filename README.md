# DashPilot (for CAR LCD Modules)

A extremely lightweight Android launcher and telemetry dashboard designed for vehicle head units (e.g., Honda,Toyota,Suzuki,Mitsubishi head units).

## Features
- **High-Performance Speedometer**: Racing-style 0–200 km/h dial with Type-R color styling, smooth needle sweep, and acceleration sensing.
- **Telemetry / OBD-II Gauges**: Live gauges for RPM, Coolant temperature, Battery voltage, Engine load, and ATF transmission fluid temperature.
- **Fuel Tank Level Indicator**: Custom fuel tank view with animated liquid level, wave effect, and percentage remaining readout.
- **Diagnostic Trouble Code (DTC) Warning**: Check-engine icon with active code scanner, alerting immediately if fault codes are found.
- **0–100 km/h Acceleration Timer**: Automatic launch timer with best time tracking.
- **Media Controls**: Integrated now-playing metadata display and transport controls (prev, play/pause, next).
- **Distraction Safety**: Configurable app blocking while moving.
- **Built-in App Grid**: Instant-access navigation panel and complete app drawer.

## Project Structure
```
DashPilot/
├── .github/workflows/
│   └── build.yml             # GitHub Actions CI to automatically build & release APK
├── app/
│   ├── build.gradle
│   ├── src/
│   │   └── main/
│   │       ├── AndroidManifest.xml
│   │       ├── java/com/honda/launcher/
│   │       │   ├── MainActivity.java        # Main launcher dashboard & UI loop
│   │       │   ├── AppDrawerActivity.java   # App grid activity
│   │       │   ├── MiniGaugeView.java       # Custom ring gauge view (RPM, Coolant, Battery, ATF)
│   │       │   ├── FuelGaugeView.java       # Custom fuel tank gauge with liquid level animation
│   │       │   ├── DtcWarningView.java      # Check engine icon & DTC trouble code alert
│   │       │   ├── SpeedometerView.java     # Custom racing speedometer view
│   │       │   ├── EqManager.java           # Audio Equalizer manager
│   │       │   ├── AuroraView.java          # Type R drifting bloom & danger glow
│   │       │   ├── ObdManager.java          # Bluetooth ELM327 OBD-II reader (PIDs, ATF, Fuel, DTC)
│   │       │   ├── EqualizerActivity.java   # Audio EQ UI screen with live FFT spectrum
│   │       │   └── NowPlayingService.java   # Notification listener service
│   │       └── res/
│   │           ├── drawable/                # Custom vector launcher icons
│   │           ├── mipmap-anydpi-v26/
│   │           └── values/
├── HONDA_CITY_NOTES.md       # Hardware specs, OBD limits, and in-car test checklist
├── gradle/wrapper/
├── build.gradle
├── settings.gradle
├── gradlew
└── gradlew.bat
```

## How to Build & Run

### Using Android Studio
1. Open Android Studio.
2. Select **Open** and choose the `DashPilot` folder.
3. Allow Gradle to sync.
4. Select your connected device or emulator and click **Run** (Shift + F10 or the green play button).

### Using the Command Line
- **Build Debug APK:**
  ```bash
  ./gradlew assembleDebug
  ```
  The resulting APK is generated at:
  `app/build/outputs/apk/debug/app-debug.apk`

- **Install directly onto a connected device or emulator via ADB:**
  ```bash
  ./gradlew installDebug
  ```

### Running on your Mac via Emulator

A virtual device configured for landscape dashboard display (`DashPilot_Device`) has been set up for you.

- **Start the emulator:**
  ```bash
  $ANDROID_HOME/emulator/emulator -avd DashPilot_Device
  ```
- **Launch the app on the emulator:**
  ```bash
  ./gradlew installDebug
  $ANDROID_HOME/platform-tools/adb shell am start -n com.honda.launcher/.MainActivity
  ```
- **Turn off the emulator:**
  ```bash
  $ANDROID_HOME/platform-tools/adb emu kill
  ```

