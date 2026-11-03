package com.helmet.guard.core.audio

/** Android-free eligibility rule so a stale preference can never bypass the UI filter. */
data class OfflineVoiceDescriptor(
    val name: String,
    val language: String,
    val networkConnectionRequired: Boolean,
    val features: Set<String>,
    val engineIsSystemApp: Boolean
)

object OfflineTtsPolicy {
    const val NETWORK_SYNTHESIS_FEATURE = "networkTts"

    fun isEligible(descriptor: OfflineVoiceDescriptor): Boolean =
        descriptor.language.equals("zh", ignoreCase = true) &&
            descriptor.engineIsSystemApp &&
            !descriptor.networkConnectionRequired &&
            descriptor.features.none { feature ->
                feature.equals(NETWORK_SYNTHESIS_FEATURE, ignoreCase = true) ||
                    feature.contains("network", ignoreCase = true)
            }
}
