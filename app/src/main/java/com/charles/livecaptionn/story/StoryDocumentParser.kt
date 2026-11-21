package com.charles.livecaptionn.story

/**
 * Parses imported story scripts in either:
 * - Bilingual Markdown table format (e.g. `story_bilingual_reference.md` with `## 第 X 章` and `| time | speaker | zh | en |`)
 * - Chapter-marked or flat UTF-8 plain text (e.g. `story_en_for_app.txt`).
 */
data class ParsedScriptLine(
    val chapterNumber: Int,
    val chapterTitle: String,
    val english: String,
    val chinese: String? = null
)

data class ParsedStoryDocument(
    val lines: List<ParsedScriptLine>,
    val chapters: List<StoryChapter>,
    val hasEnglish: Boolean,
    val hasChinese: Boolean
)

object StoryDocumentParser {

    private val CHAPTER_HEADING_REGEX = Regex(
        """^#{1,3}\s*(?:第\s*(\d+)\s*章|Chapter\s+(\d+))(?:\s*[：:·\-]?\s*(.*))?$""",
        RegexOption.IGNORE_CASE
    )
    private val SEPARATOR_ROW_REGEX = Regex("""^\|[\s:\-|]+\|$""")
    private val CJK_REGEX = Regex("""[\u4e00-\u9fff]""")
    private val SENTENCE_SPLIT_REGEX = Regex("""(?<=[.!?])\s+""")

    fun parse(raw: String): ParsedStoryDocument {
        val text = raw.removePrefix("\uFEFF")
        val rawLines = text.lineSequence().toList()
        val looksLikeBilingualMd = rawLines.any { line ->
            val t = line.trim()
            t.startsWith("|") && t.endsWith("|") && t.count { it == '|' } >= 4
        }
        return if (looksLikeBilingualMd) parseMarkdown(rawLines) else parsePlainText(rawLines)
    }

    private fun parseMarkdown(rawLines: List<String>): ParsedStoryDocument {
        var currentChapterNum = 1
        var currentChapterTitle = "第 1 章"
        val items = mutableListOf<ParsedScriptLine>()
        val chapterTitles = linkedMapOf<Int, String>()

        for (rawLine in rawLines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue

            val headingMatch = CHAPTER_HEADING_REGEX.matchEntire(trimmed)
            if (headingMatch != null) {
                val num = (headingMatch.groupValues[1].ifBlank { headingMatch.groupValues[2] }).toIntOrNull()
                    ?: (currentChapterNum + 1)
                val extra = headingMatch.groupValues[3].trim()
                currentChapterNum = num
                currentChapterTitle = if (extra.isNotBlank()) extra else "第 $num 章"
                chapterTitles.putIfAbsent(num, currentChapterTitle)
                continue
            }

            if (!trimmed.startsWith("|") || !trimmed.endsWith("|")) continue
            if (SEPARATOR_ROW_REGEX.matches(trimmed)) continue

            val cells = trimmed.removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
            if (cells.size < 4) continue
            // Skip table header row
            if (cells[0] == "时间" || cells[1] == "说话人") continue

            val speaker = cells[1]
            val zhRaw = cells[2]
            val enRaw = cells[3]
            val enClean = extractCorrectedEnglish(enRaw)
            val zhClean = if (zhRaw.isNotBlank()) {
                if (speaker.isNotBlank()) "$speaker: $zhRaw" else zhRaw
            } else {
                null
            }

            if (enClean.isNotBlank() || !zhClean.isNullOrBlank()) {
                chapterTitles.putIfAbsent(currentChapterNum, currentChapterTitle)
                items.add(
                    ParsedScriptLine(
                        chapterNumber = currentChapterNum,
                        chapterTitle = currentChapterTitle,
                        english = enClean,
                        chinese = zhClean
                    )
                )
            }
        }
        return finalizeDocument(items, chapterTitles)
    }

    private fun parsePlainText(rawLines: List<String>): ParsedStoryDocument {
        var currentChapterNum = 1
        var currentChapterTitle = "第 1 章"
        val items = mutableListOf<ParsedScriptLine>()
        val chapterTitles = linkedMapOf<Int, String>()

        for (rawLine in rawLines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue

            val headingMatch = CHAPTER_HEADING_REGEX.matchEntire(trimmed)
            if (headingMatch != null) {
                val num = (headingMatch.groupValues[1].ifBlank { headingMatch.groupValues[2] }).toIntOrNull()
                    ?: (currentChapterNum + 1)
                val extra = headingMatch.groupValues[3].trim()
                currentChapterNum = num
                currentChapterTitle = if (extra.isNotBlank()) extra else "第 $num 章"
                chapterTitles.putIfAbsent(num, currentChapterTitle)
                continue
            }

            chapterTitles.putIfAbsent(currentChapterNum, currentChapterTitle)
            if (CJK_REGEX.containsMatchIn(trimmed)) {
                items.add(
                    ParsedScriptLine(
                        chapterNumber = currentChapterNum,
                        chapterTitle = currentChapterTitle,
                        english = "",
                        chinese = trimmed
                    )
                )
            } else {
                items.add(
                    ParsedScriptLine(
                        chapterNumber = currentChapterNum,
                        chapterTitle = currentChapterTitle,
                        english = trimmed,
                        chinese = null
                    )
                )
            }
        }
        return finalizeDocument(items, chapterTitles)
    }

    private fun finalizeDocument(
        items: List<ParsedScriptLine>,
        chapterTitles: Map<Int, String>
    ): ParsedStoryDocument {
        val grouped = items.groupBy { it.chapterNumber }
        val chapters = grouped.entries.sortedBy { it.key }.map { (num, list) ->
            val title = chapterTitles[num] ?: "第 $num 章"
            val displayLines = list.mapNotNull { it.chinese?.takeIf(String::isNotBlank) ?: it.english.takeIf(String::isNotBlank) }
            StoryChapter(number = num, title = title, lines = displayLines)
        }
        return ParsedStoryDocument(
            lines = items,
            chapters = chapters,
            hasEnglish = items.any { it.english.isNotBlank() },
            hasChinese = items.any { !it.chinese.isNullOrBlank() }
        )
    }

    /** Extracts the post-correction `✓` sentence when `→ ✓` is present in bilingual MD. */
    internal fun extractCorrectedEnglish(raw: String): String {
        val trimmed = raw.trim()
        val arrowIdx = trimmed.lastIndexOf("→ ✓")
        val base = if (arrowIdx >= 0) {
            trimmed.substring(arrowIdx + "→ ✓".length).trim()
        } else {
            val checkIdx = trimmed.lastIndexOf('✓')
            if (checkIdx >= 0 && trimmed.contains('⚠')) {
                trimmed.substring(checkIdx + 1).trim()
            } else {
                trimmed.replace("⚠", "").trim()
            }
        }
        return base.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Expands multi-sentence English lines (> 45 chars) into additional single-sentence
     * candidates right next to the full line so mid-turn VAD pauses still match cleanly.
     */
    internal fun splitSentencesIfLong(english: String): List<String> {
        val clean = english.trim()
        if (clean.length <= 45) return emptyList()
        val parts = clean.split(SENTENCE_SPLIT_REGEX).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return emptyList()
        return parts
    }
}
