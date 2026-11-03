# HelmetGuard 2.2.0-alpha01

Date: 2026-11-03  
Base: `release/2.1` commit `e35d02dbb9ab555c4e695703e384d391bff89a4b`

## Scope delivered in this alpha

### Android

- Minimum Android 12 / API 31; target/compile API 35.
- `NotificationListenerService` with conservative package allowlist, group-summary/self-notification suppression and structured classification for rider-side new order, assignment/reassignment, cancellation, pickup/delivery timeout, order change and important contact events.
- Reliable business keys are the only basis for order merging. The Android notification key is not treated as an order ID; different reliable order IDs are not merged.
- Privacy policy for verification codes, long numbers, URLs, addresses and amounts. Raw caller numbers are not spoken as identities.
- API 31 `TelephonyCallback.CallStateListener` registered per active subscription. Telephony is the call-lifecycle source of truth; dialer notifications only supplement identity during a bounded join window.
- Local TTS WAV generation, PCM16 WAV parser, mono 16 kHz resampling and independently decodable IMA-ADPCM blocks.
- Audio v1 Kotlin packet codec, CRC16/CRC32, uint16 sequence arithmetic and synchronized cumulative authorization window.
- Optional 8F7A000x service discovery in `HelmetBleClient`, authenticated capability read, status subscription, control/data writes, MTU checks and audio-operation cancellation. Incident ACK/Cancel/ABORT operations are queued ahead of ordinary audio blocks.
- Announcement audio coordinator drains the priority queue and only synthesizes/streams when the helmet reports `outputReady=true`; after bytes are queued without final proof it records “playback result unknown” and does not auto-repeat on the phone.
- Notification/TTS settings page: enable, source switches, per-event switches, content level, installed Chinese voice list, phone preview, rate, pitch, target helmet volume and notification-listener shortcut.
- Structured rolling diagnostics and redacted ZIP export. Notification bodies, names, phone numbers, addresses, pairing secrets and audio are excluded by design. Stale TTS/audio cache files are removed at startup.

### ESP32-S3

- Header-only IMA-ADPCM decoder and cumulative frontier helpers, usable in native tests and firmware.
- Authenticated 8F7A000x audio service prototype. A per-device six-digit passkey is generated in NVS and participates in SMP; alpha provisioning prints it on serial.
- Capabilities report decoder support but `outputReady=false`. Up to the 50-block software-validation window can be CRC-checked and decoded, but samples are deliberately not sent to I²S in this build.
- Disconnect and incident paths invalidate audio session state. Incident/status traffic remains separate from the FFF0–FFF3 safety protocol.

## Protocol decisions frozen for v1 prototype

- PCM16 mono, 16 kHz.
- 256 valid samples maximum per independently decodable block.
- IMA ADPCM predictor and step index in every block; little-endian; low nibble first.
- At 16 kHz / 256 samples, 800 ms startup buffering requires `ceil(0.8*16000/256)=50` blocks.
- Flow control is an inclusive cumulative `authorizedThroughSeq`, never replaceable/delta credits. Sender `nextToSend` is independent and monotonic modulo uint16.
- Whole-audio CRC32 is only a final check; it cannot retract samples already played.
- Cross-platform acceptance means Android and ESP32 decode the same ADPCM bytes to identical PCM samples. Lossy output is not expected to equal the original PCM.

See `AUDIO_PROTOCOL_V1_DRAFT.md` and `audio_vectors_v1.json`.

## Verification performed

| Check | Result |
|---|---|
| Android unit tests (`testDebugUnitTest`) | PASS: 37/37 |
| Android debug APK (`assembleDebug`) | PASS |
| Android manifest/package inspection | PASS: versionCode 40, versionName 2.2.0-alpha01, minSdk 31, targetSdk 35 |
| Firmware native protocol/ADPCM tests | PASS: 11/11 |
| ESP32-S3 PlatformIO build | PASS: RAM 30,072 bytes (9.2%), flash 556,593 bytes (42.5%) |
| Android↔ESP32 ADPCM fixed decode vector | PASS in JVM and native suites |
| Physical phone install/run | NOT RUN in this environment |
| Physical BLE encrypted throughput | NOT RUN |
| MAX98357A / speaker / I²S output | NOT CONNECTED / NOT RUN |
| Incident preemption with active DMA | NOT RUN; no I²S DMA in this alpha |
| Huawei HarmonyOS 4 device test | NOT RUN |

## Artifact status

- APK is a debug-signed prototype with application ID `com.helmet.guard.debug`, not a production release build.
- Firmware binary is for PlatformIO board environment `esp32-s3-devkitc-1`. It is a protocol/decoder prototype, not a claim of audio hardware readiness.
- Hashes are supplied in `artifacts/SHA256SUMS.txt` in the packaged delivery.

## Next implementation gate

1. Obtain/confirm MAX98357A and ESP32-S3 N16R8 pin mapping, then add double-buffered I²S DMA and output capability gating.
2. Complete the bench status machine with explicit STARTED/COMPLETED/CRC_ERROR/UNDERRUN/ABORTED outcomes, measured write-without-response pacing and bounded resend against real I²S consumption.
3. Add physical pairing window and QR/printed per-device passkey provisioning; remove serial-only production provisioning.
4. Perform encrypted coexistence benchmarks with 20 Hz telemetry, 800 ms startup target, packet impairment and disconnect/reconnect.
5. Verify incident preemption clears pending GATT fragments, ADPCM/ring/DMA state and rejects old-session writes; ACK/Cancel remains highest priority.
6. Test standard Android 12+ and Huawei HarmonyOS 4 devices with real rider-app notification samples; calibrate package/channel rules without retaining raw content.
