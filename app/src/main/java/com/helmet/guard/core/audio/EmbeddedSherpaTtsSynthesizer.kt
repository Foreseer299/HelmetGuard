package com.helmet.guard.core.audio

import android.content.Context
import android.os.Build
import java.lang.reflect.Method
import kotlin.math.roundToInt

/**
 * Optional ARM64 in-app fallback. Reflection keeps normal source/test builds independent of the
 * pinned AAR; release preparation adds the AAR and model assets, then R8 keep rules preserve them.
 */
class EmbeddedSherpaTtsSynthesizer(context: Context) {
    private val appContext = context.applicationContext
    private val modelRoot = "offline_tts/vits-piper-zh_CN-xiao_ya-medium-int8"
    private val lock = Any()
    @Volatile private var closed = false
    @Volatile private var engine: Any? = null
    @Volatile private var generateMethod: Method? = null
    @Volatile private var releaseMethod: Method? = null

    fun isBundledAndSupported(): Boolean =
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } &&
            assetExists("$modelRoot/model.onnx") &&
            assetExists("$modelRoot/lexicon.txt") &&
            assetExists("$modelRoot/tokens.txt") &&
            runCatching { Class.forName("com.k2fsa.sherpa.onnx.OfflineTts") }.isSuccess

    fun synthesize(text: String, speechRate: Float, targetRateHz: Int): ShortArray = synchronized(lock) {
        check(!closed) { "Embedded TTS provider is shut down" }
        check(isBundledAndSupported()) { "Embedded offline TTS is not bundled for this ABI" }
        val active = engine ?: createEngine().also { engine = it }
        val generated = requireNotNull(generateMethod).invoke(active, text, 0, speechRate.coerceIn(.6f, 1.5f))
        val generatedClass = generated.javaClass
        val samples = generatedClass.getMethod("getSamples").invoke(generated) as FloatArray
        val sourceRate = generatedClass.getMethod("getSampleRate").invoke(generated) as Int
        val pcm = ShortArray(samples.size) { index ->
            (samples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        PcmResampler.monoToRate(pcm, sourceRate, targetRateHz)
    }

    fun shutdown() {
        closed = true
        // Native generation is not interruptible. Never wait for its monitor on Android's UI thread.
        releaseExecutor.execute {
            synchronized(lock) {
                engine?.let { active -> runCatching { releaseMethod?.invoke(active) } }
                engine = null
                generateMethod = null
                releaseMethod = null
            }
        }
    }

    companion object {
        private val releaseExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { work ->
            Thread(work, "helmet-tts-release").apply { isDaemon = true }
        }
    }

    private fun createEngine(): Any {
        val vitsClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig")
        val vits = vitsClass.getConstructor().newInstance().apply {
            vitsClass.getMethod("setModel", String::class.java).invoke(this, "$modelRoot/model.onnx")
            vitsClass.getMethod("setLexicon", String::class.java).invoke(this, "$modelRoot/lexicon.txt")
            vitsClass.getMethod("setTokens", String::class.java).invoke(this, "$modelRoot/tokens.txt")
        }
        val modelClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsModelConfig")
        val model = modelClass.getConstructor().newInstance().apply {
            modelClass.getMethod("setVits", vitsClass).invoke(this, vits)
            modelClass.getMethod("setNumThreads", Int::class.javaPrimitiveType).invoke(this, 2)
            modelClass.getMethod("setDebug", Boolean::class.javaPrimitiveType).invoke(this, false)
            modelClass.getMethod("setProvider", String::class.java).invoke(this, "cpu")
        }
        val configClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsConfig")
        val config = configClass.getConstructor().newInstance().apply {
            configClass.getMethod("setModel", modelClass).invoke(this, model)
            configClass.getMethod("setRuleFsts", String::class.java).invoke(
                this,
                listOf("date.fst", "number.fst", "phone.fst").joinToString(",") { "$modelRoot/$it" }
            )
        }
        val ttsClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTts")
        generateMethod = ttsClass.getMethod(
            "generate",
            String::class.java,
            Int::class.javaPrimitiveType,
            Float::class.javaPrimitiveType
        )
        releaseMethod = ttsClass.getMethod("release")
        return ttsClass.getConstructor(android.content.res.AssetManager::class.java, configClass)
            .newInstance(appContext.assets, config)
    }

    private fun assetExists(path: String): Boolean = runCatching { appContext.assets.open(path).use { } }.isSuccess
}
