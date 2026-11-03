package com.helmet.guard.core.diagnostics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.helmet.guard.BuildConfig
import com.helmet.guard.data.db.LogDao
import com.helmet.guard.data.db.SystemLogEntity
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

/** Structured redacted logger. Callers must pass codes/metrics, never notification body or audio. */
class DiagnosticLogger(private val dao: LogDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Channel<suspend () -> Unit>(1024)
    init {
        scope.launch {
            var count = 0
            for (write in writes) {
                runCatching { write() }
                if (++count % 128 == 0) runCatching { prune() }
            }
        }
    }
    suspend fun flush() { val done = CompletableDeferred<Unit>(); writes.send { done.complete(Unit) }; done.await() }

    fun newTraceId(): String = UUID.randomUUID().toString()

    fun log(
        traceId: String,
        component: String,
        eventType: String,
        resultCode: String,
        level: String = "INFO",
        metrics: Map<String, Any?> = emptyMap()
    ) {
        val safe = metrics.filterKeys { key ->
            val normalized = key.lowercase()
            SENSITIVE_KEYS.none { normalized.contains(it) }
        }
        val message = buildString {
            append("trace=").append(token(traceId))
            append(";elapsedMs=").append(SystemClock.elapsedRealtime())
            append(";event=").append(token(eventType))
            append(";result=").append(token(resultCode))
            safe.toSortedMap().forEach { (key, value) ->
                append(';').append(token(key)).append('=').append(token(value?.toString().orEmpty()))
            }
        }
        val entity = SystemLogEntity(timestampMs = System.currentTimeMillis(), level = level, category = component, message = message)
        writes.trySend { dao.insert(entity) }
    }

    suspend fun prune(nowMs: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        dao.deleteOlderThan(nowMs - 7L * 24 * 60 * 60 * 1_000)
        dao.trimToNewest(3_000)
    }

    private fun token(value: String): String = value.take(160).replace(Regex("[^A-Za-z0-9_.:@/+ -]"), "_")

    companion object {
        private val SENSITIVE_KEYS = listOf("text", "body", "name", "phone", "address", "passkey", "key", "audio", "pcm", "wav")
    }
}

class DiagnosticExporter(private val context: Context, private val dao: LogDao, private val logger: DiagnosticLogger, private val snapshot: () -> String) {
    suspend fun export(): File = withContext(Dispatchers.IO) {
        val outputDir = File(context.cacheDir, "exports").apply { mkdirs() }
        outputDir.listFiles()?.filter { it.name.startsWith("helmetguard-diagnostics-") }?.forEach { it.delete() }
        val output = File(outputDir, "helmetguard-diagnostics-${System.currentTimeMillis()}.zip")
        logger.flush()
        val rows = dao.recentForExport()
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            zip.putText("diagnostic-manifest.json", """{"schema":1,"redacted":true,"containsNotificationBody":false,"containsAudio":false}""")
            zip.putText(
                "versions.json",
                """{"appVersion":"${BuildConfig.VERSION_NAME}","versionCode":${BuildConfig.VERSION_CODE},"sdk":${Build.VERSION.SDK_INT},"manufacturer":"${safe(Build.MANUFACTURER)}","model":"${safe(Build.MODEL)}"}"""
            )
            val events = rows.joinToString("\n") { row ->
                """{"timeMs":${row.timestampMs},"level":"${safe(row.level)}","component":"${safe(row.category)}","data":"${safe(row.message)}"}"""
            }
            zip.putText("events.ndjson", events)
            zip.putText("ble-firmware-status.json", snapshot())
            val crash = File(context.filesDir, "last-crash-redacted.txt")
            if (crash.exists()) zip.putText("crash/last-crash.txt", crash.readText().take(64_000))
            zip.putText("README.txt", "HelmetGuard diagnostics. Notification bodies, names, phone numbers, addresses, pairing secrets and audio are intentionally excluded.\n")
        }
        output
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun safe(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").take(800)
}
