package com.helmet.guard.ui.screens

/** Preserve an international prefix, tolerate common formatting, reject short/service numbers. */
internal fun normalizedEmergencyPhone(input: String): String? {
    val compact = input.filterNot { it.isWhitespace() || it in "-()（）" }
    return compact.takeIf { it.matches(Regex("\\+?[0-9]{7,15}")) }
}

internal fun contactPhoneKey(phone: String): String {
    val normalized = normalizedEmergencyPhone(phone) ?: return phone
    return when {
        normalized.matches(Regex("\\+861[0-9]{10}")) -> normalized.removePrefix("+86")
        normalized.matches(Regex("00861[0-9]{10}")) -> normalized.removePrefix("0086")
        else -> normalized
    }
}
