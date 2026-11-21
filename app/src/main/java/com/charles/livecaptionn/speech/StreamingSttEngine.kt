package com.charles.livecaptionn.speech

import android.content.Context
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import com.charles.livecaptionn.settings.AudioSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Continuous, low-latency speech-to-text engine backed by a live
 * [VoskStreamingSession]. Reads 16kHz mono PCM from either the mic or
 * MediaProjection system-audio capture and feeds ~100ms chunks straight into
 * Vosk, emitting partial results as they evolve and final segments on
 * silence boundaries.
 *
 * This replaces the old batch SystemAudioEngine, which accumulated 2-6s
 * windows and created a fresh Recognizer per chunk, throwing away streaming
 * state and making captions feel laggy and incoherent.
 */
class StreamingSttEngine(
    private val context: Context,
    private val audioSource: AudioSource,
    private val mediaProjection: MediaProjection?,
    private val languageCode: String,
    private val localSttClient: LocalVoskSttClient,
    private val scope: CoroutineScope,
    private val onResult: (SpeechResult) -> Unit,
    private val onError: (String?) -> Unit = {}
) : SpeechEngine {

    private val statusMutable = MutableStateFlow(RecognitionStatus.IDLE)
    val status: StateFlow<RecognitionStatus> = statusMutable

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var session: VoskStreamingSession? = null

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
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        session?.finish()?.let { remainder ->
            if (remainder.isNotBlank()) onResult(SpeechResult(remainder, isFinal = true))
        }
        session?.close()
        session = null
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
        val localSession = localSttClient.openSession(languageCode, SAMPLE_RATE)
        if (localSession == null) {
            onError(
                "No on-device model installed for '$languageCode'. " +
                    "Open the language picker to download one."
            )
            statusMutable.value = RecognitionStatus.ERROR
            running = false
            return
        }
        session = localSession

        val record = try {
            buildAudioRecord()
        } catch (t: Throwable) {
            Log.e(TAG, "buildAudioRecord failed", t)
            onError("Audio capture failed: ${t.message ?: t::class.java.simpleName}")
            statusMutable.value = RecognitionStatus.ERROR
            session?.close(); session = null
            running = false
            return
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            onError(
                if (audioSource == AudioSource.SYSTEM)
                    "System audio capture failed to initialize"
                else
                    "Microphone capture failed to initialize"
            )
            statusMutable.value = RecognitionStatus.ERROR
            record?.release()
            session?.close(); session = null
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
            session?.close(); session = null
            running = false
            return
        }

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            onError("Audio capture did not start")
            statusMutable.value = RecognitionStatus.ERROR
            record.release()
            audioRecord = null
            session?.close(); session = null
            running = false
            return
        }

        statusMutable.value = RecognitionStatus.LISTENING
        onError(null)

        captureJob = scope.launch(Dispatchers.Default) { captureLoop(record) }
    }

    private suspend fun captureLoop(record: AudioRecord) {
        val buffer = ByteArray(CHUNK_BYTES)
        var silentChunkStreak = 0
        var lastEmittedPartial = ""
        var sawAnyAudioEver = false

        while (scope.isActive && running) {
            if (paused) {
                delay(120)
                continue
            }

            val read = try {
                record.read(buffer, 0, buffer.size)
            } catch (t: Throwable) {
                Log.w(TAG, "AudioRecord.read threw", t)
                onError("Audio capture read failed")
                statusMutable.value = RecognitionStatus.ERROR
                delay(500)
                continue
            }

            if (read <= 0) {
                // read == 0 is transient on some devices; anything negative is fatal-ish.
                if (read < 0) {
                    Log.w(TAG, "AudioRecord.read returned $read")
                    delay(100)
                }
                continue
            }

            val avgAbs = AudioCaptureHelper.averageAbsAmplitude(buffer, read)
            val isSilent = avgAbs < AudioCaptureHelper.SILENCE_AVERAGE_ABS_THRESHOLD

            if (isSilent) {
                silentChunkStreak += 1
                // For system audio, flag when we've been silent for a long time so the
                // user knows nothing is actually playing.
                if (audioSource == AudioSource.SYSTEM &&
                    !sawAnyAudioEver &&
                    silentChunkStreak >= SILENT_CHUNKS_BEFORE_SYSTEM_HINT
                ) {
                    onError(AudioCaptureHelper.NO_CAPTURABLE_AUDIO_MESSAGE)
                }
            } else {
                silentChunkStreak = 0
                sawAnyAudioEver = true
                onError(null)
            }

            val localSession = session ?: break
            val result = localSession.feed(buffer, read)

            if (result.accepted) {
                // Vosk hit a silence/segment boundary and emitted a finalized chunk.
                if (result.text.isNotBlank()) {
                    onResult(SpeechResult(result.text, isFinal = true))
                    lastEmittedPartial = ""
                }
            } else if (result.text.isNotBlank() && result.text != lastEmittedPartial) {
                // Live partial — drives the "words appearing as you speak" overlay.
                onResult(SpeechResult(result.text, isFinal = false))
                lastEmittedPartial = result.text
            }

            if (!paused && running) {
                statusMutable.value = RecognitionStatus.LISTENING
            }
        }
    }

    private fun buildAudioRecord(): AudioRecord? =
        AudioCaptureHelper.buildMono16kAudioRecord(
            context = context,
            audioSource = audioSource,
            mediaProjection = mediaProjection,
            minBufferBytes = CHUNK_BYTES * 8,
            onError = onError
        )

    companion object {
        private const val TAG = "StreamingSttEngine"
        private const val SAMPLE_RATE = AudioCaptureHelper.SAMPLE_RATE
        /** ~100ms at 16kHz mono 16-bit = 3200 bytes. Small chunks = live partials. */
        private const val CHUNK_BYTES = 3200
        private const val SILENT_CHUNKS_BEFORE_SYSTEM_HINT = 30 // ~3s of silence
    }
}
