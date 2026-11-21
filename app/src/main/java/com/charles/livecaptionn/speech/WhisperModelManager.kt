package com.charles.livecaptionn.speech

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class WhisperSmallStatus(
    val installed: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null
)

/**
 * Optional downloader for `whisper-small.en` (int8), giving stronger offline
 * recognition on heavy accents & background music than the bundled `base.en`.
 */
class WhisperModelManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val mutableStatus = MutableStateFlow(WhisperSmallStatus(installed = isInstalled()))
    val status: StateFlow<WhisperSmallStatus> = mutableStatus.asStateFlow()

    fun modelDir(): File = File(context.filesDir, "whisper/small.en")

    fun isInstalled(): Boolean {
        val dir = modelDir()
        return REQUIRED_FILES.all { name ->
            val f = File(dir, name)
            f.exists() && f.length() > 1_000
        }
    }

    suspend fun downloadSmallEn(): Boolean = withContext(Dispatchers.IO) {
        if (mutableStatus.value.downloading) return@withContext false
        mutableStatus.value = WhisperSmallStatus(installed = isInstalled(), downloading = true, progress = 0f)
        val dir = modelDir().apply { mkdirs() }
        val tmpDir = File(context.cacheDir, "whisper-small-tmp").apply { mkdirs() }
        try {
            val totalFiles = REQUIRED_FILES.size
            REQUIRED_FILES.forEachIndexed { idx, fileName ->
                val outFile = File(tmpDir, fileName)
                downloadWithMirrors(fileName, outFile) { fileFrac ->
                    val overall = (idx + fileFrac) / totalFiles.toFloat()
                    mutableStatus.value = WhisperSmallStatus(
                        installed = false,
                        downloading = true,
                        progress = overall.coerceIn(0f, 1f)
                    )
                }
            }
            REQUIRED_FILES.forEach { fileName ->
                val src = File(tmpDir, fileName)
                val dst = File(dir, fileName)
                if (dst.exists()) dst.delete()
                if (!src.renameTo(dst)) {
                    src.copyTo(dst, overwrite = true)
                    src.delete()
                }
            }
            mutableStatus.value = WhisperSmallStatus(installed = true, downloading = false, progress = 1f)
            true
        } catch (t: Throwable) {
            tmpDir.deleteRecursively()
            mutableStatus.value = WhisperSmallStatus(
                installed = isInstalled(),
                downloading = false,
                progress = 0f,
                error = t.message ?: "下载失败"
            )
            false
        }
    }

    fun deleteSmallEn() {
        modelDir().deleteRecursively()
        mutableStatus.value = WhisperSmallStatus(installed = false)
    }

    private fun downloadWithMirrors(
        fileName: String,
        target: File,
        onProgress: (Float) -> Unit
    ) {
        var lastError: Throwable? = null
        for (base in MIRROR_BASES) {
            try {
                val url = "$base/$fileName"
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("HTTP ${resp.code}")
                    }
                    val body = resp.body ?: throw IllegalStateException("Empty body")
                    val total = body.contentLength().coerceAtLeast(1L)
                    body.byteStream().use { input ->
                        FileOutputStream(target).use { out ->
                            val buf = ByteArray(16 * 1024)
                            var written = 0L
                            while (true) {
                                val read = input.read(buf)
                                if (read < 0) break
                                out.write(buf, 0, read)
                                written += read
                                onProgress((written.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                return
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("All mirrors failed")
    }

    companion object {
        private val REQUIRED_FILES = listOf(
            "small.en-encoder.int8.onnx",
            "small.en-decoder.int8.onnx",
            "small.en-tokens.txt"
        )
        private val MIRROR_BASES = listOf(
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-small.en/resolve/main",
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small.en/resolve/main"
        )
    }
}
