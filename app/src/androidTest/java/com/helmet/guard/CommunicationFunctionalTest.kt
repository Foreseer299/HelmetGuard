package com.helmet.guard

import android.content.Context
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import com.helmet.guard.ui.theme.HelmetGuardTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import com.helmet.guard.core.audio.LocalTtsSynthesizer
import com.helmet.guard.core.audio.ImaAdpcmCodec
import com.helmet.guard.domain.AnnouncementDecision
import com.helmet.guard.domain.AnnouncementSource
import com.helmet.guard.ui.screens.AnnouncementSettingsScreen
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CommunicationFunctionalTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = context.appGraph

    @Test fun modeSwitchPersistsAndPersonalModeRejectsRiderSources() = runBlocking<Unit> {
        graph.preferences.setAnnouncementMode("PERSONAL")
        withTimeout(3000) { while (graph.announcementSettings.state.value.mode != "PERSONAL") delay(20) }
        compose.setContent { HelmetGuardTheme { AnnouncementSettingsScreen(graph) {} } }
        compose.onNodeWithText("普通用户").assertIsDisplayed()
        compose.onNodeWithText("骑手").performClick()
        withTimeout(3000) { while (graph.announcementSettings.state.value.mode != "RIDER") delay(20) }
        compose.onNodeWithText("骑手").assertIsDisplayed()
        screenshot("rider-mode.png")
        compose.onNodeWithText("普通用户").performClick()
        withTimeout(3000) { while (graph.announcementSettings.state.value.mode != "PERSONAL") delay(20) }
        compose.onNodeWithText("仅处理短信和来电").assertIsDisplayed()
        screenshot("personal-mode.png")
        assertFalse(graph.announcementSettings.state.value.allows(AnnouncementSource.RIDER_APP))
        assertTrue(graph.announcementSettings.state.value.allows(AnnouncementSource.SMS))
    }

    @Test fun systemNotificationTraversesListenerAndSelectedSourcePolicy() = runBlocking<Unit> {
        graph.announcements.setSafetyBlocked(false)
        graph.announcements.clear()
        graph.preferences.setAnnouncementMode("RIDER")
        graph.preferences.setAnnouncementSources(true, true, true)
        graph.preferences.setAnnouncementsEnabled(true)
        graph.preferences.setRiderPackageEnabled("com.android.shell", true)
        withTimeout(3000) { while (graph.announcementSettings.state.value.riderPackages.isEmpty() || !graph.announcementSettings.state.value.enabled || !graph.announcementSettings.state.value.riderApps || graph.announcementSettings.state.value.mode != "RIDER") delay(20) }
        withTimeout(25000) { while (!com.helmet.guard.core.notification.HelmetNotificationListener.connected.value) delay(50) }
        val stamp = SystemClock.elapsedRealtime()
        shell("cmd notification post -t '新订单' hg-test-$stamp '有新订单，请及时接单'")
        withTimeout(10000) {
            while (graph.announcements.lastEvaluation.value?.announcement?.createdElapsedMs?.let { it >= stamp } != true) delay(50)
        }
        assertEquals(AnnouncementDecision.SPEAK, graph.announcements.lastEvaluation.value?.decision)
        assertEquals("com.android.shell", graph.announcements.lastEvaluation.value?.announcement?.sourcePackage)
        graph.announcements.setSafetyBlocked(true)
        assertNull(graph.announcements.poll(SystemClock.elapsedRealtime()))
        graph.announcements.setSafetyBlocked(false)
        graph.preferences.setRiderPackageEnabled("com.android.shell", false)
        graph.preferences.setAnnouncementsEnabled(false)
    }

    @Test fun bundledChineseTtsProducesPcmAndAdpcmOnAndroid12() = runBlocking<Unit> {
        lateinit var synth: LocalTtsSynthesizer
        withContext(Dispatchers.Main) { synth = LocalTtsSynthesizer(context) }
        try {
            val pcm = withTimeout(60000) { synth.synthesize("您有一条新订单", targetRateHz = 16000) }
            assertTrue("Audio must be nonempty", pcm.size > 1600)
            assertTrue("Audio must contain a signal", pcm.any { it.toInt() != 0 })
            val blocks = ImaAdpcmCodec.encode(pcm)
            assertTrue(blocks.isNotEmpty())
            assertTrue(com.helmet.guard.core.audio.HelmetAudioProtocol.wholeAudioCrc32(blocks.map { it.toByteArray() }) in 0..0xffffffffL)
            assertEquals(pcm.size, blocks.sumOf { ImaAdpcmCodec.decode(it).size })
            val dir = File(context.getExternalFilesDir(null), "functional-test").apply { mkdirs() }
            File(dir, "tts-result.txt").writeText("rateHz=16000 samples=${pcm.size} blocks=${blocks.size} nonzero=${pcm.count { it.toInt()!=0 }}")
        } finally { withContext(Dispatchers.Main) { synth.shutdown() } }
    }

    @Test fun telemetryLabelsHaveChineseGroupsAndUnits() {
        compose.setContent { HelmetGuardTheme { com.helmet.guard.ui.screens.TelemetryScreen(graph, {}, { _, _ -> }) } }
        compose.onNodeWithText("加速度").assertIsDisplayed()
        compose.onNodeWithText("角速度").assertIsDisplayed()
        screenshot("telemetry-data.png")
        compose.onNodeWithText("倾角与姿态").performScrollTo().assertIsDisplayed()
        screenshot("telemetry-angles.png")
    }

    @Test fun answeredCallRevokesAlreadyQueuedAnnouncement() = runBlocking<Unit> {
        val repository = com.helmet.guard.core.notification.AnnouncementRepository()
        val sessions = com.helmet.guard.core.call.CallSessionCoordinator(repository)
        sessions.onState(1, android.telephony.TelephonyManager.CALL_STATE_RINGING, SystemClock.elapsedRealtime())
        withTimeout(3000) { while (repository.lastEvaluation.value?.announcement == null) delay(25) }
        sessions.onState(1, android.telephony.TelephonyManager.CALL_STATE_IDLE, SystemClock.elapsedRealtime())
        assertNull(repository.poll(SystemClock.elapsedRealtime()))
    }

    @Test fun diagnosticsExportContainsFlushedEventAndStatus() = runBlocking<Unit> {
        graph.diagnostics.log("functional-test", "test", "EXPORT", "PASS")
        val archive = graph.diagnosticExporter.export()
        java.util.zip.ZipFile(archive).use { z ->
            assertNotNull(z.getEntry("ble-firmware-status.json"))
            assertTrue(z.getInputStream(z.getEntry("events.ndjson")).bufferedReader().readText().contains("functional-test"))
        }
    }

    private fun screenshot(name: String) {
        val dir = File(context.getExternalFilesDir(null), "functional-test").apply { mkdirs() }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command).let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
}
