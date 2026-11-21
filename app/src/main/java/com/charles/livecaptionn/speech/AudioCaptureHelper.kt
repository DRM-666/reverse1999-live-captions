package com.charles.livecaptionn.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import androidx.core.content.ContextCompat
import com.charles.livecaptionn.settings.AudioSource
import kotlin.math.abs

/**
 * Shared 16 kHz mono 16-bit PCM capture helper for local STT engines.
 */
internal object AudioCaptureHelper {
    const val SAMPLE_RATE = 16_000
    const val SILENCE_AVERAGE_ABS_THRESHOLD = 120
    const val NO_CAPTURABLE_AUDIO_MESSAGE =
        "No capturable system audio yet. Play unmuted media in an app that allows audio capture."

    fun buildMono16kAudioRecord(
        context: Context,
        audioSource: AudioSource,
        mediaProjection: MediaProjection?,
        minBufferBytes: Int,
        onError: (String?) -> Unit
    ): AudioRecord? {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(minBufferBytes)

        return when (audioSource) {
            AudioSource.SYSTEM -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    onError("System audio capture requires Android 10 (API 29) or newer. Please switch to Microphone mode.")
                    return null
                }
                val projection = mediaProjection ?: run {
                    onError("MediaProjection not available for system audio")
                    return null
                }
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuf)
                    .build()
            }
            AudioSource.MIC -> {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    onError("Microphone permission not granted. Allow microphone access and try again.")
                    return null
                }
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuf)
                    .build()
            }
        }
    }

    fun averageAbsAmplitude(pcmData: ByteArray, length: Int): Int {
        var sum = 0L
        var samples = 0
        var i = 0
        val end = minOf(length, pcmData.size) - 1
        while (i < end) {
            val low = pcmData[i].toInt() and 0xFF
            val high = pcmData[i + 1].toInt()
            val sample = (high shl 8) or low
            sum += abs(sample)
            samples += 1
            i += 2
        }
        return if (samples == 0) 0 else (sum / samples).toInt()
    }

    fun pcm16ToFloatArray(pcmData: ByteArray, lengthBytes: Int): FloatArray {
        val sampleCount = minOf(lengthBytes, pcmData.size) / 2
        val out = FloatArray(sampleCount)
        var i = 0
        var idx = 0
        while (idx < sampleCount) {
            val low = pcmData[i].toInt() and 0xFF
            val high = pcmData[i + 1].toInt()
            val sample = ((high shl 8) or low).toShort()
            out[idx] = sample / 32768.0f
            i += 2
            idx += 1
        }
        return out
    }
}
