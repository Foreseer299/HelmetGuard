#include <unity.h>

#include "HelmetProtocol.h"

void test_crc_vector() {
    const uint8_t bytes[] = {0xAA, 0x55, 0x01, 0x03, 0x08, 0x01, 0x02};
    TEST_ASSERT_EQUAL_HEX8(0x0F, helmetguard::crc8(bytes, sizeof(bytes)));
}

void test_encode_decode_round_trip() {
    const uint8_t payload[] = {1, 2, 3, 4, 5, 6};
    uint8_t frame[32] = {};
    const size_t length = helmetguard::encodeFrame(helmetguard::kCommand, 37, payload, sizeof(payload), frame, sizeof(frame));
    TEST_ASSERT_EQUAL_UINT32(13, length);
    helmetguard::FrameView decoded{};
    TEST_ASSERT_TRUE(helmetguard::decodeFrame(frame, length, decoded));
    TEST_ASSERT_EQUAL_HEX8(helmetguard::kCommand, decoded.type);
    TEST_ASSERT_EQUAL_UINT8(37, decoded.sequence);
    TEST_ASSERT_EQUAL_UINT8_ARRAY(payload, decoded.payload, sizeof(payload));
}

void test_corrupt_frame_is_rejected() {
    const uint8_t payload[] = {3, 5};
    uint8_t frame[16] = {};
    const size_t length = helmetguard::encodeFrame(helmetguard::kCommand, 1, payload, sizeof(payload), frame, sizeof(frame));
    frame[6] ^= 0x20;
    helmetguard::FrameView decoded{};
    TEST_ASSERT_FALSE(helmetguard::decodeFrame(frame, length, decoded));
}

void test_little_endian_helpers() {
    uint8_t value[4] = {};
    helmetguard::putU32Le(value, 0x89ABCDEFU);
    TEST_ASSERT_EQUAL_HEX8(0xEF, value[0]);
    TEST_ASSERT_EQUAL_HEX8(0x89, value[3]);
    TEST_ASSERT_EQUAL_HEX32(0x89ABCDEFU, helmetguard::readU32Le(value));
}

void test_published_telemetry_vector() {
    const uint8_t payload[] = {
        0x04, 0x03, 0x02, 0x01, 0x64, 0x00, 0x38, 0xFF,
        0xD4, 0x03, 0x7D, 0x00, 0x1F, 0xFF, 0x32, 0x00,
        0x78, 0x00, 0xDD, 0xFF, 0x84, 0x03, 0x57, 0x07
    };
    const uint8_t expected[] = {
        0xAA, 0x55, 0x01, 0x01, 0x2A, 0x18,
        0x04, 0x03, 0x02, 0x01, 0x64, 0x00, 0x38, 0xFF,
        0xD4, 0x03, 0x7D, 0x00, 0x1F, 0xFF, 0x32, 0x00,
        0x78, 0x00, 0xDD, 0xFF, 0x84, 0x03, 0x57, 0x07, 0x3F
    };
    uint8_t actual[sizeof(expected)] = {};
    TEST_ASSERT_EQUAL_UINT32(
        sizeof(expected),
        helmetguard::encodeFrame(helmetguard::kTelemetry, 0x2A, payload, sizeof(payload), actual, sizeof(actual))
    );
    TEST_ASSERT_EQUAL_UINT8_ARRAY(expected, actual, sizeof(expected));
}

void test_published_incident_vector() {
    const uint8_t frame[] = {
        0xAA, 0x55, 0x01, 0x02, 0x2B, 0x10,
        0x44, 0x33, 0x22, 0x11, 0x88, 0x77, 0x66, 0x55,
        0x82, 0x02, 0xDB, 0x02, 0x19, 0x00, 0x0F, 0x03, 0x7B
    };
    helmetguard::FrameView decoded{};
    TEST_ASSERT_TRUE(helmetguard::decodeFrame(frame, sizeof(frame), decoded));
    TEST_ASSERT_EQUAL_HEX8(helmetguard::kIncident, decoded.type);
    TEST_ASSERT_EQUAL_HEX32(0x11223344U, helmetguard::readU32Le(decoded.payload));
    TEST_ASSERT_EQUAL_HEX32(0x55667788U, helmetguard::readU32Le(decoded.payload + 4));
}

int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_crc_vector);
    RUN_TEST(test_encode_decode_round_trip);
    RUN_TEST(test_corrupt_frame_is_rejected);
    RUN_TEST(test_little_endian_helpers);
    RUN_TEST(test_published_telemetry_vector);
    RUN_TEST(test_published_incident_vector);
    return UNITY_END();
}
