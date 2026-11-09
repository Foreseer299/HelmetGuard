package com.helmet.guard.core.sms

/** An SMS attempt can authorize a bounded number of newly opened confirmation windows. */
internal class SmsConfirmationGate {
    private class Attempt(val openedAt: Long, var clicksLeft: Int) {
        val windows = mutableSetOf<Int>()
        val handled = mutableSetOf<Int>()
    }

    private val pending = mutableMapOf<String, Attempt>()

    @Synchronized fun begin(id: String, now: Long, clicks: Int) {
        val overlaps = pending.isNotEmpty()
        // An identical dialog cannot be attributed safely when two attempts overlap.
        if (overlaps) pending.values.forEach { it.clicksLeft = 0 }
        pending[id] = Attempt(now, if (overlaps) 0 else clicks)
    }

    @Synchronized fun end(id: String) { pending.remove(id) }

    @Synchronized fun clear() { pending.clear() }

    @Synchronized fun shouldInspect(windowId: Int, eventAt: Long, now: Long, opened: Boolean): Boolean {
        val attempt = pending.values.singleOrNull() ?: return false
        if (windowId < 0 || eventAt < attempt.openedAt || !isOpen(attempt, now)) return false
        if (opened) attempt.windows.add(windowId)
        return windowId in attempt.windows && windowId !in attempt.handled
    }

    @Synchronized fun click(windowId: Int, eventAt: Long, now: Long, action: () -> Boolean): Boolean {
        val attempt = pending.values.singleOrNull() ?: return false
        if (!shouldInspect(windowId, eventAt, now, opened = false)) return false
        attempt.handled.add(windowId)
        attempt.clicksLeft--
        return action()
    }

    private fun isOpen(attempt: Attempt, now: Long): Boolean =
        attempt.clicksLeft > 0 && now - attempt.openedAt in 0 until WINDOW_MS

    private companion object { const val WINDOW_MS = 15_000L }
}

internal fun isSmsConfirmationTitle(text: String, appLabel: String): Boolean {
    if (appLabel.isBlank()) return false
    fun normalized(value: String) = value.filterNot { it.isWhitespace() || it in "“”\"「」" }
    val label = normalized(appLabel)
    return normalized(text) in setOf(
        "${label}将发短信", "${label}将发送短信",
        "${label}wantstosendanSMS", "${label}isabouttosendatextmessage"
    )
}

internal fun isSmsConfirmationCancel(text: String): Boolean =
    Regex("(取消|Cancel)([(（][0-9]+[)）])?").matches(text.filterNot(Char::isWhitespace))
