#pragma once

#include <cstddef>
#include <cstdint>
#include "HelmetAudioProtocol.h"

namespace helmetguard::audio {

enum class AudioPhase : uint8_t { IDLE, OPEN, STARTED, RECEIVING, COMPLETE, CANCELLED, ERROR };
enum class BlockAcceptResult : uint8_t {
    ACCEPTED,
    INPUT_COMPLETE,
    SESSION_MISMATCH,
    AUDIO_MISMATCH,
    NOT_STARTED,
    SEQUENCE_GAP,
    OUTSIDE_AUTHORIZATION,
    INVALID_LAST_FLAG,
    BYTE_COUNT_OVERFLOW,
    FINAL_CRC_MISMATCH
};

/**
 * Deterministic protocol state. It does not own PCM buffers or I2S; callers enqueue decoded blocks
 * only after ACCEPTED/INPUT_COMPLETE. CRC validates the received ADPCM stream, not audio already played.
 */
class AudioSessionState {
public:
    void reset(AudioPhase phase = AudioPhase::IDLE) {
        phase_ = phase;
        sessionId_ = 0;
        audioId_ = 0;
        expectedSequence_ = 0;
        authorizedThrough_ = 0xFFFFU;
        totalBlocks_ = 0;
        totalEncodedBytes_ = 0;
        receivedBlocks_ = 0;
        receivedEncodedBytes_ = 0;
        consumedBlocks_ = 0;
        expectedCrc32_ = 0;
        rollingCrc32_ = 0xFFFFFFFFU;
        outputReady_ = false;
        expiryMs_ = 0; startedMs_ = 0; volume_ = 0;
    }

    bool open(uint32_t sessionId, bool outputReady) {
        if (sessionId == 0) return false;
        reset(AudioPhase::OPEN);
        sessionId_ = sessionId;
        outputReady_ = outputReady;
        authorizedThrough_ = kRequiredStartupBlocks - 1U;
        return true;
    }

    bool start(uint32_t sessionId, uint32_t audioId, const StartAudioView& start, uint32_t nowMs = 0) {
        if (phase_ != AudioPhase::OPEN || !outputReady_ || sessionId != sessionId_ || audioId == 0) return false;
        if (start.sampleRateHz != kSampleRateHz || start.blockSamples != kBlockSamples ||
            start.channels != 1 || start.codec != 1 || start.totalSamples == 0 || start.totalBlocks == 0 ||
            start.totalEncodedBytes == 0 || start.startBufferMs < kDefaultStartBufferMs ||
            start.expiryAfterAcceptMs < 1000 || start.priority > 100 || start.volume > 100) return false;
        audioId_ = audioId;
        startedMs_ = nowMs; expiryMs_ = start.expiryAfterAcceptMs; volume_ = start.volume;
        totalBlocks_ = start.totalBlocks;
        totalEncodedBytes_ = start.totalEncodedBytes;
        expectedCrc32_ = start.wholeAudioCrc32;
        phase_ = AudioPhase::STARTED;
        return true;
    }

    BlockAcceptResult acceptBlock(
        uint32_t sessionId,
        uint32_t audioId,
        uint16_t sequence,
        const uint8_t* encodedBlock,
        size_t encodedLength,
        bool lastBlock
    ) {
        if (sessionId != sessionId_) return BlockAcceptResult::SESSION_MISMATCH;
        if (audioId != audioId_) return BlockAcceptResult::AUDIO_MISMATCH;
        if (phase_ != AudioPhase::STARTED && phase_ != AudioPhase::RECEIVING) return BlockAcceptResult::NOT_STARTED;
        if (sequence != expectedSequence_) return BlockAcceptResult::SEQUENCE_GAP;
        if (forwardDistance(sequence, authorizedThrough_) > 0x7FFFU) return BlockAcceptResult::OUTSIDE_AUTHORIZATION;
        const bool isFinalIndex = receivedBlocks_ + 1U == totalBlocks_;
        if (lastBlock != isFinalIndex) return BlockAcceptResult::INVALID_LAST_FLAG;
        if (encodedBlock == nullptr || encodedLength == 0 || receivedEncodedBytes_ + encodedLength > totalEncodedBytes_) {
            return BlockAcceptResult::BYTE_COUNT_OVERFLOW;
        }

        rollingCrc32_ = crc32Update(rollingCrc32_, encodedBlock, encodedLength);
        receivedEncodedBytes_ += static_cast<uint32_t>(encodedLength);
        ++receivedBlocks_;
        expectedSequence_ = static_cast<uint16_t>(expectedSequence_ + 1U);
        phase_ = AudioPhase::RECEIVING;

        if (!isFinalIndex) return BlockAcceptResult::ACCEPTED;
        if (receivedEncodedBytes_ != totalEncodedBytes_ || (rollingCrc32_ ^ 0xFFFFFFFFU) != expectedCrc32_) {
            phase_ = AudioPhase::ERROR;
            return BlockAcceptResult::FINAL_CRC_MISMATCH;
        }
        return BlockAcceptResult::INPUT_COMPLETE;
    }

    void blockConsumed() {
        if (consumedBlocks_ < receivedBlocks_) {
            ++consumedBlocks_;
            authorizedThrough_ = static_cast<uint16_t>(authorizedThrough_ + 1U);
            if (consumedBlocks_ == totalBlocks_ && receivedBlocks_ == totalBlocks_) phase_ = AudioPhase::COMPLETE;
        }
    }

    bool expired(uint32_t nowMs) const {
        return expiryMs_ > 0 && (phase_ == AudioPhase::STARTED || phase_ == AudioPhase::RECEIVING) && static_cast<uint32_t>(nowMs - startedMs_) >= expiryMs_;
    }
    uint8_t volume() const { return volume_; }

    void cancel() { phase_ = AudioPhase::CANCELLED; }

    AudioPhase phase() const { return phase_; }
    uint32_t sessionId() const { return sessionId_; }
    uint32_t audioId() const { return audioId_; }
    uint16_t expectedSequence() const { return expectedSequence_; }
    uint16_t authorizedThrough() const { return authorizedThrough_; }
    uint32_t totalBlocks() const { return totalBlocks_; }
    uint32_t receivedBlocks() const { return receivedBlocks_; }
    uint32_t consumedBlocks() const { return consumedBlocks_; }
    bool outputReady() const { return outputReady_; }

private:
    AudioPhase phase_ = AudioPhase::IDLE;
    uint32_t sessionId_ = 0;
    uint32_t audioId_ = 0;
    uint16_t expectedSequence_ = 0;
    uint16_t authorizedThrough_ = 0xFFFFU;
    uint32_t totalBlocks_ = 0;
    uint32_t totalEncodedBytes_ = 0;
    uint32_t receivedBlocks_ = 0;
    uint32_t receivedEncodedBytes_ = 0;
    uint32_t consumedBlocks_ = 0;
    uint32_t expectedCrc32_ = 0;
    uint32_t rollingCrc32_ = 0xFFFFFFFFU;
    bool outputReady_ = false;
    uint32_t startedMs_ = 0, expiryMs_ = 0;
    uint8_t volume_ = 0;
};

inline int16_t applyVolume(int16_t sample, uint8_t volume) {
    const uint8_t bounded = volume > 100 ? 100 : volume;
    return static_cast<int16_t>(static_cast<int32_t>(sample) * bounded / 100);
}

}  // namespace helmetguard::audio
