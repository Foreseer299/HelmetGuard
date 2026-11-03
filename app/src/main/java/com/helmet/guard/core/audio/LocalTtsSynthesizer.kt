package com.helmet.guard.core.audio

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

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
    private lateinit var tts: TextToSpeech
    @Volatile private var enginePackage: String = ""
    @Volatile private var engineIsSystemApp: Boolean = false

    init {
        cleanupStaleFiles()
        tts = TextToSpeech(appContext) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                tts.language = Locale.SIMPLIFIED_CHINESE
                enginePackage = tts.defaultEngine.orEmpty()
                engineIsSystemApp = isSystemApplication(enginePackage)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { id -> pending.remove(id)?.complete(Result.success(fileFor(id))) }
                    }
                    @Deprecated("Deprecated by Android")
                    override fun onError(utteranceId: String?) = fail(utteranceId, "TTS synthesis failed")
                    override fun onError(utteranceId: String?, errorCode: Int) = fail(utteranceId, "TTS error $errorCode")
                })
            }
            if (!ready.isCompleted) ready.complete(ok)
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

    suspend fun speakPreview(text: String, voiceName: String? = null, speechRate: Float = 1f, pitch: Float = 1f): Boolean {
        val initialized = (withTimeoutOrNull(5_000) { ready.await() } ?: false)
        val voice = if (initialized) resolveEligibleVoice(voiceName) else null
        if (voice != null) {
            tts.voice = voice
            tts.setSpeechRate(speechRate.coerceIn(.6f, 1.5f))
            tts.setPitch(pitch.coerceIn(.6f, 1.5f))
            return tts.speak(text.take(120), TextToSpeech.QUEUE_FLUSH, Bundle(), "preview") == TextToSpeech.SUCCESS
        }
        if (!embeddedFallback.isBundledAndSupported()) return false
        return runCatching {
            val pcm = withContext(Dispatchers.Default) { embeddedFallback.synthesize(text.take(120), speechRate, 16_000) }
            playPreviewPcm(pcm, 16_000)
            true
        }.getOrDefault(false)
    }

    suspend fun synthesize(
        text: String,
        voiceName: String? = null,
        speechRate: Float = 1f,
        pitch: Float = 1f,
        targetRateHz: Int = 16_000
    ): ShortArray {
        require(text.isNotBlank() && text.length <= 120)
        val systemReady = (withTimeoutOrNull(5_000) { ready.await() } ?: false)
        val systemVoice = if (systemReady) resolveEligibleVoice(voiceName) else null
        if (systemVoice != null) {
            try {
                return synthesizeWithSystem(text, systemVoice, speechRate, pitch, targetRateHz)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A local system engine can still fail at synthesis time; use the bundled local provider only.
            }
        }
        check(embeddedFallback.isBundledAndSupported()) { "No verified offline Chinese TTS provider" }
        return withContext(Dispatchers.Default) {
            embeddedFallback.synthesize(text, speechRate, targetRateHz)
        }
    }

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
        val started = tts.synthesizeToFile(text, Bundle(), file, id)
        if (started != TextToSpeech.SUCCESS) {
            pending.remove(id)
            throw IllegalStateException("TTS engine rejected synthesis")
        }
        return try {
            val completed = result.await().getOrThrow()
            withContext(Dispatchers.IO) { PcmResampler.toMonoAt(WavPcmReader.read(completed.readBytes()), targetRateHz) }
        } finally {
            pending.remove(id)
            file.delete()
        }
    }

    fun cleanupStaleFiles(nowMs: Long = System.currentTimeMillis()) {
        appContext.cacheDir.listFiles { file -> file.name.startsWith("tts_") }?.forEach { file ->
            if (nowMs - file.lastModified() > 60 * 60 * 1_000L) file.delete()
        }
    }

    fun shutdown() {
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

    private suspend fun playPreviewPcm(pcm: ShortArray, sampleRate: Int) = withContext(Dispatchers.Default) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        try {
            check(track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) == pcm.size)
            track.play()
            delay((pcm.size * 1_000L / sampleRate) + 100L)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun fileFor(id: String) = File(appContext.cacheDir, "tts_$id.wav")
    private fun fail(id: String?, message: String) {
        id?.let { pending.remove(it)?.complete(Result.failure(IllegalStateException(message))) }
    }
}
