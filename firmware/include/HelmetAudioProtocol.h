#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>

namespace helmetguard::audio {

constexpr uint8_t kVersion = 1;
constexpr uint32_t kSampleRateHz = 16000;
constexpr uint16_t kBlockSamples = 256;
constexpr uint16_t kDefaultStartBufferMs = 800;
constexpr uint16_t kRequiredStartupBlocks = 50;  // ceil(800ms * 16kHz / 256)
constexpr size_t kBlockHeaderBytes = 8;
constexpr size_t kStartAudioPayloadBytes = 26;

inline uint16_t readU16Le(const uint8_t* p) {
    return static_cast<uint16_t>(p[0]) | static_cast<uint16_t>(p[1]) << 8U;
}
inline int16_t readI16Le(const uint8_t* p) { return static_cast<int16_t>(readU16Le(p)); }
inline uint32_t readU32Le(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | static_cast<uint32_t>(p[1]) << 8U |
           static_cast<uint32_t>(p[2]) << 16U | static_cast<uint32_t>(p[3]) << 24U;
}

inline uint16_t crc16Ccitt(const uint8_t* data, size_t length) {
    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < length; ++i) {
        crc ^= static_cast<uint16_t>(data[i]) << 8U;
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc & 0x8000U) != 0 ? static_cast<uint16_t>((crc << 1U) ^ 0x1021U)
                                         : static_cast<uint16_t>(crc << 1U);
        }
    }
    return crc;
}

inline uint32_t crc32Update(uint32_t crc, const uint8_t* data, size_t length) {
    for (size_t i = 0; i < length; ++i) {
        crc ^= data[i];
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc & 1U) != 0 ? (crc >> 1U) ^ 0xEDB88320U : crc >> 1U;
        }
    }
    return crc;
}

struct StartAudioView {
    uint8_t codec = 0;
    uint8_t channels = 0;
    uint16_t sampleRateHz = 0;
    uint32_t totalSamples = 0;
    uint16_t totalBlocks = 0;
    uint16_t blockSamples = 0;
    uint32_t totalEncodedBytes = 0;
    uint16_t startBufferMs = 0;
    uint16_t expiryAfterAcceptMs = 0;
    uint8_t priority = 0;
    uint8_t volume = 0;
    uint32_t wholeAudioCrc32 = 0;
};

inline bool parseStartAudioPayload(const uint8_t* data, size_t length, StartAudioView& out) {
    if (data == nullptr || length != kStartAudioPayloadBytes) return false;
    out.codec = data[0];
    out.channels = data[1];
    out.sampleRateHz = readU16Le(data + 2);
    out.totalSamples = readU32Le(data + 4);
    out.totalBlocks = readU16Le(data + 8);
    out.blockSamples = readU16Le(data + 10);
    out.totalEncodedBytes = readU32Le(data + 12);
    out.startBufferMs = readU16Le(data + 16);
    out.expiryAfterAcceptMs = readU16Le(data + 18);
    out.priority = data[20];
    out.volume = data[21];
    out.wholeAudioCrc32 = readU32Le(data + 22);
    const uint64_t maximumSamples = static_cast<uint64_t>(out.totalBlocks) * kBlockSamples;
    const uint64_t minimumSamples = static_cast<uint64_t>(out.totalBlocks - (out.totalBlocks > 0 ? 1U : 0U)) * kBlockSamples + 1U;
    return out.sampleRateHz == kSampleRateHz && out.blockSamples == kBlockSamples &&
           out.channels == 1 && out.codec == 1 && out.totalSamples >= minimumSamples &&
           out.totalSamples <= maximumSamples && out.totalBlocks > 0 && out.totalEncodedBytes > 0 &&
           out.startBufferMs >= kDefaultStartBufferMs && out.expiryAfterAcceptMs >= 1000 &&
           out.priority <= 100 && out.volume <= 100;
}

struct ControlFrameView {
    uint8_t opcode = 0;
    uint32_t sessionId = 0;
    uint32_t audioId = 0;
    const uint8_t* payload = nullptr;
    uint16_t payloadLength = 0;
};

inline bool parseControlFrame(const uint8_t* data, size_t length, ControlFrameView& out) {
    if (data == nullptr || length < 14 || data[0] != kVersion) return false;
    const uint16_t payloadLength = readU16Le(data + 10);
    if (length != 12U + payloadLength + 2U) return false;
    if (crc16Ccitt(data, length - 2) != readU16Le(data + length - 2)) return false;
    out.opcode = data[1];
    out.sessionId = readU32Le(data + 2);
    out.audioId = readU32Le(data + 6);
    out.payloadLength = payloadLength;
    out.payload = data + 12;
    return true;
}

