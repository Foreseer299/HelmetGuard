# HelmetGuard BLE Audio Protocol v1 — implementation draft

Status: alpha04 software implementation. ESP32 code now initializes an I²S/MAX98357A path, validates START_AUDIO, buffers 50 blocks, streams PCM, extends cumulative authorization on consumption, and reports terminal state. This is build/native-test evidence only: no physical amplifier, speaker, encrypted-throughput, or audible-playback bench pass is claimed. `outputReady` means software I²S initialization succeeded, not that wiring was physically verified.

## UUIDs

| Item | UUID | Properties |
|---|---|---|
| Service | `8F7A0000-6B1D-4A63-9F2A-4F5E52A21000` | Primary |
| Capabilities | `8F7A0001-6B1D-4A63-9F2A-4F5E52A21000` | Read |
| Control | `8F7A0002-6B1D-4A63-9F2A-4F5E52A21000` | Write + Indicate |
| Data | `8F7A0003-6B1D-4A63-9F2A-4F5E52A21000` | Write Without Response |
| Status | `8F7A0004-6B1D-4A63-9F2A-4F5E52A21000` | Notify |

Existing safety service FFF0–FFF3 remains unchanged and has priority over audio.
All multi-byte values are little-endian. Control and Data use CRC-16/CCITT-FALSE (`poly=1021`, `init=FFFF`, no reflection, `xorout=0000`).

## Audio format

- mono, 16 kHz;
- IMA ADPCM 4-bit;
- maximum 256 valid samples per independently decodable block;
- block header: `predictor:i16, stepIndex:u8, reserved:u8=0, validSamples:u16, encodedBytes:u16`;
- predictor is valid sample 0;
- remaining samples are low nibble first, then high nibble;
- `encodedBytes = ceil((validSamples - 1)/2)`.

Cross-language vectors are in `audio_vectors_v1.json`. The required equality is Android decode == ESP32 decode. ADPCM is lossy, so decoded PCM is not required to equal original PCM.

## Startup buffering and cumulative authorization

At 16 kHz and 256 samples/block each block covers 16 ms. An 800 ms startup buffer therefore needs **50 blocks**.

Audio v1 does not use replaceable/delta credits. The receiver reports an inclusive cumulative frontier:

```text
authorizedThroughSeq:u16
```

The sender may transmit only sequences from its current `nextToSend` through that frontier. `nextToSend` is never overwritten by a status update. A stale or duplicate status cannot grant capacity twice. Before playback, the receiver must authorize at least the 50 startup blocks, and it may continue extending the frontier whenever buffer capacity is available. After playback begins, it extends the frontier as blocks leave the receive buffer.

A lost Status notification can only stall the sender. After 750 ms without progress, the sender uses reliable `QUERY_STATUS`; it must not speculate beyond the frontier.

## Control, capabilities and status prototype frames

All frames below end in CRC16 over every preceding byte.

```text
Capabilities (18 bytes):
version:u8, codec:u8, flags:u8, channels:u8, sampleRate:u32,
blockSamples:u16, startupBlocks:u16, maximumBlockBytes:u16,
preferredMtu:u16, crc16:u16
flags bit0=decoderSupported, bit1=outputReady

Control:
version:u8, opcode:u8, sessionId:u32, audioId:u32,
payloadLength:u16, payload:N, crc16:u16

START_AUDIO payload (26 bytes):
codec:u8, channels:u8, sampleRate:u16, totalSamples:u32,
totalBlocks:u16, blockSamples:u16, totalEncodedBytes:u32,
startBufferMs:u16, expiryAfterAcceptMs:u16, priority:u8, volume:u8,
wholeAudioCrc32:u32

Status (18 bytes; Android also accepts the legacy 14-byte form):
version:u8, code:u8, sessionId:u32, expectedSeq:u16,
authorizedThroughSeq:u16, flags:u8, reserved:u8,
receivedBlocks:u16, submittedBlocks:u16, crc16:u16
flags bit0=outputReady
```

Status codes: `0 IDLE`, `1 OPEN`, `2 INTEGRITY_ERROR`, `3 SESSION_MISMATCH`, `4 SEQUENCE_GAP`, `5 MALFORMED`, `6 CANCELLED`, `7 BUFFERING`, `8 PLAYING`, `9 COMPLETED`, `10 UNDERRUN`, `11 START_REJECTED`, `12 EXPIRED`. Android claims `PLAYED_CONFIRMED` only after a same-session `COMPLETED`; any terminal ambiguity remains `PLAYBACK_RESULT_UNKNOWN` and is not replayed automatically.

## Data packet

```text
version:u8
flags:u8
sessionId:u32
audioId:u32
blockSeq:u16
payloadLength:u16
adpcmBlock:N
packetCrc16:u16
```

Fixed overhead is 16 bytes. ATT value max is `negotiatedMtu - 3`; therefore maximum ADPCM block bytes are `MTU - 3 - 16`. Dynamic TTS is enabled only after measured MTU, encrypted throughput, buffer capacity and concurrent telemetry are acceptable.

## Sessions and recovery

- A new random non-zero sessionId is opened on each BLE READY connection.
- Every utterance has a new non-zero audioId.
- Receiver accepts only expectedSeq; future blocks return `SEQ_GAP` and are not queued.
- Duplicates and future blocks are rejected without advancing state; Status repeats the current expectedSeq.
- App keeps separate fresh-send and retransmit cursors, retains blocks, ignores stale NACKs after a newer expectedSeq, and resends from expectedSeq without rewinding cumulative authorization. Duplicate NACKs in one burst are coalesced, but the same unadvanced gap starts another bounded retry round after 500 ms.
- Disconnect or new session clears audio state. Old packets cannot resume playback.
- Expiry is relative: phone checks age before START; helmet counts expiry from START acceptance using its own monotonic clock.

## Streaming integrity

Each Data packet is checked before decode with CRC16. A whole-audio CRC32 covers the concatenation of encoded ADPCM blocks in sequence order and is checked at the end. Because playback is streamed, final CRC32 cannot protect or retract audio already played; a mismatch is reported as partial/error.

`i2s_write` only submits bytes to a DMA transmit buffer. The submittedBlocks counter counts successful DMA submissions and is not a played-sample counter. Before terminal COMPLETED, the legacy driver path submits nine 256-sample zero buffers (more than the configured eight-buffer DMA ring), then waits for a fresh I2S_EVENT_TX_DONE. This forces the preceding audio out of the ring before normal stop; normal completion never zeroes the tail. Abort uses a generation check and the same I/O mutex to stop/clear DMA. This conservative drain strategy still requires bench verification for the selected SDK/hardware. START volume is applied to PCM before DMA submission (`0` is silence, `100` is unity gain). `expiryAfterAcceptMs` limits receipt of the encoded stream; once all blocks pass CRC, expiry no longer truncates DMA drain.

## Accident preemption

On a hardware or phone incident, the helmet rejects the current audioId, stops ordinary I2S DMA without waiting for a long fade, clears DMA/ring buffers, rejects late packets for the aborted session/audio pair, and emits `ABORTED_BY_INCIDENT`. FFF2 incident and FFF3 ACK/Cancel remain ahead of audio traffic.

## Security

Audio Capabilities/Control/Data and safety command characteristic FFF3 require an authenticated encrypted BLE link. Companion Device association is not sufficient. The prototype firmware generates and stores a per-device six-digit passkey, exposes it on the serial provisioning channel, and uses it in SMP bonding. Production packaging must replace the serial provisioning step with a per-device printed/QR credential and a physical pairing window. Performance and preemption tests must use the final encrypted mode.
