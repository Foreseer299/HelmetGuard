package com.helmet.guard.core.detector

import com.helmet.guard.domain.DetectionFeatures
import com.helmet.guard.domain.DetectorConfig
import com.helmet.guard.domain.DetectorResult
import com.helmet.guard.domain.DetectorSnapshot
import com.helmet.guard.domain.DetectorStage
import com.helmet.guard.domain.IncidentCandidate
import com.helmet.guard.domain.IncidentSource
import com.helmet.guard.domain.RejectionReason
import com.helmet.guard.domain.TelemetrySample
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Multi-stage detector tuned for helmet IMU streams (recommended 20-50 Hz).
 *
 * It intentionally rejects the two most common false-positive shapes:
 * 1. speed bumps: short vertical impulse with little rotation/orientation change;
 * 2. an unattended helmet drop: quiet pre-window + free fall + perfectly still post-window.
 *
 * No IMU-only algorithm can prove that a helmet is worn. Production hardware should add a
 * capacitive/pressure wear sensor. This detector exposes every feature for recording and tuning.
 */
class AdvancedAccidentDetector(
    private var config: DetectorConfig = DetectorConfig()
) {
    private data class CandidateContext(
        val startedElapsedMs: Long,
        val detectedAtMs: Long,
        val baselineRoll: Float,
        val baselinePitch: Float,
        val preMotion: Float,
        val samples: MutableList<TelemetrySample>,
        var preliminaryScore: Float = 0f,
        var dropSuspected: Boolean = false,
        var features: DetectionFeatures = DetectionFeatures()
    )

    private val preWindow = ArrayDeque<TelemetrySample>()
    private val stillWindow = ArrayDeque<TelemetrySample>()
    private var context: CandidateContext? = null
    private var stage = DetectorStage.IDLE
    private var lastElapsedMs = 0L
    private var lastRejection: RejectionReason? = null
    private var snapshot = DetectorSnapshot()

    @Synchronized
    fun updateConfig(value: DetectorConfig) {
        config = value
        reset()
    }

    @Synchronized
    fun process(sample: TelemetrySample, isSimulation: Boolean = false): DetectorResult {
        val now = sample.receivedElapsedMs
        if (lastElapsedMs > 0 && (now <= lastElapsedMs || now - lastElapsedMs > config.maximumSampleGapMs)) {
            reset(RejectionReason.STALE_DATA)
        }
        lastElapsedMs = now

        val result = when (stage) {
            DetectorStage.IDLE -> processIdle(sample)
            DetectorStage.CANDIDATE, DetectorStage.OBSERVING -> processObservation(sample)
            DetectorStage.VERIFYING_STILLNESS -> processStillness(sample, isSimulation)
            DetectorStage.COOLDOWN -> DetectorResult(snapshot)
        }
        addPreSample(sample)
        return result
    }

    @Synchronized
    fun markCountdownActive() {
        stage = DetectorStage.COOLDOWN
        snapshot = snapshot.copy(stage = stage, label = "事故倒计时")
    }

    @Synchronized
    fun reset(reason: RejectionReason? = null) {
        stage = DetectorStage.IDLE
        context = null
        stillWindow.clear()
        if (reason != null) lastRejection = reason
        snapshot = DetectorSnapshot(
            stage = DetectorStage.IDLE,
            confidence = 0f,
            label = reason?.toDisplayText() ?: "正常监测",
            lastRejection = reason ?: lastRejection
        )
    }

    @Synchronized fun currentSnapshot(): DetectorSnapshot = snapshot

    private fun processIdle(sample: TelemetrySample): DetectorResult {
        if (!sample.imuHealthy || !sample.calibrated || sample.accelerationG < config.impactThresholdG) {
            snapshot = snapshot.copy(stage = DetectorStage.IDLE, confidence = 0f, label = "正常监测")
            return DetectorResult(snapshot)
        }

        val baseline = preWindow.toList()
        val baselineRoll = circularMean(baseline.map { it.roll }).takeIf { !it.isNaN() } ?: sample.roll
        val baselinePitch = circularMean(baseline.map { it.pitch }).takeIf { !it.isNaN() } ?: sample.pitch
        // Exclude the immediate pre-impact fall from the riding background. Otherwise free fall
        // itself makes a previously unattended helmet look as if it had been moving normally.
        val background = baseline.filter { sample.receivedElapsedMs - it.receivedElapsedMs >= 300L }
        val preMotion = motionScore(background.ifEmpty { baseline })
        context = CandidateContext(
            startedElapsedMs = sample.receivedElapsedMs,
            detectedAtMs = sample.receivedAtMs,
            baselineRoll = baselineRoll,
            baselinePitch = baselinePitch,
            preMotion = preMotion,
            samples = mutableListOf(sample)
        )
        stage = DetectorStage.OBSERVING
        val initialConfidence = normalized(sample.accelerationG, config.impactThresholdG, config.hardImpactThresholdG) * 0.3f
        snapshot = DetectorSnapshot(stage, initialConfidence, "检测到冲击，正在复核")
        return DetectorResult(snapshot)
    }

    private fun processObservation(sample: TelemetrySample): DetectorResult {
        val ctx = context ?: return DetectorResult(snapshot).also { reset() }
        ctx.samples += sample
        val elapsed = sample.receivedElapsedMs - ctx.startedElapsedMs
        val features = observationFeatures(ctx)
        val score = preliminaryScore(features)
        ctx.features = features
        ctx.preliminaryScore = score
        snapshot = DetectorSnapshot(
            DetectorStage.OBSERVING,
            score,
            "复核冲击与姿态变化",
            features,
            lastRejection
        )
        if (elapsed < config.observationWindowMs) return DetectorResult(snapshot)

        val speedBumpLikely = features.peakAccelerationG < config.hardImpactThresholdG &&
            features.peakAngularSpeedDps < config.speedBumpRotationMaxDps &&
            features.orientationChangeDeg < config.speedBumpOrientationMaxDeg
        if (speedBumpLikely) return reject(RejectionReason.SPEED_BUMP_LIKELY)

        val hasRotationEvidence = features.peakAngularSpeedDps >= config.rotationThresholdDps ||
            features.orientationChangeDeg >= config.orientationChangeThresholdDeg
        if (!hasRotationEvidence && features.peakAccelerationG < config.hardImpactThresholdG) {
            return reject(RejectionReason.INSUFFICIENT_ROTATION)
        }

        ctx.dropSuspected = features.preImpactMotionScore <= config.helmetDropPreMotionMax &&
            features.freeFallDurationMs >= config.minimumFreeFallMs
        stillWindow.clear()
        stillWindow += sample
        stage = DetectorStage.VERIFYING_STILLNESS
        snapshot = DetectorSnapshot(stage, score, "正在确认碰撞后状态", features, lastRejection)
        return DetectorResult(snapshot)
    }

    private fun processStillness(sample: TelemetrySample, isSimulation: Boolean): DetectorResult {
        val ctx = context ?: return DetectorResult(snapshot).also { reset() }
        stillWindow += sample
        while (stillWindow.size > 300) stillWindow.removeFirst()
        val elapsed = sample.receivedElapsedMs - (stillWindow.firstOrNull()?.receivedElapsedMs ?: sample.receivedElapsedMs)
        val accVariance = variance(stillWindow.map { it.accelerationG })
        val gyroRms = rms(stillWindow.map { it.angularSpeedDps })
        val features = ctx.features.copy(
            postImpactAccVariance = accVariance,
            postImpactGyroRms = gyroRms,
            sampleCount = ctx.features.sampleCount + stillWindow.size
        )
        val still = accVariance <= config.stillAccVarianceMax && gyroRms <= config.stillGyroRmsMax
        val liveConfidence = (ctx.preliminaryScore + if (still) 0.18f else 0f).coerceIn(0f, 1f)
        snapshot = DetectorSnapshot(stage, liveConfidence, "正在确认骑手活动", features, lastRejection)
        if (elapsed < config.stillnessWindowMs) return DetectorResult(snapshot)

        val exactStillness = accVariance < config.stillAccVarianceMax * 0.22f &&
            gyroRms < config.stillGyroRmsMax * 0.22f
        if (ctx.dropSuspected && exactStillness && features.peakAccelerationG < config.hardImpactThresholdG) {
            return reject(RejectionReason.HELMET_DROP_LIKELY)
        }
        if (!still && ctx.preliminaryScore < 0.84f) return reject(RejectionReason.RIDER_MOVING)

        val confidence = (ctx.preliminaryScore + when {
            still -> 0.18f
            features.peakAccelerationG >= config.hardImpactThresholdG -> 0.08f
            else -> 0f
        }).coerceIn(0f, 1f)
        if (confidence < config.triggerScore) return reject(RejectionReason.LOW_SCORE)

        val incident = IncidentCandidate(
            eventId = ctx.detectedAtMs,
            detectedAtMs = ctx.detectedAtMs,
            source = IncidentSource.PHONE_ALGORITHM,
            confidence = confidence,
            features = features,
            isSimulation = isSimulation
        )
        stage = DetectorStage.COOLDOWN
        snapshot = DetectorSnapshot(stage, confidence, "高置信度疑似事故", features, lastRejection)
        return DetectorResult(snapshot, incident)
    }

    private fun reject(reason: RejectionReason): DetectorResult {
        val features = context?.features ?: snapshot.features
        lastRejection = reason
        stage = DetectorStage.IDLE
        context = null
        stillWindow.clear()
        snapshot = DetectorSnapshot(DetectorStage.IDLE, 0f, reason.toDisplayText(), features, reason)
        return DetectorResult(snapshot)
    }

    private fun observationFeatures(ctx: CandidateContext): DetectionFeatures {
        val samples = ctx.samples
        val peakAcc = samples.maxOfOrNull { it.accelerationG } ?: 1f
        val peakGyro = samples.maxOfOrNull { it.angularSpeedDps } ?: 0f
        val orientation = samples.maxOfOrNull {
            max(angleDistance(it.roll, ctx.baselineRoll), angleDistance(it.pitch, ctx.baselinePitch))
        } ?: 0f
        val freeFallSamples = (preWindow.toList().takeLast(50) + samples).sortedBy { it.receivedElapsedMs }
        val freeFallMs = longestDuration(freeFallSamples) { it.accelerationG <= config.freeFallThresholdG }
        return DetectionFeatures(
            peakAccelerationG = peakAcc,
            peakAngularSpeedDps = peakGyro,
            orientationChangeDeg = orientation,
            freeFallDurationMs = freeFallMs,
            preImpactMotionScore = ctx.preMotion,
            sampleCount = samples.size
        )
    }

    private fun preliminaryScore(f: DetectionFeatures): Float {
        val impact = normalized(f.peakAccelerationG, config.impactThresholdG, config.hardImpactThresholdG)
        val rotation = normalized(f.peakAngularSpeedDps, config.speedBumpRotationMaxDps, config.rotationThresholdDps * 1.8f)
        val posture = normalized(f.orientationChangeDeg, config.speedBumpOrientationMaxDeg, 90f)
        val severityBonus = if (f.peakAccelerationG >= config.hardImpactThresholdG) 0.14f else 0f
        val freeFallEvidence = if (f.freeFallDurationMs >= config.minimumFreeFallMs) 0.06f else 0f
        return (impact * 0.34f + rotation * 0.28f + posture * 0.24f + severityBonus + freeFallEvidence).coerceIn(0f, 0.9f)
    }

    private fun addPreSample(sample: TelemetrySample) {
        preWindow += sample
        val cutoff = sample.receivedElapsedMs - 2_500L
        while (preWindow.isNotEmpty() && preWindow.first().receivedElapsedMs < cutoff) preWindow.removeFirst()
    }

    private fun motionScore(samples: List<TelemetrySample>): Float {
        if (samples.size < 4) return 0f
        val accDynamics = sqrt(variance(samples.map { it.accelerationG }))
        val gyro = rms(samples.map { it.angularSpeedDps }) / 180f
        return (accDynamics * 2.2f + gyro).coerceIn(0f, 1f)
    }

    private fun longestDuration(samples: List<TelemetrySample>, predicate: (TelemetrySample) -> Boolean): Long {
        var start: Long? = null
        var best = 0L
        var previous = 0L
        for (sample in samples) {
            if (predicate(sample)) {
                if (start == null || (previous > 0 && sample.receivedElapsedMs - previous > config.maximumSampleGapMs)) start = sample.receivedElapsedMs
                previous = sample.receivedElapsedMs
                best = max(best, sample.receivedElapsedMs - (start ?: sample.receivedElapsedMs))
            } else start = null
        }
        return best
    }

    private fun normalized(value: Float, start: Float, full: Float): Float =
        if (full <= start) 0f else ((value - start) / (full - start)).coerceIn(0f, 1f)

    private fun variance(values: List<Float>): Float {
        if (values.size < 2) return Float.MAX_VALUE
        val mean = values.average().toFloat()
        return values.sumOf { val d = it - mean; (d * d).toDouble() }.toFloat() / values.size
    }

    private fun rms(values: List<Float>): Float {
        if (values.isEmpty()) return Float.MAX_VALUE
        return sqrt(values.sumOf { (it * it).toDouble() } / values.size).toFloat()
    }

    private fun angleDistance(a: Float, b: Float): Float {
        val delta = abs(a - b) % 360f
        return min(delta, 360f - delta)
    }

    private fun circularMean(values: List<Float>): Float = if (values.isEmpty()) Float.NaN else values.average().toFloat()

    private fun RejectionReason.toDisplayText(): String = when (this) {
        RejectionReason.SPEED_BUMP_LIKELY -> "已识别为减速带/垂直颠簸"
        RejectionReason.HELMET_DROP_LIKELY -> "已识别为未佩戴头盔掉落"
        RejectionReason.INSUFFICIENT_ROTATION -> "冲击无明显翻滚，已排除"
        RejectionReason.RIDER_MOVING -> "碰撞后仍有持续活动，已排除"
        RejectionReason.STALE_DATA -> "传感器数据中断，检测器已复位"
        RejectionReason.LOW_SCORE -> "综合置信度不足，已排除"
    }
}
