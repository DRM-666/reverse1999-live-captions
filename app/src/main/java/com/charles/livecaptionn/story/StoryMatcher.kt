package com.charles.livecaptionn.story

import java.util.Locale

/**
 * Chapter-aware, sequential-window post-processor of final speech-recognition results.
 *
 * Preserves chronological duplicates across chapters, expands long multi-sentence entries
 * into sub-sentences for VAD pauses, and advances chapters automatically as dialogue progresses.
 */
class StoryMatcher private constructor(
    private val lines: List<Line>,
    startChapter: Int,
    private val chapterAware: Boolean,
    private val onChapterChanged: (Int) -> Unit
) {
    private data class Line(
        val chapterNumber: Int,
        val original: String,
        val normalized: String,
        val words: Set<String>
    )

    var activeChapter: Int = startChapter.coerceAtLeast(1)
        private set
    var cursor: Int = -1
        private set
    private var consecutiveMisses: Int = 0

    constructor(script: String) : this(
        lines = buildLinesFromPlainScript(script),
        startChapter = 1,
        chapterAware = false,
        onChapterChanged = {}
    )

    /** Returns null when the recognized line is too short, ambiguous, or unlike the script. */
    fun match(recognized: String): String? {
        if (lines.isEmpty()) return null
        val query = normalize(recognized)
        val queryWords = query.split(' ').filter { it.isNotEmpty() }
        if (queryWords.size < 4 || query.length < 16 || query.length > 300) return null
        val wordSet = queryWords.toSet()

        // Tier 1: Sequential forward window (after lock-on, or opening window of selected chapter).
        val windowRange = computeTier1Window()
        if (windowRange != null) {
            val hit = searchWindow(query, wordSet, windowRange)
            if (hit != null) {
                commitMatch(hit.first, allowBackjump = true)
                return lines[hit.first].original
            }
        }

        // Tier 2: Chapter-local relocation when user skips lines within the active chapter.
        if (chapterAware) {
            val chapterIndices = chapterSearchIndices()
            if (chapterIndices.isNotEmpty()) {
                val hit = searchStrict(
                    query = query,
                    wordSet = wordSet,
                    indices = chapterIndices,
                    minScore = CHAPTER_THRESHOLD,
                    minGap = CHAPTER_GAP,
                    maxLenRatio = 0.30,
                    minOverlap = 0.60
                )
                if (hit != null) {
                    commitMatch(hit.first, allowBackjump = hit.second >= 0.90)
                    return lines[hit.first].original
                }
            }
        }

        // Tier 3: Global fallback (keeps strict ambiguity & budget guards, plus anti-backjump).
        val globalHit = searchStrict(
            query = query,
            wordSet = wordSet,
            indices = lines.indices,
            minScore = GLOBAL_THRESHOLD,
            minGap = GLOBAL_GAP,
            maxLenRatio = 0.25,
            minOverlap = 0.65
        )
        if (globalHit != null) {
            val (idx, score) = globalHit
            val isFarBackjump = cursor >= 0 && idx < cursor - BACKJUMP_GUARD_LINES
            if (!isFarBackjump || (score >= STRONG_BACKJUMP_THRESHOLD && consecutiveMisses >= BACKJUMP_MISS_THRESHOLD)) {
                commitMatch(idx, allowBackjump = true)
            } else {
                // Return text without dragging cursor back to an earlier chapter's repeated phrase.
                consecutiveMisses = 0
            }
            return lines[idx].original
        }

        consecutiveMisses++
        if (consecutiveMisses >= FAIL_RESET_MISSES) {
            cursor = -1
            consecutiveMisses = 0
        }
        return null
    }

    private fun computeTier1Window(): IntRange? {
        if (cursor >= 0) {
            val start = (cursor + 1).coerceAtMost(lines.lastIndex)
            val end = (cursor + WINDOW_SIZE).coerceAtMost(lines.lastIndex)
            return if (start <= end) start..end else null
        }
        if (chapterAware) {
            val firstInChapter = lines.indexOfFirst { it.chapterNumber == activeChapter }
            if (firstInChapter >= 0) {
                val end = (firstInChapter + WINDOW_SIZE).coerceAtMost(lines.lastIndex)
                return firstInChapter..end
            }
        }
        return null
    }

    private fun chapterSearchIndices(): List<Int> {
        val current = mutableListOf<Int>()
        val next = mutableListOf<Int>()
        for (i in lines.indices) {
            when (lines[i].chapterNumber) {
                activeChapter -> current.add(i)
                activeChapter + 1 -> if (next.size < NEXT_CHAPTER_PEEK) next.add(i)
            }
        }
        if (current.isEmpty()) return emptyList()
        val inBackHalf = cursor < 0 || cursor >= current[current.size / 2]
        return if (inBackHalf) current + next else current
    }

    private fun searchWindow(query: String, wordSet: Set<String>, range: IntRange): Pair<Int, Double>? {
        var bestIdx = -1
        var bestScore = 0.0
        var compared = 0
        var editCells = 0
        for (idx in range) {
            val line = lines[idx]
            if (kotlin.math.abs(line.normalized.length - query.length) > query.length * 0.35) continue
            val overlap = wordSet.count { it in line.words }
            if (overlap.toDouble() / maxOf(wordSet.size, line.words.size) < 0.55) continue
            compared++
            editCells += query.length * line.normalized.length
            if (compared > MAX_COMPARISONS || editCells > MAX_EDIT_CELLS) return null
            val score = similarity(query, line.normalized)
            if (score >= WINDOW_THRESHOLD && score > bestScore) {
                bestScore = score
                bestIdx = idx
            }
        }
        return if (bestIdx >= 0) bestIdx to bestScore else null
    }

    private fun searchStrict(
        query: String,
        wordSet: Set<String>,
        indices: Iterable<Int>,
        minScore: Double,
        minGap: Double,
        maxLenRatio: Double,
        minOverlap: Double
    ): Pair<Int, Double>? {
        var bestIdx = -1
        var bestScore = 0.0
        var bestNorm = ""
        var secondScore = 0.0
        var compared = 0
        var editCells = 0
        for (idx in indices) {
            val line = lines[idx]
            if (kotlin.math.abs(line.normalized.length - query.length) > query.length * maxLenRatio) continue
            val overlap = wordSet.count { it in line.words }
            if (overlap.toDouble() / maxOf(wordSet.size, line.words.size) < minOverlap) continue
            compared++
            editCells += query.length * line.normalized.length
            if (compared > MAX_COMPARISONS || editCells > MAX_EDIT_CELLS) return null
            val score = similarity(query, line.normalized)
            if (score > bestScore) {
                if (bestNorm != line.normalized) secondScore = bestScore
                bestIdx = idx
                bestScore = score
                bestNorm = line.normalized
            } else if (line.normalized != bestNorm && score > secondScore) {
                secondScore = score
            }
        }
        return if (bestIdx >= 0 && bestScore >= minScore && bestScore - secondScore >= minGap) {
            bestIdx to bestScore
        } else {
            null
        }
    }

    private fun commitMatch(idx: Int, allowBackjump: Boolean) {
        consecutiveMisses = 0
        if (allowBackjump || idx >= cursor) {
            cursor = idx
            val ch = lines[idx].chapterNumber
            if (chapterAware && ch != activeChapter && ch > 0) {
                activeChapter = ch
                onChapterChanged(ch)
            }
        }
    }

    companion object {
        const val MAX_LINES = 10_000
        private const val WINDOW_SIZE = 15
        private const val NEXT_CHAPTER_PEEK = 8
        private const val WINDOW_THRESHOLD = 0.78
        private const val CHAPTER_THRESHOLD = 0.84
        private const val CHAPTER_GAP = 0.06
        private const val GLOBAL_THRESHOLD = 0.87
        private const val GLOBAL_GAP = 0.08
        private const val STRONG_BACKJUMP_THRESHOLD = 0.93
        private const val BACKJUMP_GUARD_LINES = 5
        private const val BACKJUMP_MISS_THRESHOLD = 4
        private const val FAIL_RESET_MISSES = 10
        private const val MAX_COMPARISONS = 120
        private const val MAX_EDIT_CELLS = 300_000

        fun fromDocument(
            doc: ParsedStoryDocument,
            startChapter: Int = 1,
            onChapterChanged: (Int) -> Unit = {}
        ): StoryMatcher? {
            val expanded = mutableListOf<Line>()
            for (item in doc.lines) {
                if (item.english.isBlank()) continue
                addExpandedLine(expanded, item.chapterNumber, item.english)
                for (sub in StoryDocumentParser.splitSentencesIfLong(item.english)) {
                    addExpandedLine(expanded, item.chapterNumber, sub)
                }
                if (expanded.size >= MAX_LINES) break
            }
            if (expanded.isEmpty()) return null
            val hasMultiChapters = doc.chapters.size > 1
            val validStart = if (startChapter > 0 && expanded.any { it.chapterNumber == startChapter }) {
                startChapter
            } else {
                expanded.first().chapterNumber
            }
            return StoryMatcher(
                lines = expanded.take(MAX_LINES),
                startChapter = validStart,
                chapterAware = hasMultiChapters,
                onChapterChanged = onChapterChanged
            )
        }

        private fun buildLinesFromPlainScript(script: String): List<Line> {
            val out = mutableListOf<Line>()
            for (raw in script.lineSequence()) {
                val trimmed = raw.trim()
                if (trimmed.isEmpty() || trimmed.length > 300) continue
                addExpandedLine(out, chapterNumber = 1, raw = trimmed)
                for (sub in StoryDocumentParser.splitSentencesIfLong(trimmed)) {
                    addExpandedLine(out, chapterNumber = 1, raw = sub)
                }
                if (out.size >= MAX_LINES) break
            }
            return out.take(MAX_LINES)
        }

        private fun addExpandedLine(target: MutableList<Line>, chapterNumber: Int, raw: String) {
            val clean = raw.trim()
            if (clean.isEmpty() || clean.length > 300) return
            val normalized = normalize(clean)
            val words = normalized.split(' ').filter { it.isNotEmpty() }.toSet()
            if (words.size < 4) return
            // Only skip immediate consecutive identical duplicate (not cross-chapter duplicates!).
            val prev = target.lastOrNull()
            if (prev != null && prev.chapterNumber == chapterNumber && prev.normalized == normalized) return
            target.add(Line(chapterNumber, clean, normalized, words))
        }

        private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9']+"), " ")
            .trim().replace(Regex("\\s+"), " ")

        private fun similarity(a: String, b: String): Double {
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in a.indices) {
                current[0] = i + 1
                for (j in b.indices) {
                    val cost = if (a[i] == b[j]) 0 else 1
                    current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
                }
                val swap = previous
                previous = current
                current = swap
            }
            return 1.0 - previous[b.length].toDouble() / maxOf(a.length, b.length)
        }
    }
}
