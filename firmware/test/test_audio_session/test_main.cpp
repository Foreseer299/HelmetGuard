#include <unity.h>
#include <array>
#include <cstdint>
#include "HelmetAudioSession.h"

using namespace helmetguard::audio;

namespace {
StartAudioView makeStart(const uint8_t* bytes, size_t length, uint32_t crc, uint32_t blocks = 1) {
    StartAudioView out{};
    out.sampleRateHz = kSampleRateHz;
    out.blockSamples = kBlockSamples;
    out.channels = 1;
    out.codec = 1;
    out.totalSamples = static_cast<uint32_t>(blocks) * kBlockSamples;
    out.totalBlocks = static_cast<uint16_t>(blocks);
    out.totalEncodedBytes = static_cast<uint32_t>(length);
    out.wholeAudioCrc32 = crc;
    out.startBufferMs = kDefaultStartBufferMs;
    out.expiryAfterAcceptMs = 15000;
    out.priority = 20;
    out.volume = 60;
    return out;
}
uint32_t crc32(const uint8_t* bytes, size_t length) { return crc32Update(0xFFFFFFFFU, bytes, length) ^ 0xFFFFFFFFU; }
}

void test_android_start_audio_fixed_vector_parses() {
    const uint8_t payload[] = {
        0x01,0x01,0x80,0x3E,0x00,0x01,0x00,0x00,0x01,0x00,0x00,0x01,0x12,
        0x00,0x00,0x00,0x20,0x03,0x98,0x3A,0x14,0x3C,0x42,0x73,0x1D,0x38
    };
    StartAudioView start{};
    TEST_ASSERT_TRUE(parseStartAudioPayload(payload, sizeof(payload), start));
    TEST_ASSERT_EQUAL_UINT16(16000, start.sampleRateHz);
    TEST_ASSERT_EQUAL_UINT32(256, start.totalSamples);
    TEST_ASSERT_EQUAL_UINT16(1, start.totalBlocks);
    TEST_ASSERT_EQUAL_UINT32(18, start.totalEncodedBytes);
    TEST_ASSERT_EQUAL_HEX32(0x381D7342U, start.wholeAudioCrc32);
}

void test_requires_open_start_and_matching_ids() {
    const uint8_t bytes[] = {1, 2, 3};
    AudioSessionState state;
    TEST_ASSERT_TRUE(state.open(0x11223344U, true));
    auto start = makeStart(bytes, sizeof(bytes), crc32(bytes, sizeof(bytes)));
    TEST_ASSERT_TRUE(state.start(0x11223344U, 7U, start));
    TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(BlockAcceptResult::SESSION_MISMATCH),
        static_cast<uint8_t>(state.acceptBlock(8U, 7U, 0, bytes, sizeof(bytes), true)));
    TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(BlockAcceptResult::AUDIO_MISMATCH),
        static_cast<uint8_t>(state.acceptBlock(0x11223344U, 8U, 0, bytes, sizeof(bytes), true)));
}

void test_final_crc_and_playback_completion_are_distinct() {
    const uint8_t bytes[] = {1, 2, 3, 4};
    AudioSessionState state;
    TEST_ASSERT_TRUE(state.open(1U, true));
    auto start = makeStart(bytes, sizeof(bytes), crc32(bytes, sizeof(bytes)));
    TEST_ASSERT_TRUE(state.start(1U, 2U, start));
    TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(BlockAcceptResult::INPUT_COMPLETE),
        static_cast<uint8_t>(state.acceptBlock(1U, 2U, 0, bytes, sizeof(bytes), true)));
    TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(AudioPhase::RECEIVING), static_cast<uint8_t>(state.phase()));
    state.blockConsumed();
    TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(AudioPhase::COMPLETE), static_cast<uint8_t>(state.phase()));
}

void test_gap_does_not_advance_or_duplicate_authorization() {
    const uint8_t bytes[] = {9, 8, 7, 6};
    AudioSessionState state;
    TEST_ASSERT_TRUE(state.open(1U, true));
    auto start = makeStart(bytes, sizeof(bytes) * 2U, crc32(bytes, sizeof(bytes)), 2);
    start.totalEncodedBytes = sizeof(bytes) * 2U;
    TEST_ASSERT_TRUE(state.start(1U, 2U, start));
    TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(BlockAcceptResult::SEQUENCE_GAP),
        static_cast<uint8_t>(state.acceptBlock(1U, 2U, 1, bytes, sizeof(bytes), false)));
    TEST_ASSERT_EQUAL_UINT16(0, state.expectedSequence());
    const uint16_t before = state.authorizedThrough();
    TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(BlockAcceptResult::ACCEPTED),
        static_cast<uint8_t>(state.acceptBlock(1U, 2U, 0, bytes, sizeof(bytes), false)));
    TEST_ASSERT_EQUAL_UINT16(before, state.authorizedThrough());
    state.blockConsumed();
    TEST_ASSERT_EQUAL_UINT16(static_cast<uint16_t>(before + 1U), state.authorizedThrough());
}

void test_bad_whole_crc_is_only_final_integrity_failure() {
    const uint8_t bytes[] = {1, 1, 2, 3, 5, 8};
    AudioSessionState state;
    TEST_ASSERT_TRUE(state.open(1U, true));
    auto start = makeStart(bytes, sizeof(bytes), 0xDEADBEEFU);
    TEST_ASSERT_TRUE(state.start(1U, 2U, start));
    TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(BlockAcceptResult::FINAL_CRC_MISMATCH),
        static_cast<uint8_t>(state.acceptBlock(1U, 2U, 0, bytes, sizeof(bytes), true)));
    TEST_ASSERT_EQUAL_UINT8(static_cast<uint8_t>(AudioPhase::ERROR), static_cast<uint8_t>(state.phase()));
}

void test_volume_zero_unity_and_clamp() {
    TEST_ASSERT_EQUAL_INT16(0, applyVolume(-32768, 0));
    TEST_ASSERT_EQUAL_INT16(-32768, applyVolume(-32768, 100));
    TEST_ASSERT_EQUAL_INT16(32767, applyVolume(32767, 255));
    TEST_ASSERT_EQUAL_INT16(500, applyVolume(1000, 50));
}
void test_expiry_uses_monotonic_wrap_safe_clock() {
    const uint8_t bytes[] = {1,2,3}; AudioSessionState state;
    state.open(1, true); auto start = makeStart(bytes, 3, crc32(bytes, 3));
    start.expiryAfterAcceptMs = 1000;
    TEST_ASSERT_TRUE(state.start(1, 2, start, 0xFFFFFF00U));
    TEST_ASSERT_FALSE(state.expired(0x00000100U));
    TEST_ASSERT_TRUE(state.expired(0x00000300U));
}

int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_volume_zero_unity_and_clamp);
    RUN_TEST(test_expiry_uses_monotonic_wrap_safe_clock);
    RUN_TEST(test_android_start_audio_fixed_vector_parses);
    RUN_TEST(test_requires_open_start_and_matching_ids);
    RUN_TEST(test_final_crc_and_playback_completion_are_distinct);
    RUN_TEST(test_gap_does_not_advance_or_duplicate_authorization);
    RUN_TEST(test_bad_whole_crc_is_only_final_integrity_failure);
    return UNITY_END();
}
