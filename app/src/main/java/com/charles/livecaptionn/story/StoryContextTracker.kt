package com.charles.livecaptionn.story

/**
 * Maintains a sequential sliding window over Chinese story lines across chapters
 * so LLM context correction receives only the ~10 nearest lines and automatically
 * advances (and persists) the active chapter when dialogue crosses a boundary.
 */
class StoryContextTracker private constructor(
    private val entries: List<IndexedEntry>,
    startChapter: Int,
    private val onChapterChanged: (Int) -> Unit
) {
    private data class IndexedEntry(val chapterNumber: Int, val text: String)

    constructor(lines: List<String>) : this(
        entries = lines.map { IndexedEntry(chapterNumber = 1, text = it) },
        startChapter = 1,
        onChapterChanged = {}
    )

    var activeChapter: Int = startChapter.coerceAtLeast(1)
        private set

    var cursorIndex: Int = entries.indexOfFirst { it.chapterNumber == activeChapter }.coerceAtLeast(0)
        private set

    val isEmpty: Boolean get() = entries.isEmpty()

    /** Returns `(1-based index -> line)` pairs around [cursorIndex], spanning cleanly across chapter ends. */
    fun currentWindow(before: Int = 2, ahead: Int = 7): List<Pair<Int, String>> {
        if (entries.isEmpty()) return emptyList()
        val start = (cursorIndex - before).coerceAtLeast(0)
        val end = (cursorIndex + ahead).coerceAtMost(entries.lastIndex)
        return (start..end).map { idx -> (idx + 1) to entries[idx].text }
    }

    /** Moves the cursor just past [matchedOneBasedLine] and auto-advances chapter when crossed. */
    fun advanceIfMatched(matchedOneBasedLine: Int?) {
        if (matchedOneBasedLine == null || matchedOneBasedLine <= 0) return
        val zeroBased = matchedOneBasedLine - 1
        if (zeroBased in entries.indices && zeroBased >= cursorIndex - 2) {
            val matchedChapter = entries[zeroBased].chapterNumber
            cursorIndex = (zeroBased + 1).coerceAtMost(entries.lastIndex)
            val nextChapter = entries[cursorIndex].chapterNumber
            val effectiveChapter = maxOf(matchedChapter, nextChapter)
            if (effectiveChapter > activeChapter) {
                activeChapter = effectiveChapter
                onChapterChanged(effectiveChapter)
            }
        }
    }

    fun reset() {
        cursorIndex = entries.indexOfFirst { it.chapterNumber == activeChapter }.coerceAtLeast(0)
    }

    companion object {
        private val CJK_REGEX = Regex("[\\u4e00-\\u9fff]")

        fun containsChinese(text: String): Boolean = CJK_REGEX.containsMatchIn(text)

        fun fromSources(
            bundle: StoryBundle?,
            parsedDoc: ParsedStoryDocument?,
            activeChapter: Int,
            onChapterChanged: (Int) -> Unit = {}
        ): StoryContextTracker? {
            if (parsedDoc != null && parsedDoc.hasChinese) {
                val entries = parsedDoc.lines
                    .filter { !it.chinese.isNullOrBlank() }
                    .map { IndexedEntry(it.chapterNumber, it.chinese!!) }
                if (entries.isNotEmpty()) {
                    val start = if (activeChapter > 0) activeChapter else entries.first().chapterNumber
                    return StoryContextTracker(entries, start, onChapterChanged)
                }
            }
            if (bundle != null && bundle.languageCode != "en" && bundle.chapters.isNotEmpty()) {
                val entries = bundle.chapters
                    .sortedBy { it.number }
                    .flatMap { ch -> ch.lines.map { line -> IndexedEntry(ch.number, line) } }
                if (entries.isNotEmpty()) {
                    val start = if (activeChapter > 0) activeChapter else entries.first().chapterNumber
                    return StoryContextTracker(entries, start, onChapterChanged)
                }
            }
            return null
        }

        fun fromBundleAndScript(
            bundle: StoryBundle?,
            activeChapter: Int,
            manualScript: String?
        ): StoryContextTracker? = fromSources(
            bundle = bundle,
            parsedDoc = manualScript?.let(StoryDocumentParser::parse),
            activeChapter = activeChapter
        )
    }
}
