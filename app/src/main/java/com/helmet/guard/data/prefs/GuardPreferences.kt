package com.helmet.guard.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.guardDataStore by preferencesDataStore("guard_preferences_v2")

class GuardPreferences(private val context: Context) {
    private object Keys {
        val monitoring = booleanPreferencesKey("monitoring_enabled")
        val boundAddress = stringPreferencesKey("bound_address")
        val boundName = stringPreferencesKey("bound_name")
        val countdown = intPreferencesKey("countdown_seconds")
        val simulation = booleanPreferencesKey("simulation_enabled")
        val smsSubscription = intPreferencesKey("sms_subscription_id")
        val riderName = stringPreferencesKey("rider_name")
        val riderPhone = stringPreferencesKey("rider_phone")
        val oemSetupConfirmed = booleanPreferencesKey("oem_setup_confirmed")
        val onboardingComplete = booleanPreferencesKey("onboarding_complete")
        val lastHeartbeat = longPreferencesKey("last_service_heartbeat")
        val announcementsEnabled = booleanPreferencesKey("announcements_enabled")
        val phoneTestPlayback = booleanPreferencesKey("phone_test_playback")
        val smsSpeechMode = stringPreferencesKey("sms_speech_mode")
        val callSpeechMode = stringPreferencesKey("call_speech_mode")
        val announcementMode = stringPreferencesKey("announcement_mode")
        val riderPackages = stringSetPreferencesKey("announcement_rider_packages")
        val announcementDetail = stringPreferencesKey("announcement_detail")
        val ttsVoiceName = stringPreferencesKey("tts_voice_name")
        val ttsSpeechRate = floatPreferencesKey("tts_speech_rate")
        val ttsPitch = floatPreferencesKey("tts_pitch")
        val helmetVolume = intPreferencesKey("helmet_volume")
        val announceCalls = booleanPreferencesKey("announce_calls")
        val announceSms = booleanPreferencesKey("announce_sms")
        val announceRiderApps = booleanPreferencesKey("announce_rider_apps")
        val announceRiderIncome = booleanPreferencesKey("announce_rider_income")
        val hideVerificationCodes = booleanPreferencesKey("hide_verification_codes")
        val mutedAnnouncementEvents = stringSetPreferencesKey("muted_announcement_events")
    }

    val monitoringEnabled: Flow<Boolean> = context.guardDataStore.data.map { it[Keys.monitoring] ?: false }
    val boundAddress: Flow<String?> = context.guardDataStore.data.map { it[Keys.boundAddress] }
    val boundName: Flow<String?> = context.guardDataStore.data.map { it[Keys.boundName] }
    val countdownSeconds: Flow<Int> = context.guardDataStore.data.map { (it[Keys.countdown] ?: 15).coerceIn(5, 30) }
    val simulationEnabled: Flow<Boolean> = context.guardDataStore.data.map { it[Keys.simulation] ?: false }
    val smsSubscriptionId: Flow<Int> = context.guardDataStore.data.map { it[Keys.smsSubscription] ?: -1 }
    val riderName: Flow<String> = context.guardDataStore.data.map { it[Keys.riderName] ?: "骑手" }
    val riderPhone: Flow<String> = context.guardDataStore.data.map { it[Keys.riderPhone] ?: "" }
    val oemSetupConfirmed: Flow<Boolean> = context.guardDataStore.data.map { it[Keys.oemSetupConfirmed] ?: false }
    val onboardingComplete: Flow<Boolean> = context.guardDataStore.data.map { it[Keys.onboardingComplete] ?: false }
    val lastServiceHeartbeat: Flow<Long> = context.guardDataStore.data.map { it[Keys.lastHeartbeat] ?: 0L }
    val announcementMode: Flow<String> = context.guardDataStore.data.map { it[Keys.announcementMode] ?: "PERSONAL" }
    val riderPackages: Flow<Set<String>> = context.guardDataStore.data.map { it[Keys.riderPackages] ?: emptySet() }
    val phoneTestPlayback: Flow<Boolean> = context.guardDataStore.data.map { it[Keys.phoneTestPlayback] ?: false }
    val smsSpeechMode: Flow<String> = context.guardDataStore.data.map { it[Keys.smsSpeechMode] ?: "SOURCE" }
    val callSpeechMode: Flow<String> = context.guardDataStore.data.map { it[Keys.callSpeechMode] ?: "SOURCE" }
    val announcementsEnabled: Flow<Boolean> = context.guardDataStore.data.map { it[Keys.announcementsEnabled] ?: false }
    val announcementDetail: Flow<String> = context.guardDataStore.data.map { it[Keys.announcementDetail] ?: "STANDARD" }
    val ttsVoiceName: Flow<String> = context.guardDataStore.data.map { it[Keys.ttsVoiceName] ?: "" }
    val ttsSpeechRate: Flow<Float> = context.guardDataStore.data.map { (it[Keys.ttsSpeechRate] ?: 1.0f).coerceIn(0.6f, 1.5f) }
    val ttsPitch: Flow<Float> = context.guardDataStore.data.map { (it[Keys.ttsPitch] ?: 1.0f).coerceIn(0.6f, 1.5f) }
    val helmetVolume: Flow<Int> = context.guardDataStore.data.map { (it[Keys.helmetVolume] ?: 60).coerceIn(0, 100) }
    val announceCalls: Flow<Boolean> = context.guardDataStore.data.map { it[Keys.announceCalls] ?: true }
    val announceSms: Flow<Boolean> = context.guardDataStore.data.map { it[Keys.announceSms] ?: true }
    val announceRiderApps: Flow<Boolean> = context.guardDataStore.data.map { it[Keys.announceRiderApps] ?: true }
    val announceRiderIncome: Flow<Boolean> = context.guardDataStore.data.map { it[Keys.announceRiderIncome] ?: true }
    val hideVerificationCodes: Flow<Boolean> = context.guardDataStore.data.map { it[Keys.hideVerificationCodes] ?: true }
    val mutedAnnouncementEvents: Flow<Set<String>> = context.guardDataStore.data.map { it[Keys.mutedAnnouncementEvents] ?: emptySet() }

