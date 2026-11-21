package com.charles.livecaptionn.story

import java.util.Locale

data class StoryMatchOutcome(
    val text: String?,
    val tier: String,
    val score: Double,
    val matchedChapter: Int = -1,
    val matchedIndex: Int = -1,
    val bestCandidateScore: Double = 0.0,
    val bestCandidatePreview: String = ""
)

/**
 * Chapter-aware, sequential-window post-processor of final speech-recognition results.
 *
 * Uses hybrid fuzzy-word + character similarity + sub-clause containment inside the
 * active chapter/window so proper-noun variations and VAD segmentation splits still match,
 * while keeping strict global fallback bounds and anti-backjump protection.
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
        val words: List<String>,
        val wordSet: Set<String>
    )

    var activeChapter: Int = startChapter.coerceAtLeast(1)
        private set
    var cursor: Int = -1
        private set
    private var consecutiveMisses: Int = 0

    val indexedLineCount: Int get() = lines.size

    constructor(script: String) : this(
        lines = buildLinesFromPlainScript(script),
        startChapter = 1,
        chapterAware = false,
        onChapterChanged = {}
    )

    /** Returns null when the recognized line is too short, ambiguous, or unlike the script. */
    fun match(recognized: String): String? = matchDetailed(recognized).text

    fun matchDetailed(recognized: String): StoryMatchOutcome {
        if (lines.isEmpty()) {
            return StoryMatchOutcome(null, "EMPTY", 0.0)
        }
        val query = normalize(recognized)
        val queryWords = query.split(' ').filter { it.isNotEmpty() }
        val minWords = if (chapterAware && cursor >= 0) 3 else 4
        val minChars = if (chapterAware && cursor >= 0) 12 else 16
        if (queryWords.size < minWords || query.length < minChars || query.length > MAX_QUERY_CHARS) {
            return StoryMatchOutcome(null, "TOO_SHORT", 0.0)
        }
        val wordSet = queryWords.toSet()

        var bestMissScore = 0.0
        var bestMissPreview = ""

        // Tier 1: Sequential forward window (after lock-on, or opening window of selected chapter).
        val windowRange = computeTier1Window()
        if (windowRange != null) {
            val res = searchWindowHybrid(query, queryWords, wordSet, windowRange)
            if (res.bestScore > bestMissScore) {
                bestMissScore = res.bestScore
                bestMissPreview = res.bestPreview
            }
            if (res.matchedIdx >= 0) {
                commitMatch(res.matchedIdx, allowBackjump = true)
                val line = lines[res.matchedIdx]
                return StoryMatchOutcome(
                    text = line.original,
                    tier = "T1_WINDOW",
                    score = res.matchedScore,
                    matchedChapter = line.chapterNumber,
                    matchedIndex = res.matchedIdx,
                    bestCandidateScore = res.matchedScore,
                    bestCandidatePreview = line.original
                )
            }
        }

        // Tier 2: Chapter-local relocation (relaxed hybrid scoring inside the known chapter).
        if (chapterAware) {
            val chapterIndices = chapterSearchIndices()
            if (chapterIndices.isNotEmpty()) {
                val res = searchChapterHybrid(query, queryWords, wordSet, chapterIndices)
                if (res.bestScore > bestMissScore) {
                    bestMissScore = res.bestScore
                    bestMissPreview = res.bestPreview
                }
                if (res.matchedIdx >= 0) {
                    commitMatch(res.matchedIdx, allowBackjump = res.matchedScore >= 0.84)
                    val line = lines[res.matchedIdx]
                    return StoryMatchOutcome(
                        text = line.original,
                        tier = "T2_CHAPTER",
                        score = res.matchedScore,
                        matchedChapter = line.chapterNumber,
                        matchedIndex = res.matchedIdx,
                        bestCandidateScore = res.matchedScore,
                        bestCandidatePreview = line.original
                    )
                }
            }
        }

        // Tier 3: Global fallback (keeps strict ambiguity & budget guards, plus anti-backjump).
        if (queryWords.size >= 4 && query.length >= 16) {
            val globalHit = searchStrict(
                query = query,
                wordSet = wordSet,
                indices = lines.indices,
                minScore = GLOBAL_THRESHOLD,
                minGap = GLOBAL_GAP,
                maxLenRatio = 0.25,
                minOverlap = 0.65
            )
            if (globalHit.bestRawScore > bestMissScore) {
                bestMissScore = globalHit.bestRawScore
                bestMissPreview = globalHit.bestRawPreview
            }
            if (globalHit.matchedIdx >= 0) {
                val idx = globalHit.matchedIdx
                val score = globalHit.matchedScore
                val isFarBackjump = cursor >= 0 && idx < cursor - BACKJUMP_GUARD_LINES
                if (!isFarBackjump || (score >= STRONG_BACKJUMP_THRESHOLD && consecutiveMisses >= BACKJUMP_MISS_THRESHOLD)) {
                    commitMatch(idx, allowBackjump = true)
                } else {
                    consecutiveMisses = 0
                }
                val line = lines[idx]
                return StoryMatchOutcome(
                    text = line.original,
                    tier = "T3_GLOBAL",
                    score = score,
                    matchedChapter = line.chapterNumber,
                    matchedIndex = idx,
                    bestCandidateScore = score,
                    bestCandidatePreview = line.original
                )
            }
        }

        consecutiveMisses++
        if (consecutiveMisses >= FAIL_RESET_MISSES) {
            cursor = -1
            consecutiveMisses = 0
        }
        return StoryMatchOutcome(
            text = null,
            tier = "MISS",
            score = bestMissScore,
            bestCandidateScore = bestMissScore,
            bestCandidatePreview = bestMissPreview.take(80)
        )
    }

    private fun computeTier1Window(): IntRange? {
        if (cursor >= 0) {
            val chapterFloor = if (chapterAware) {
                lines.indexOfFirst { it.chapterNumber == activeChapter }.coerceAtLeast(0)
            } else {
                0
            }
            val start = (cursor - 1).coerceAtLeast(chapterFloor)
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

    private data class SearchResult(
        val matchedIdx: Int = -1,
        val matchedScore: Double = 0.0,
        val bestScore: Double = 0.0,
        val bestPreview: String = ""
    )

    private fun searchWindowHybrid(
        query: String,
        queryWords: List<String>,
        wordSet: Set<String>,
        range: IntRange
    ): SearchResult {
        var bestIdx = -1
        var bestScore = 0.0
        var topCandidateScore = 0.0
        var topCandidatePreview = ""
        var compared = 0
        var editCells = 0

        for (idx in range) {
            val line = lines[idx]
            val maxLen = maxOf(query.length, line.normalized.length)
            val minLen = minOf(query.length, line.normalized.length)
            if (maxLen > minLen * 2.4) continue

            val fuzzyStats = computeFuzzyWordStats(queryWords, wordSet, line.words, line.wordSet)
            val isTightAhead = cursor >= 0 && idx in (cursor + 1)..(cursor + NEAR_CURSOR_LOOKAHEAD)
            var fallbackCharSim = 0.0
            if (fuzzyStats.f1 < 0.36 && fuzzyStats.containment < 0.65) {
                if (!isTightAhead || minLen < 10) continue
                fallbackCharSim = nearCursorCharSimilarity(query, line.normalized)
                if (fallbackCharSim < NEAR_CURSOR_CHAR_THRESHOLD) continue
            }

            compared++
            editCells += query.length * line.normalized.length
            if (compared > MAX_COMPARISONS || editCells > MAX_EDIT_CELLS) break

            val baseScore = hybridScore(query, line.normalized, fuzzyStats, minLen)
            val score = maxOf(baseScore, fallbackCharSim)
            if (score > topCandidateScore) {
                topCandidateScore = score
                topCandidatePreview = line.original
            }
            val threshold = if (isTightAhead && idx <= cursor + 4 && minLen >= 11) {
                NEXT_LINE_THRESHOLD
            } else {
                WINDOW_THRESHOLD
            }
            // Prefer moving forward over re-matching cursor - 1 unless cursor - 1 is clearly stronger.
            val effectiveScore = if (idx <= cursor) score - 0.04 else score
            if (score >= threshold && effectiveScore > bestScore) {
                bestScore = effectiveScore
                bestIdx = idx
            }
        }
        return SearchResult(
            matchedIdx = bestIdx,
            matchedScore = if (bestIdx >= 0) topCandidateScore else 0.0,
            bestScore = topCandidateScore,
            bestPreview = topCandidatePreview
        )
    }

    private fun searchChapterHybrid(
        query: String,
        queryWords: List<String>,
        wordSet: Set<String>,
        indices: List<Int>
    ): SearchResult {
        var bestIdx = -1
        var bestScore = 0.0
        var bestNorm = ""
        var secondScore = 0.0
        var topRawScore = 0.0
        var topRawPreview = ""
        var compared = 0
        var editCells = 0

        for (idx in indices) {
            val line = lines[idx]
            val maxLen = maxOf(query.length, line.normalized.length)
            val minLen = minOf(query.length, line.normalized.length)
            if (maxLen > minLen * 2.0) continue

            val fuzzyStats = computeFuzzyWordStats(queryWords, wordSet, line.words, line.wordSet)
            if (fuzzyStats.f1 < 0.45 && fuzzyStats.containment < 0.72) continue

            compared++
            editCells += query.length * line.normalized.length
            if (compared > MAX_COMPARISONS * 2 || editCells > MAX_EDIT_CELLS * 2) break

            val score = hybridScore(query, line.normalized, fuzzyStats, minLen)
            if (score > topRawScore) {
                topRawScore = score
                topRawPreview = line.original
            }
            if (score > bestScore) {
                if (bestNorm != line.normalized) secondScore = bestScore
                bestIdx = idx
                bestScore = score
                bestNorm = line.normalized
            } else if (line.normalized != bestNorm && score > secondScore) {
                secondScore = score
            }
        }
        val accepted = bestIdx >= 0 && bestScore >= CHAPTER_THRESHOLD && bestScore - secondScore >= CHAPTER_GAP
        return SearchResult(
            matchedIdx = if (accepted) bestIdx else -1,
            matchedScore = if (accepted) bestScore else 0.0,
            bestScore = topRawScore,
            bestPreview = topRawPreview
        )
    }

    private data class StrictResult(
        val matchedIdx: Int = -1,
        val matchedScore: Double = 0.0,
        val bestRawScore: Double = 0.0,
        val bestRawPreview: String = ""
    )

    private fun searchStrict(
        query: String,
        wordSet: Set<String>,
        indices: Iterable<Int>,
        minScore: Double,
        minGap: Double,
        maxLenRatio: Double,
        minOverlap: Double
    ): StrictResult {
        var bestIdx = -1
        var bestScore = 0.0
        var bestNorm = ""
        var secondScore = 0.0
        var topRawScore = 0.0
        var topRawPreview = ""
        var compared = 0
        var editCells = 0
        for (idx in indices) {
            val line = lines[idx]
            if (kotlin.math.abs(line.normalized.length - query.length) > query.length * maxLenRatio) continue
            val overlap = wordSet.count { it in line.wordSet }
            if (overlap.toDouble() / maxOf(wordSet.size, line.wordSet.size) < minOverlap) continue
            compared++
            editCells += query.length * line.normalized.length
            if (compared > MAX_COMPARISONS || editCells > MAX_EDIT_CELLS) {
                return StrictResult(bestRawScore = topRawScore, bestRawPreview = topRawPreview)
            }
            val score = similarity(query, line.normalized)
            if (score > topRawScore) {
                topRawScore = score
                topRawPreview = line.original
            }
            if (score > bestScore) {
                if (bestNorm != line.normalized) secondScore = bestScore
                bestIdx = idx
                bestScore = score
                bestNorm = line.normalized
            } else if (line.normalized != bestNorm && score > secondScore) {
                secondScore = score
            }
        }
        val accepted = bestIdx >= 0 && bestScore >= minScore && bestScore - secondScore >= minGap
        return StrictResult(
            matchedIdx = if (accepted) bestIdx else -1,
            matchedScore = if (accepted) bestScore else 0.0,
            bestRawScore = topRawScore,
            bestRawPreview = topRawPreview
        )
    }

    private data class FuzzyWordStats(val f1: Double, val containment: Double, val matchedCount: Int)

    private fun computeFuzzyWordStats(
        qWords: List<String>,
        qSet: Set<String>,
        lWords: List<String>,
        lSet: Set<String>
    ): FuzzyWordStats {
        if (qWords.isEmpty() || lWords.isEmpty()) return FuzzyWordStats(0.0, 0.0, 0)
        var matchedQ = 0
        for (qw in qSet) {
            if (qw in lSet || (qw.length >= 4 && lSet.any { lw -> lw.length >= 4 && similarity(qw, lw) >= 0.72 })) {
                matchedQ++
            }
        }
        var matchedL = 0
        for (lw in lSet) {
            if (lw in qSet || (lw.length >= 4 && qSet.any { qw -> qw.length >= 4 && similarity(lw, qw) >= 0.72 })) {
                matchedL++
            }
        }
        val precision = matchedQ.toDouble() / qSet.size
        val recall = matchedL.toDouble() / lSet.size
        val f1 = if (precision + recall > 0.0) 2.0 * precision * recall / (precision + recall) else 0.0
        val containment = maxOf(precision, recall)
        val matchedCount = minOf(matchedQ, matchedL)
        return FuzzyWordStats(f1, containment, matchedCount)
    }

    private fun nearCursorCharSimilarity(query: String, lineNorm: String): Double {
        var best = similarity(query, lineNorm)
        if (lineNorm.length > query.length + 4) {
            val words = lineNorm.split(' ').filter { it.isNotEmpty() }
            if (words.size >= 3) {
                val tailBuf = StringBuilder()
                for (i in words.lastIndex downTo 1) {
                    if (tailBuf.isNotEmpty()) tailBuf.insert(0, ' ')
                    tailBuf.insert(0, words[i])
                    if (tailBuf.length in (query.length * 0.65).toInt()..(query.length * 1.35).toInt()) {
                        best = maxOf(best, similarity(query, tailBuf.toString()))
                    }
                }
            }
        }
        return best
    }

    private fun hybridScore(
        query: String,
        lineNorm: String,
        stats: FuzzyWordStats,
        minLen: Int
    ): Double {
        val charSim = similarity(query, lineNorm)
        val blended = 0.35 * charSim + 0.65 * stats.f1
        val containBoost = if (stats.matchedCount >= 4 && minLen >= 16 && stats.containment >= 0.74) {
            0.84 * stats.containment
        } else {
            0.0
        }
        return maxOf(charSim, blended, containBoost)
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
        private const val MAX_QUERY_CHARS = 500
        private const val WINDOW_SIZE = 32
        private const val NEXT_CHAPTER_PEEK = 16
        private const val WINDOW_THRESHOLD = 0.66
        private const val NEXT_LINE_THRESHOLD = 0.58
        private const val NEAR_CURSOR_LOOKAHEAD = 6
        private const val NEAR_CURSOR_CHAR_THRESHOLD = 0.56
        private const val CHAPTER_THRESHOLD = 0.72
        private const val CHAPTER_GAP = 0.03
        private const val GLOBAL_THRESHOLD = 0.87
        private const val GLOBAL_GAP = 0.08
        private const val STRONG_BACKJUMP_THRESHOLD = 0.93
        private const val BACKJUMP_GUARD_LINES = 5
        private const val BACKJUMP_MISS_THRESHOLD = 4
        private const val FAIL_RESET_MISSES = 10
        private const val MAX_COMPARISONS = 140
        private const val MAX_EDIT_CELLS = 350_000

        fun fromDocument(
            doc: ParsedStoryDocument,
            startChapter: Int = 1,
            onChapterChanged: (Int) -> Unit = {}
        ): StoryMatcher? {
            val expanded = mutableListOf<Line>()
            val engLines = doc.lines.filter { it.english.isNotBlank() }
            for (i in engLines.indices) {
                val item = engLines[i]
                addExpandedLine(expanded, item.chapterNumber, item.english, minWordCount = 3)
                for (sub in StoryDocumentParser.splitSentencesIfLong(item.english)) {
                    addExpandedLine(expanded, item.chapterNumber, sub, minWordCount = 3)
                }
                // Merge adjacent short line pairs so VAD pauses combining 2 quick turns still match.
                if (i + 1 < engLines.size) {
                    val next = engLines[i + 1]
                    if (next.chapterNumber == item.chapterNumber && item.english.length <= 60 && next.english.length <= 160) {
                        addExpandedLine(
                            expanded,
                            item.chapterNumber,
                            "${item.english.trim()} ${next.english.trim()}",
                            minWordCount = 4
                        )
                    }
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
                if (trimmed.isEmpty() || trimmed.length > 350) continue
                addExpandedLine(out, chapterNumber = 1, raw = trimmed, minWordCount = 4)
                for (sub in StoryDocumentParser.splitSentencesIfLong(trimmed)) {
                    addExpandedLine(out, chapterNumber = 1, raw = sub, minWordCount = 4)
                }
                if (out.size >= MAX_LINES) break
            }
            return out.take(MAX_LINES)
        }

        private fun addExpandedLine(
            target: MutableList<Line>,
            chapterNumber: Int,
            raw: String,
            minWordCount: Int
        ) {
            val clean = raw.trim()
            if (clean.isEmpty() || clean.length > 350) return
            val normalized = normalize(clean)
            val words = normalized.split(' ').filter { it.isNotEmpty() }
            if (words.size < minWordCount) return
            val wordSet = words.toSet()
            val prev = target.lastOrNull()
            if (prev != null && prev.chapterNumber == chapterNumber && prev.normalized == normalized) return
            target.add(Line(chapterNumber, clean, normalized, words, wordSet))
        }

        private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9']+"), " ")
            .trim().replace(Regex("\\s+"), " ")

        private fun similarity(a: String, b: String): Double {
            if (a.isEmpty() && b.isEmpty()) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
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
