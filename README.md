# ESP Control Studio

Native Android app for discovering and controlling ESP32 devices over Bluetooth Low Energy (BLE).

## Build Android APK
Every push to `main` starts the **Android APK** GitHub Actions workflow.

1. Open the repository's **Actions** tab.
2. Open the latest successful **Android APK** run.
3. Download the `ESPControlStudio-debug-apk` artifact.

## BLE configuration
Open **Settings → Connection config**. The active settings are:
- GATT service UUID
- Command characteristic UUID
- Events characteristic UUID
- Advertised device-name prefix (default `ESPC-`)

These settings are stored locally and can be changed without rebuilding the APK.

## Protocol and firmware
- Android/firmware contract: [docs/ESPControl-BLE-Protocol-v2.md](docs/ESPControl-BLE-Protocol-v2.md)
- Universal ESP32 firmware: [firmware/esp32-universal-ble](firmware/esp32-universal-ble)
- Firmware build: PlatformIO; GitHub Actions runs the ESP32 Firmware workflow.

The app discovers each device's switch/RGB candidates and effect catalogue from CAPS. Candidate IDs are local to each connected ESP32. GPIO mapping, LED layout and hardware callbacks belong to the firmware.

Protocol v2 is BLE-only. The old ESPCTL handshake, application-layer encryption, UDP discovery and relay routing have been removed. BLE link security is separate and is not enabled by this protocol implementation.
