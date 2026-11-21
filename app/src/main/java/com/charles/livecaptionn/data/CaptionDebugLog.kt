package com.charles.livecaptionn.data

import android.content.Context
import com.charles.livecaptionn.data.feedback.DiagnosticsHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thread-safe rolling debug log for diagnosing audio capture, Whisper recognition,
 * StoryMatcher hit/miss scores, and chapter transitions on real devices.
 */
class CaptionDebugLog(private val context: Context) {

    private val lock = Any()
    private val buffer = ArrayDeque<String>(MAX_MEMORY_LINES)
    private val logFile: File
        get() = File(context.filesDir, "caption_debug.log")

    init {
        loadRecentFromDisk()
    }

    fun log(tag: String, message: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$ts][$tag] $message"
        synchronized(lock) {
            if (buffer.size >= MAX_MEMORY_LINES) buffer.removeFirst()
            buffer.addLast(line)
            runCatching {
                val file = logFile
                if (file.exists() && file.length() > MAX_FILE_BYTES) {
                    val tail = buffer.takeLast(MAX_MEMORY_LINES / 2)
                    file.writeText(tail.joinToString("\n") + "\n", Charsets.UTF_8)
                } else {
                    file.appendText(line + "\n", Charsets.UTF_8)
                }
            }
        }
    }

    fun lineCount(): Int = synchronized(lock) { buffer.size }

    fun exportFullText(extraHeader: String = ""): String {
        val diagnostics = runCatching { DiagnosticsHelper.collect(context) }.getOrDefault("")
        val linesSnapshot = synchronized(lock) { buffer.toList() }
        return buildString {
            appendLine(diagnostics)
            if (extraHeader.isNotBlank()) {
                appendLine(extraHeader)
                appendLine()
            }
            appendLine("## Runtime Log (${linesSnapshot.size} lines)")
            appendLine()
            for (line in linesSnapshot) {
                appendLine(line)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            runCatching { logFile.delete() }
        }
    }

    private fun loadRecentFromDisk() {
        synchronized(lock) {
            runCatching {
                val file = logFile
                if (!file.exists()) return
                val all = file.readLines(Charsets.UTF_8)
                val tail = all.takeLast(MAX_MEMORY_LINES)
                buffer.clear()
                buffer.addAll(tail)
            }
        }
    }

    companion object {
        private const val MAX_MEMORY_LINES = 1500
        private const val MAX_FILE_BYTES = 512 * 1024L
    }
}
