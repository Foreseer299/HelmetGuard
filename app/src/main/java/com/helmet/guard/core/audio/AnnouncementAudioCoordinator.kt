package com.helmet.guard.core.audio

import android.content.Context
import android.os.SystemClock
import com.helmet.guard.core.ble.HelmetBleClient
import com.helmet.guard.core.diagnostics.DiagnosticLogger
import com.helmet.guard.core.notification.AnnouncementRepository
import com.helmet.guard.core.notification.AnnouncementSettingsState
import com.helmet.guard.domain.Announcement
import com.helmet.guard.domain.ConnectionPhase
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serializes notification announcements into the optional BLE audio service.
 * No automatic phone replay occurs after bytes are queued because playback is then unknown.
 */
class AnnouncementAudioCoordinator(
    context: Context,
    private val repository: AnnouncementRepository,
    private val settings: AnnouncementSettingsState,
    private val ble: HelmetBleClient,
    private val diagnostics: DiagnosticLogger
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tts: LocalTtsSynthesizer? = null
    private val random = SecureRandom()
    private var job: Job? = null
    private var activeJob: Job? = null
    private var activeAnnouncement: Announcement? = null

    fun start() {
        if (job != null) return
        if (tts == null) tts = LocalTtsSynthesizer(appContext)
        job = scope.launch {
            coroutineScope {
                val wakeup = Channel<Unit>(Channel.CONFLATED)
                launch(start = CoroutineStart.UNDISPATCHED) {
                    repository.cancellations.collect { trace ->
                        if (trace == null || activeAnnouncement?.traceId == trace) {
                            activeJob?.cancel()
                            ble.abortAudioForIncident()
                        }
                    }
                }
                launch(start = CoroutineStart.UNDISPATCHED) {
                    repository.announcements.collect { incoming ->
                        if (incoming.priority.rank > (activeAnnouncement?.priority?.rank ?: Int.MAX_VALUE)) {
                            activeJob?.cancel()
                            ble.abortAudioForIncident()
                        }
                        wakeup.trySend(Unit)
                    }
                }
                wakeup.trySend(Unit)
                for (signal in wakeup) {
                    while (isActive && !repository.safetyBlocked) {
                        val next = repository.poll(SystemClock.elapsedRealtime()) ?: break
                        activeAnnouncement = next
                        activeJob = launch {
                            try { process(next) }
                            catch (cancelled: CancellationException) {
                                log(next, "CANCELLED", "PREEMPTED_OR_REVOKED")
                                throw cancelled
                            } catch (error: Exception) {
                                log(next, "ERROR", error.javaClass.simpleName)
                            }
                        }
                        activeJob?.join()
                        activeJob = null
                        activeAnnouncement = null
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        tts?.shutdown()
        tts = null
    }

    private suspend fun process(announcement: Announcement) {
        val epoch = repository.epoch
        val config = settings.state.value
        if (!repository.permits(announcement.traceId, epoch) || SystemClock.elapsedRealtime() >= announcement.expiresAtElapsedMs) return log(announcement, "NOT_PLAYED_CONFIRMED", "EXPIRED_OR_INCIDENT")
        if (!config.enabled || !config.allows(announcement.source)) return log(announcement, "NOT_PLAYED_CONFIRMED", "PROFILE_DISABLED")
        if (ble.state.value.phase != ConnectionPhase.READY) return log(announcement, "NOT_PLAYED_CONFIRMED", "HELMET_NOT_READY")
        val capabilities = ble.audioCapabilities.value
            ?: return log(announcement, "NOT_PLAYED_CONFIRMED", "AUDIO_CAPABILITIES_UNAVAILABLE")
        if (!capabilities.decoderSupported || !capabilities.outputReady) {
            return log(announcement, "NOT_PLAYED_CONFIRMED", "HELMET_OUTPUT_UNAVAILABLE")
        }
        if (capabilities.codec != HelmetAudioProtocol.CODEC_IMA_ADPCM_BLOCK_V1 ||
            capabilities.sampleRateHz != HelmetAudioProtocol.SAMPLE_RATE_HZ ||
            capabilities.blockSamples != HelmetAudioProtocol.BLOCK_SAMPLES ||
            capabilities.startupBlocks < HelmetAudioProtocol.requiredBlocksForBuffer(HelmetAudioProtocol.DEFAULT_START_BUFFER_MS)
        ) return log(announcement, "NOT_PLAYED_CONFIRMED", "CAPABILITY_MISMATCH")
        val mtu = ble.state.value.mtu ?: return log(announcement, "NOT_PLAYED_CONFIRMED", "MTU_UNKNOWN")
        if (mtu < HelmetAudioProtocol.MINIMUM_DYNAMIC_AUDIO_MTU) {
            return log(announcement, "NOT_PLAYED_CONFIRMED", "MTU_TOO_SMALL", mapOf("mtu" to mtu))
        }

        val synthesizer = tts ?: return log(announcement, "NOT_PLAYED_CONFIRMED", "TTS_UNAVAILABLE")
        val synthesisStarted = SystemClock.elapsedRealtime()
        log(announcement, "TTS_STARTED", "LOCAL_SYNTHESIS")
        val pcm = runCatching {
            withTimeout(15_000) { withContext(Dispatchers.Main) {
                synthesizer.synthesize(
                    announcement.speechText,
                    config.voiceName.ifBlank { null },
                    config.speechRate,
                    config.pitch,
                    HelmetAudioProtocol.SAMPLE_RATE_HZ
                )
            } }
        }.getOrElse {
            if (it is CancellationException) throw it
            return log(announcement, "NOT_PLAYED_CONFIRMED", "TTS_FAILED")
        }
        currentCoroutineContext().ensureActive()
        if (!repository.permits(announcement.traceId, epoch) || SystemClock.elapsedRealtime() >= announcement.expiresAtElapsedMs) return log(announcement, "NOT_PLAYED_CONFIRMED", "EXPIRED_OR_INCIDENT")
        if (!settings.state.value.enabled || !settings.state.value.allows(announcement.source)) return log(announcement, "NOT_PLAYED_CONFIRMED", "SOURCE_DISABLED")
        log(announcement, "TTS_COMPLETED", "LOCAL_SYNTHESIS", mapOf("durationMs" to SystemClock.elapsedRealtime() - synthesisStarted, "samples" to pcm.size))
        val blocks = ImaAdpcmCodec.encode(pcm.take(HelmetAudioProtocol.SAMPLE_RATE_HZ * 12).toShortArray()).map { it.toByteArray() }
        if (blocks.isEmpty() || blocks.any { it.size > capabilities.maximumBlockBytes || it.size + HelmetAudioProtocol.DATA_FIXED_OVERHEAD_BYTES > mtu - 3 }) {
            return log(announcement, "NOT_PLAYED_CONFIRMED", "ENCODED_BLOCK_TOO_LARGE")
        }

        val sessionId = randomNonZeroU32()
        val audioId = randomNonZeroU32()
        log(announcement, "TRANSFER_STARTED", "BLE_STREAM", mapOf("blocks" to blocks.size, "mtu" to mtu))
        val outcome = stream(sessionId, audioId, minOf(pcm.size, HelmetAudioProtocol.SAMPLE_RATE_HZ * 12), blocks, config.volume, announcement.expiresAtElapsedMs, announcement.traceId, epoch)
        when (outcome) {
            StreamOutcome.PLAYED_CONFIRMED -> log(
                announcement,
                "PLAYED_CONFIRMED",
                "HELMET_COMPLETED_FINAL_CRC_AND_I2S",
                mapOf("blocks" to blocks.size, "samples" to pcm.size)
            )
            StreamOutcome.NOT_SENT -> log(announcement, "NOT_PLAYED_CONFIRMED", "SESSION_REJECTED")
            StreamOutcome.QUEUED_RESULT_UNKNOWN -> log(
                announcement,
                "PLAYBACK_RESULT_UNKNOWN",
                "BYTES_QUEUED_WITHOUT_COMPLETION_PROOF",
                mapOf("blocks" to blocks.size, "samples" to pcm.size)
            )
        }
    }

    private suspend fun stream(
        sessionId: Long,
        audioId: Long,
        totalSamples: Int,
        blocks: List<ByteArray>,
        volume: Int,
        expiresAt: Long,
        traceId: String,
        epoch: Long
    ): StreamOutcome = coroutineScope {
        val cursor = ReliableAudioSendCursor(blocks.size)
        val statuses = Channel<AudioStatus>(Channel.UNLIMITED)
        var outputReady = false
        var retryLimitExceeded = false
        var anyPacketQueued = false
        val statusJob = launch(start = CoroutineStart.UNDISPATCHED) {
            ble.audioStatuses.collect { status ->
                if (status.sessionId == sessionId) {
                    diagnostics.log(traceId, "ble", "STREAM_STATUS", "STATUS_${status.code}", metrics = mapOf("stream" to sessionId, "clip" to audioId, "expected" to status.expectedSequence, "authorized" to status.authorizedThrough, "received" to status.receivedBlocks, "submitted" to status.submittedBlocks))
                    outputReady = status.outputReady
                    cursor.updateAuthorization(status.authorizedThrough)
                    if (!cursor.onStatus(status.code, status.expectedSequence)) retryLimitExceeded = true
                    statuses.trySend(status)
                }
            }
        }
        try {
            currentCoroutineContext().ensureActive()
            if (!repository.permits(traceId, epoch) || SystemClock.elapsedRealtime() >= expiresAt) return@coroutineScope StreamOutcome.NOT_SENT
            if (!ble.openAudioSession(sessionId)) return@coroutineScope StreamOutcome.NOT_SENT
            val opened = withTimeoutOrNull(2_500) { statuses.receive() }
            if (opened == null || !outputReady || opened.code == HelmetAudioProtocol.STATUS_CANCELLED) {
                return@coroutineScope StreamOutcome.NOT_SENT
            }
            currentCoroutineContext().ensureActive()
            if (!repository.permits(traceId, epoch) || SystemClock.elapsedRealtime() >= expiresAt) return@coroutineScope StreamOutcome.NOT_SENT
            val start = HelmetAudioProtocol.startPayload(
                totalSamples = totalSamples.toLong(),
                totalBlocks = blocks.size,
                totalEncodedBytes = blocks.sumOf { it.size }.toLong(),
                wholeCrc32 = HelmetAudioProtocol.wholeAudioCrc32(blocks),
                volume = volume,
                expiryAfterAcceptMs = ((totalSamples.toLong() * 1000 / HelmetAudioProtocol.SAMPLE_RATE_HZ) + 10_000).coerceIn(15_000, 60_000).toInt()
            )
            if (!ble.startAudio(sessionId, audioId, start)) return@coroutineScope StreamOutcome.NOT_SENT

            val expectedDurationMs = (totalSamples.toLong() * 1_000L / HelmetAudioProtocol.SAMPLE_RATE_HZ).coerceAtLeast(1_000L)
            val deadline = SystemClock.elapsedRealtime() + expectedDurationMs + 10_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                var claim = cursor.nextClaim()
                while (claim != null) {
                    currentCoroutineContext().ensureActive()
                    if (!repository.permits(traceId, epoch)) throw CancellationException("Incident active")
                    val block = blocks[claim.blockIndex]
                    val flags = if (claim.blockIndex == blocks.lastIndex) 0x01 else 0
                    val packet = HelmetAudioProtocol.encodeDataPacket(sessionId, audioId, claim.sequence, flags, block)
                    if (!ble.writeAudioPacket(packet)) {
                        ble.abortAudio(sessionId, audioId)
                        return@coroutineScope if (anyPacketQueued) StreamOutcome.QUEUED_RESULT_UNKNOWN else StreamOutcome.NOT_SENT
                    }
                    anyPacketQueued = true
                    claim = cursor.nextClaim()
                }
                if (retryLimitExceeded) {
                    ble.abortAudio(sessionId, audioId)
                    return@coroutineScope StreamOutcome.QUEUED_RESULT_UNKNOWN
                }

                if (!cursor.allFreshBlocksSent()) ble.queryAudioStatus(sessionId, audioId)
                val status = withTimeoutOrNull(750) { statuses.receive() }
                if (status == null) {
                    ble.queryAudioStatus(sessionId, audioId)
                    continue
                }
                when (status.code) {
                    HelmetAudioProtocol.STATUS_COMPLETED -> return@coroutineScope StreamOutcome.PLAYED_CONFIRMED
                    HelmetAudioProtocol.STATUS_CANCELLED,
                    HelmetAudioProtocol.STATUS_INTEGRITY_ERROR,
                    HelmetAudioProtocol.STATUS_MALFORMED,
                    HelmetAudioProtocol.STATUS_EXPIRED,
                    HelmetAudioProtocol.STATUS_START_REJECTED -> {
                        return@coroutineScope if (anyPacketQueued) StreamOutcome.QUEUED_RESULT_UNKNOWN else StreamOutcome.NOT_SENT
                    }
                }
            }
            ble.abortAudio(sessionId, audioId)
            if (anyPacketQueued) StreamOutcome.QUEUED_RESULT_UNKNOWN else StreamOutcome.NOT_SENT
        } finally {
            // A queued or partly played stream must not survive cancellation of its owning task.
            if (anyPacketQueued && !currentCoroutineContext().isActive) ble.abortAudio(sessionId, audioId)
            statusJob.cancel()
            statuses.close()
        }
    }

    private fun randomNonZeroU32(): Long {
        var value: Long
        do value = random.nextInt().toLong() and 0xffff_ffffL while (value == 0L)
        return value
    }

    private fun log(announcement: Announcement, result: String, reason: String, metrics: Map<String, Any?> = emptyMap()) {
        diagnostics.log(announcement.traceId, "audio", announcement.category.name, result, metrics = metrics + ("reason" to reason))
    }

    private enum class StreamOutcome { PLAYED_CONFIRMED, NOT_SENT, QUEUED_RESULT_UNKNOWN }
}
