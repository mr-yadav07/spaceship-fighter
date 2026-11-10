# ESP Control Studio — rewritten Android app

Fresh Kotlin + Jetpack Compose project (no decompiled code from the old APK).
Only the **product shape** (screens, routes, Owner Key flow) was read from the
APK and the two spec documents; everything here is written from scratch using
current Android best practice.

## What's implemented

- **UI**: the agreed Main v2 mockup — rainbow disc (tap *or* slide, continuous),
  R/G/B/brightness sliders with per-channel on/off, oval revolver (Classic /
  Accelerate / Free-spin scrolling, every item tappable, not just the centred
  one), Settings → Devices / Security / Scroll, Scan panel, translucent panels
  (rest of the screen stays usable), right-side thumb-friendly layout.
  A **Demo data** toggle in Settings lets you see and drive the whole UI with 5
  fake RGB + 5 fake switch devices before any real device exists.
- **Protocol**: `docs/PROTOCOL.md` — one packet format for local UDP, internet
  relay UDP and BLE, built from your two spec documents (device discovery,
  Owner Key handshake, RGB/brightness/effects/status, 3+1+1 s heartbeat).
- **Transports**: `LocalUdpTransport`, `RelayUdpTransport`, `BleTransport` —
  real Android APIs (`DatagramSocket` bound to the Wi-Fi network, native BLE
  GATT with MTU negotiation and write-without-response for fast colour
  updates), not stubs.
- **Security**: `OwnerKeyStore` encrypts the Owner Key at rest with an
  Android Keystore AES key; `Crypto.kt` does the HMAC handshake and
  ChaCha20-Poly1305 session encryption from the protocol doc.
- **DeviceHub**: the single place that owns pairing, route selection
  (local UDP > BLE > relay, whichever answered most recently), automatic
  reconnect, latest-value-wins coalescing for slider drags, and
  verify-after-change (asks for status only if the device didn't already send
  one — spec §6/§10).

## What's NOT done yet (needs your decisions)

1. **BLE UUIDs** (`BleTransport.kt`, `SERVICE`/`CHR_COMMAND`/`CHR_EVENTS`) are
   placeholders — set them to match the firmware once you pick real ones.
2. **Relay server** — `docs/PROTOCOL.md` defines the packets `DeviceHub`
   already parses (`T_RELAY_REGISTER`/`T_RELAY_LIST`); the server itself
   (association, NAT traversal, multi-owner routing) is not part of this repo.
3. **Effects screen UI** (effect picker, speed) and **Groups/Scenes** from the
   earlier mockup aren't wired into this rewrite yet — the control layer
   (`DeviceHub.triggerEffect/sendEffectData`) is ready for them.
4. **App icon** — only a placeholder theme colour is set, no launcher icon.
5. I have no device, emulator or Gradle here to build/run this — please build
   it in Android Studio and send any error output back.

## How to open

1. Unzip, open the folder in Android Studio (it will prompt to sync Gradle —
   uses AGP 8.11.1 / Kotlin 2.2.0 / Compose, current stable as of Nov 2026).
2. Run on a device/emulator. Turn on **Settings → Demo data** to see the full
   UI without any ESP hardware.
3. Edit `app/build.gradle.kts` → `RELAY_HOST`/`RELAY_PORT` once the relay
   server exists; local UDP and BLE work without it.

## From the old APK

The APK (`ESP_Control_Studio_cyberpunk_debug.apk`) is Kotlin + Compose with a
BLE/TCP/JSON-ish command layer and a "cyberpunk" visual theme — no source was
reused. If you'd rather evolve that codebase than restart, send the Android
Studio project (or its GitHub repo) instead of the APK and I'll work from that
directly — decompiled code is unreliable to build on.


## Runtime connection settings

The Android app exposes BLE GATT UUIDs, BLE advertised-name prefix, local UDP listen/device ports, and optional relay hostname/port under **Settings → Connection config**. These values are persisted locally and can be changed after installation without rebuilding the APK. The ESP firmware must still be configured to use matching UUIDs and UDP ports. Owner Key is edited separately under Security.
