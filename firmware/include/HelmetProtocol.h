#pragma once

#include <stddef.h>
#include <stdint.h>

namespace helmetguard {

constexpr uint8_t kHeader1 = 0xAA;
constexpr uint8_t kHeader2 = 0x55;
constexpr uint8_t kVersion = 0x01;
constexpr uint8_t kTelemetry = 0x01;
constexpr uint8_t kIncident = 0x02;
constexpr uint8_t kButton = 0x03;
constexpr uint8_t kCommand = 0x04;
constexpr uint8_t kHeartbeat = 0x05;

constexpr uint8_t kAckIncident = 0x01;
constexpr uint8_t kCancel = 0x02;
constexpr uint8_t kFind = 0x03;
constexpr uint8_t kCalibrate = 0x04;
constexpr uint8_t kSyncTime = 0x05;

constexpr size_t kFrameOverhead = 7;
constexpr size_t kMaximumPayload = 128;

struct FrameView {
    uint8_t type;
    uint8_t sequence;
    const uint8_t* payload;
    uint8_t payloadLength;
};

inline uint8_t crc8(const uint8_t* data, size_t length) {
    uint8_t crc = 0;
    for (size_t i = 0; i < length; ++i) {
        crc ^= data[i];
        for (uint8_t bit = 0; bit < 8; ++bit) {
            crc = (crc & 0x80U) ? static_cast<uint8_t>((crc << 1U) ^ 0x07U)
                                : static_cast<uint8_t>(crc << 1U);
        }
    }
    return crc;
}

inline void putU16Le(uint8_t* target, uint16_t value) {
    target[0] = static_cast<uint8_t>(value & 0xffU);
    target[1] = static_cast<uint8_t>((value >> 8U) & 0xffU);
}

inline void putI16Le(uint8_t* target, int16_t value) {
    putU16Le(target, static_cast<uint16_t>(value));
}

inline void putU32Le(uint8_t* target, uint32_t value) {
    target[0] = static_cast<uint8_t>(value & 0xffU);
    target[1] = static_cast<uint8_t>((value >> 8U) & 0xffU);
    target[2] = static_cast<uint8_t>((value >> 16U) & 0xffU);
    target[3] = static_cast<uint8_t>((value >> 24U) & 0xffU);
}

inline uint32_t readU32Le(const uint8_t* source) {
    return static_cast<uint32_t>(source[0]) |
           (static_cast<uint32_t>(source[1]) << 8U) |
           (static_cast<uint32_t>(source[2]) << 16U) |
           (static_cast<uint32_t>(source[3]) << 24U);
}

inline size_t encodeFrame(
    uint8_t type,
    uint8_t sequence,
    const uint8_t* payload,
    uint8_t payloadLength,
    uint8_t* output,
    size_t capacity
) {
    const size_t total = kFrameOverhead + payloadLength;
    if (output == nullptr || total > capacity || payloadLength > kMaximumPayload) return 0;
    output[0] = kHeader1;
    output[1] = kHeader2;
    output[2] = kVersion;
    output[3] = type;
    output[4] = sequence;
    output[5] = payloadLength;
    for (uint8_t i = 0; i < payloadLength; ++i) output[6 + i] = payload[i];
    output[6 + payloadLength] = crc8(output, 6 + payloadLength);
    return total;
}

inline bool decodeFrame(const uint8_t* data, size_t length, FrameView& result) {
    if (data == nullptr || length < kFrameOverhead || data[0] != kHeader1 || data[1] != kHeader2) return false;
    if (data[2] != kVersion) return false;
    const uint8_t payloadLength = data[5];
    if (payloadLength > kMaximumPayload || length != kFrameOverhead + payloadLength) return false;
    if (crc8(data, 6 + payloadLength) != data[6 + payloadLength]) return false;
    result = FrameView{data[3], data[4], data + 6, payloadLength};
    return true;
}

inline int16_t scaledI16(float value, float scale) {
    const float scaled = value * scale;
    if (scaled > 32767.0f) return 32767;
    if (scaled < -32768.0f) return -32768;
    return static_cast<int16_t>(scaled >= 0.0f ? scaled + 0.5f : scaled - 0.5f);
}

}  // namespace helmetguard
