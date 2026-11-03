package com.helmet.guard

import android.app.Application
import com.helmet.guard.service.GuardNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HelmetGuardApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                // Exception messages can contain notification contents. Store classes/frames only.
                val lines = buildString {
                    var cause: Throwable? = error
                    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
                    while (cause != null && seen.add(cause)) {
                        appendLine(cause.javaClass.name)
                        cause.stackTrace.forEach { appendLine("  at $it") }
                        cause = cause.cause
                    }
                }
                java.io.File(filesDir, "last-crash-redacted.txt").writeText(lines.take(64_000))
            }
            previous?.uncaughtException(thread, error)
        }
        GuardNotifications.createChannels(this)
        cleanupStaleSensitiveFiles()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { graph.diagnostics.prune() }
        graph.incidents.restorePending()
    }

    private fun cleanupStaleSensitiveFiles(nowMs: Long = System.currentTimeMillis()) {
        cacheDir.listFiles()?.filter { it.name.startsWith("tts_") || it.name.startsWith("audio_") }
            ?.filter { nowMs - it.lastModified() > 60 * 60 * 1_000L }
            ?.forEach { it.delete() }
    }
}

val android.content.Context.appGraph: AppGraph
    get() = (applicationContext as HelmetGuardApp).graph
