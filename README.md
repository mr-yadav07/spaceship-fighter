# RGB IoT Controller

Android application source is supplied as `ESPControlStudio.zip` in the repository root.

## Build APK with GitHub Actions

1. Upload `ESPControlStudio.zip` to the repository root (do not rename it).
2. Commit the upload to `main`.
3. Open **Actions → Build Android APK**.
4. Download the `ESPControlStudio-debug-apk` artifact from the completed run.

The workflow extracts the project, installs Java 17 / Android SDK 36 / Gradle 8.13, runs `assembleDebug`, and stores the APK as a 30-day artifact.

## Integration details still required for real hardware

- Replace the placeholder BLE service / command / event UUIDs in `BleTransport.kt` with the exact UUIDs used by the ESP firmware.
- Provide a deployed relay hostname and port before internet relay control can work. Local UDP and BLE do not require the relay.
- The ESP firmware must implement the ESPCTL v1 packet format documented in `docs/PROTOCOL.md`.

The initial GitHub Actions build produces a debug APK; it is not a Play Store-signed release.
