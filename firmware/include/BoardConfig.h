#pragma once

// ESP32-S3-DevKitC-1 reference wiring. Change these values for your PCB.
#define HG_PIN_I2C_SDA 8
#define HG_PIN_I2C_SCL 9
#define HG_PIN_BUTTON 4
#define HG_PIN_BUZZER 5
#define HG_PIN_BATTERY_ADC 1

// MAX98357A reference wiring (ESP32-S3 -> amplifier): BCLK, LRC/WS, DIN.
// SD/MODE may be tied high for mono output. Verify these pins against the final PCB before assembly.
#define HG_PIN_I2S_BCLK 12
#define HG_PIN_I2S_LRC 13
#define HG_PIN_I2S_DOUT 14
#ifndef HG_ENABLE_I2S_AUDIO
#define HG_ENABLE_I2S_AUDIO 1
#endif

// Most 3.3 V active buzzers turn on at HIGH. Set to 0 for active-low hardware.
#define HG_BUZZER_ACTIVE_HIGH 1

// A Li-ion cell MUST NOT be connected directly to an ESP32 ADC.
// Set to 1 only after adding the documented 100k/100k divider and 100 nF filter.
#define HG_HAS_BATTERY_DIVIDER 0
#define HG_BATTERY_DIVIDER_RATIO 2.0f
#define HG_BATTERY_EMPTY_V 3.30f
#define HG_BATTERY_FULL_V 4.15f

#define HG_SAMPLE_RATE_HZ 50
#define HG_TELEMETRY_RATE_HZ 25
#define HG_HARDWARE_COUNTDOWN_SECONDS 15

// Hardware-side detector is a fail-safe, not a replacement for App-side scoring.
#define HG_IMPACT_CANDIDATE_G 4.5f
#define HG_HARD_IMPACT_G 7.5f
#define HG_ROTATION_EVIDENCE_DPS 250.0f
#define HG_POST_STILL_GYRO_DPS 22.0f
#define HG_POST_STILL_ACC_DELTA_G 0.12f
#define HG_POST_STILL_REQUIRED_MS 1800UL
#define HG_OBSERVATION_MS 1200UL

#define HG_DEVICE_NAME_PREFIX "HelmetGuard-S3"
