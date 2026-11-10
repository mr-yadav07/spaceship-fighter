# ESPCTL v1 — wire protocol

This is the single source of truth for both the Android app and the ESP firmware.
Keep app and firmware on the same protocol version (`Wire.VERSION`).

## Transport-agnostic packet format

The exact same bytes are used over local UDP, the internet relay, and BLE (as one
GATT write / notification value each — no fragmentation at this layer).

```
offset  size  field
0       2     magic "EC" (0x45 0x43)
2       1     version (1)
3       1     type
4       1     flags (bit0 = encrypted)
5       6     device id (= device's MAC, 6 bytes)
11      4     session id (0 before handshake)
15      4     sequence number (per direction, increases by 1 every packet)
19      2     body length (big-endian)
21      N     body (plaintext, or ChaCha20-Poly1305 ciphertext + 16-byte tag if encrypted)
```

All multi-byte integers are big-endian. The 21-byte header is always sent in the
clear — it is also used as the AEAD "associated data" so it cannot be tampered
with even though it isn't secret.

Local UDP port: device listens on **47800**, app listens on **47801**.
BLE service/characteristics: see `BleTransport.kt` (`a6c6000…`, placeholder UUIDs
— replace with the firmware's real ones once decided).

## Discovery and pairing (plaintext)

1. App broadcasts `T_DISCOVER { nonceA[8], hint[8] }` every 3 s.
   `hint = HMAC-SHA256(authKey, "HINT" || nonceA)[0:8]` — proves the app *has*
   some Owner Key without revealing it.
2. Device replies `T_ANNOUNCE { authorized:1, type:1, fw:1, nameLen:1, name }`.
   `authorized` is best-effort (device can't verify the hint cryptographically);
   real authorization happens in the handshake below.
3. On "Connect", app sends `T_HELLO_REQ { nonceA[16], mac[16] }` where
   `mac = HMAC-SHA256(authKey, "HELLO" || nonceA || deviceId)[0:16]`.
4. Device checks `mac`. If it has an Owner Key configured and it matches:
   replies `T_HELLO_RESP { nonceD[16], session[4], mac[16] }` where
   `mac = HMAC-SHA256(authKey, "RESP" || nonceA || nonceD || session || deviceId)[0:16]`.
   If it does not match: replies `T_ERROR { E_AUTH }` (plaintext).
5. Both sides derive `sessionKey = HMAC-SHA256(authKey, "SESSION" || nonceA || nonceD)`
   and use it for ChaCha20-Poly1305 on every packet of that session (`flags.bit0 = 1`).

`authKey = SHA-256("espctl-v1|" + ownerKey)`. The Owner Key string itself is
never sent over the air.

## Session packets (encrypted)

| Type | Name | Body (app → device) |
|---|---|---|
| 0x20 | CAPS_GET | *(empty)* |
| 0x30 | SET_RGB | `out, r, g, b` |
| 0x31 | SET_BRIGHT | `out, level` |
| 0x32 | SET_POWER | `out, on(0/1)` |
| 0x33 | SET_ONOFF | `out, on(0/1)` |
| 0x40 | EFFECT_DATA | `effectId, chunkIndex, chunkTotal, data…` |
| 0x41 | EFFECT_TRIGGER | `out, effectId, start(0/1)` |
| 0x50 | GET_STATUS | `out` (0xFF = all outputs) |

`out` is an 8-bit output index; `0xFF` means "all outputs of that kind".

| Type | Name | Body (device → app) |
|---|---|---|
| 0x21 | CAPS | `type, fw, nameLen, name…, nRgb, (outId,maxLeds)…, nOnOff, outId…` |
| 0x60 | STATUS | `count, (outId, kind, fieldMask, fields…)…` — `kind 0`=RGB, `1`=on/off; RGB field order is on?,rgb?,brightness?,effect? gated by bits 0/1/2/3 of fieldMask |
| 0x61 | ACK | `seq[4], code` |
| 0x62 | NO_DATA_1 | *(empty)* — sent once, 3 s after the device's last valid inbound packet |
| 0x63 | NO_DATA_2 | *(empty)* — sent once, 4 s after |
| 0x70 | PING | *(empty)* — app sends if idle >900 ms so the device's 3+1+1 s timer never falsely fires |
| 0x7F | ERROR | `code` (see `Wire.E_*`) |

Status push is latest-value-wins: the device applies every value it receives
immediately, but only *reports* 500 ms after the last change (or instantly for
ON/OFF, effect trigger and explicit GET_STATUS — see firmware spec §10).

## Relay registration (plaintext, internet UDP only)

`T_RELAY_REGISTER { role:1, ownerId[8], count:1, deviceId[6]… }` — sent by the
app every 15 s (keeps NAT mapping open) and by the device once connectivity
comes up. `ownerId = SHA-256(authKey || "owner")[0:8]`: a pseudonymous id the
relay can group devices by, without ever learning the Owner Key.

The relay's own control messages (`T_RELAY_OK`, `T_RELAY_LIST`) and its
routing behaviour are **server-side and out of scope here** — this repo only
defines the packet shapes `DeviceHub.kt` already parses
(`Payloads.parseRelayList`). Build the relay to match `docs/PROTOCOL.md`,
not the other way round.

## Error codes

`E_BAD_CMD=1, E_NO_SESSION=2, E_AUTH=3, E_RANGE=4, E_BUSY=5, E_UNSUPPORTED=6`.
On `E_NO_SESSION` the app drops its session key and re-runs the handshake
automatically (handles device reboot without user action).

## Why this shape

- **One format, three transports** — exactly what the spec asked for ("use
  one unified command and packet format across local and remote UDP"); BLE
  reuses it too so there is only one codec to maintain.
- **AEAD, not a custom cipher** — ChaCha20-Poly1305 is fast on an ESP8266 and
  gives authenticity + confidentiality in one pass; swapping it later only
  touches `Crypto.kt`.
- **HMAC challenge instead of sending the key** — satisfies "Owner Key must
  never be broadcast during discovery" and "no Owner Key in discovery
  broadcasts" from both specs.
- **Per-direction sequence + 64-packet replay window** (`ReplayWindow` in
  `PacketCodec.kt`) — "sequence numbers and session information should be
  supported", and makes replay/duplication attacks pointless without needing
  TCP.
