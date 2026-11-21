package com.charles.livecaptionn.speech

import android.content.Context
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import com.charles.livecaptionn.settings.AudioSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Multi-accent on-device speech engine powered by Silero VAD + Sherpa-ONNX Whisper.
 *
 * Uses a strict producer-consumer pipeline: [captureLoop] continuously drains [AudioRecord]
 * into Silero VAD with zero blocking so system audio buffers never overflow, while
 * [decodeLoop] runs Whisper inference asynchronously on completed VAD segments.
 */
class SherpaWhisperEngine(
    private val context: Context,
    private val audioSource: AudioSource,
    private val mediaProjection: MediaProjection?,
    private val whisperClient: LocalWhisperSttClient,
    private val scope: CoroutineScope,
    private val onResult: (SpeechResult) -> Unit,
    private val onError: (String?) -> Unit = {},
    private val onLog: (String) -> Unit = {}
) : SpeechEngine {

    private val statusMutable = MutableStateFlow(RecognitionStatus.IDLE)
    val status: StateFlow<RecognitionStatus> = statusMutable

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var decodeJob: Job? = null
    private var segmentChannel: Channel<FloatArray>? = null

    @Volatile private var running = false
    @Volatile private var paused = false

    override fun start() {
        if (running) return
        running = true
        paused = false
        scope.launch(Dispatchers.IO) { startInternal() }
    }

    override fun stop() {
        running = false
        paused = false
        captureJob?.cancel()
        captureJob = null
        decodeJob?.cancel()
        decodeJob = null
        segmentChannel?.close()
        segmentChannel = null
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        if (audioSource == AudioSource.SYSTEM) {
            try { mediaProjection?.stop() } catch (_: Throwable) {}
        }
        statusMutable.value = RecognitionStatus.IDLE
    }

    override fun pause() {
        paused = true
        statusMutable.value = RecognitionStatus.PAUSED
    }

    override fun resume() {
        if (!running) return
        paused = false
        statusMutable.value = RecognitionStatus.LISTENING
    }

    private suspend fun startInternal() {
        statusMutable.value = RecognitionStatus.PROCESSING
        val loadStart = System.currentTimeMillis()
        if (!whisperClient.ensureLoaded()) {
            onLog("ensureLoaded() FAILED")
            onError("Failed to load local Whisper speech model.")
            statusMutable.value = RecognitionStatus.ERROR
            running = false
            return
        }
        onLog("Loaded ${whisperClient.activeModelName()} in ${System.currentTimeMillis() - loadStart}ms")
        val vad = whisperClient.currentVad()
        if (vad == null) {
            onError("VAD initialization failed.")
            statusMutable.value = RecognitionStatus.ERROR
            running = false
            return
        }
        vad.clear()

        val record = try {
            AudioCaptureHelper.buildMono16kAudioRecord(
                context = context,
                audioSource = audioSource,
                mediaProjection = mediaProjection,
                minBufferBytes = READ_BYTES * 16,
                onError = onError
            )
        } catch (t: Throwable) {
            Log.e(TAG, "buildAudioRecord failed", t)
            onError("Audio capture failed: ${t.message ?: t::class.java.simpleName}")
            statusMutable.value = RecognitionStatus.ERROR
            running = false
            return
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            onError(
                if (audioSource == AudioSource.SYSTEM) "System audio capture failed to initialize"
                else "Microphone capture failed to initialize"
            )
            statusMutable.value = RecognitionStatus.ERROR
            record?.release()
            running = false
            return
        }
        audioRecord = record

        try {
            record.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            onError("Audio capture failed to start: ${t.message ?: t::class.java.simpleName}")
            statusMutable.value = RecognitionStatus.ERROR
            record.release()
            audioRecord = null
            running = false
            return
        }

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            onError("Audio capture did not start")
            statusMutable.value = RecognitionStatus.ERROR
            record.release()
            audioRecord = null
            running = false
            return
        }

        val ch = Channel<FloatArray>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        segmentChannel = ch
        statusMutable.value = RecognitionStatus.LISTENING
        onError(null)
        decodeJob = scope.launch(Dispatchers.Default) { decodeLoop(ch) }
        captureJob = scope.launch(Dispatchers.IO) { captureLoop(record, ch) }
    }

    private suspend fun captureLoop(record: AudioRecord, ch: Channel<FloatArray>) {
        val pcmBuffer = ByteArray(READ_BYTES)
        val vadWindow = FloatArray(VAD_WINDOW_SAMPLES)
        var vadFill = 0
        var silentChunkStreak = 0
        var sawAnyAudioEver = false
        var loggedAudioActive = false

        while (scope.isActive && running) {
            if (paused) {
                delay(120)
                continue
            }

            val read = try {
                record.read(pcmBuffer, 0, pcmBuffer.size)
            } catch (t: Throwable) {
                Log.w(TAG, "AudioRecord.read threw", t)
                onLog("AudioRecord.read threw: ${t.message}")
                onError("Audio capture read failed")
                statusMutable.value = RecognitionStatus.ERROR
                delay(400)
                continue
            }
            if (read <= 0) {
                if (read < 0) {
                    onLog("AudioRecord.read returned $read")
                    delay(80)
                }
                continue
            }

            val avgAbs = AudioCaptureHelper.averageAbsAmplitude(pcmBuffer, read)
            if (avgAbs < AudioCaptureHelper.SILENCE_AVERAGE_ABS_THRESHOLD) {
                silentChunkStreak += 1
                if (audioSource == AudioSource.SYSTEM &&
                    !sawAnyAudioEver &&
                    silentChunkStreak >= SILENT_CHUNKS_BEFORE_HINT
                ) {
                    onError(AudioCaptureHelper.NO_CAPTURABLE_AUDIO_MESSAGE)
                }
            } else {
                silentChunkStreak = 0
                sawAnyAudioEver = true
                if (!loggedAudioActive) {
                    loggedAudioActive = true
                    onLog("System/Mic audio flowing (avgAbs=$avgAbs)")
                }
                onError(null)
            }

            val vad = whisperClient.currentVad() ?: break
            val floats = AudioCaptureHelper.pcm16ToFloatArray(pcmBuffer, read)
            var offset = 0
            while (offset < floats.size) {
                val needed = VAD_WINDOW_SAMPLES - vadFill
                val take = minOf(needed, floats.size - offset)
                System.arraycopy(floats, offset, vadWindow, vadFill, take)
                vadFill += take
                offset += take

                if (vadFill == VAD_WINDOW_SAMPLES) {
                    vad.acceptWaveform(vadWindow)
                    vadFill = 0
                }
            }

            while (!vad.empty()) {
                val segment = vad.front()
                vad.pop()
                if (segment.samples.size >= MIN_SEGMENT_SAMPLES) {
                    ch.trySend(segment.samples)
                }
            }
        }
    }

    private suspend fun decodeLoop(ch: Channel<FloatArray>) {
        for (samples in ch) {
            if (!running) break
            if (paused) continue
            statusMutable.value = RecognitionStatus.PROCESSING
            val t0 = System.currentTimeMillis()
            val text = whisperClient.transcribe(samples)
            val durSec = samples.size / 16000.0
            val costMs = System.currentTimeMillis() - t0
            onLog("VAD seg=${"%.2f".format(durSec)}s decode=${costMs}ms text='${text.take(80)}'")
            if (text.isNotBlank()) {
                onResult(SpeechResult(text, isFinal = true))
            }
            if (!paused && running) {
                statusMutable.value = RecognitionStatus.LISTENING
            }
        }
    }

    companion object {
        private const val TAG = "SherpaWhisperEngine"
        private const val VAD_WINDOW_SAMPLES = 512 // 32ms @ 16kHz
        private const val READ_BYTES = 2048 // 64ms @ 16kHz mono 16-bit
        private const val MIN_SEGMENT_SAMPLES = 3_200 // 0.2s
        private const val SILENT_CHUNKS_BEFORE_HINT = 48 // ~3s
    }
}
