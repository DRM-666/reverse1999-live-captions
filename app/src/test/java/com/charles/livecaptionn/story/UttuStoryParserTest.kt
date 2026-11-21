package com.charles.livecaptionn.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UttuStoryParserTest {

    @Test
    fun parseUttuHtml_extractsChaptersAndStripsTags() {
        val html = """
            <html><head><title>亚特兰蒂斯谋杀案 | Reverse: 1999</title></head><body>
            <script type="text/plain" id="chaptersData">{
              "chapters": [
                {
                  "number": "1",
                  "title": "第一发现人",
                  "content": "<div class=\"location-label\">宅邸·展厅</div><b data-voice=\"v1\">劳伦斯:</b> 海洋，一位严苛的守密人。<br><b data-voice=\"v2\">维尔汀:</b> &quot;请等一下。&quot;<br>"
                },
                {
                  "number": "2",
                  "title": "鞋印的研究",
                  "content": "十四行诗: 我明白了。"
                }
              ]
            }</script>
            </body></html>
        """.trimIndent()

        val bundle = UttuStoryRepository.parseUttuHtml(html, UttuStoryRepository.DEFAULT_ATLANTIS_URL)
        assertNotNull(bundle)
        assertEquals("亚特兰蒂斯谋杀案", bundle!!.eventTitle)
        assertEquals(2, bundle.chapters.size)
        assertEquals("第一发现人", bundle.chapters[0].title)
        assertEquals(
            listOf("宅邸·展厅", "劳伦斯: 海洋，一位严苛的守密人。", "维尔汀: \"请等一下。\""),
            bundle.chapters[0].lines
        )
        assertEquals(listOf("十四行诗: 我明白了。"), bundle.chapters[1].lines)
    }

    @Test
    fun parseUttuHtml_missingChaptersScript_returnsNull() {
        assertNull(UttuStoryRepository.parseUttuHtml("<html><body>no data</body></html>", "https://example.com"))
    }

    @Test
    fun storyContextTracker_advancesSlidingWindow() {
        val tracker = StoryContextTracker((1..15).map { "角色: 台词 $it" })
        val first = tracker.currentWindow(before = 2, ahead = 4)
        assertEquals(1, first.first().first)
        assertEquals(5, first.last().first)

        tracker.advanceIfMatched(4)
        assertEquals(4, tracker.cursorIndex)
        val next = tracker.currentWindow(before = 1, ahead = 3)
        assertEquals(4, next.first().first)
        assertTrue(next.any { it.first == 6 })
    }

    @Test
    fun llmParser_parsesWrappedJsonAndFallsBackOnGarbage() {
        val ok = LlmCaptionCorrector.parseModelOutput(
            "```json\n{\"en\":\"The sea keeps its secrets.\",\"line\":3}\n```",
            "The see keeps its secret."
        )
        assertTrue(ok.corrected)
        assertEquals("The sea keeps its secrets.", ok.text)
        assertEquals(3, ok.matchedLine)

        val bad = LlmCaptionCorrector.parseModelOutput("not json", "raw text")
        assertEquals("raw text", bad.text)
        assertEquals(false, bad.corrected)
    }

    @Test
    fun whisperCleaner_stripsHallucinations() {
        assertEquals("", com.charles.livecaptionn.speech.LocalWhisperSttClient.cleanWhisperText("[Music]"))
        assertEquals("", com.charles.livecaptionn.speech.LocalWhisperSttClient.cleanWhisperText("(laughter)"))
        assertEquals("", com.charles.livecaptionn.speech.LocalWhisperSttClient.cleanWhisperText("Thank you."))
        assertEquals(
            "The ocean is a strict keeper of secrets.",
            com.charles.livecaptionn.speech.LocalWhisperSttClient.cleanWhisperText("[Music] The ocean is a strict keeper of secrets.")
        )
    }

    @Test
    fun parseCatalogHtml_extractsChineseTitleAndDedupes() {
        val html = """
            <a href=/story/event/4_1/ data-locale-titles="{&quot;chs&quot;:{&quot;title&quot;:&quot;亚特兰蒂斯谋杀案&quot;}}">
              <h2>A Death at Romenel Manor</h2>
            </a>
            <a href="/story/main/chapter-1/" data-locale-titles="{&quot;chs&quot;:{&quot;title&quot;:&quot;在我们的时代里&quot;}}">In Our Time</a>
            <a href="/story/main/chapter-1/">Duplicate</a>
        """.trimIndent()
        val items = UttuStoryRepository.parseCatalogHtml(html, StoryCategory.CN)
        assertEquals(2, items.size)
        assertEquals("/story/event/4_1/", items[0].storyPath)
        assertTrue(items[0].title.contains("亚特兰蒂斯谋杀案"))
        assertEquals("/story/main/chapter-1/", items[1].storyPath)
        assertTrue(items[1].title.contains("在我们的时代里"))
    }

    @Test
    fun buildCandidateUrls_prefersEnglishThenFallsBackToChinese() {
        val preferEn = UttuStoryRepository.buildCandidateUrls("/story/main/chapter-5/", preferEnglish = true)
        assertEquals("https://uttu.merui.net/story/main/chapter-5/en/", preferEn[0].first)
        assertEquals("en", preferEn[0].second)
        assertEquals("https://uttu.merui.net/story/main/chapter-5/chs/", preferEn[1].first)
        assertEquals("chs", preferEn[1].second)
    }

    @Test
    fun stripSpeakerPrefix_stripsEnglishCharacterLabel() {
        assertEquals("Good morning.", UttuStoryRepository.stripSpeakerPrefix("Vertin: Good morning."))
        assertEquals("No speaker here", UttuStoryRepository.stripSpeakerPrefix("No speaker here"))
    }
}
