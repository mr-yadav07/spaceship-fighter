# ESP Control Studio

Native Android application for discovering and controlling ESP devices over Bluetooth Low Energy (BLE), local Wi-Fi UDP, and an optional UDP relay.

## Build APK

The complete Android project source is stored directly in this repository. Every push to `main` starts the Android build workflow.

1. Open the **Actions** tab.
2. Select the **Android APK** workflow run.
3. Download the `ESPControlStudio-debug-apk` artifact after the run succeeds.

## App configuration

Open **Settings → Connection config** in the app.

- BLE service, command, and event UUIDs must match the GATT service implemented by the ESP firmware.
- The BLE advertised-name prefix must match the firmware's device name.
- Local UDP ports must match the firmware protocol settings.
- Relay hostname and UDP port are optional. Leave the hostname blank to disable relay.

Open **Settings → Security (Owner Key)** to configure the Owner Key. It must match the key configured on the ESP devices.

Connection settings are stored locally on the phone. Changing these settings does not require rebuilding the APK.

## Scope

This repository contains the Android client only. It does not include ESP firmware or a relay server. The app and firmware must implement the same ESPCTL packet format and compatible authentication handshake.
