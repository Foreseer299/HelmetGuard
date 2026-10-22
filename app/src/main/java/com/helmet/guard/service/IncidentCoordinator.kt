package com.helmet.guard.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.helmet.guard.appGraph
import com.helmet.guard.core.location.LocationOutcome
import com.helmet.guard.core.location.NativeLocationProvider
import com.helmet.guard.core.sms.SmsPipeline
import com.helmet.guard.data.db.AccidentEntity
import com.helmet.guard.data.db.GuardDatabase
import com.helmet.guard.data.prefs.GuardPreferences
import com.helmet.guard.domain.DetectionFeatures
import com.helmet.guard.domain.EmergencyContact
import com.helmet.guard.domain.EmergencyStage
import com.helmet.guard.domain.EmergencyUiState
import com.helmet.guard.domain.HardwareIncidentIdentity
import com.helmet.guard.domain.IncidentCandidate
import com.helmet.guard.domain.IncidentSource
import com.helmet.guard.domain.LocationFix
import com.helmet.guard.domain.SmsPartState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class IncidentCoordinator(
    private val context: Context,
    private val database: GuardDatabase,
    private val preferences: GuardPreferences,
    private val locationProvider: NativeLocationProvider,
    private val smsPipeline: SmsPipeline,
    private val onHardwareAck: (HardwareIncidentIdentity) -> Unit = { hardware ->
        val ble = context.appGraph.ble
        if (ble.state.value.device?.address == hardware.address) ble.acknowledge(hardware.eventId)
    },
    private val onHardwareCancel: (HardwareIncidentIdentity) -> Boolean = { hardware ->
        val ble = context.appGraph.ble
        ble.state.value.device?.address == hardware.address && ble.cancelAlarm(hardware.eventId)
    }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ ->
        context.appGraph.runtime.postMessage("事故处理未完成，请检查应用状态并手动求助；头盔未确认的事件会继续重发")
    })
    private val mutex = Mutex()
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val _state = MutableStateFlow(EmergencyUiState())
    val state: StateFlow<EmergencyUiState> = _state.asStateFlow()
    private var activeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var sendImmediately = false

    fun trigger(incoming: IncidentCandidate, countdownSeconds: Int? = null) {
        scope.launch {
            var attemptedEventId: Long? = null
            try {
            mutex.withLock {
                val candidate = incoming.copy(eventId = maxOf(System.currentTimeMillis(), (database.accidents().maxEventId() ?: 0L) + 1))
                val hardware = candidate.hardware
                if (hardware != null) {
                    val existing = database.accidents().byHardwareId(hardware.deviceId, hardware.eventId)
                    if (existing != null) {
                        acknowledgeHardware(hardware)
                        if (existing.stage == EmergencyStage.CANCELLED.name) cancelHardware(hardware)
                        return@withLock
                    }
                }
                val current = _state.value
                if (current.stage in ACTIVE_STAGES) {
                    if (current.isSimulation && !candidate.isSimulation) {
                        activeJob?.cancelAndJoin()
                        current.eventId?.let { database.accidents().updateStage(it, EmergencyStage.CANCELLED.name, "真实事件到达，演练已停止") }
                        releaseWakeLock()
                    } else {
                        // A hardware confirmation may belong to an already active phone detection.
                        // Persist its original wire identity before stopping firmware retransmission.
                        if (hardware != null && !current.isSimulation && current.eventId != null) {
                            val active = database.accidents().byId(current.eventId)
                            if (active != null && active.hardwareEventId == null) {
                                database.accidents().attachHardware(active.eventId, hardware.deviceId, hardware.eventId, hardware.address)
                                acknowledgeHardware(hardware)
                            }
                        }
                        return@withLock
                    }
                }
                attemptedEventId = candidate.eventId
                val countdown = (countdownSeconds ?: preferences.countdownSeconds.first()).coerceIn(5, 30)
                val deadline = System.currentTimeMillis() + countdown * 1_000L
                val initialStage = EmergencyStage.COUNTDOWN
                database.accidents().upsert(candidate.toEntity(initialStage, deadline))
                hardware?.let { acknowledgeHardware(it) }
                _state.value = EmergencyUiState(
                    stage = initialStage,
                    eventId = candidate.eventId,
                    secondsLeft = countdown,
                    confidence = candidate.confidence,
                    detail = if (candidate.isSimulation) "模拟演练：不会发送真实短信" else "请确认是否安全；倒计时结束后将自动求救",
                    canCancel = true,
                    isSimulation = candidate.isSimulation
                )
                sendImmediately = false
                notifyState()
                acquireWakeLock()
                activeJob = launchProcessing(candidate.eventId) { runCountdown(candidate, deadline) }
            }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val detail = "事故流程未能启动，请手动联系紧急联系人；检测器已恢复"
                attemptedEventId?.let { id ->
                    runCatching { database.accidents().updateStage(id, EmergencyStage.FAILED.name, detail) }
                    if (_state.value.eventId == id) {
                        _state.value = _state.value.copy(stage = EmergencyStage.FAILED, detail = detail, canCancel = false)
                        notifyState()
                    }
                }
                context.appGraph.detector.reset()
                context.appGraph.runtime.updateDetector(context.appGraph.detector.currentSnapshot())
                context.appGraph.runtime.postMessage(detail)
                releaseWakeLock()
            }
        }
    }

    fun triggerManual(isSimulation: Boolean = false) {
        val now = System.currentTimeMillis()
        trigger(
            IncidentCandidate(now, now, IncidentSource.MANUAL, 1f, DetectionFeatures(peakAccelerationG = 0f), isSimulation),
            countdownSeconds = 5
        )
    }

    fun cancel(eventId: Long? = null) {
        scope.launch {
            mutex.withLock {
                val current = _state.value
                if (current.stage != EmergencyStage.COUNTDOWN || (eventId != null && eventId >= 0 && current.eventId != eventId)) return@withLock
                activeJob?.cancelAndJoin()
                current.eventId?.let {
                    val record = database.accidents().byId(it)
                    database.accidents().updateStage(it, EmergencyStage.CANCELLED.name, "用户确认安全并取消")
                    record?.hardwareIdentity()?.let { hardware -> cancelHardware(hardware) }
                }
                _state.value = current.copy(stage = EmergencyStage.CANCELLED, secondsLeft = 0, detail = "已确认安全，本次不会发送求救短信", canCancel = false)
                notifyState()
                releaseWakeLock()
            }
        }
    }

    fun sendNow(eventId: Long? = null) {
        scope.launch {
            mutex.withLock {
                val current = _state.value
                if (current.stage != EmergencyStage.COUNTDOWN || (eventId != null && eventId >= 0 && current.eventId != eventId)) return@withLock
                sendImmediately = true
            }
        }
    }

    fun restorePending() {
        scope.launch {
            mutex.withLock {
                if (_state.value.stage in ACTIVE_STAGES) return@withLock
                val pending = database.accidents().pending() ?: return@withLock
                val candidate = pending.toCandidate()
                val restoredRemaining = (pending.countdownDeadlineMs - System.currentTimeMillis()).coerceIn(0L, 30_000L)
                val restoredStage = runCatching { EmergencyStage.valueOf(pending.stage) }.getOrDefault(EmergencyStage.FAILED)
                _state.value = EmergencyUiState(
                    restoredStage, pending.eventId,
                    (restoredRemaining / 1_000L).toInt(),
                    pending.confidence, "恢复上次未完成的事故处理", pending.stage == EmergencyStage.COUNTDOWN.name,
                    pending.isSimulation
                )
                sendImmediately = false
                notifyState()
                acquireWakeLock()
                activeJob = launchProcessing(candidate.eventId) {
                    if (pending.stage == EmergencyStage.COUNTDOWN.name) {
                        runCountdown(candidate, System.currentTimeMillis() + restoredRemaining)
                    } else if (pending.stage == EmergencyStage.SENDING.name && database.smsParts().forEvent(pending.eventId).any { it.kind == "SENT" }) {
                        // The platform may already have sent these parts before process death.
                        // Resume receipts instead of issuing a second sendMultipartTextMessage.
                        awaitSmsResults(pending.eventId, pending.locationFix())
                    } else dispatch(candidate)
                }
            }
        }
    }

    private fun launchProcessing(eventId: Long, block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val detail = "事故处理发生异常：${error.message ?: error.javaClass.simpleName}。请立即手动联系紧急联系人。"
            runCatching { database.accidents().updateStage(eventId, EmergencyStage.FAILED.name, detail) }
            _state.value = _state.value.copy(
                stage = EmergencyStage.FAILED,
                eventId = eventId,
                secondsLeft = 0,
                detail = detail,
                canCancel = false
            )
            notifyState()
            context.appGraph.runtime.postMessage(detail)
            releaseWakeLock()
        }
    }

    private suspend fun runCountdown(candidate: IncidentCandidate, wallDeadlineMs: Long) {
        val remainingAtStart = (wallDeadlineMs - System.currentTimeMillis()).coerceAtLeast(0)
        val elapsedDeadline = android.os.SystemClock.elapsedRealtime() + remainingAtStart
        while (true) {
            val leftMs = elapsedDeadline - android.os.SystemClock.elapsedRealtime()
            if (leftMs <= 0 || sendImmediately) break
            val seconds = ((leftMs + 999) / 1_000).toInt()
            _state.value = _state.value.copy(secondsLeft = seconds)
            notifyState()
            delay(250)
        }
        if (candidate.isSimulation) {
            database.accidents().updateStage(candidate.eventId, EmergencyStage.SIMULATED.name, "模拟演练完成，未发送短信")
            _state.value = _state.value.copy(stage = EmergencyStage.SIMULATED, secondsLeft = 0, detail = "演练完成：定位与短信均未真实执行", canCancel = false)
            notifyState()
            releaseWakeLock()
            return
        }
        mutex.withLock {
            _state.value = _state.value.copy(stage = EmergencyStage.LOCATING, secondsLeft = 0, canCancel = false)
        }
        dispatch(candidate)
    }

    private suspend fun dispatch(candidate: IncidentCandidate) {
        // Also protects restoration of a simulation interrupted after countdown.
        if (candidate.isSimulation) {
            finish(candidate.eventId, EmergencyStage.SIMULATED, "演练完成：定位与短信均未真实执行", null)
            return
        }
        val eventId = candidate.eventId
        database.accidents().updateStage(eventId, EmergencyStage.LOCATING.name, "正在获取事故位置")
        _state.value = _state.value.copy(stage = EmergencyStage.LOCATING, secondsLeft = 0, detail = "正在通过系统定位服务获取位置", canCancel = false)
        notifyState()
        promoteServiceForLocation()
        delay(150)
        val locationOutcome = locationProvider.locate(10_000)
        val fix: LocationFix? = (locationOutcome as? LocationOutcome.Success)?.fix
        val locationDetail = when (locationOutcome) {
            is LocationOutcome.Success -> if (locationOutcome.fix.historical) "使用最近一次有效位置" else "已获取事故位置"
            LocationOutcome.PermissionMissing -> "无定位权限"
            LocationOutcome.ProviderDisabled -> "系统定位已关闭"
            is LocationOutcome.Unavailable -> locationOutcome.reason
        }

        database.accidents().complete(
            eventId, EmergencyStage.SENDING.name, locationDetail,
            fix?.latitude, fix?.longitude, fix?.accuracyMeters, fix?.timeMs, fix?.provider
        )
        _state.value = _state.value.copy(stage = EmergencyStage.SENDING, detail = "$locationDetail，正在发送求救短信")
        notifyState()

        val contacts = database.contacts().active().map {
            EmergencyContact(it.id, it.name, it.phone, it.relationship, it.priority, it.enabled)
        }
        val report = smsPipeline.sendEmergency(
            eventId = eventId,
            contacts = contacts,
            requestedSubscriptionId = preferences.smsSubscriptionId.first(),
            riderName = preferences.riderName.first(),
            riderPhone = preferences.riderPhone.first(),
            location = fix,
            confidence = candidate.confidence,
            detectedAtMs = candidate.detectedAtMs,
            isSimulation = false
        )
        if (!report.launched) {
            val detail = report.errors.joinToString("；").ifBlank { "短信发送未启动" }
            finish(eventId, EmergencyStage.FAILED, "$detail。请立即手动拨打紧急电话。", fix)
            return
        }

        _state.value = _state.value.copy(detail = "已排队 ${report.queuedContacts} 位联系人、${report.queuedParts} 个短信分段，等待运营商回执")
        notifyState()
        awaitSmsResults(eventId, fix)
    }

    private suspend fun awaitSmsResults(eventId: Long, fix: LocationFix?) {
        withTimeoutOrNull(20_000) {
            database.smsParts().observeForEvent(eventId).first { rows ->
                val submitted = rows.filter { it.kind == "SENT" }
                submitted.isNotEmpty() && submitted.none { it.state == SmsPartState.QUEUED.name }
            }
        }
        database.smsParts().markQueuedTimedOut(eventId, "20 秒内未收到系统回执")
        val results = database.smsParts().forEvent(eventId).filter { it.kind == "SENT" }
        val accepted = results.count { it.state == SmsPartState.SUBMITTED.name }
        val total = results.size
        val finalStage = when {
            total == 0 || accepted == 0 -> EmergencyStage.FAILED
            accepted == total -> EmergencyStage.SENT
            else -> EmergencyStage.PARTIAL_SUCCESS
        }
        val detail = when (finalStage) {
            EmergencyStage.SENT -> "全部 $total 个短信分段已由运营商接受；送达状态将在历史记录中继续更新"
            EmergencyStage.PARTIAL_SUCCESS -> "$accepted/$total 个短信分段已提交，部分发送失败；请尝试电话求助"
            else -> "未收到任何短信提交成功回执，请立即手动拨打紧急电话"
        }
        finish(eventId, finalStage, detail, fix)
    }

    private suspend fun finish(eventId: Long, stage: EmergencyStage, detail: String, fix: LocationFix?) {
        database.accidents().complete(eventId, stage.name, detail, fix?.latitude, fix?.longitude, fix?.accuracyMeters, fix?.timeMs, fix?.provider)
        _state.value = _state.value.copy(stage = stage, detail = detail, canCancel = false)
        notifyState()
        releaseWakeLock()
    }

    private fun notifyState() {
        runCatching {
            notifications?.notify(GuardNotifications.EMERGENCY_ID, GuardNotifications.emergency(context, _state.value))
        }.onFailure {
            context.appGraph.runtime.postMessage("无法显示事故通知，请检查通知权限")
        }
    }

    private fun promoteServiceForLocation() {
        val intent = Intent(context, HelmetGuardService::class.java).setAction(HelmetGuardService.ACTION_LOCATION_MODE)
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        releaseWakeLock()
        val manager = context.getSystemService(PowerManager::class.java) ?: return
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HelmetGuard:incident").apply {
            setReferenceCounted(false)
            acquire(70_000)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    private fun IncidentCandidate.toEntity(stage: EmergencyStage, deadline: Long) = AccidentEntity(
        eventId, detectedAtMs, System.currentTimeMillis(), source.name, stage.name, confidence,
        features.peakAccelerationG, features.peakAngularSpeedDps, features.orientationChangeDeg,
        features.freeFallDurationMs, deadline,
        null, null, null, null, null,
        if (isSimulation) "模拟事故" else "等待用户确认", isSimulation,
        hardware?.deviceId, hardware?.eventId, hardware?.address
    )

    private fun AccidentEntity.toCandidate() = IncidentCandidate(
        eventId, detectedAtMs, runCatching { IncidentSource.valueOf(source) }.getOrDefault(IncidentSource.PHONE_ALGORITHM),
        confidence,
        DetectionFeatures(peakAccelerationG, peakAngularSpeedDps, orientationChangeDeg, freeFallDurationMs),
        isSimulation, hardwareIdentity()
    )

    private fun AccidentEntity.locationFix(): LocationFix? {
        return LocationFix(latitude ?: return null, longitude ?: return null, locationAccuracyMeters ?: 9999f,
            locationTimeMs ?: detectedAtMs, locationProvider ?: "unknown", historical = true)
    }

    private fun AccidentEntity.hardwareIdentity(): HardwareIncidentIdentity? {
        return HardwareIncidentIdentity(hardwareDeviceId ?: return null, hardwareEventId ?: return null, hardwareAddress ?: return null)
    }

    private fun acknowledgeHardware(hardware: HardwareIncidentIdentity) {
        runCatching { onHardwareAck(hardware) }
    }

    private fun cancelHardware(hardware: HardwareIncidentIdentity) {
        val queued = runCatching { onHardwareCancel(hardware) }.getOrDefault(false)
        if (!queued) context.appGraph.runtime.postMessage("手机已取消；头盔未连接，请长按头盔按键取消本地报警")
    }

    companion object {
        private val ACTIVE_STAGES = setOf(EmergencyStage.COUNTDOWN, EmergencyStage.LOCATING, EmergencyStage.SENDING)
    }
}
