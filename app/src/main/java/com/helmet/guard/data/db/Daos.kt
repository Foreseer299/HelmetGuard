package com.helmet.guard.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM emergency_contacts ORDER BY priority ASC, id ASC")
    fun observeAll(): Flow<List<EmergencyContactEntity>>

    @Query("SELECT * FROM emergency_contacts WHERE enabled = 1 AND phone != '' ORDER BY priority ASC, id ASC")
    suspend fun active(): List<EmergencyContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EmergencyContactEntity): Long

    @Query("UPDATE emergency_contacts SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM emergency_contacts WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TelemetryDao {
    @Insert suspend fun insertSession(entity: TelemetrySessionEntity): Long
    @Insert suspend fun insertSamples(entities: List<TelemetrySampleEntity>)

    @Query("UPDATE telemetry_sessions SET endedAtMs = :endedAtMs, sampleCount = :sampleCount WHERE id = :id")
    suspend fun finishSession(id: Long, endedAtMs: Long, sampleCount: Int)

    @Query("UPDATE telemetry_sessions SET sampleCount = :sampleCount WHERE id = :id")
    suspend fun updateCount(id: Long, sampleCount: Int)

    @Query("SELECT * FROM telemetry_sessions ORDER BY startedAtMs DESC")
    fun observeSessions(): Flow<List<TelemetrySessionEntity>>

    @Query("SELECT * FROM telemetry_sessions WHERE id = :id")
    suspend fun session(id: Long): TelemetrySessionEntity?

    @Query("SELECT * FROM telemetry_samples WHERE sessionId = :sessionId ORDER BY receivedAtMs ASC")
    suspend fun samples(sessionId: Long): List<TelemetrySampleEntity>

    @Query("UPDATE telemetry_sessions SET endedAtMs = :endedAtMs WHERE endedAtMs IS NULL")
    suspend fun closeAbandonedSessions(endedAtMs: Long)

    @Query("DELETE FROM telemetry_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)
}

@Dao
interface AccidentDao {
    @Query("SELECT MAX(eventId) FROM accidents")
    suspend fun maxEventId(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AccidentEntity)

    @Query("SELECT * FROM accidents ORDER BY detectedAtMs DESC")
    fun observeAll(): Flow<List<AccidentEntity>>

    @Query("SELECT * FROM accidents WHERE stage IN ('COUNTDOWN','LOCATING','SENDING') ORDER BY detectedAtMs DESC LIMIT 1")
    suspend fun pending(): AccidentEntity?

    @Query("SELECT * FROM accidents WHERE hardwareDeviceId = :deviceId AND hardwareEventId = :hardwareEventId LIMIT 1")
    suspend fun byHardwareId(deviceId: Long, hardwareEventId: Long): AccidentEntity?

    @Query("UPDATE accidents SET hardwareDeviceId = :deviceId, hardwareEventId = :hardwareEventId, hardwareAddress = :address WHERE eventId = :eventId")
    suspend fun attachHardware(eventId: Long, deviceId: Long, hardwareEventId: Long, address: String)

    @Query("SELECT * FROM accidents WHERE eventId = :eventId LIMIT 1")
    suspend fun byId(eventId: Long): AccidentEntity?

    @Query("UPDATE accidents SET stage = :stage, detail = :detail, updatedAtMs = :updatedAtMs WHERE eventId = :eventId")
    suspend fun updateStage(eventId: Long, stage: String, detail: String, updatedAtMs: Long = System.currentTimeMillis())

    @Query("UPDATE accidents SET stage = :stage, detail = :detail, latitude = :latitude, longitude = :longitude, locationAccuracyMeters = :accuracy, locationTimeMs = :locationTime, locationProvider = :provider, updatedAtMs = :updatedAtMs WHERE eventId = :eventId")
    suspend fun complete(
        eventId: Long,
        stage: String,
        detail: String,
        latitude: Double?,
        longitude: Double?,
        accuracy: Float?,
        locationTime: Long?,
        provider: String?,
        updatedAtMs: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM accidents") suspend fun clear()
}

@Dao
interface SmsPartDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SmsPartEntity)

    @Query("SELECT * FROM sms_parts WHERE eventId = :eventId ORDER BY contactId, kind, partIndex")
    fun observeForEvent(eventId: Long): Flow<List<SmsPartEntity>>

    @Query("SELECT * FROM sms_parts WHERE eventId = :eventId ORDER BY contactId, kind, partIndex")
    suspend fun forEvent(eventId: Long): List<SmsPartEntity>

    @Query("UPDATE sms_parts SET state = 'TIMED_OUT', detail = :detail, updatedAtMs = :updatedAtMs WHERE eventId = :eventId AND kind = 'SENT' AND state = 'QUEUED'")
    suspend fun markQueuedTimedOut(eventId: Long, detail: String, updatedAtMs: Long = System.currentTimeMillis())
}

@Dao
interface LogDao {
    @Insert suspend fun insert(entity: SystemLogEntity)
    @Query("SELECT * FROM system_logs ORDER BY timestampMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 300): Flow<List<SystemLogEntity>>
    @Query("DELETE FROM system_logs") suspend fun clear()
}
