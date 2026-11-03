#include <unity.h>

#include <cstdint>

#include "HelmetAudioProtocol.h"

using namespace helmetguard::audio;

void test_fixed_adpcm_vector_matches_android_decode() {
    const uint8_t blockBytes[] = {
        0x00,0x00,0x53,0x00,0x15,0x00,0x0A,0x00,0x80,0x90,0xA2,0xD3,0xC4,0xC4,0xD4,0xD5,0xE6,0xF6
    };
    const int16_t expected[] = {
        0,2543,231,2333,-3400,5286,-2610,7439,-6918,10282,-10530,
        14653,-15818,21044,-24009,21044,-24009,29236,-24009,29236,-32200
    };
    AdpcmBlockView block{};
    TEST_ASSERT_TRUE(parseAdpcmBlock(blockBytes, sizeof(blockBytes), block));
    int16_t decoded[21] = {};
    TEST_ASSERT_TRUE(decodeAdpcmBlock(block, decoded, 21));
    for (size_t i = 0; i < 21; ++i) TEST_ASSERT_EQUAL_INT16(expected[i], decoded[i]);
}

void test_crc16_ccitt_vector() {
    const uint8_t data[] = {'1','2','3','4','5','6','7','8','9'};
    TEST_ASSERT_EQUAL_HEX16(0x29B1, crc16Ccitt(data, sizeof(data)));
}

void test_android_open_session_control_vector() {
    const uint8_t bytes[] = {0x01,0x01,0x44,0x33,0x22,0x11,0,0,0,0,0,0,0x78,0x22};
    ControlFrameView frame{};
    TEST_ASSERT_TRUE(parseControlFrame(bytes, sizeof(bytes), frame));
    TEST_ASSERT_EQUAL_UINT8(1, frame.opcode);
    TEST_ASSERT_EQUAL_HEX32(0x11223344, frame.sessionId);
    TEST_ASSERT_EQUAL_UINT16(0, frame.payloadLength);
}

void test_startup_authorization_is_fifty_blocks() {
    TEST_ASSERT_EQUAL_UINT16(50, kRequiredStartupBlocks);
    CumulativeAuthorizationFrontier frontier;
    frontier.reset(0);
    frontier.authorizeInitialStartup();
    TEST_ASSERT_EQUAL_UINT16(49, frontier.authorizedThrough());
    for (uint16_t i = 0; i < 50; ++i) TEST_ASSERT_TRUE(frontier.accept(i));
    TEST_ASSERT_FALSE(frontier.accept(50));
    frontier.extendByFreedBlocks(16);
    TEST_ASSERT_EQUAL_UINT16(65, frontier.authorizedThrough());
    for (uint16_t i = 50; i <= 65; ++i) TEST_ASSERT_TRUE(frontier.accept(i));
}

void test_wrong_or_duplicate_sequence_is_rejected() {
    CumulativeAuthorizationFrontier frontier;
    frontier.reset(10);
    frontier.authorizeInitialStartup(4);
    TEST_ASSERT_FALSE(frontier.accept(11));
    TEST_ASSERT_TRUE(frontier.accept(10));
    TEST_ASSERT_FALSE(frontier.accept(10));
    TEST_ASSERT_TRUE(frontier.accept(11));
}

int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_fixed_adpcm_vector_matches_android_decode);
    RUN_TEST(test_crc16_ccitt_vector);
    RUN_TEST(test_android_open_session_control_vector);
    RUN_TEST(test_startup_authorization_is_fifty_blocks);
    RUN_TEST(test_wrong_or_duplicate_sequence_is_rejected);
    return UNITY_END();
}
