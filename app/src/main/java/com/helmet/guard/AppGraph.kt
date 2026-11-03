package com.helmet.guard

import android.content.Context
import com.helmet.guard.core.ble.CompanionPairing
import com.helmet.guard.core.audio.AnnouncementAudioCoordinator
import com.helmet.guard.core.ble.HelmetBleClient
import com.helmet.guard.core.call.CallSessionCoordinator
import com.helmet.guard.core.compatibility.DeviceReliabilityCenter
import com.helmet.guard.core.detector.AdvancedAccidentDetector
import com.helmet.guard.core.diagnostics.DiagnosticExporter
import com.helmet.guard.core.diagnostics.DiagnosticLogger
import com.helmet.guard.core.location.NativeLocationProvider
import com.helmet.guard.core.notification.AnnouncementRepository
import com.helmet.guard.core.notification.AnnouncementSettingsState
import com.helmet.guard.core.sms.SmsPipeline
import com.helmet.guard.data.db.GuardDatabase
import com.helmet.guard.data.prefs.GuardPreferences
import com.helmet.guard.data.recording.TelemetryRecorder
import com.helmet.guard.domain.DetectorSnapshot
import com.helmet.guard.domain.TelemetrySample
import com.helmet.guard.service.IncidentCoordinator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class RuntimeStore {
    private val _telemetry = MutableStateFlow<TelemetrySample?>(null)
    val telemetry: StateFlow<TelemetrySample?> = _telemetry.asStateFlow()
    private val _detector = MutableStateFlow(DetectorSnapshot())
    val detector: StateFlow<DetectorSnapshot> = _detector.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun postMessage(message: String) { _messages.tryEmit(message) }

    fun update(sample: TelemetrySample, detector: DetectorSnapshot) {
        _telemetry.value = sample
        _detector.value = detector
    }

    fun updateDetector(detector: DetectorSnapshot) { _detector.value = detector }
}

class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    val database = GuardDatabase.create(appContext)
    val diagnostics = DiagnosticLogger(database.logs())
    val preferences = GuardPreferences(appContext)
    val announcementSettings = AnnouncementSettingsState(preferences)
    val announcements = AnnouncementRepository()
    val ble = HelmetBleClient(appContext)
    val diagnosticExporter = DiagnosticExporter(appContext, database.logs(), diagnostics) {
        val connection = ble.state.value
        val cap = ble.audioCapabilities.value
        org.json.JSONObject().apply {
            put("phase", connection.phase.name); put("mtu", connection.mtu)
            put("decoderSupported", cap?.decoderSupported); put("outputReady", cap?.outputReady)
            put("codec", cap?.codec); put("sampleRateHz", cap?.sampleRateHz)
            put("mode", announcementSettings.state.value.mode)
            put("phoneTestPlayback", announcementSettings.state.value.phoneTestPlayback)
            put("smsSpeechMode", announcementSettings.state.value.smsSpeechMode.name)
            put("callSpeechMode", announcementSettings.state.value.callSpeechMode.name)
            put("reminderServiceRunning", com.helmet.guard.service.AnnouncementService.running.value)
            put("notificationAccessGranted", com.helmet.guard.core.notification.NotificationListenerRecovery.hasAccess(appContext))
            put("listenerRecoveryResult", com.helmet.guard.core.notification.NotificationListenerRecovery.state.value.result)
            put("listenerRebindRequestedElapsedMs", com.helmet.guard.core.notification.NotificationListenerRecovery.state.value.requestedElapsedMs)
            put("announcementsEnabled", announcementSettings.state.value.enabled)
            put("smsEnabled", announcementSettings.state.value.sms)
            put("callsEnabled", announcementSettings.state.value.calls)
            put("verificationReminderEnabled", announcementSettings.state.value.hideVerificationCodes)
            put("defaultSmsPackage", android.provider.Telephony.Sms.getDefaultSmsPackage(appContext))
            put("defaultDialerPackage", appContext.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage)
            put("notificationListenerConnected", com.helmet.guard.core.notification.HelmetNotificationListener.connected.value)
            put("contactsPermission", androidx.core.content.ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            put("phoneStatePermission", androidx.core.content.ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            put("safetyBlocked", announcements.safetyBlocked)
        }.toString()
    }
    val announcementAudio = AnnouncementAudioCoordinator(appContext, announcements, announcementSettings, ble, diagnostics)
    val companionPairing = CompanionPairing(appContext)
    val detector = AdvancedAccidentDetector()
    val callSessions = CallSessionCoordinator(announcements) { announcementSettings.state.value.profile() }
    val location = NativeLocationProvider(appContext)
    val sms = SmsPipeline(appContext, database)
    val recorder = TelemetryRecorder(appContext, database.telemetry())
    val reliability = DeviceReliabilityCenter(appContext)
    val runtime = RuntimeStore()
    val incidents = IncidentCoordinator(appContext, database, preferences, location, sms)
}
