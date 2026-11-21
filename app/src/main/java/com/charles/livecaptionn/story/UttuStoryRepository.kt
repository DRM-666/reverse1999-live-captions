package com.charles.livecaptionn.story

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

enum class StoryCategory(val label: String, val path: String) {
    CN("国服最新", "/story/cn/"),
    EVENT("版本活动", "/story/event/"),
    MAIN("主线剧情", "/story/main/"),
    CHARACTER("角色剧情", "/story/character/"),
    ANECDOTE("轶事", "/story/anecdote/"),
    OTHER("其他", "/story/other/")
}

data class StoryCatalogItem(
    val title: String,
    val storyPath: String,
    val category: StoryCategory
)

data class StoryChapter(
    val number: Int,
    val title: String,
    val lines: List<String>
)

data class StoryBundle(
    val eventTitle: String,
    val sourceUrl: String,
    val languageCode: String = "chs",
    val chapters: List<StoryChapter>
)

data class UttuFetchState(
    val loading: Boolean = false,
    val bundle: StoryBundle? = null,
    val error: String? = null,
    val catalogLoading: Boolean = false,
    val selectedCategory: StoryCategory = StoryCategory.CN,
    val catalogItems: List<StoryCatalogItem> = emptyList(),
    val catalogError: String? = null
)

/**
 * Browses UTTU story catalogs (CN-latest, Events, Main Story, Characters, Anecdotes)
 * and fetches/caches chapter scripts (auto-preferring official English when available,
 * falling back to Chinese for CN-latest events like 4.1 亚特兰蒂斯谋杀案).
 */
class UttuStoryRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val cacheFile: File
        get() = File(context.filesDir, "story/uttu_story_cache.json")

    private val catalogCache = mutableMapOf<StoryCategory, List<StoryCatalogItem>>()

    private val mutableState = MutableStateFlow(UttuFetchState(bundle = loadCached()))
    val state: StateFlow<UttuFetchState> = mutableState.asStateFlow()

    fun currentBundle(): StoryBundle? = mutableState.value.bundle

    suspend fun loadCatalog(category: StoryCategory, forceRefresh: Boolean = false) = withContext(Dispatchers.IO) {
        val cached = if (!forceRefresh) catalogCache[category] else null
        if (cached != null) {
            mutableState.value = mutableState.value.copy(
                selectedCategory = category,
                catalogItems = cached,
                catalogLoading = false,
                catalogError = null
            )
            return@withContext
        }
        mutableState.value = mutableState.value.copy(
            selectedCategory = category,
            catalogLoading = true,
            catalogError = null
        )
        try {
            val html = httpGet("$BASE_ORIGIN${category.path}")
            val items = parseCatalogHtml(html, category)
            if (items.isEmpty()) {
                throw IllegalStateException("未解析到「${category.label}」目录条目")
            }
            catalogCache[category] = items
            mutableState.value = mutableState.value.copy(
                selectedCategory = category,
                catalogItems = items,
                catalogLoading = false,
                catalogError = null
            )
        } catch (t: Throwable) {
            mutableState.value = mutableState.value.copy(
                selectedCategory = category,
                catalogLoading = false,
                catalogError = t.message ?: "获取目录失败"
            )
        }
    }

    suspend fun fetchStory(storyPathOrUrl: String, preferEnglish: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val trimmed = storyPathOrUrl.trim().ifBlank { DEFAULT_ATLANTIS_URL }
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        try {
            val candidates = buildCandidateUrls(trimmed, preferEnglish)
            var loaded: StoryBundle? = null
            var lastFailure: String? = null
            for ((url, lang) in candidates) {
                try {
                    val html = httpGet(url)
                    val parsed = parseUttuHtml(html, url, lang)
                    if (parsed != null) {
                        loaded = parsed
                        break
                    }
                } catch (t: Throwable) {
                    lastFailure = t.message
                }
            }
            val bundle = loaded
                ?: throw IllegalStateException(lastFailure ?: "未在页面中找到剧情数据（#chaptersData）")
            saveCached(bundle)
            mutableState.value = mutableState.value.copy(loading = false, bundle = bundle, error = null)
            true
        } catch (t: Throwable) {
            mutableState.value = mutableState.value.copy(
                loading = false,
                error = t.message ?: "拉取剧情失败"
            )
            false
        }
    }

    suspend fun fetchFromUrl(url: String = DEFAULT_ATLANTIS_URL): Boolean =
        fetchStory(url, preferEnglish = false)

    fun clearCache() {
        runCatching { cacheFile.delete() }
        mutableState.value = mutableState.value.copy(loading = false, bundle = null, error = null)
    }

    private fun httpGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    private fun loadCached(): StoryBundle? = runCatching {
        val file = cacheFile
        if (!file.exists()) return null
        bundleAdapter.fromJson(file.readText(Charsets.UTF_8))
    }.getOrNull()

    private fun saveCached(bundle: StoryBundle) {
        val file = cacheFile
        file.parentFile?.mkdirs()
        file.writeText(bundleAdapter.toJson(bundle), Charsets.UTF_8)
    }

    private data class RawChaptersPayload(
        val chapters: List<RawChapter> = emptyList()
    )

    private data class RawChapter(
        val number: String? = null,
        val title: String? = null,
        val content: String? = null
    )

    companion object {
        const val BASE_ORIGIN = "https://uttu.merui.net"
        const val DEFAULT_ATLANTIS_URL = "https://uttu.merui.net/story/event/4_1/chs/"

        private val moshi: Moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        private val rawAdapter = moshi.adapter(RawChaptersPayload::class.java)
        private val bundleAdapter = moshi.adapter(StoryBundle::class.java)

        private val CHAPTERS_SCRIPT_REGEX = Regex(
            """<script[^>]*id=["']chaptersData["'][^>]*>([\s\S]*?)</script>""",
            RegexOption.IGNORE_CASE
        )
        private val TITLE_REGEX = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
        private val MUSIC_CUE_REGEX = Regex("""<div[^>]*class=["'][^"']*music-cue[^"']*["'][^>]*>[\s\S]*?</div>""", RegexOption.IGNORE_CASE)
        private val BR_REGEX = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        private val BLOCK_TAG_REGEX = Regex("""</?(?:div|p|li|h\d|hr)[^>]*>""", RegexOption.IGNORE_CASE)
        private val ANY_TAG_REGEX = Regex("""<[^>]+>""")

        private val CATALOG_LINK_REGEX = Regex(
            """<a\b([^>]*href=(?:"(/story/(?:main|event|character|anecdote|other)/[^"]+)"|(/story/(?:main|event|character|anecdote|other)/[^\s>]+))[^>]*)>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        )
        private val CHS_LOCALE_TITLE_REGEX = Regex(
            """(?:&quot;|")chs(?:&quot;|")\s*:\s*\{[^}]*?(?:&quot;|")title(?:&quot;|")\s*:\s*(?:&quot;|")([^&"]+)(?:&quot;|")"""
        )

        internal fun buildCandidateUrls(input: String, preferEnglish: Boolean): List<Pair<String, String>> {
            val normalized = if (input.startsWith("http://") || input.startsWith("https://")) {
                input
            } else {
                "$BASE_ORIGIN/${input.trimStart('/')}"
            }
            val clean = normalized.substringBefore("?").trimEnd('/')
            if (clean.endsWith("/en")) return listOf("$clean/" to "en")
            if (clean.endsWith("/chs")) return listOf("$clean/" to "chs")
            if (clean.endsWith("/cht")) return listOf("$clean/" to "cht")
            if (clean.endsWith("/jp") || clean.endsWith("/kr")) return listOf("$clean/" to "other")
            return if (preferEnglish) {
                listOf("$clean/en/" to "en", "$clean/chs/" to "chs")
            } else {
                listOf("$clean/chs/" to "chs", "$clean/en/" to "en")
            }
        }

        internal fun parseCatalogHtml(html: String, category: StoryCategory): List<StoryCatalogItem> {
            val seen = LinkedHashSet<String>()
            val items = mutableListOf<StoryCatalogItem>()
            for (match in CATALOG_LINK_REGEX.findAll(html)) {
                val attrs = match.groupValues[1]
                val rawHref = match.groupValues[2].ifBlank { match.groupValues[3] }
                    .substringBefore("?").trimEnd('/') + "/"
                if (!rawHref.startsWith("/story/")) continue
                if (!seen.add(rawHref)) continue

                val chsTitle = CHS_LOCALE_TITLE_REGEX.find(attrs)?.groupValues?.getOrNull(1)
                val bodyText = decodeHtmlEntities(
                    match.groupValues[4].replace(ANY_TAG_REGEX, " ").replace(Regex("\\s+"), " ").trim()
                )
                val displayTitle = when {
                    !chsTitle.isNullOrBlank() && bodyText.isNotBlank() && !bodyText.contains(chsTitle) ->
                        "$chsTitle · $bodyText"
                    !chsTitle.isNullOrBlank() -> chsTitle
                    bodyText.isNotBlank() -> bodyText
                    else -> rawHref.removePrefix("/story/").trim('/')
                }
                items.add(StoryCatalogItem(title = displayTitle, storyPath = rawHref, category = category))
            }
            return items
        }

        /** Pure parser exposed for unit tests. */
        internal fun parseUttuHtml(
            html: String,
            sourceUrl: String,
            languageCode: String = if (sourceUrl.contains("/en")) "en" else "chs"
        ): StoryBundle? {
            val match = CHAPTERS_SCRIPT_REGEX.find(html) ?: return null
            val payload = runCatching { rawAdapter.fromJson(match.groupValues[1].trim()) }.getOrNull() ?: return null
            val rawTitle = TITLE_REGEX.find(html)?.groupValues?.getOrNull(1)
                ?.substringBefore("|")?.trim()
                ?.takeIf { it.isNotBlank() } ?: "UTTU Story"

            val chapters = payload.chapters.mapIndexedNotNull { idx, item ->
                val number = item.number?.trim()?.toIntOrNull() ?: (idx + 1)
                val title = item.title?.trim()?.takeIf { it.isNotBlank() } ?: "第 $number 章"
                val lines = cleanChapterHtml(item.content.orEmpty())
                if (lines.isEmpty()) null else StoryChapter(number = number, title = title, lines = lines)
            }
            if (chapters.isEmpty()) return null
            return StoryBundle(
                eventTitle = rawTitle,
                sourceUrl = sourceUrl,
                languageCode = languageCode,
                chapters = chapters
            )
        }

        internal fun cleanChapterHtml(contentHtml: String): List<String> {
            val withoutMusic = contentHtml.replace(MUSIC_CUE_REGEX, "\n")
            val withNewlines = withoutMusic
                .replace(BR_REGEX, "\n")
                .replace(BLOCK_TAG_REGEX, "\n")
                .replace(ANY_TAG_REGEX, "")
            val decoded = decodeHtmlEntities(withNewlines)
            return decoded.lineSequence()
                .map { it.replace(Regex("[ \\t]+"), " ").trim() }
                .filter { line ->
                    line.isNotEmpty() &&
                        line != "---" &&
                        !line.startsWith("<!--") &&
                        line.length <= 400
                }
                .toList()
        }

        /** Strips `Speaker: ` prefix when feeding English UTTU lines into exact StoryMatcher. */
        internal fun stripSpeakerPrefix(line: String): String {
            val idx = line.indexOf(':')
            if (idx in 1..28) {
                val after = line.substring(idx + 1).trim()
                if (after.isNotEmpty()) return after
            }
            return line
        }

        private fun decodeHtmlEntities(input: String): String = input
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
    }
}
