package com.helmet.guard.core.detector

import android.os.SystemClock
import com.helmet.guard.domain.TelemetrySample
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.sin
import kotlin.random.Random

enum class MockScenario(val displayName: String) {
    NORMAL_RIDE("正常骑行"), SPEED_BUMP("减速带"), HELMET_DROP("头盔掉落"), CRASH("真实碰撞特征")
}

class MockTelemetryEngine {
    fun play(scenario: MockScenario, realtime: Boolean = true): Flow<TelemetrySample> = flow {
        val random = Random(scenario.ordinal + 42)
        val interval = 50L
        val total = 180
        val baseElapsed = SystemClock.elapsedRealtime()
        val baseWall = System.currentTimeMillis()
        for (index in 0 until total) {
            val t = index * interval
            val noise = { scale: Float -> (random.nextFloat() - 0.5f) * scale }
            var ax = noise(0.08f); var ay = noise(0.08f); var az = 1f + noise(0.08f)
            var gx = noise(10f); var gy = noise(10f); var gz = noise(10f)
            var roll = sin(index / 15f) * 3f; var pitch = sin(index / 22f) * 2f
            when (scenario) {
                MockScenario.NORMAL_RIDE -> {
                    ax += sin(index / 5f) * 0.15f
                    gy += sin(index / 7f) * 35f
                }
                MockScenario.SPEED_BUMP -> if (index == 55) {
                    az = 4.5f; gx = 35f; gy = 45f
                }
                MockScenario.HELMET_DROP -> when (index) {
                    in 45..48 -> { ax = 0.05f; ay = 0.04f; az = 0.08f; gx = 30f; gy = 55f }
                    49 -> { ax = 3.8f; ay = 2.2f; az = 3.1f; gx = 260f; gy = 310f; roll = 52f }
                    in 50 until total -> { ax = noise(0.008f); ay = noise(0.008f); az = 1f + noise(0.008f); gx = noise(2f); gy = noise(2f); gz = noise(2f); roll = 52f }
                }
                MockScenario.CRASH -> {
                    if (index in 0..44) { ax += sin(index / 3f) * 0.22f; gy += sin(index / 4f) * 70f }
                    when (index) {
                        in 45..47 -> { ax = 0.08f; ay = 0.08f; az = 0.12f }
                        48 -> { ax = 5.4f; ay = 3.2f; az = 2.3f; gx = 460f; gy = 390f; gz = 180f; roll = 68f; pitch = 41f }
                        in 49..72 -> { roll = 72f; pitch = 45f; gx = noise(45f); gy = noise(45f); az = 0.7f + noise(0.2f) }
                        in 73 until total -> { roll = 74f + noise(0.3f); pitch = 46f + noise(0.3f); ax = noise(0.025f); ay = 0.72f + noise(0.025f); az = 0.69f + noise(0.025f); gx = noise(7f); gy = noise(7f); gz = noise(7f) }
                    }
                }
            }
            emit(TelemetrySample(t, baseElapsed + t, baseWall + t, ax, ay, az, gx, gy, gz, roll, pitch, 0f, 88, true, true, sequence = index and 0xff))
            if (realtime) delay(interval)
        }
    }
}
