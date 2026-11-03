# HelmetGuard 2.2.0-alpha02 prototype release

Date: 2026-11-03  
Scope: Android 12+ / API 31+, ARM64, food-delivery rider notifications only

## Product-scope corrections

- Rider-side events only: new order, assignment/reassignment, cancellation, pickup/delivery timeout, order change, and important work contact.
- No Taobao/JD/Meituan consumer-order or e-commerce shipping/delivery templates. Ordinary-user notification scope remains SMS and call identity support.
- Rider expected income, delivery/running fee, reward, and subsidy are enabled by default. Customer payment, product total, and cash-on-delivery remain hidden.

## Android changes

- Rider business classification now runs before generic promotion filtering, preventing coupon wording from suppressing an actual rider event.
- STANDARD speech is structured and excludes free-form notification text. DETAILED text redacts addresses, phone numbers, verification codes, URLs, full order identifiers, and non-rider-income amounts.
- Only a reliable same-source/same-package `businessKey` may replace a pending order state. Late states cannot overwrite newer states; unrelated orders remain separate; queue capacity defaults to three.
- TTS now rejects non-system engines, network-required Voice objects, and Voice feature sets that indicate network synthesis both at listing and synthesis time.
- ARM64 embedded sherpa-onnx/VITS fallback code, deterministic dependency preparation, offline PCM conversion/resampling, and local preview are implemented. No cloud fallback exists and the app requests no INTERNET permission.
- BLE sender has independent fresh/retransmit cursors, stale-NACK protection, cumulative authorization, sequence-gap/underrun recovery, final completion proof, and incident-triggered urgent ABORT. Unknown playback results are never automatically repeated on the phone.
- Extended Compose icon dependency was replaced with the core icon package, materially reducing D8 memory and app method footprint.

## ESP32-S3 firmware changes

- FFF3 safety commands now require encrypted and MITM-authenticated writes.
- START_AUDIO validates the exact Android 26-byte payload and binds sessionId + audioId.
- Each ADPCM packet has CRC16; accepted ADPCM bytes feed rolling CRC32; a final mismatch stops the stream but is not claimed to retract earlier audio.
- A 56-block PCM queue supports the required 50-block/800 ms startup buffer. The playback task writes PCM16/16 kHz mono to configurable MAX98357A I²S pins and extends the inclusive authorization frontier only after block consumption.
- Sequence gaps, stale session/audio identifiers, malformed blocks, underrun, cancellation, final completion, and output readiness have explicit status codes.
- Incident handling stops I²S without a fade, zeroes DMA, clears queued PCM, invalidates the old session, and reports cancellation before reset.
- Both I²S-enabled and `HG_ENABLE_I2S_AUDIO=0` builds are supported.

## Verification completed

- Android JVM tests: **45/45 passed**.
- PlatformIO native firmware tests: **16/16 passed**.
- ESP32-S3 I²S build: passed; RAM 30,168 / 327,680 bytes, flash 573,357 / 1,310,720 bytes. No-audio build also passed; RAM 30,112 bytes, flash 557,981 bytes.
- Android embedded-TTS debug APK: built for ARM64, model/native assets inspected, APK Signature Scheme v2 verified.
- Protocol vectors include OPEN_SESSION, Android START_AUDIO payload/control frame, 50-block authorization, CRC16, and cross-language ADPCM decode equality.

## Not claimed / release gates

- No physical MAX98357A/speaker audible-playback pass, encrypted BLE throughput benchmark, packet-impairment bench, incident DMA timing measurement, Android/HarmonyOS 4 device matrix, or dual-SIM call matrix was run in this software-only environment.
- `outputReady=true` means I²S driver initialization succeeded. `COMPLETED` proves the firmware consumed the validated stream through I²S DMA; it is not electrical proof that a disconnected or faulty speaker emitted sound.
- The debug APK uses the Android debug certificate and is for prototype installation only.
- The candidate pre-trained AISHELL-3 weight archive contains no explicit weight LICENSE. Runtime and training-data licenses do not automatically license the weights. The bundled APK is evaluation-only; public/production redistribution is blocked pending explicit weight-license evidence. See `THIRD_PARTY_TTS.md`.
