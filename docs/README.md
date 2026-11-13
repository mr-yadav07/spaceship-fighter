# ESP Control Studio — project notes

The Android app keeps the existing Compose UI and uses a BLE-only backend.

## Architecture
- UI: existing Main v2 screens, Revolver menu, device lists, RGB/switch controls, effects and firmware configuration panels.
- BLE transport: native Android GATT client in `BleTransport.kt`.
- Protocol: compact binary BLE v2 codec in `ProtocolV2.kt`.
- DeviceHub: device discovery, per-device candidate capabilities, sparse commands, state feedback and per-candidate latest-value-wins coalescing.
- Firmware: universal ESP32 BLE adapter in `firmware/esp32-universal-ble`.

## Device model
Each ESP32 advertises `ESPC-<12 hex ID>`, then provides CAPS after the app connects. CAPS contains its display name, switch/RGB candidates, LED counts and effect catalogue. Candidate IDs are local to that ESP32; the app always scopes commands to the connected device.

## State and command behavior
- Switch commands carry candidate ID and ON/OFF.
- RGB commands carry candidate ID, RGB, brightness and independent power state.
- Effect commands carry candidate ID, effect ID and speed; effect ID 0 stops.
- App queues latest unsent value per candidate and does not overwrite other candidates.
- Firmware validates a complete batch before applying it. Hardware hooks report success only after application.
- Firmware sends consolidated delta feedback 500 ms after the latest applied change. App requests a full status snapshot after connecting.

## Firmware configuration
Edit the candidate/effect descriptor arrays in the firmware. Implement the hardware apply hooks and state readers for the actual board. GPIO mapping and LED driver choices remain firmware-side; the Android app does not assume fixed GPIOs or fixed candidate counts.

## Security boundary
Protocol v2 has no application-layer authentication or encryption. The retained Owner Key screen is a legacy local setting and is not transmitted or used by BLE v2. BLE pairing/bonding is a separate configuration choice.

## Build
- Android: GitHub Actions workflow **Android APK**.
- ESP32: PlatformIO project under `firmware/esp32-universal-ble`; GitHub Actions workflow **ESP32 Firmware**.
