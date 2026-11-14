# ESPControl BLE Protocol v2

This is the new app/firmware contract. It replaces ESPCTL framing, owner-key handshake, application session crypto, replay windows, UDP discovery and relay routing. The Android app communicates over BLE GATT only.

## GATT
- Service UUID: `a6c60001-6b9f-4d8e-a251-5bd248e8c101`
- Command characteristic (write): `a6c60002-6b9f-4d8e-a251-5bd248e8c101`
- Event characteristic (notify): `a6c60003-6b9f-4d8e-a251-5bd248e8c101`
- Advertise the service UUID. Device name: `ESPC-` + stable 12-hex device identifier (station MAC without separators).
- Client requests MTU 517; firmware requests/sets MTU 517. A protocol frame is 6 bytes plus payload and must fit the negotiated ATT payload. Reject oversized frames; do not truncate. If the negotiated MTU is smaller, firmware must limit outgoing frames accordingly.

## Frame
All integers are unsigned little-endian unless stated otherwise.

| Offset | Size | Meaning |
|---|---:|---|
| 0 | 1 | Magic `0xEC` |
| 1 | 1 | Magic `0x53` |
| 2 | 1 | Protocol version `2` |
| 3 | 1 | Message type |
| 4 | 2 | Payload length, LE |
| 6 | N | Payload |

Receivers validate magic, version, exact frame length, message type, payload bounds and candidate existence before dispatch. IDs are local to the connected ESP32.

## Message types
- `0x01 CAPS_REQUEST` App -> ESP
- `0x02 CAPS` ESP -> App
- `0x10 SWITCH_SET` App -> ESP
- `0x11 RGB_SET` App -> ESP
- `0x12 EFFECT_SET` App -> ESP
- `0x13 RGB_TRANSITION` App -> ESP (target RGB + duration + common start delay)
- `0x20 UPDATE` ESP -> App (delta only)
- `0x21 STATUS_REQUEST` App -> ESP
- `0x22 STATUS` ESP -> App (complete snapshot)

## CAPS payload
`deviceNameLen:u8, deviceName:utf8, candidateCount:u8, candidates...`
Candidate entry:
- Switch: `kind:u8=1, id:u8, nameLen:u8, name:utf8`
- RGB: `kind:u8=2, id:u8, ledCount:u16, nameLen:u8, name:utf8`
Then: `effectCount:u8`, repeated `effectId:u8, nameLen:u8, name:utf8`.
IDs are unique within a candidate kind on one ESP32. Names are display labels. Effects are advertised as a device catalog and selected per RGB candidate.

## Commands (sparse batches)
Each command payload starts with `count:u8`; only included candidates change.
- SWITCH_SET: repeated `candidateId:u8, value:u8` where 0=OFF, 1=ON.
- RGB_SET: repeated `candidateId:u8, red:u8, green:u8, blue:u8, brightness:u8, power:u8`. Power 0=OFF, 1=ON. RGB color, brightness and power are independent values; power-off must not erase the stored color or brightness.
- RGB_TRANSITION: repeated `candidateId:u8, red:u8, green:u8, blue:u8, brightness:u8, power:u8, durationMs:u16, startDelayMs:u16`. Firmware interpolates from the currently rendered state to the target locally at approximately 60 FPS. Updates received during an active transition replace its target from the current interpolated value; first transition in a group uses the common start delay.
- EFFECT_SET: repeated `candidateId:u8, effectId:u8, speed:u8`. Effect ID 0 means stop.
- The app coalesces latest unsent RGB batches per device and serializes acknowledged GATT writes per connection. Different candidates must never overwrite one another. Selected devices receive updates concurrently, with a shared start delay to reduce visible skew. BLE radio airtime is still shared; this is near-simultaneous, not physically simultaneous.

## Feedback
UPDATE and STATUS start with `count:u8` and use typed entries:
- Switch: `kind:u8=1, candidateId:u8, value:u8`
- RGB: `kind:u8=2, candidateId:u8, red:u8, green:u8, blue:u8, brightness:u8, power:u8`
- Effect: `kind:u8=3, candidateId:u8, effectId:u8, speed:u8`
UPDATE contains only values that have actually been applied and changed. STATUS contains all configured switch/RGB candidates and the current effect state for each RGB candidate.

## Scheduling and safety
- Apply accepted values promptly. RGB transitions render locally at approximately 60 FPS; non-RGB commands apply promptly. The first frame of a coordinated group uses a common start delay so independently delivered BLE packets can begin close together.
- After an applied change, schedule a consolidated UPDATE for 500 ms after the most recent applied change. Reset the debounce when another change is applied. Keep only the latest state for a given (kind,id), while preserving other candidates.
- STATUS_REQUEST returns a full snapshot read from actual/current state.
- Validate the complete batch before applying any entry. Malformed frames, unknown IDs, invalid booleans and unsupported effects are rejected safely. Never write an unconfigured GPIO from a packet.
- RGB strip/matrix layout, LED count, GPIO mapping, effect implementation and hardware drivers are firmware configuration. The app never assumes physical GPIO mappings.
- This v2 baseline has no application-layer authentication/encryption. BLE link security can be configured separately; do not claim app-level encryption.
