# ESPControl BLE Protocol v2

This is the new app/firmware contract. It replaces ESPCTL v1 framing, UDP discovery, owner-key handshake, session crypto, replay windows and relay routing for the BLE-only implementation.

## GATT
- Service UUID: `a6c60001-6b9f-4d8e-a251-5bd248e8c101`
- Command characteristic (write): `a6c60002-6b9f-4d8e-a251-5bd248e8c101`
- Event characteristic (notify): `a6c60003-6b9f-4d8e-a251-5bd248e8c101`
- Advertise the service UUID. Device name prefix: `ESPC-`; suffix is a stable 12-hex device identifier (normally the station MAC without separators).
- Negotiate MTU 185. One protocol frame must fit in the negotiated ATT payload. Reject oversized frames; do not silently truncate.

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

The receiver must validate magic, version, exact frame length, message type, payload bounds and candidate existence before dispatch. No implicit global candidate IDs: IDs are local to the connected ESP32.

## Message types
- `0x01 CAPS_REQUEST` App -> ESP
- `0x02 CAPS` ESP -> App
- `0x10 SWITCH_SET` App -> ESP
- `0x11 RGB_SET` App -> ESP
- `0x12 EFFECT_SET` App -> ESP
- `0x20 UPDATE` ESP -> App (delta only)
- `0x21 STATUS_REQUEST` App -> ESP
- `0x22 STATUS` ESP -> App (complete snapshot)

## CAPS payload
`deviceNameLen:u8, deviceName:utf8, candidateCount:u8, candidates...`
Candidate entry:
- Switch: `kind:u8=1, id:u8, nameLen:u8, name:utf8`
- RGB: `kind:u8=2, id:u8, ledCount:u16, nameLen:u8, name:utf8`
Then: `effectCount:u8`, repeated `effectId:u8, nameLen:u8, name:utf8`.
IDs are unique only within their candidate kind on one ESP32. An RGB ID may equal a switch ID. Names are display labels, not routing keys.

## Commands (sparse batches)
Each command payload starts with `count:u8`; only included candidates change.
- SWITCH_SET: repeated `candidateId:u8, value:u8` where 0=OFF, 1=ON.
- RGB_SET: repeated `candidateId:u8, red:u8, green:u8, blue:u8`.
- EFFECT_SET: repeated `candidateId:u8, effectId:u8, speed:u8`. Effect ID 0 means stop. Unsupported effect IDs must be ignored and reported as an error/update only if useful.
A packet may update multiple candidates of its one type. Do not send unchanged candidates.

## Feedback
UPDATE and STATUS use the same sparse entry encoding, preceded by `count:u8`:
- Switch entry: `kind:u8=1, candidateId:u8, value:u8`
- RGB entry: `kind:u8=2, candidateId:u8, red:u8, green:u8, blue:u8`
- Effect entry: `kind:u8=3, candidateId:u8, effectId:u8, speed:u8`
UPDATE contains only changed values. STATUS contains every configured candidate and its current state. The app sends STATUS_REQUEST when it needs a full refresh.

## Scheduling and correctness
- App coalesces slider writes per (device, kind, candidateId); latest unsent value replaces older unsent values. Different candidates and different kinds never overwrite each other.
- ESP firmware uses the same per-candidate latest-value-wins rule before hardware application. A pending value may be replaced, but commands for other candidates must remain pending.
- Apply values promptly; do not wait 500 ms to apply hardware.
- After hardware application, schedule one consolidated UPDATE 500 ms after the most recent applied change. Reset the debounce on further changes. Emit only the latest changed values per candidate.
- STATUS_REQUEST cancels no work; return a complete snapshot after reading actual/current device state.
- Malformed frames, unknown IDs, out-of-range values and unsupported types are ignored safely. Never write an unconfigured GPIO based on a packet.
- RGB is an RGB color command; LED strip/matrix layout, pixel count and physical rendering are firmware/user logic. The app does not assume a specific LED library or GPIO.
- This v2 baseline is unauthenticated BLE GATT. Pairing/bonding and characteristic permissions may be enabled by a product build; do not claim application-layer encryption.
