# RGB IoT Controller

Android project for controlling ESP devices over BLE and local Wi-Fi UDP, with optional internet relay support.

## Build APK with GitHub Actions

1. Upload `ESPControlStudio-GitHubBuild.zip` to the repository root without renaming it.
2. Commit the upload to `main`.
3. The Android APK workflow starts automatically.
4. Open **Actions** and download the `ESPControlStudio-debug-apk` artifact from the completed run.

The workflow extracts the project, installs Java 17, Gradle 8.13 and Android SDK 36, then runs `:app:assembleDebug`.

## Runtime configuration (no rebuild required)

After installing the APK, open **Settings → Connection config** to edit:
- BLE service, command and events UUIDs
- BLE advertised device-name prefix
- Local UDP app listening port and ESP device port
- Optional relay hostname/IP and UDP port

Open **Settings → Security (Owner Key)** to change the Owner Key. Settings persist locally on the phone.

These values must match the ESP firmware. Editing app settings does not automatically reconfigure firmware.

## Requirements for real hardware

The ESP firmware must implement the ESPCTL packet format and authentication handshake described in `docs/PROTOCOL.md`. Internet relay requires a compatible relay server; leave the relay hostname blank to disable it. The workflow produces a debug APK, not a Play Store-signed release.