    suspend fun setMonitoringEnabled(value: Boolean) = context.guardDataStore.edit { it[Keys.monitoring] = value }
    suspend fun setCountdownSeconds(value: Int) = context.guardDataStore.edit { it[Keys.countdown] = value.coerceIn(5, 30) }
    suspend fun setSimulationEnabled(value: Boolean) = context.guardDataStore.edit { it[Keys.simulation] = value }
    suspend fun setSmsSubscriptionId(value: Int) = context.guardDataStore.edit { it[Keys.smsSubscription] = value }
    suspend fun setRider(name: String, phone: String) = context.guardDataStore.edit {
        it[Keys.riderName] = name.trim().ifBlank { "骑手" }
        it[Keys.riderPhone] = phone.trim()
    }
    suspend fun setOemSetupConfirmed(value: Boolean) = context.guardDataStore.edit { it[Keys.oemSetupConfirmed] = value }
    suspend fun setOnboardingComplete(value: Boolean) = context.guardDataStore.edit { it[Keys.onboardingComplete] = value }
    suspend fun setAnnouncementMode(value: String) = context.guardDataStore.edit { it[Keys.announcementMode] = if (value == "RIDER") "RIDER" else "PERSONAL" }
    suspend fun setRiderPackageEnabled(packageName: String, enabled: Boolean) = context.guardDataStore.edit { prefs ->
        val packages = (prefs[Keys.riderPackages] ?: emptySet()).toMutableSet()
        if (enabled) packages.add(packageName) else packages.remove(packageName)
        prefs[Keys.riderPackages] = packages
    }
    suspend fun setPhoneTestPlayback(value: Boolean) = context.guardDataStore.edit { it[Keys.phoneTestPlayback] = value }
    suspend fun setSmsSpeechMode(value: String) = context.guardDataStore.edit { it[Keys.smsSpeechMode] = value }
    suspend fun setCallSpeechMode(value: String) = context.guardDataStore.edit { it[Keys.callSpeechMode] = value }
    suspend fun setAnnouncementsEnabled(value: Boolean) = context.guardDataStore.edit { it[Keys.announcementsEnabled] = value }
    suspend fun setAnnouncementDetail(value: String) = context.guardDataStore.edit { it[Keys.announcementDetail] = value }
    suspend fun setTtsVoiceName(value: String) = context.guardDataStore.edit { it[Keys.ttsVoiceName] = value }
    suspend fun setTtsSpeechRate(value: Float) = context.guardDataStore.edit { it[Keys.ttsSpeechRate] = value.coerceIn(.6f, 1.5f) }
    suspend fun setTtsPitch(value: Float) = context.guardDataStore.edit { it[Keys.ttsPitch] = value.coerceIn(.6f, 1.5f) }
    suspend fun setHelmetVolume(value: Int) = context.guardDataStore.edit { it[Keys.helmetVolume] = value.coerceIn(0, 100) }
    suspend fun setAnnouncementSources(calls: Boolean, sms: Boolean, riderApps: Boolean) = context.guardDataStore.edit {
        it[Keys.announceCalls] = calls; it[Keys.announceSms] = sms; it[Keys.announceRiderApps] = riderApps
    }
    suspend fun setAnnounceRiderIncome(value: Boolean) = context.guardDataStore.edit { it[Keys.announceRiderIncome] = value }
    suspend fun setHideVerificationCodes(value: Boolean) = context.guardDataStore.edit { it[Keys.hideVerificationCodes] = value }
    suspend fun setAnnouncementEventEnabled(category: String, enabled: Boolean) = context.guardDataStore.edit { prefs ->
        val muted = (prefs[Keys.mutedAnnouncementEvents] ?: emptySet()).toMutableSet()
        if (enabled) muted.remove(category) else muted.add(category)
        prefs[Keys.mutedAnnouncementEvents] = muted
    }
    suspend fun heartbeat(now: Long = System.currentTimeMillis()) = context.guardDataStore.edit { it[Keys.lastHeartbeat] = now }

    suspend fun bindDevice(address: String, name: String) = context.guardDataStore.edit {
        it[Keys.boundAddress] = address
        it[Keys.boundName] = name
    }

    suspend fun clearDevice() = context.guardDataStore.edit {
        it.remove(Keys.boundAddress)
        it.remove(Keys.boundName)
    }
}
