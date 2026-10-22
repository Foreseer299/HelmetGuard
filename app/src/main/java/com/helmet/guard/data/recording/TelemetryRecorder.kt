package com.helmet.guard.data.recording

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.helmet.guard.data.db.TelemetryDao
import com.helmet.guard.data.db.TelemetrySampleEntity
import com.helmet.guard.data.db.TelemetrySessionEntity
import com.helmet.guard.domain.DetectorSnapshot
import com.helmet.guard.domain.RecordingChannels
import com.helmet.guard.domain.RecordingLabel
import com.helmet.guard.domain.RecordingSession
import com.helmet.guard.domain.RecordingState
import com.helmet.guard.domain.TelemetrySample
import kotlinx.coroutines.CoroutineExceptionHandler
import com.helmet.guard.appGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class TelemetryRecorder(
    private val context: Context,
    private val dao: TelemetryDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ ->
        context.appGraph.runtime.postMessage("参数记录未保存，请检查存储空间")
    })
    private val mutex = Mutex()
    @Volatile private var recordingSimulation = false
    val isSimulationRecording: Boolean get() = _state.value.isRecording && recordingSimulation
    private val pending = mutableListOf<TelemetrySampleEntity>()
    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()
    private val recoveryJob = scope.launch { dao.closeAbandonedSessions(System.currentTimeMillis()) }

    val sessions: StateFlow<List<RecordingSession>> = dao.observeSessions().map { rows ->
        rows.map { it.toDomain() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            while (isActive) {
                delay(1_000)
                flush()
            }
        }
    }

    suspend fun start(
        label: RecordingLabel,
        notes: String,
        channels: RecordingChannels,
        deviceName: String,
        isSimulation: Boolean
    ): Long {
        recoveryJob.join()
        require(channels.hasAny) { "至少选择一项记录参数" }
        return mutex.withLock {
            stopLocked()
            val now = System.currentTimeMillis()
            val id = dao.insertSession(
                TelemetrySessionEntity(
                    startedAtMs = now,
                    endedAtMs = null,
                    label = label.name,
                    notes = notes.trim(),
                    recordAcceleration = channels.acceleration,
                    recordGyroscope = channels.gyroscope,
                    recordAttitude = channels.attitude,
                    recordDeviceState = channels.deviceState,
                    recordDetectorFeatures = channels.detectorFeatures,
                    sampleCount = 0,
                    deviceName = deviceName,
                    isSimulation = isSimulation
                )
            )
            recordingSimulation = isSimulation
            _state.value = RecordingState(id, now, 0, channels, label)
            id
        }
    }

    suspend fun stop() = mutex.withLock { stopLocked() }

    private suspend fun stopLocked() {
        val current = _state.value
        val id = current.activeSessionId ?: return
        flushLocked()
        dao.finishSession(id, System.currentTimeMillis(), _state.value.sampleCount)
        _state.value = RecordingState(channels = current.channels, label = current.label)
    }

    suspend fun record(sample: TelemetrySample, detector: DetectorSnapshot, isSimulation: Boolean = false) {
        if (recordingSimulation != isSimulation) return
        val current = _state.value
        val sessionId = current.activeSessionId ?: return
        val c = current.channels
        val row = TelemetrySampleEntity(
            sessionId = sessionId,
            receivedAtMs = sample.receivedAtMs,
            deviceTimestampMs = sample.deviceTimestampMs,
            sequence = sample.sequence,
            ax = sample.ax.takeIf { c.acceleration },
            ay = sample.ay.takeIf { c.acceleration },
            az = sample.az.takeIf { c.acceleration },
            accelerationG = sample.accelerationG.takeIf { c.acceleration },
            gx = sample.gx.takeIf { c.gyroscope },
            gy = sample.gy.takeIf { c.gyroscope },
            gz = sample.gz.takeIf { c.gyroscope },
            angularSpeedDps = sample.angularSpeedDps.takeIf { c.gyroscope },
            roll = sample.roll.takeIf { c.attitude },
            pitch = sample.pitch.takeIf { c.attitude },
            yaw = sample.yaw.takeIf { c.attitude },
            batteryPercent = sample.batteryPercent.takeIf { c.deviceState && sample.batteryValid },
            batteryValid = sample.batteryValid.takeIf { c.deviceState },
            imuHealthy = sample.imuHealthy.takeIf { c.deviceState },
            calibrated = sample.calibrated.takeIf { c.deviceState },
            detectorStage = detector.stage.name.takeIf { c.detectorFeatures },
            detectorConfidence = detector.confidence.takeIf { c.detectorFeatures },
            impactPeakG = detector.features.peakAccelerationG.takeIf { c.detectorFeatures },
            orientationChangeDeg = detector.features.orientationChangeDeg.takeIf { c.detectorFeatures },
            rejection = detector.lastRejection?.name.takeIf { c.detectorFeatures }
        )
        mutex.withLock {
            val latest = _state.value
            if (latest.activeSessionId != sessionId || recordingSimulation != isSimulation) return@withLock
            pending += row
            _state.value = latest.copy(sampleCount = latest.sampleCount + 1)
            if (pending.size >= 100) flushLocked()
        }
    }

    suspend fun delete(sessionId: Long) {
        if (_state.value.activeSessionId == sessionId) stop()
        dao.deleteSession(sessionId)
    }

    suspend fun loadSamples(sessionId: Long): List<TelemetrySampleEntity> = dao.samples(sessionId)

    suspend fun createCsvShareIntent(sessionId: Long): Intent? = withContext(Dispatchers.IO) {
        flush()
        val session = dao.session(sessionId) ?: return@withContext null
        val rows = dao.samples(sessionId)
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(exportDir, "helmetguard-${session.id}-${session.label}.csv")
        file.bufferedWriter().use { writer ->
            writer.appendLine("receivedAtMs,deviceTimestampMs,sequence,ax,ay,az,totalAccG,gx,gy,gz,totalGyroDps,roll,pitch,yaw,battery,batteryValid,imuHealthy,calibrated,detectorStage,confidence,impactPeakG,orientationChangeDeg,rejection")
            rows.forEach { r ->
                writer.appendLine(listOf(
                    r.receivedAtMs, r.deviceTimestampMs, r.sequence,
                    r.ax, r.ay, r.az, r.accelerationG,
                    r.gx, r.gy, r.gz, r.angularSpeedDps,
                    r.roll, r.pitch, r.yaw, r.batteryPercent, r.batteryValid,
                    r.imuHealthy, r.calibrated, r.detectorStage, r.detectorConfidence,
                    r.impactPeakG, r.orientationChangeDeg, r.rejection
                ).joinToString(",") { csv(it) })
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "HelmetGuard 实验参数 ${session.label}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private suspend fun flush() = mutex.withLock { flushLocked() }

    private suspend fun flushLocked() {
        if (pending.isEmpty()) return
        val batch = pending.toList()
        dao.insertSamples(batch)
        pending.clear()
        _state.value.activeSessionId?.let { dao.updateCount(it, _state.value.sampleCount) }
    }

    private fun csv(value: Any?): String {
        if (value == null) return ""
        val text = value.toString()
        return if (text.any { it == ',' || it == '"' || it == '\n' }) "\"${text.replace("\"", "\"\"")}\"" else text
    }

    private fun TelemetrySessionEntity.toDomain() = RecordingSession(
        id, startedAtMs, endedAtMs,
        runCatching { RecordingLabel.valueOf(label) }.getOrDefault(RecordingLabel.OTHER),
        notes,
        RecordingChannels(recordAcceleration, recordGyroscope, recordAttitude, recordDeviceState, recordDetectorFeatures),
        sampleCount, deviceName, isSimulation
    )
}