struct AdpcmBlockView {
    int16_t predictor = 0;
    uint8_t stepIndex = 0;
    uint16_t validSamples = 0;
    const uint8_t* encoded = nullptr;
    uint16_t encodedBytes = 0;
};

inline bool parseAdpcmBlock(const uint8_t* data, size_t length, AdpcmBlockView& out) {
    if (data == nullptr || length < kBlockHeaderBytes) return false;
    out.predictor = readI16Le(data);
    out.stepIndex = data[2];
    const uint8_t reserved = data[3];
    out.validSamples = readU16Le(data + 4);
    out.encodedBytes = readU16Le(data + 6);
    out.encoded = data + kBlockHeaderBytes;
    if (reserved != 0 || out.stepIndex > 88 || out.validSamples == 0 || out.validSamples > kBlockSamples) return false;
    const uint16_t expectedBytes = static_cast<uint16_t>(out.validSamples / 2U);
    return out.encodedBytes == expectedBytes && length == kBlockHeaderBytes + out.encodedBytes;
}

inline bool decodeAdpcmBlock(const AdpcmBlockView& block, int16_t* output, size_t outputCapacity) {
    if (output == nullptr || outputCapacity < block.validSamples || block.validSamples == 0 || block.stepIndex > 88) return false;
    static constexpr int8_t indexTable[16] = {-1,-1,-1,-1,2,4,6,8,-1,-1,-1,-1,2,4,6,8};
    static constexpr int16_t stepTable[89] = {
        7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,
        73,80,88,97,107,118,130,143,157,173,190,209,230,253,279,307,337,371,408,
        449,494,544,598,658,724,796,876,963,1060,1166,1282,1411,1552,1707,1878,
        2066,2272,2499,2749,3024,3327,3660,4026,4428,4871,5358,5894,6484,7132,
        7845,8630,9493,10442,11487,12635,13899,15289,16818,18500,20350,22385,
        24623,27086,29794,32767
    };
    int predictor = block.predictor;
    int index = block.stepIndex;
    output[0] = block.predictor;
    for (uint16_t sample = 1; sample < block.validSamples; ++sample) {
        const uint8_t packed = block.encoded[(sample - 1U) / 2U];
        const uint8_t code = ((sample - 1U) & 1U) == 0 ? packed & 0x0FU : (packed >> 4U) & 0x0FU;
        const int step = stepTable[index];
        int difference = step >> 3;
        if ((code & 4U) != 0) difference += step;
        if ((code & 2U) != 0) difference += step >> 1;
        if ((code & 1U) != 0) difference += step >> 2;
        predictor += (code & 8U) != 0 ? -difference : difference;
        predictor = std::max(-32768, std::min(32767, predictor));
        index = std::max(0, std::min(88, index + indexTable[code]));
        output[sample] = static_cast<int16_t>(predictor);
    }
    return true;
}

inline uint16_t addSequence(uint16_t value, uint16_t amount) { return static_cast<uint16_t>(value + amount); }
inline uint16_t forwardDistance(uint16_t from, uint16_t to) { return static_cast<uint16_t>(to - from); }
inline bool isStrictlyAfter(uint16_t candidate, uint16_t reference) {
    const uint16_t distance = forwardDistance(reference, candidate);
    return distance > 0 && distance <= 0x7FFFU;
}

/** Receiver advertises an inclusive cumulative authorization frontier, never a delta credit. */
class CumulativeAuthorizationFrontier {
public:
    void reset(uint16_t initialExpected = 0) {
        expected_ = initialExpected;
        authorizedThrough_ = static_cast<uint16_t>(initialExpected - 1U);
    }
    void authorizeInitialStartup(uint16_t blocks = kRequiredStartupBlocks) {
        authorizedThrough_ = static_cast<uint16_t>(expected_ + blocks - 1U);
    }
    void extendByFreedBlocks(uint16_t blocks) { authorizedThrough_ = static_cast<uint16_t>(authorizedThrough_ + blocks); }
    bool accept(uint16_t sequence) {
        if (sequence != expected_) return false;
        if (forwardDistance(sequence, authorizedThrough_) > 0x7FFFU) return false;
        expected_ = static_cast<uint16_t>(expected_ + 1U);
        return true;
    }
    uint16_t expected() const { return expected_; }
    uint16_t authorizedThrough() const { return authorizedThrough_; }
private:
    uint16_t expected_ = 0;
    uint16_t authorizedThrough_ = 0xFFFFU;
};

}  // namespace helmetguard::audio
