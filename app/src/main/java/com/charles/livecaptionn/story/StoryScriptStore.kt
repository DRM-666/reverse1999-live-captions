package com.charles.livecaptionn.story

import android.content.Context
import android.net.Uri
import androidx.core.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Private, user-supplied UTF-8 text or Markdown script. No game scripts are distributed with the app. */
class StoryScriptStore(private val context: Context) {
    private val file get() = File(context.filesDir, "story-script.txt")
    fun exists(): Boolean = file.isFile
    fun load(): String? = if (file.isFile) file.readText(Charsets.UTF_8) else null
    fun loadParsed(): ParsedStoryDocument? = load()?.let(StoryDocumentParser::parse)
    fun clear() { file.delete() }

    fun importFile(uri: Uri): ParsedStoryDocument {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                require(total <= MAX_BYTES) { "文件不能超过 2 MB" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException("无法打开文件")
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require(text.isNotBlank()) { "文件没有文本" }
        val parsed = StoryDocumentParser.parse(text)
        require(parsed.lines.isNotEmpty()) { "未解析到有效台词行" }
        require(parsed.lines.size <= StoryMatcher.MAX_LINES) { "台词不能超过 10000 行" }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
        return parsed
    }

    companion object { private const val MAX_BYTES = 2 * 1024 * 1024 }
}
