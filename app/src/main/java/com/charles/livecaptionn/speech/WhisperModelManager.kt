package com.charles.livecaptionn.speech

import android.content.Context
import android.net.Uri
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
import java.util.zip.ZipInputStream

enum class WhisperVariant(
    val id: String,
    val displayName: String,
    val tierNote: String,
    val repo: String
) {
    SMALL_EN(
        id = "small.en",
        displayName = "Whisper Small (int8)",
        tierNote = "主力推荐 · 约 358 MB · 英音/口音与背景音最稳",
        repo = "csukuangfj/sherpa-onnx-whisper-small.en"
    ),
    BASE_EN(
        id = "base.en",
        displayName = "Whisper Base (int8)",
        tierNote = "轻量均衡 · 速度更快 · 体积更小",
        repo = "csukuangfj/sherpa-onnx-whisper-base.en"
    ),
    TINY_EN(
        id = "tiny.en",
        displayName = "Whisper Tiny (int8)",
        tierNote = "超轻极速 · 适合快速体验或低配机",
        repo = "csukuangfj/sherpa-onnx-whisper-tiny.en"
    );

    fun requiredFiles(): List<String> = listOf(
        "$id-encoder.int8.onnx",
        "$id-decoder.int8.onnx",
        "$id-tokens.txt"
    )
}

data class WhisperSmallStatus(
    val installed: Boolean = false,
    val bundledSmall: Boolean = false,
    val installedVariants: Set<WhisperVariant> = emptySet(),
    val activeVariant: WhisperVariant? = null,
    val downloading: Boolean = false,
    val downloadingVariant: WhisperVariant? = null,
    val progress: Float = 0f,
    val statusMessage: String? = null,
    val error: String? = null
) {
    val isReady: Boolean get() = bundledSmall || installedVariants.isNotEmpty()
}

/**
 * Manages downloadable & local-zip-importable Sherpa-ONNX Whisper models
 * (`small.en`, `base.en`, `tiny.en`) stored in `filesDir/whisper/<variant>/`.
 * External models survive APK upgrades cleanly when using the lightweight (`lite`) build.
 */
class WhisperModelManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val mutableStatus = MutableStateFlow(buildIdleStatus())
    val status: StateFlow<WhisperSmallStatus> = mutableStatus.asStateFlow()

    fun refreshStatus(message: String? = null, error: String? = null) {
        mutableStatus.value = buildIdleStatus(message = message, error = error)
    }

    fun isInstalled(): Boolean = hasAnyDiskModel(context) || hasBundledSmallEn(context)

    fun selectVariant(variant: WhisperVariant): Boolean {
        if (!isVariantInstalled(context, variant)) return false
        activeVariantFile(context).apply {
            parentFile?.mkdirs()
            writeText(variant.id)
        }
        refreshStatus(message = "已切换为 ${variant.displayName}")
        return true
    }

    suspend fun downloadVariant(variant: WhisperVariant): Boolean = withContext(Dispatchers.IO) {
        if (mutableStatus.value.downloading) return@withContext false
        val current = buildIdleStatus()
        mutableStatus.value = current.copy(
            downloading = true,
            downloadingVariant = variant,
            progress = 0f,
            statusMessage = "正在从镜像源下载 ${variant.displayName}…",
            error = null
        )
        val dir = variantDir(context, variant).apply { mkdirs() }
        val tmpDir = File(context.cacheDir, "whisper-tmp-${variant.id}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val files = variant.requiredFiles()
            val totalFiles = files.size
            files.forEachIndexed { idx, fileName ->
                val outFile = File(tmpDir, fileName)
                downloadWithMirrors(variant.repo, fileName, outFile) { fileFrac ->
                    val overall = (idx + fileFrac) / totalFiles.toFloat()
                    mutableStatus.value = mutableStatus.value.copy(
                        downloading = true,
                        downloadingVariant = variant,
                        progress = overall.coerceIn(0f, 1f)
                    )
                }
            }
            files.forEach { fileName ->
                val src = File(tmpDir, fileName)
                val dst = File(dir, fileName)
                if (dst.exists()) dst.delete()
                if (!src.renameTo(dst)) {
                    src.copyTo(dst, overwrite = true)
                    src.delete()
                }
            }
            tmpDir.deleteRecursively()
            activeVariantFile(context).apply {
                parentFile?.mkdirs()
                writeText(variant.id)
            }
            mutableStatus.value = buildIdleStatus(message = "${variant.displayName} 下载完成，重启服务即可生效")
            true
        } catch (t: Throwable) {
            tmpDir.deleteRecursively()
            mutableStatus.value = buildIdleStatus(error = t.message ?: "下载失败，请检查网络或改用本地导入")
            false
        }
    }

    suspend fun downloadSmallEn(): Boolean = downloadVariant(WhisperVariant.SMALL_EN)

    suspend fun importFromZipUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (mutableStatus.value.downloading) return@withContext false
        mutableStatus.value = buildIdleStatus(message = "正在校验并解压模型压缩包…").copy(downloading = true, progress = 0.2f)
        val tmpDir = File(context.cacheDir, "whisper-zip-import").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("无法读取所选文件")
            var extractedCount = 0
            input.use { raw ->
                ZipInputStream(raw.buffered()).use { zis ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val baseName = File(entry.name).name
                        if (!isAllowedModelFile(baseName)) continue
                        val outFile = File(tmpDir, baseName)
                        if (!outFile.canonicalPath.startsWith(tmpDir.canonicalPath + File.separator)) {
                            error("压缩包路径异常: ${entry.name}")
                        }
                        FileOutputStream(outFile).use { fos ->
                            while (true) {
                                val read = zis.read(buf)
                                if (read < 0) break
                                fos.write(buf, 0, read)
                            }
                        }
                        extractedCount++
                    }
                }
            }
            if (extractedCount == 0) {
                error("压缩包内未找到有效的 Whisper int8 模型文件（*encoder.int8.onnx / *decoder.int8.onnx / *tokens.txt）")
            }
            val matchedVariant = WhisperVariant.entries.firstOrNull { variant ->
                variant.requiredFiles().all { req ->
                    val f = File(tmpDir, req)
                    f.exists() && f.length() > 1_000
                }
            } ?: error("压缩包文件不完整，需要成套的 encoder.int8.onnx、decoder.int8.onnx 和 tokens.txt")

            val dstDir = variantDir(context, matchedVariant).apply { mkdirs() }
            matchedVariant.requiredFiles().forEach { req ->
                val src = File(tmpDir, req)
                val dst = File(dstDir, req)
                if (dst.exists()) dst.delete()
                if (!src.renameTo(dst)) {
                    src.copyTo(dst, overwrite = true)
                    src.delete()
                }
            }
            tmpDir.deleteRecursively()
            activeVariantFile(context).apply {
                parentFile?.mkdirs()
                writeText(matchedVariant.id)
            }
            mutableStatus.value = buildIdleStatus(message = "已成功导入 ${matchedVariant.displayName}")
            true
        } catch (t: Throwable) {
            tmpDir.deleteRecursively()
            mutableStatus.value = buildIdleStatus(error = "导入失败：${t.message ?: "请检查压缩包内容"}")
            false
        }
    }

    fun deleteVariant(variant: WhisperVariant) {
        variantDir(context, variant).deleteRecursively()
        val remaining = installedDiskVariants(context)
        val activeFile = activeVariantFile(context)
        if (remaining.isEmpty()) {
            activeFile.delete()
        } else if (resolveSelectedVariant(context) == null) {
            activeFile.writeText(remaining.first().id)
        }
        refreshStatus(message = "已删除外置 ${variant.displayName}")
    }

    fun deleteSmallEn() = deleteVariant(WhisperVariant.SMALL_EN)

    private fun buildIdleStatus(message: String? = null, error: String? = null): WhisperSmallStatus {
        val bundled = hasBundledSmallEn(context)
        val installedSet = installedDiskVariants(context).toSet()
        val active = resolveActiveVariant(context)
        return WhisperSmallStatus(
            installed = bundled || installedSet.isNotEmpty(),
            bundledSmall = bundled,
            installedVariants = installedSet,
            activeVariant = active,
            downloading = false,
            downloadingVariant = null,
            progress = 0f,
            statusMessage = message,
            error = error
        )
    }

    private fun downloadWithMirrors(
        repo: String,
        fileName: String,
        target: File,
        onProgress: (Float) -> Unit
    ) {
        var lastError: Throwable? = null
        val mirrors = listOf(
            "https://hf-mirror.com/$repo/resolve/main",
            "https://huggingface.co/$repo/resolve/main"
        )
        for (base in mirrors) {
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
        throw lastError ?: IllegalStateException("镜像连接失败")
    }

    private fun isAllowedModelFile(name: String): Boolean {
        return WhisperVariant.entries.any { v -> name in v.requiredFiles() }
    }

    companion object {
        private const val BUNDLED_SMALL_TOKENS = "models/whisper-small.en/small.en-tokens.txt"

        fun rootDir(context: Context): File = File(context.filesDir, "whisper")

        fun variantDir(context: Context, variant: WhisperVariant): File = File(rootDir(context), variant.id)

        private fun activeVariantFile(context: Context): File = File(rootDir(context), "active_variant.txt")

        fun isVariantInstalled(context: Context, variant: WhisperVariant): Boolean {
            val dir = variantDir(context, variant)
            return variant.requiredFiles().all { name ->
                val f = File(dir, name)
                f.exists() && f.length() > 1_000
            }
        }

        fun installedDiskVariants(context: Context): List<WhisperVariant> =
            WhisperVariant.entries.filter { isVariantInstalled(context, it) }

        fun hasAnyDiskModel(context: Context): Boolean = installedDiskVariants(context).isNotEmpty()

        fun hasBundledSmallEn(context: Context): Boolean = try {
            context.assets.open(BUNDLED_SMALL_TOKENS).close()
            true
        } catch (_: Throwable) {
            false
        }

        private fun resolveSelectedVariant(context: Context): WhisperVariant? {
            val file = activeVariantFile(context)
            if (!file.exists()) return null
            val savedId = runCatching { file.readText().trim() }.getOrNull().orEmpty()
            val candidate = WhisperVariant.entries.firstOrNull { it.id == savedId } ?: return null
            return if (isVariantInstalled(context, candidate)) candidate else null
        }

        fun resolveActiveVariant(context: Context): WhisperVariant? {
            return resolveSelectedVariant(context)
                ?: installedDiskVariants(context).firstOrNull()
                ?: if (hasBundledSmallEn(context)) WhisperVariant.SMALL_EN else null
        }
    }
}
