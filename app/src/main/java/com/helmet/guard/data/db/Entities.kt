package com.helmet.guard.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "emergency_contacts")
data class EmergencyContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String,
    val relationship: String,
    val priority: Int,
    val enabled: Boolean
)

@Entity(tableName = "telemetry_sessions")
data class TelemetrySessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val label: String,
    val notes: String,
    val recordAcceleration: Boolean,
    val recordGyroscope: Boolean,
    val recordAttitude: Boolean,
    val recordDeviceState: Boolean,
    val recordDetectorFeatures: Boolean,
    val sampleCount: Int,
    val deviceName: String,
    val isSimulation: Boolean
)

@Entity(
    tableName = "telemetry_samples",
    foreignKeys = [ForeignKey(
        entity = TelemetrySessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId"), Index(value = ["sessionId", "receivedAtMs"])]
)
data class TelemetrySampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val receivedAtMs: Long,
    val deviceTimestampMs: Long,
    val sequence: Int,
    val ax: Float?, val ay: Float?, val az: Float?, val accelerationG: Float?,
    val gx: Float?, val gy: Float?, val gz: Float?, val angularSpeedDps: Float?,
    val roll: Float?, val pitch: Float?, val yaw: Float?,
    val batteryPercent: Int?, val batteryValid: Boolean?, val imuHealthy: Boolean?, val calibrated: Boolean?,
    val detectorStage: String?, val detectorConfidence: Float?,
    val impactPeakG: Float?, val orientationChangeDeg: Float?, val rejection: String?
)

@Entity(tableName = "accidents", indices = [Index("detectedAtMs"), Index(value = ["hardwareDeviceId", "hardwareEventId"], unique = true)])
data class AccidentEntity(
    @PrimaryKey val eventId: Long,
    val detectedAtMs: Long,
    val updatedAtMs: Long,
    val source: String,
    val stage: String,
    val confidence: Float,
    val peakAccelerationG: Float,
    val peakAngularSpeedDps: Float,
    val orientationChangeDeg: Float,
    val freeFallDurationMs: Long,
    val countdownDeadlineMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val locationAccuracyMeters: Float?,
    val locationTimeMs: Long?,
    val locationProvider: String?,
    val detail: String,
    val isSimulation: Boolean,
    val hardwareDeviceId: Long? = null,
    val hardwareEventId: Long? = null,
    val hardwareAddress: String? = null
)

@Entity(
    tableName = "sms_parts",
    primaryKeys = ["eventId", "contactId", "partIndex", "kind"],
    indices = [Index("eventId")]
)
data class SmsPartEntity(
    val eventId: Long,
    val contactId: Long,
    val contactName: String,
    val phoneMasked: String,
    val partIndex: Int,
    val partCount: Int,
    val kind: String,
    val state: String,
    val resultCode: Int,
    val detail: String,
    val updatedAtMs: Long
)

@Entity(tableName = "system_logs", indices = [Index("timestampMs")])
data class SystemLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val level: String,
    val category: String,
    val message: String
)
