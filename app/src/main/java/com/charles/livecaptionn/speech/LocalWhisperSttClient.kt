package com.charles.livecaptionn.speech

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages the on-device Sherpa-ONNX Whisper recognizer and Silero VAD.
 *
 * Loads lazily when a captioning session starts and releases all native
 * ONNX memory via [release] as soon as the user turns off the service switch.
 */
class LocalWhisperSttClient(private val context: Context) {

    private val mutex = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var activeModelLabel: String = "whisper-small.en"

    fun smallModelDir(): File = File(context.filesDir, "whisper/small.en")

    fun isSmallModelInstalled(): Boolean = true

    fun activeModelName(): String = "Whisper Small (int8 · 内置)"

    suspend fun ensureLoaded(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (recognizer != null && vad != null) return@withLock true
            return@withLock try {
                val vadConfig = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = VAD_ASSET_PATH,
                        threshold = 0.46f,
                        minSilenceDuration = 0.35f,
                        minSpeechDuration = 0.20f,
                        windowSize = 512,
                        maxSpeechDuration = 6.0f
                    ),
                    sampleRate = AudioCaptureHelper.SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false
                )
                val loadedVad = Vad(context.assets, vadConfig)

                val whisperConfig = OfflineWhisperModelConfig(
                    encoder = "$WHISPER_SMALL_ASSET_DIR/small.en-encoder.int8.onnx",
                    decoder = "$WHISPER_SMALL_ASSET_DIR/small.en-decoder.int8.onnx",
                    language = "en",
                    task = "transcribe",
                    tailPaddings = -1
                )
                val tokensPath = "$WHISPER_SMALL_ASSET_DIR/small.en-tokens.txt"

                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                val modelConfig = OfflineModelConfig(
                    whisper = whisperConfig,
                    tokens = tokensPath,
                    numThreads = threads,
                    debug = false,
                    provider = "cpu",
                    modelType = "whisper"
                )
                val recConfig = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(
                        sampleRate = AudioCaptureHelper.SAMPLE_RATE,
                        featureDim = 80
                    ),
                    modelConfig = modelConfig,
                    decodingMethod = "greedy_search"
                )
                val loadedRecognizer = OfflineRecognizer(context.assets, recConfig)
                vad = loadedVad
                recognizer = loadedRecognizer
                Log.i(TAG, "Loaded $activeModelLabel with $threads threads")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load Sherpa-ONNX Whisper", t)
                releaseInternal()
                false
            }
        }
    }

    suspend fun currentVad(): Vad? = mutex.withLock { vad }

    suspend fun transcribe(samples: FloatArray, sampleRate: Int = AudioCaptureHelper.SAMPLE_RATE): String =
        withContext(Dispatchers.Default) {
            if (samples.size < sampleRate / 5) return@withContext ""
            mutex.withLock {
                val rec = recognizer ?: return@withContext ""
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(samples, sampleRate)
                    rec.decode(stream)
                    cleanWhisperText(rec.getResult(stream).text)
                } catch (t: Throwable) {
                    Log.w(TAG, "Whisper decode failed", t)
                    ""
                } finally {
                    try { stream.release() } catch (_: Throwable) {}
                }
            }
        }

    /** Explicitly frees native ONNX Runtime & Silero VAD memory when service stops. */
    suspend fun release() = withContext(Dispatchers.IO) {
        mutex.withLock { releaseInternal() }
    }

    private fun releaseInternal() {
        try { vad?.release() } catch (_: Throwable) {}
        vad = null
        try { recognizer?.release() } catch (_: Throwable) {}
        recognizer = null
        Log.i(TAG, "Released Whisper and VAD native models")
    }

    companion object {
        private const val TAG = "LocalWhisperSttClient"
        private const val WHISPER_SMALL_ASSET_DIR = "models/whisper-small.en"
        private const val VAD_ASSET_PATH = "models/vad/silero_vad.int8.onnx"

        private val BRACKETED_HALLUCINATION = Regex("^\\s*(?:\\[[^\\]]*]|\\([^)]*\\))\\s*$")
        private val INLINE_TAGS = Regex("\\[(?:MUSIC|Music|BLANK_AUDIO|APPLAUSE|LAUGHTER|SILENCE|NOISE|INAUDIBLE|SOUND|BGM)]|\\((?:music|sighs|laughter|applause|silence)\\)", RegexOption.IGNORE_CASE)
        private val COMMON_NOISE_PHRASES = setOf(
            "thank you.", "thank you", "thanks for watching.", "thanks for watching!",
            "you", "you.", "bye.", "bye", ".", "...", "hmm", "hmm.", "uh", "ah",
            "fuck", "fuck.", "shit", "shit."
        )

        internal fun cleanWhisperText(raw: String): String {
            val cleaned = raw.replace(INLINE_TAGS, " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (cleaned.isEmpty()) return ""
            if (BRACKETED_HALLUCINATION.matches(cleaned)) return ""
            if (cleaned.lowercase() in COMMON_NOISE_PHRASES) return ""
            if (cleaned.none { it.isLetterOrDigit() }) return ""
            return cleaned
        }
    }
}
