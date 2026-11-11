package com.helmet.guard.core.audio

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class OfflineTtsEngineInfo(
    val packageName: String,
    val trustedSystemEngine: Boolean,
    val eligibleChineseVoices: Int,
    val embeddedFallbackAvailable: Boolean
)

/** Local TTS only. Text and generated audio stay in app-private storage and are deleted after use. */
class LocalTtsSynthesizer(context: Context) {
    private val appContext = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Result<File>>>()
    private val embeddedFallback = EmbeddedSherpaTtsSynthesizer(appContext)
    private val synthesisMutex = Mutex()
    private val previewPlayer = PhoneTestPlayer(appContext)
    @Volatile private var closed = false
    private lateinit var tts: TextToSpeech
    @Volatile private var enginePackage: String = ""
    @Volatile private var engineIsSystemApp: Boolean = false

    init {
        cleanupStaleFiles()
        tts = TextToSpeech(appContext) { status ->
            // Some engines complete initialization immediately; defer until assignment is complete.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (closed) return@post
                val ok = status == TextToSpeech.SUCCESS
                if (ok) {
                    tts.language = Locale.SIMPLIFIED_CHINESE
                    enginePackage = tts.defaultEngine.orEmpty()
                    engineIsSystemApp = isSystemApplication(enginePackage)
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) {
                            utteranceId?.let { id ->
                                val waiting = pending.remove(id)
                                if (waiting == null || !waiting.complete(Result.success(fileFor(id)))) fileFor(id).delete()
                            }
                        }
                        @Deprecated("Deprecated by Android")
                        override fun onError(utteranceId: String?) = fail(utteranceId, "TTS synthesis failed")
                        override fun onError(utteranceId: String?, errorCode: Int) = fail(utteranceId, "TTS error $errorCode")
                    })
                }
                if (!ready.isCompleted) ready.complete(ok)
            }
        }
    }

    suspend fun availableChineseVoices(): List<Voice> {
        if (!(withTimeoutOrNull(5_000) { ready.await() } ?: false)) return emptyList()
        return eligibleVoices().sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
    }

    suspend fun offlineEngineInfo(): OfflineTtsEngineInfo {
        val initialized = (withTimeoutOrNull(5_000) { ready.await() } ?: false)
        return OfflineTtsEngineInfo(
            enginePackage,
            initialized && engineIsSystemApp,
            if (initialized) eligibleVoices().size else 0,
            embeddedFallback.isBundledAndSupported()
        )
    }

    suspend fun speakPreview(text: String, voiceName: String? = null, speechRate: Float = 1f, pitch: Float = 1f, volume: Int = 100): Boolean = try {
        val pcm = synthesize(text.take(120), voiceName, speechRate, pitch)
        previewPlayer.play(pcm, volume = volume) { !closed }
        true
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { false }

    suspend fun synthesize(
        text: String,
        voiceName: String? = null,
        speechRate: Float = 1f,
        pitch: Float = 1f,
        targetRateHz: Int = 16_000
    ): ShortArray = synthesisMutex.withLock {
        check(!closed) { "TTS provider is shut down" }
        require(text.isNotBlank() && text.length <= 120)
        if (preferEmbedded(voiceName)) return@withLock withContext(Dispatchers.Default) {
            embeddedFallback.synthesize(text, speechRate, targetRateHz)
        }
        val systemReady = (withTimeoutOrNull(5_000) { ready.await() } ?: false)
        val systemVoice = if (systemReady) resolveEligibleVoice(voiceName) else null
        suspend fun embedded(): ShortArray {
            check(embeddedFallback.isBundledAndSupported()) { "No verified offline Chinese TTS provider" }
            return withContext(Dispatchers.Default) {
                embeddedFallback.synthesize(text, speechRate, targetRateHz)
            }
        }
        if (systemVoice != null) {
            synthesizeWithFallback(
                primary = { synthesizeWithSystem(text, systemVoice, speechRate, pitch, targetRateHz) },
                fallback = { embedded() }
            )
        } else embedded()
    }

    private fun preferEmbedded(voiceName: String?): Boolean =
        embeddedFallback.isBundledAndSupported() && (voiceName.isNullOrBlank() || voiceName == EMBEDDED_VOICE)

    companion object { const val EMBEDDED_VOICE = "embedded:xiaoya" }

    private suspend fun synthesizeWithSystem(
        text: String,
        voice: Voice,
        speechRate: Float,
        pitch: Float,
        targetRateHz: Int
    ): ShortArray {
        tts.voice = voice
        tts.setSpeechRate(speechRate.coerceIn(.6f, 1.5f))
        tts.setPitch(pitch.coerceIn(.6f, 1.5f))
        val id = UUID.randomUUID().toString()
        val file = fileFor(id)
        val result = CompletableDeferred<Result<File>>()
        pending[id] = result
        var completedNormally = false
        return try {
            check(tts.synthesizeToFile(text, Bundle(), file, id) == TextToSpeech.SUCCESS) { "TTS engine rejected synthesis" }
            val completed = result.await().getOrThrow()
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.IO) {
                PcmResampler.toMonoAt(WavPcmReader.read(completed.readBytes()), targetRateHz)
            }.also { completedNormally = true }
        } finally {
            pending.remove(id)
            result.cancel()
            if (!completedNormally) runCatching { tts.stop() }
            file.delete()
        }
    }

    fun cleanupStaleFiles(nowMs: Long = System.currentTimeMillis()) {
        appContext.cacheDir.listFiles { file -> file.name.startsWith("tts_") }?.forEach { file ->
            if (nowMs - file.lastModified() > 60 * 60 * 1_000L) file.delete()
        }
    }

    fun shutdown() {
        closed = true
        ready.complete(false)
        pending.values.forEach { it.cancel() }
        pending.clear()
        embeddedFallback.shutdown()
        if (::tts.isInitialized) tts.shutdown()
    }

    private fun eligibleVoices(): List<Voice> = tts.voices.orEmpty().filter { voice ->
        OfflineTtsPolicy.isEligible(
            OfflineVoiceDescriptor(
                name = voice.name,
                language = voice.locale.language,
                networkConnectionRequired = voice.isNetworkConnectionRequired,
                features = voice.features.orEmpty(),
                engineIsSystemApp = engineIsSystemApp
            )
        )
    }

    private fun resolveEligibleVoice(requestedName: String?): Voice? {
        val voices = eligibleVoices()
        return requestedName?.takeIf { it.isNotBlank() }?.let { name -> voices.firstOrNull { it.name == name } }
            ?: voices.firstOrNull()
    }

    @Suppress("DEPRECATION")
    private fun isSystemApplication(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val info = runCatching { appContext.packageManager.getApplicationInfo(packageName, 0) }.getOrNull() ?: return false
        return info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

    private fun fileFor(id: String) = File(appContext.cacheDir, "tts_$id.wav")
    private fun fail(id: String?, message: String) {
        id?.let { pending.remove(it)?.complete(Result.failure(IllegalStateException(message))) }
    }
}
