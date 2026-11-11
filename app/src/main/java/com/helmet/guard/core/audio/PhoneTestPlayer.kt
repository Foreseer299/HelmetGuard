package com.helmet.guard.core.audio

import android.content.Context
import android.media.*
import kotlinx.coroutines.*

/** Plays already-synthesized PCM. Cancellation/focus loss always stops output. */
class PhoneTestPlayer(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    suspend fun play(pcm: ShortArray, sampleRate: Int = 16000, volume: Int = 100, keepPlaying: () -> Boolean = { true }): Int = withContext(Dispatchers.Default) {
        require(pcm.isNotEmpty() && sampleRate > 0)
        currentCoroutineContext().ensureActive()
        if (!keepPlaying()) throw CancellationException("Playback revoked")
        val job = currentCoroutineContext().job
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener { state ->
                if (state == AudioManager.AUDIOFOCUS_LOSS || state == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) job.cancel(CancellationException("Audio focus lost"))
            }.build()
        check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio focus denied" }
        var track: AudioTrack? = null
        try {
            currentCoroutineContext().ensureActive()
            val output = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
            track = output
            output.setVolume(volume.coerceIn(0, 100) / 100f)
            check(output.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) == pcm.size)
            currentCoroutineContext().ensureActive()
            if (!keepPlaying()) throw CancellationException("Playback revoked")
            output.play()
            withTimeout(pcm.size * 1000L / sampleRate + 2000) {
                while (output.playbackHeadPosition < pcm.size) { currentCoroutineContext().ensureActive(); if (!keepPlaying()) throw CancellationException("Playback revoked"); delay(20) }
            }
            output.playbackHeadPosition
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            audio.abandonAudioFocusRequest(focus)
        }
    }
}
