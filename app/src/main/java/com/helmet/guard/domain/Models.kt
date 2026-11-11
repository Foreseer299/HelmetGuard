package com.helmet.guard.domain

import kotlin.math.sqrt

data class TelemetrySample(
    val deviceTimestampMs: Long,
    val receivedElapsedMs: Long,
    val receivedAtMs: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
    val roll: Float,
    val pitch: Float,
    val yaw: Float,
    val batteryPercent: Int,
    val imuHealthy: Boolean,
    val calibrated: Boolean,
    val batteryValid: Boolean = true,
    val sequence: Int = 0
) {
    val accelerationG: Float get() = sqrt(ax * ax + ay * ay + az * az)
    val angularSpeedDps: Float get() = sqrt(gx * gx + gy * gy + gz * gz)

    companion object {
        val STATIC = TelemetrySample(
            0, 0, 0, 0f, 0f, 1f, 0f, 0f, 0f,
            0f, 0f, 0f, 100, imuHealthy = true, calibrated = true
        )
    }
}

enum class ConnectionPhase {
    UNBOUND, BLUETOOTH_OFF, PERMISSION_REQUIRED, DISCONNECTED, SCANNING,
    CONNECTING, NEGOTIATING, DISCOVERING, SUBSCRIBING, READY, STALE, ERROR
}

data class HelmetDevice(
    val name: String,
    val address: String,
    val rssi: Int? = null,
    val batteryPercent: Int? = null,
    val lastSeenMs: Long = 0L,
    val isCompatible: Boolean = false,
    val isConnectable: Boolean = true
)

data class ConnectionState(
    val phase: ConnectionPhase = ConnectionPhase.UNBOUND,
    val device: HelmetDevice? = null,
    val mtu: Int? = null,
    val subscribedCharacteristics: Int = 0,
    val lastPacketElapsedMs: Long = 0L,
    val message: String = "未绑定头盔"
) {
    val isReady: Boolean get() = phase == ConnectionPhase.READY
}

enum class DetectorStage { IDLE, CANDIDATE, OBSERVING, VERIFYING_STILLNESS, COOLDOWN }
enum class IncidentSource { PHONE_ALGORITHM, HELMET_HARDWARE, MANUAL }
enum class RejectionReason { SPEED_BUMP_LIKELY, HELMET_DROP_LIKELY, INSUFFICIENT_ROTATION, RIDER_MOVING, STALE_DATA, LOW_SCORE }

data class DetectionFeatures(
    val peakAccelerationG: Float = 1f,
    val peakAngularSpeedDps: Float = 0f,
    val orientationChangeDeg: Float = 0f,
    val freeFallDurationMs: Long = 0,
    val preImpactMotionScore: Float = 0f,
    val postImpactAccVariance: Float = 0f,
    val postImpactGyroRms: Float = 0f,
    val sampleCount: Int = 0
)

data class DetectorSnapshot(
    val stage: DetectorStage = DetectorStage.IDLE,
    val confidence: Float = 0f,
    val label: String = "正常监测",
    val features: DetectionFeatures = DetectionFeatures(),
    val lastRejection: RejectionReason? = null
)

data class IncidentCandidate(
    val eventId: Long,
    val detectedAtMs: Long,
    val source: IncidentSource,
    val confidence: Float,
    val features: DetectionFeatures,
    val isSimulation: Boolean = false,
    val hardware: HardwareIncidentIdentity? = null
)

data class HardwareIncidentIdentity(val deviceId: Long, val eventId: Long, val address: String)

data class DetectorResult(
    val snapshot: DetectorSnapshot,
    val incident: IncidentCandidate? = null
)

data class DetectorConfig(
    val impactThresholdG: Float = 3.2f,
    val hardImpactThresholdG: Float = 5.8f,
    val rotationThresholdDps: Float = 220f,
    val orientationChangeThresholdDeg: Float = 38f,
    val freeFallThresholdG: Float = 0.35f,
    val minimumFreeFallMs: Long = 80,
    val observationWindowMs: Long = 1_200,
    val stillnessWindowMs: Long = 3_500,
    val stillAccVarianceMax: Float = 0.075f,
    val stillGyroRmsMax: Float = 18f,
    val triggerScore: Float = 0.66f,
    val helmetDropPreMotionMax: Float = 0.10f,
    val speedBumpRotationMaxDps: Float = 115f,
    val speedBumpOrientationMaxDeg: Float = 16f,
    val maximumSampleGapMs: Long = 500
)

enum class RecordingLabel(val displayName: String) {
    NORMAL_RIDE("正常骑行"), SPEED_BUMP("减速带"), EMERGENCY_BRAKE("急刹车"),
    HELMET_DROP("头盔掉落"), CRASH_TEST("碰撞实验"), OTHER("其他")
}

data class RecordingChannels(
    val acceleration: Boolean = true,
    val gyroscope: Boolean = true,
    val attitude: Boolean = true,
    val deviceState: Boolean = true,
    val detectorFeatures: Boolean = true
) {
    val hasAny: Boolean get() = acceleration || gyroscope || attitude || deviceState || detectorFeatures
}

data class RecordingSession(
    val id: Long,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val label: RecordingLabel,
    val notes: String,
    val channels: RecordingChannels,
    val sampleCount: Int,
    val deviceName: String,
    val isSimulation: Boolean
)

data class RecordingState(
    val activeSessionId: Long? = null,
    val startedAtMs: Long? = null,
    val sampleCount: Int = 0,
    val channels: RecordingChannels = RecordingChannels(),
    val label: RecordingLabel = RecordingLabel.NORMAL_RIDE
) {
    val isRecording: Boolean get() = activeSessionId != null
}

data class EmergencyContact(
    val id: Long = 0,
    val name: String,
    val phone: String,
    val relationship: String = "家属",
    val priority: Int = 0,
    val enabled: Boolean = true
)

enum class EmergencyStage { SAFE, COUNTDOWN, LOCATING, SENDING, PARTIAL_SUCCESS, SENT, FAILED, CANCELLED, SIMULATED }

data class EmergencyUiState(
    val stage: EmergencyStage = EmergencyStage.SAFE,
    val eventId: Long? = null,
    val secondsLeft: Int = 0,
    val confidence: Float = 0f,
    val detail: String = "监测正常",
    val canCancel: Boolean = false,
    val isSimulation: Boolean = false
)

enum class SmsPartState { QUEUED, SUBMITTED, DELIVERED, FAILED, TIMED_OUT }

data class SmsPartResult(
    val eventId: Long,
    val contactId: Long,
    val partIndex: Int,
    val state: SmsPartState,
    val resultCode: Int,
    val detail: String
)

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timeMs: Long,
    val provider: String,
    val historical: Boolean
) {
    val ageMs: Long get() = (System.currentTimeMillis() - timeMs).coerceAtLeast(0)
}

data class ReliabilityCheck(
    val key: String,
    val title: String,
    val detail: String,
    val passed: Boolean,
    val required: Boolean = true
)

data class ManufacturerGuide(
    val brand: String,
    val systemName: String,
    val steps: List<String>,
    val caution: String
)
