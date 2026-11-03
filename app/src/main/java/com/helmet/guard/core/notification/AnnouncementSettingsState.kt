package com.helmet.guard.core.notification

import com.helmet.guard.data.prefs.GuardPreferences
import com.helmet.guard.domain.AnnouncementProfile
import com.helmet.guard.domain.AnnouncementSource
import com.helmet.guard.domain.ContentDetail
import com.helmet.guard.domain.RiderEventCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class AnnouncementSettingsSnapshot(
    val enabled: Boolean = false,
    val mode: String = "PERSONAL",
    val riderPackages: Set<String> = emptySet(),
    val detail: ContentDetail = ContentDetail.STANDARD,
    val voiceName: String = "",
    val speechRate: Float = 1f,
    val pitch: Float = 1f,
    val volume: Int = 60,
    val calls: Boolean = true,
    val sms: Boolean = true,
    val riderApps: Boolean = true,
    val riderIncome: Boolean = true,
    val hideVerificationCodes: Boolean = true,
    val mutedCategories: Set<RiderEventCategory> = emptySet()
) {
    fun allows(source: AnnouncementSource): Boolean = when (source) {
        AnnouncementSource.CALL -> calls
        AnnouncementSource.SMS -> sms
        AnnouncementSource.RIDER_APP -> mode == "RIDER" && riderApps
        AnnouncementSource.UNKNOWN -> false
    }

    fun profile(): AnnouncementProfile {
        val max = when (detail) {
            ContentDetail.HINT_ONLY -> 0
            ContentDetail.BRIEF -> 24
            ContentDetail.STANDARD -> 48
            ContentDetail.DETAILED, ContentDetail.CUSTOM -> 90
        }
        return AnnouncementProfile(
            enabled = enabled,
            detail = detail,
            maxCharacters = max,
            announceVerificationCodePresence = hideVerificationCodes,
            announceRiderIncome = riderIncome,
            enabledCategories = RiderEventCategory.entries.toSet() - mutedCategories
        )
    }
}

class AnnouncementSettingsState(preferences: GuardPreferences) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(AnnouncementSettingsSnapshot())
    val state: StateFlow<AnnouncementSettingsSnapshot> = _state.asStateFlow()

    init {
        scope.launch { preferences.announcementMode.collectLatest { update { copy(mode = it) } } }
        scope.launch { preferences.riderPackages.collectLatest { update { copy(riderPackages = it) } } }
        scope.launch { preferences.announcementsEnabled.collectLatest { update { copy(enabled = it) } } }
        scope.launch { preferences.announcementDetail.collectLatest { raw -> update { copy(detail = runCatching { ContentDetail.valueOf(raw) }.getOrDefault(ContentDetail.STANDARD)) } } }
        scope.launch { preferences.ttsVoiceName.collectLatest { update { copy(voiceName = it) } } }
        scope.launch { preferences.ttsSpeechRate.collectLatest { update { copy(speechRate = it) } } }
        scope.launch { preferences.ttsPitch.collectLatest { update { copy(pitch = it) } } }
        scope.launch { preferences.helmetVolume.collectLatest { update { copy(volume = it) } } }
        scope.launch { preferences.announceCalls.collectLatest { update { copy(calls = it) } } }
        scope.launch { preferences.announceSms.collectLatest { update { copy(sms = it) } } }
        scope.launch { preferences.announceRiderApps.collectLatest { update { copy(riderApps = it) } } }
        scope.launch { preferences.announceRiderIncome.collectLatest { update { copy(riderIncome = it) } } }
        scope.launch { preferences.hideVerificationCodes.collectLatest { update { copy(hideVerificationCodes = it) } } }
        scope.launch {
            preferences.mutedAnnouncementEvents.collectLatest { names ->
                val categories = names.mapNotNull { runCatching { RiderEventCategory.valueOf(it) }.getOrNull() }.toSet()
                update { copy(mutedCategories = categories) }
            }
        }
    }

    @Synchronized
    private fun update(block: AnnouncementSettingsSnapshot.() -> AnnouncementSettingsSnapshot) {
        _state.value = _state.value.block()
    }
}
