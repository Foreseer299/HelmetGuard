package com.helmet.guard.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.helmet.guard.appGraph
import com.helmet.guard.core.ble.HelmetButton
import com.helmet.guard.core.detector.AdvancedAccidentDetector
import com.helmet.guard.domain.HardwareIncidentIdentity
import com.helmet.guard.domain.EmergencyStage
import com.helmet.guard.core.detector.MockScenario
import com.helmet.guard.core.detector.MockTelemetryEngine
import com.helmet.guard.domain.ConnectionPhase
import com.helmet.guard.domain.DetectionFeatures
import com.helmet.guard.domain.IncidentCandidate
import com.helmet.guard.domain.IncidentSource
import com.helmet.guard.domain.TelemetrySample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HelmetGuardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val graph by lazy { appGraph }
    private var simulationJob: Job? = null
    private val simulationDetector = AdvancedAccidentDetector()

    override fun onCreate() {
        super.onCreate()
        enterForeground(includeLocation = false)
        scope.launch {
            graph.ble.telemetry.collect {
                simulationJob?.cancel()
                processSample(it, false)
            }
        }
        scope.launch {
            graph.ble.hardwareIncidents.collect { event ->
                val now = System.currentTimeMillis()
                simulationJob?.cancel()
                val address = graph.ble.state.value.device?.address ?: return@collect
                graph.incidents.trigger(
                    IncidentCandidate(
                        now, now, IncidentSource.HELMET_HARDWARE, 0.94f,
                        DetectionFeatures(
                            peakAccelerationG = event.peakAccelerationG,
                            peakAngularSpeedDps = event.peakAngularSpeedDps,
                            postImpactGyroRms = 0f
                        ),
                        hardware = HardwareIncidentIdentity(event.deviceId, event.eventId, address)
                    ),
                    event.requestedCountdownSeconds.takeIf { it in 5..30 }
                )

            }
        }
        scope.launch {
            graph.ble.buttonEvents.collect { button ->
                when (button) {
                    HelmetButton.LONG -> graph.incidents.cancel()
                    HelmetButton.DOUBLE -> graph.incidents.sendNow()
                    else -> Unit
                }
            }
        }
        scope.launch {
            combine(graph.ble.state, graph.recorder.state) { connection, recording -> connection to recording.isRecording }
                .collect { (connection, recording) ->
                    val manager = getSystemService(android.app.NotificationManager::class.java)
                    runCatching {
                        manager?.notify(GuardNotifications.MONITORING_ID, GuardNotifications.monitoring(this@HelmetGuardService, connection, recording))
                    }
                }
        }
        scope.launch {
            graph.ble.state
                .map { state -> state.device?.takeIf { state.phase == ConnectionPhase.READY }?.let { it.address to it.name } }
                .distinctUntilChanged()
                .collect { readyDevice ->
                    readyDevice?.let { (address, name) -> graph.preferences.bindDevice(address, name) }
                }
        }
        scope.launch {
            graph.incidents.state.map { it.stage }.distinctUntilChanged().collect { stage ->
                if (stage !in setOf(EmergencyStage.COUNTDOWN, EmergencyStage.LOCATING, EmergencyStage.SENDING)) {
                    graph.detector.reset()
                    simulationDetector.reset()
                }
            }
        }
        scope.launch {
            while (isActive) {
                graph.preferences.heartbeat()
                delay(60_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> scope.launch {
                graph.preferences.setMonitoringEnabled(true)
                graph.preferences.boundAddress.first()?.let { graph.ble.reconnect(it) }
            }
            ACTION_STOP -> scope.launch {
                graph.preferences.setMonitoringEnabled(false)
                simulationJob?.cancel()
                graph.recorder.stop()
                graph.ble.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_SAFE -> graph.incidents.cancel(intent?.getLongExtra(EXTRA_EVENT_ID, -1))
            ACTION_SEND_NOW -> graph.incidents.sendNow(intent?.getLongExtra(EXTRA_EVENT_ID, -1))
            ACTION_MANUAL_SOS -> graph.incidents.triggerManual(intent?.getBooleanExtra(EXTRA_SIMULATION, false) ?: false)
            ACTION_LOCATION_MODE -> enterForeground(includeLocation = true)
            ACTION_RUN_SIMULATION -> runSimulation(intent?.getStringExtra(EXTRA_SCENARIO))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        simulationJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun processSample(sample: TelemetrySample, simulation: Boolean) {
        val engine = if (simulation) simulationDetector else graph.detector
        val result = engine.process(sample, simulation)
        graph.runtime.update(sample, result.snapshot)
        graph.recorder.record(sample, result.snapshot, simulation)
        result.incident?.let {
            engine.markCountdownActive()
            graph.incidents.trigger(it.copy(isSimulation = simulation))
        }
    }

    private fun runSimulation(rawScenario: String?) {
        if ((graph.recorder.state.value.isRecording && !graph.recorder.isSimulationRecording) || graph.ble.state.value.phase in setOf(ConnectionPhase.READY, ConnectionPhase.STALE) ||
            graph.incidents.state.value.stage in setOf(EmergencyStage.COUNTDOWN, EmergencyStage.LOCATING, EmergencyStage.SENDING)) {
            graph.runtime.postMessage("请先停止记录、断开头盔并完成当前事故处理，再运行模拟演练")
            return
        }
        val scenario = runCatching { MockScenario.valueOf(rawScenario.orEmpty()) }.getOrDefault(MockScenario.NORMAL_RIDE)
        simulationJob?.cancel()
        simulationJob = scope.launch {
            simulationDetector.reset()
            MockTelemetryEngine().play(scenario).collect { processSample(it, true) }
        }
    }

    private fun enterForeground(includeLocation: Boolean) {
        val notification = GuardNotifications.monitoring(this, graph.ble.state.value, graph.recorder.state.value.isRecording)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (includeLocation && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            runCatching { startForeground(GuardNotifications.MONITORING_ID, notification, type) }
                .onFailure { startForeground(GuardNotifications.MONITORING_ID, notification) }
        } else startForeground(GuardNotifications.MONITORING_ID, notification)
    }

    companion object {
        const val ACTION_START = "com.helmet.guard.action.START"
        const val ACTION_STOP = "com.helmet.guard.action.STOP"
        const val ACTION_SAFE = "com.helmet.guard.action.SAFE"
        const val ACTION_SEND_NOW = "com.helmet.guard.action.SEND_NOW"
        const val ACTION_MANUAL_SOS = "com.helmet.guard.action.MANUAL_SOS"
        const val ACTION_LOCATION_MODE = "com.helmet.guard.action.LOCATION_MODE"
        const val ACTION_RUN_SIMULATION = "com.helmet.guard.action.RUN_SIMULATION"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_SIMULATION = "simulation"
        const val EXTRA_SCENARIO = "scenario"
    }
}
