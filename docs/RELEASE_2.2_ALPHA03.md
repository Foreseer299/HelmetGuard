> 历史外部交付说明：独立核对发现工作区未包含下述多数核心实现。本文仅保留历史，不作当前验收依据；请以 RELEASE_2.2_ALPHA04.md 和 TEST_RESULTS_ALPHA04.md 为准。

# HelmetGuard 2.2.0-alpha03 — independent-review fixes

Date: 2026-11-03

## Scope and review decisions

This alpha applies review findings only where they match the agreed first-release product boundary. It does not broaden the product into consumer shopping/logistics apps, courier workflows, A2DP audio, Android below API 31, or HarmonyOS 6.

Accepted and implemented:

- Android requests BLE MTU 247 and records the callback value; failed or unstarted negotiation explicitly falls back to the real default MTU 23.
- Firmware treats I²S DMA submission and `I2S_EVENT_TX_DONE` as different stages. A block is consumed only after TX completion, and normal completion never zeroes the final DMA audio.
- Accident state and a generation/latch gate preempt synthesis and transfer, clear normal announcements, prioritize ABORT, and reject late synthesis results until the incident lifecycle ends.
- START volume 0–100 is applied to PCM before DMA submission. Zero is mute, 100 is unity, and malformed values cannot amplify or overflow samples.
- Repeated NACKs for one unadvanced gap are coalesced for 500 ms but can start bounded later retry rounds, up to three rounds per block.
- Incoming-call announcements are revocable by trace ID on answer/hang-up. If overlapping SIM sessions cannot be linked reliably to a dialer notification, identity is not guessed and the generic “有来电” path remains.
- Announcement TTL is checked after TTS and again before START. Firmware enforces the START receive deadline with monotonic time and reports `EXPIRED`.
- Rider notification sources default to none. The settings page lists launchable third-party apps and only exact package names explicitly selected by the user are accepted.
- Stale `tts_*` cache files are removed at synthesizer startup. Diagnostics now include stage durations and a redacted BLE/firmware snapshot with MTU, loss, capability, status, receive and consume counters.
- A dedicated ESP32-S3 N16R8 board definition is included alongside the existing N8/no-audio build variants.

Not adopted as product expansion:

- No automatic rider-app allowlist and no consumer Meituan, Taobao, JD, shipment/signature, or courier categories.
- No A2DP hardware dependency.
- No claim that final whole-stream CRC can retract audio already played; it remains a final integrity signal only.
- No public/production authorization is inferred for AISHELL-3 candidate weights from the runtime or dataset licenses.

## Protocol changes

- Audio status code `12`: `EXPIRED`.
- Extended status frame is 18 bytes and adds `receivedBlocks` and `consumedBlocks`; Android still accepts the legacy 14-byte status frame.
- Receive expiry is selected from expected audio duration plus margin, clamped to 15–60 seconds.
- Completion requires final CRC success, all blocks consumed, and TX completion for the last DMA descriptor.

See `AUDIO_PROTOCOL_V1_DRAFT.md` for byte-level details.

## Software validation

Validated in this workspace:

- Android JVM tests: 48/48 passed.
- Firmware native tests: 19/19 passed.
- ESP32-S3 N8 audio build: passed.
- ESP32-S3 N8 no-audio build: passed.
- ESP32-S3 N16R8 audio build: passed using the explicit 16 MB flash / 8 MB OPI PSRAM board profile.
- Android debug APK: passed; versionCode 42, versionName 2.2.0-alpha03, minSdk 31, targetSdk 35, ARM64 only.
- APK inspection confirmed the local sherpa runtime and selected offline TTS files are present and `rule.far` is absent.

## Validation boundary

This is software/native/build evidence, not a physical-bench pass. No claim is made yet for MAX98357A wiring, loudspeaker output, I²S audibility, encrypted BLE throughput, real dual-SIM behavior, real TTS-engine behavior on target phones, accident preemption latency on hardware, or end-to-end road use.

## Distribution gate

The bundled AISHELL-3 candidate weight is for internal evaluation only. The model archive provides no explicit weight license. Do not publicly or commercially distribute the evaluation APK until explicit weight permission or legal review is obtained. See `THIRD_PARTY_TTS.md`.
