package com.helmet.guard.core.notification

import com.helmet.guard.domain.Announcement
import com.helmet.guard.domain.AnnouncementDecision
import com.helmet.guard.domain.AnnouncementEvaluation
import com.helmet.guard.domain.AnnouncementProfile
import com.helmet.guard.domain.NotificationEvent
import java.util.PriorityQueue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local privacy-preserving announcement bus. It never persists notification body text.
 * Queue ordering is by priority then creation time; exact duplicates are suppressed for 60 seconds.
 */
class AnnouncementRepository {
    private val _announcements = MutableSharedFlow<Announcement>(extraBufferCapacity = 32)
    val announcements: SharedFlow<Announcement> = _announcements.asSharedFlow()
    private val _lastEvaluation = MutableStateFlow<AnnouncementEvaluation?>(null)
    val lastEvaluation: StateFlow<AnnouncementEvaluation?> = _lastEvaluation.asStateFlow()
    private val scheduler = AnnouncementScheduler()
    private val _cancellations = MutableSharedFlow<String?>(extraBufferCapacity = 32)
    val cancellations = _cancellations.asSharedFlow()
    @Volatile var epoch: Long = 0
        private set
    private val revoked = linkedSetOf<String>()
    @Synchronized fun permits(traceId: String, startedEpoch: Long): Boolean = !safetyBlocked && epoch == startedEpoch && traceId !in revoked
    @Volatile var safetyBlocked: Boolean = false
        private set

    @Synchronized
    fun setSafetyBlocked(blocked: Boolean) {
        if (safetyBlocked == blocked) return
        safetyBlocked = blocked
        if (blocked) { epoch++; scheduler.clear(); _cancellations.tryEmit(null) }
    }

    @Synchronized
    fun revoke(traceId: String) {
        revoked.add(traceId); if (revoked.size > 1024) revoked.remove(revoked.first())
        scheduler.revoke(traceId); _cancellations.tryEmit(traceId) }

    @Synchronized
    fun submit(event: NotificationEvent, profile: AnnouncementProfile = AnnouncementProfile()): AnnouncementEvaluation {
        if (safetyBlocked) return AnnouncementEvaluation(AnnouncementDecision.FILTERED, "INCIDENT_ACTIVE")
        val evaluated = AnnouncementPolicy.evaluate(event, profile)
        val announcement = evaluated.announcement
        val result = if (announcement != null) scheduler.enqueue(announcement, event.receivedElapsedMs) else evaluated
        _lastEvaluation.value = result
        result.announcement?.let { _announcements.tryEmit(it) }
        return result
    }

    @Synchronized
    fun poll(nowElapsedMs: Long): Announcement? = scheduler.poll(nowElapsedMs)

    @Synchronized
    fun clear() {
        epoch++
        scheduler.clear()
        _cancellations.tryEmit(null)
    }
}

class AnnouncementScheduler(
    private val duplicateWindowMs: Long = 60_000,
    private val maximumQueueSize: Int = 3
) {
    private val queue = PriorityQueue<Announcement>(
        compareByDescending<Announcement> { it.priority.rank }.thenBy { it.createdElapsedMs }
    )
    private val fingerprints = linkedMapOf<String, Long>()

    @Synchronized
    fun enqueue(item: Announcement, nowElapsedMs: Long): AnnouncementEvaluation {
        prune(nowElapsedMs)
        val previous = fingerprints[item.fingerprint]
        if (previous != null && nowElapsedMs - previous <= duplicateWindowMs) {
            return AnnouncementEvaluation(AnnouncementDecision.DUPLICATE, "EXACT_DUPLICATE")
        }
        val sameBusiness = item.businessKey?.let { key ->
            queue.firstOrNull { queued ->
                queued.businessKey == key && queued.source == item.source && queued.sourcePackage == item.sourcePackage
            }
        }
        if (sameBusiness != null) {
            if (item.createdElapsedMs <= sameBusiness.createdElapsedMs) {
                return AnnouncementEvaluation(AnnouncementDecision.DUPLICATE, "STALE_BUSINESS_UPDATE")
            }
            queue.remove(sameBusiness)
        }
        fingerprints[item.fingerprint] = nowElapsedMs
        queue += item
        var retained = true
        if (queue.size > maximumQueueSize) {
            val removable = queue.minWithOrNull(compareBy<Announcement> { it.priority.rank }.thenBy { it.createdElapsedMs })
            if (removable != null) {
                queue.remove(removable)
                retained = removable !== item
            }
        }
        if (!retained) return AnnouncementEvaluation(AnnouncementDecision.FILTERED, "QUEUE_FULL_LOWER_PRIORITY")
        val reason = if (sameBusiness == null) "QUEUED" else "REPLACED_SAME_BUSINESS_KEY"
        return AnnouncementEvaluation(AnnouncementDecision.SPEAK, reason, item)
    }

    @Synchronized
    fun poll(nowElapsedMs: Long): Announcement? {
        prune(nowElapsedMs)
        return queue.poll()
    }

    @Synchronized
    fun revoke(traceId: String) { queue.removeIf { it.traceId == traceId } }

    @Synchronized
    fun clear() {
        queue.clear()
        fingerprints.clear()
    }

    private fun prune(nowElapsedMs: Long) {
        queue.removeIf { nowElapsedMs >= it.expiresAtElapsedMs }
        val iterator = fingerprints.entries.iterator()
        while (iterator.hasNext()) {
            if (nowElapsedMs - iterator.next().value > duplicateWindowMs) iterator.remove()
        }
    }
}
