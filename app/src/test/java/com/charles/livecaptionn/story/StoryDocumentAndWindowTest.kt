package com.charles.livecaptionn.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryDocumentAndWindowTest {

    @Test
    fun parseMarkdown_extractsChaptersCorrectedEnglishAndChinese() {
        val md = """
            # 《重返未来：1999》4.1 亚特兰蒂斯谋杀案 · 中英对照脚本

            ## 第 1 章
            | 时间 | 说话人 | 中文（官方意译·仅供参考） | 英文（配音原声逐字听写） |
            |---|---|---|---|
            | 0:00 | 劳伦斯 | 海洋是严苛的守密人。 | Man has always seen the ocean as an alluring vault. |
            | 1:20 | 芭万女士 | 是谋杀案，伊芙琳小姐。 | ⚠ It's a merger, miss Evelyn. → ✓ It's a murder, Miss Evelyn. |

            ## 第 2 章
            | 时间 | 说话人 | 中文（官方意译·仅供参考） | 英文（配音原声逐字听写） |
            |---|---|---|---|
            | 0:10 | 警员 | 一切看起来都很正常。 | Everything seems completely normal around here. |
        """.trimIndent()

        val doc = StoryDocumentParser.parse(md)
        assertEquals(2, doc.chapters.size)
        assertTrue(doc.hasEnglish)
        assertTrue(doc.hasChinese)
        assertEquals(3, doc.lines.size)
        assertEquals("It's a murder, Miss Evelyn.", doc.lines[1].english)
        assertEquals("芭万女士: 是谋杀案，伊芙琳小姐。", doc.lines[1].chinese)
        assertEquals(2, doc.lines[2].chapterNumber)
    }

    @Test
    fun sequentialWindow_disambiguatesRepeatedPhrasesAndAutoAdvancesChapter() {
        val md = """
            ## 第 1 章
            | 时间 | 说话人 | 中文 | 英文 |
            |---|---|---|---|
            | 0:00 | A | 第一句 | Man has always seen the ocean as an alluring vault. |
            | 0:10 | B | 重复句 | What on earth is happening inside this manor? |

            ## 第 2 章
            | 时间 | 说话人 | 中文 | 英文 |
            |---|---|---|---|
            | 0:00 | C | 第二章开场 | The detective examined the muddy footprints by the door. |
            | 0:10 | D | 重复句 | What on earth is happening inside this manor? |
            | 0:20 | D | 结尾 | Please remain calm until the inspection finishes. |
        """.trimIndent()

        val doc = StoryDocumentParser.parse(md)
        val chapterChanges = mutableListOf<Int>()
        val matcher = StoryMatcher.fromDocument(doc, startChapter = 1) { chapterChanges.add(it) }!!

        // Lock onto line 0 in Chapter 1
        assertEquals(
            "Man has always seen the ocean as an alluring vault.",
            matcher.match("man has always seen the ocean as a vault")
        )
        assertEquals(1, matcher.activeChapter)

        // Cross into Chapter 2 naturally via forward window
        assertEquals(
            "The detective examined the muddy footprints by the door.",
            matcher.match("detective examined the muddy footprints by the door")
        )
        assertEquals(2, matcher.activeChapter)
        assertEquals(listOf(2), chapterChanges)

        // Repeated phrase in Chapter 2 should stay in Chapter 2 (no backjump to Ch 1)
        assertEquals(
            "What on earth is happening inside this manor?",
            matcher.match("what on earth is happning inside this manor")
        )
        assertEquals(2, matcher.activeChapter)
        assertTrue(matcher.cursor >= 2)
    }

    @Test
    fun longMultiSentenceLine_expandsSubSentencesForVadMatching() {
        val longLine = "I shall not pretend it was an easy voyage across the rough Atlantic waters. Even now the newspaper reports remain vivid in my memory."
        val doc = StoryDocumentParser.parse(longLine)
        val matcher = StoryMatcher.fromDocument(doc)!!

        // Speaking only the second sentence should still match cleanly
        val matched = matcher.match("Even now the newspaper reports remain vivid in my memory")
        assertNotNull(matched)
        assertTrue(matched!!.contains("newspaper reports remain vivid"))
    }

    @Test
    fun antiBackjump_preventsGlobalMatchFromDraggingCursorBackward() {
        val sentences = listOf(
            "Good morning everyone at the Atlantis exhibition hall.",
            "Captain Sunderland piloted the submarine into deep waters.",
            "The Foundation sent Miss Bovan to inspect every relic.",
            "Mister Bloom checked the clock in the quiet hallway.",
            "Abigail showed her new watercolor painting in the study.",
            "Rain tapped steadily against the tall leaded glass windows.",
            "Inspector Evelyn questioned the staff near the fireplace.",
            "Muddy footprints trailed across the Persian carpet toward stairs.",
            "Nobody noticed the missing bronze key until midnight struck.",
            "A sudden thunderclap rattled the porcelain cups on silver trays.",
            "Lawrence defended his expedition plans before the anxious guests.",
            "The ancient Rusalki vessel gleamed softly beneath the gaslight.",
            "Maria remembered hunting rabbits inside the snowy German forest.",
            "Footsteps echoed from the empty gallery above the ballroom.",
            "At last the truth emerged from beneath layers of deception."
        )
        val doc = StoryDocumentParser.parse(sentences.joinToString("\n"))
        val matcher = StoryMatcher.fromDocument(doc)!!

        // Jump cursor to line 12 (past index 5)
        assertNotNull(matcher.match("The ancient Rusalki vessel gleamed softly beneath the gaslight"))
        val cursorAt12 = matcher.cursor
        assertTrue(cursorAt12 > 5)

        // Match line 1 globally (more than 5 lines behind) -> returns line 1, but does NOT pull cursor back to 0
        assertNotNull(matcher.match("Good morning everyone at the Atlantis exhibition hall"))
        assertEquals(cursorAt12, matcher.cursor)
    }

    @Test
    fun consecutiveMisses_resetsCursorAfterLimit() {
        val script = "The detective examined the muddy footprints.\nPlease remain calm until inspection finishes."
        val matcher = StoryMatcher(script)
        assertNotNull(matcher.match("The detective examined the muddy footprints"))
        assertEquals(0, matcher.cursor)

        repeat(10) {
            assertNull(matcher.match("Completely unrelated spoken words number $it here"))
        }
        assertEquals(-1, matcher.cursor)
    }

    @Test
    fun contextTracker_autoAdvancesAcrossChapters() {
        val md = """
            ## 第 1 章
            | 时间 | 说话人 | 中文 | 英文 |
            |---|---|---|---|
            | 0:00 | 劳伦斯 | 第一章第一句 | First sentence in chapter one. |
            | 0:05 | 劳伦斯 | 第一章第二句 | Second sentence in chapter one. |

            ## 第 2 章
            | 时间 | 说话人 | 中文 | 英文 |
            |---|---|---|---|
            | 0:00 | 玛利亚 | 第二章第一句 | First sentence in chapter two. |
        """.trimIndent()
        val doc = StoryDocumentParser.parse(md)
        val changed = mutableListOf<Int>()
        val tracker = StoryContextTracker.fromSources(null, doc, activeChapter = 1) { changed.add(it) }!!

        val window = tracker.currentWindow(before = 1, ahead = 4)
        assertEquals(3, window.size) // spans across Ch 1 and Ch 2!
        tracker.advanceIfMatched(3) // matched line 3 -> Ch 2
        assertEquals(2, tracker.activeChapter)
        assertEquals(listOf(2), changed)
    }

    @Test
    fun hybridMatcher_handlesProperNounSlipClippedPrefixAndAdjacentMerge() {
        val md = """
            ## 第 1 章
            | 时间 | 说话人 | 中文 | 英文 |
            |---|---|---|---|
            | 0:00 | 劳伦斯 | 幸会 | Madame Bovan, it is truly an honor to make your acquaintance. |
            | 0:10 | ？？？ | 未必 | Not necessarily. |
            | 0:12 | ？？？ | 看盖子 | See that lid on the top. |

            ## 第 2 章
            | 时间 | 说话人 | 中文 | 英文 |
            |---|---|---|---|
            | 0:00 | 警员 | 另一章 | The carriage arrived before dawn at the gate. |
        """.trimIndent()
        val doc = StoryDocumentParser.parse(md)
        val matcher = StoryMatcher.fromDocument(doc, startChapter = 1)!!

        // Clipped prefix + misspelled proper noun in Tier 1/2
        val first = matcher.matchDetailed("bowen it is truly honor to make your acquaintance")
        assertNotNull(first.text)
        assertTrue(first.text!!.contains("make your acquaintance"))

        // Adjacent short-line merge ("Not necessarily." + "See that lid on the top.")
        val merged = matcher.matchDetailed("not necessarily see that lid on the top")
        assertNotNull(merged.text)
        assertTrue(merged.text!!.contains("See that lid on the top"))
    }
}
