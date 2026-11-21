package com.charles.livecaptionn.ui.l10n

import java.util.Locale

/**
 * A runtime-localized string table for the whole app UI.
 *
 * Each map key is the canonical English source string; when a key has no
 * translation yet the English text is returned, so the UI degrades gracefully
 * while ML Kit downloads a model / translates in the background — the UI never
 * renders blank or half-localized text.
 */
class UiStrings(
    private val map: Map<String, String> = emptyMap(),
    val isZh: Boolean = false
) {

    operator fun get(key: String): String = map[key] ?: key

    val isLocalized: Boolean get() = map.isNotEmpty()

    /** Instant bilingual helper avoiding heavy ML Kit translation for custom Chinese UI copy. */
    fun pick(zh: String, en: String): String = if (isZh) zh else en

    /** Translator for map values with an empty-translation guard. */
    fun translated(key: String): String? = map[key]

    /**
     * Localizes an English format template while preserving its placeholders.
     * Falls back to the English template if the translation is missing or
     * dropped/mangled the %-specifier (which would otherwise crash or corrupt
     * String.format output).
     */
    fun format(template: String, vararg args: Any?): String {
        val localized = map[template] ?: template
        if (localized != template &&
            UiStringCatalog.placeholderCount(localized) != UiStringCatalog.placeholderCount(template)
        ) {
            return typedFormat(template, args) ?: template
        }
        return typedFormat(localized, args) ?: typedFormat(template, args) ?: template
    }

    private fun typedFormat(template: String, args: Array<out Any?>): String? = try {
        String.format(Locale.getDefault(), template, *args)
    } catch (_: Throwable) {
        null
    }

    companion object {
        val EMPTY = UiStrings(emptyMap())
    }
}

/**
 * Single source of truth for every user-facing English string the app can
 * render. These are translated on-device by ML Kit into the chosen UI language
 * and then served through [UiStrings]; English is always the fallback.
 *
 * Keys (and format templates) themselves are the English source text, so the
 * catalog stays in lock-step with the Compose screens without a separate
 * id<->text mapping to keep maintained.
 */
object UiStringCatalog {

    /** Short display name of the app itself, used directly as a catalog key. */
    const val appName = "Live CaptionN"

    /** Every unique English string / template the UI may pass through `t[...]`. */
    val ALL: List<String> = listOf(
        // App / top bar
        appName,
        "Start",
        "Stop",
        "Ready",
        "Status",
        "Idle",
        "Listening",
        "Processing",
        "Paused",
        "Error",
        "History",

        // Permissions
        "Permissions",
        "Microphone",
        "Overlay",
        "Grant",

        // Audio source
        "Audio Source",
        "System Audio",
        "Captures audio from videos and apps that allow playback capture. If Android asks, choose Share entire screen.",
        "Uses the device microphone.",
        "Transcription engine",
        "Remote Whisper",
        "Android built-in",
        "Local Vosk",
        "Runs transcription fully on this device.",
        "language",
        "languages",
        "installed.",
        "Tip: for noticeably stronger transcription, tap Manage models and install a LARGE server-grade model for the languages you use — 80 MB to 2 GB each, fully offline after download.",
        "Manage on-device models",
        "Sends captured audio to the configured Whisper ASR endpoint.",
        "Uses Android's built-in recognizer. Available locales depend on what's installed on this device.",

        // Language pickers
        "Languages",
        "Source (spoken)",
        "No languages available",
        "Pick a language",
        "Target (translation)",
        "Swap",
        "On-device transcription: source language is limited to the Vosk models installed on this phone.",
        "Download more languages",
        "On-device translation via Google ML Kit — %d languages, fully offline after first download.",
        "Could not reach LibreTranslate at %s — showing a fallback list. Save a valid URL to load the full language set from your server.",
        "Loading languages from LibreTranslate…",
        "%d languages reported by LibreTranslate. Add more to your server by installing extra Argos Translate packages.",
        "Auto-detect source",
        "Close",
        "Choose language",
        "Search",
        "Requires Pro",
        "No languages match \"%s\".",

        // Vosk model management
        "On-device speech models",
        "Vosk models run fully offline on this phone. Downloading a model is a one-time step; uninstall any time to free storage. For the strongest transcription, pick the LARGE server-grade model for the languages you use most.",
        "Installed",
        "Large · server-grade accuracy (Pro)",
        "Full Vosk server models with the lowest error rates. Each one is 80 MB to 2 GB but runs entirely on-device after the one-time download. This is the strongest transcription option for every language.",
        "Small · fast & light",
        "Compact ~40 MB models for quick installs or low-storage phones. Accuracy is noticeably lower than the large variants.",
        "Models are fetched from alphacephei.com/vosk/models over HTTPS.",
        "Languages without a model here (for example Indonesian) may still work from the microphone with the “Android built-in” engine, if your phone's speech service supports that language. Android may need to download the language first.",
        "Delete",
        "Get",
        "LARGE",
        "bundled",
        "Pro",
        "Pro feature",
        "Upgrade to Pro to unlock this feature. The upgrade removes ads and adds high-accuracy models, more languages, profiles, and advanced caption tools.",
        "Not now",
        "Opening Google Play…",
        "Download failed (HTTP 404): this model is no longer published",
        "Download failed: no internet connection",
        "Download failed: the connection timed out",
        "There is no on-device speech model for %s here. Try the “Android built-in” engine (uses your phone's speech service, microphone only, if it supports this language) or pick another language.",
        "Downloading %s model…",
        "Download %s model (~%d MB)",
        "Retry %s model download (~%d MB)",

        // Overlay settings
        "Text size: %dsp",
        "Opacity: %d%%",
        "Show original text",
        "Overlay theme (Pro)",
        "Overlay font (Pro)",

        // Server / translation engine
        "Translation engine",
        "On-device (ML Kit)",
        "LibreTranslate",
        "Google ML Kit translates fully on this device — no server required. The first time you use a language pair, a ~30 MB model downloads and is then cached offline forever.",
        "LibreTranslate URL",
        "Example: %s",
        "The app fetches /languages to populate the dropdowns.",
        "Save",
        "Refresh",
        "Language fetch error: %s",
        "Speech-to-Text URL (Whisper)",
        "Save STT URL",

        // Update banner
        "Update available",
        "Download APK",
        "View release",
        "Later",
        "You installed this app from the Play Store. The GitHub build is a pre-release and may be unstable. Installing it will also disable Play Store auto-updates for this app.",

        // History
        "Transcript History",
        "No matching transcripts",
        "No transcripts yet",
        "Try a different search term.",
        "Transcripts will appear here as you use captioning.",
        "Search transcripts…",
        "Clear search",
        "Clear",
        "Copy",
        "Back",
        "Delete entry",
        "Delete this transcript entry?\n\n\"%s\"",
        "Cancel",

        // Premium
        "Upgrade",
        "Ad-Free removes all ads. Pro unlocks larger on-device speech models, more translation languages, and extra overlay themes.",
        "Go Ad-Free",
        "Ad-Free active",
        "Go Pro",
        "Pro active",
        "Manage subscription",
        "Email used at checkout",
        "Restore purchase",
        "Refresh status",

        // Feedback / support
        "Support & Feedback",
        "GitHub feedback is not configured.",
        "Report a Problem",
        "Submitted Reports",
        "Open",
        "Closed",
        "Your report will be submitted to this app's public GitHub issue tracker. Do not include passwords, private keys, medical information, financial information, or anything you do not want visible to the repository maintainers.",
        "Title / Subject *",
        "Description *",
        "Include phone/app diagnostics",
        "Diagnostics include app version, device model, Android version, storage/memory info. No personal data is collected.",
        "Name (optional)",
        "Email (optional)",
        "Image attached",
        "Change image",
        "Attach screenshot / image",
        "Configuration error.",
        "Created: %s",
        "Open in browser",
        "Comments (%d)",
        "Post a Reply",
        "Your reply",
        "Attach",
        "Send",
        "Submit",
        "Dismiss",
        "Report submitted successfully!",
        "Remove",

        // Overlay window
        "Pause captioning",
        "Minimize overlay",
        "Close overlay",
        "Resume captioning",

        // Notification / foreground service
        "Captioning in progress",
        "Listening and translating speech.",
        "Pause/Resume",
        "Live Captioning",
        "Shows when live captioning is active.",

        // Update notifier
        "LiveCaptionN update available",
        "%s is ready to install",
        "Download",
        "App updates",
        "Notifies you when a new version of LiveCaptionN is available on GitHub",

        // Interface language feature
        "Interface language",
        "Choose interface language",
        "English",
        "Machine-translated interface",
        "On-device ML Kit translation may be inaccurate.",
        "UI text is translated on-device by Google ML Kit, not by human translators, and may be inaccurate.",
        "Downloading translation model…",
        "Translating the interface…",
        "Translating the interface… (%d/%d)",
        "Translation failed. Tap to retry.",
        "Switch to English",
        "Switch",
        "Retry",
        "Current",

        // Onboarding
        "Welcome",
        "Live captions & translation on your device",
        "Choose your interface language",
        "You can change this later in Settings.",
        "Get started"
    ).distinct()

    private val PLACEHOLDER = Regex("%\\d*[_a-zA-Z%]")

    fun placeholderCount(text: String): Int = PLACEHOLDER.findAll(text).count()

    /**
     * Whether a string carries enough meaning on its own to be worth
     * translating. Skips blank text, punctuation-only tokens, and stray
     * single characters (e.g. the "sp" unit suffix), which ML Kit handles
     * poorly and which would degrade translation quality.
     */
    fun isSegmentTranslatable(segment: String): Boolean {
        val trimmed = segment.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.length < 2) return false
        if (trimmed.all { it.isWhitespace() || !it.isLetter() }) return false
        return true
    }

    /**
     * Hand-crafted Simplified Chinese dictionary for instant, zero-network localization
     * of every core UI label without needing Google ML Kit model downloads.
     */
    val BUILTIN_ZH_CN: Map<String, String> = mapOf(
        appName to "剧情英文字幕",
        "Start" to "启动字幕",
        "Stop" to "停止",
        "Ready" to "就绪",
        "Status" to "状态",
        "Idle" to "空闲",
        "Listening" to "监听中",
        "Processing" to "识别中",
        "Paused" to "已暂停",
        "Error" to "异常",
        "History" to "历史记录",

        "Permissions" to "必要权限",
        "Microphone" to "麦克风",
        "Overlay" to "悬浮窗",
        "Grant" to "去授权",
        "Grant overlay/audio permissions and install the selected speech model before starting." to "请先授予悬浮窗/音频权限并准备好语音识别模型后再启动。",

        "Audio Source" to "音频来源与识别引擎",
        "System Audio" to "系统内录",
        "Captures audio from videos and apps that allow playback capture. If Android asks, choose Share entire screen." to "直接采集游戏、视频等应用内音频（系统弹窗询问时请选择“共享整个屏幕”）。",
        "Uses the device microphone." to "使用手机麦克风拾音。",
        "Transcription engine" to "语音识别引擎",
        "Remote Whisper" to "远程 Whisper",
        "Android built-in" to "系统内置识别",
        "Local Vosk" to "本地 Vosk",
        "Runs transcription fully on this device." to "完全在手机本地离线识别语音。",
        "language" to "种语言",
        "languages" to "种语言",
        "installed." to "已安装。",
        "Tip: for noticeably stronger transcription, tap Manage models and install a LARGE server-grade model for the languages you use — 80 MB to 2 GB each, fully offline after download." to "提示：若用 Vosk 识别较难口音，可在模型管理中下载 LARGE 大模型（80 MB–2 GB，仅需下载一次）。",
        "Manage on-device models" to "管理离线模型",
        "Sends captured audio to the configured Whisper ASR endpoint." to "将采集音频发送至下方配置的远程 Whisper 接口。",
        "Uses Android's built-in recognizer. Available locales depend on what's installed on this device." to "调用系统自带语音识别（仅限麦克风），可用语种取决于手机系统支持。",

        "Languages" to "字幕与翻译语言",
        "Source (spoken)" to "识别语言（音频原声）",
        "No languages available" to "暂无可用语言",
        "Pick a language" to "请选择语言",
        "Target (translation)" to "显示语言（输出字幕）",
        "Swap" to "互换",
        "On-device transcription: source language is limited to the Vosk models installed on this phone." to "本地 Vosk 引擎仅支持手机中已安装的离线语音模型语种。",
        "Download more languages" to "下载更多语种模型",
        "On-device translation via Google ML Kit — %d languages, fully offline after first download." to "支持通过 Google ML Kit 离线翻译（共 %d 种语言）；若识别与输出同为英语（en -> en），则直接展示/匹配英文原声字幕，不走翻译。",
        "Could not reach LibreTranslate at %s — showing a fallback list. Save a valid URL to load the full language set from your server." to "未能连接 LibreTranslate（%s），当前显示备用语言列表。",
        "Loading languages from LibreTranslate…" to "正在从 LibreTranslate 拉取语言列表…",
        "%d languages reported by LibreTranslate. Add more to your server by installing extra Argos Translate packages." to "LibreTranslate 共返回 %d 种语言。",
        "Auto-detect source" to "自动识别源语言",
        "Close" to "关闭",
        "Choose language" to "选择语言",
        "Search" to "搜索",
        "Requires Pro" to "需要 Pro",
        "No languages match \"%s\"." to "没有匹配“%s”的语言。",

        "On-device speech models" to "Vosk 本地语音模型",
        "Vosk models run fully offline on this phone. Downloading a model is a one-time step; uninstall any time to free storage. For the strongest transcription, pick the LARGE server-grade model for the languages you use most." to "Vosk 模型完全在本地离线运行，一次下载后可反复使用，随时可删除释放空间。",
        "Installed" to "已安装",
        "Large · server-grade accuracy (Pro)" to "大容量 · 高精度版",
        "Full Vosk server models with the lowest error rates. Each one is 80 MB to 2 GB but runs entirely on-device after the one-time download. This is the strongest transcription option for every language." to "完整服务端级别识别模型（约 80 MB–2 GB），离线高精度。",
        "Small · fast & light" to "轻量版 · 快速省空间",
        "Compact ~40 MB models for quick installs or low-storage phones. Accuracy is noticeably lower than the large variants." to "约 40 MB 的轻量模型，安装快、占用少，但复杂口音准确度低于大模型。",
        "Models are fetched from alphacephei.com/vosk/models over HTTPS." to "模型通过 HTTPS 从 alphacephei.com/vosk/models 下载。",
        "Languages without a model here (for example Indonesian) may still work from the microphone with the “Android built-in” engine, if your phone's speech service supports that language. Android may need to download the language first." to "若列表里没有某语言，可在麦克风模式下尝试“系统自带”引擎。",
        "Delete" to "删除",
        "Get" to "下载",
        "LARGE" to "大型",
        "bundled" to "内置",
        "Pro" to "高级版",
        "Pro feature" to "高级功能",
        "Upgrade to Pro to unlock this feature. The upgrade removes ads and adds high-accuracy models, more languages, profiles, and advanced caption tools." to "解锁更多高级功能。",
        "Not now" to "暂不",
        "Opening Google Play…" to "正在打开 Google Play…",
        "Download failed (HTTP 404): this model is no longer published" to "下载失败（HTTP 404）：该模型已下线",
        "Download failed: no internet connection" to "下载失败：无网络连接",
        "Download failed: the connection timed out" to "下载失败：连接超时",
        "There is no on-device speech model for %s here. Try the “Android built-in” engine (uses your phone's speech service, microphone only, if it supports this language) or pick another language." to "暂无 %s 的本地模型，可切换其他语种或改用系统识别。",
        "Downloading %s model…" to "正在下载 %s 模型…",
        "Download %s model (~%d MB)" to "下载 %s 模型（约 %d MB）",
        "Retry %s model download (~%d MB)" to "重试下载 %s 模型（约 %d MB）",

        "Text size: %dsp" to "字幕字号：%dsp",
        "Opacity: %d%%" to "背景透明度：%d%%",
        "Show original text" to "显示原文参考行",
        "Overlay theme (Pro)" to "字幕条配色主题",
        "Overlay font (Pro)" to "字幕字体样式",

        "Translation engine" to "翻译与服务器设置",
        "On-device (ML Kit)" to "本地离线 (ML Kit)",
        "LibreTranslate" to "自建 LibreTranslate",
        "Google ML Kit translates fully on this device — no server required. The first time you use a language pair, a ~30 MB model downloads and is then cached offline forever." to "Google ML Kit 完全在本地翻译，无需自建服务器（仅首次跨语种翻译时需下载约 30 MB 离线包；若只看纯英文字幕请保持 en -> en）。",
        "LibreTranslate URL" to "LibreTranslate 地址",
        "Example: %s" to "示例：%s",
        "Save" to "保存",
        "Refresh" to "刷新",
        "Language fetch error: %s" to "拉取语言列表失败：%s",
        "Speech-to-Text URL (Whisper)" to "远程 Whisper 接口地址",
        "Save STT URL" to "保存识别地址",

        "Update available" to "发现新版本",
        "Download APK" to "下载更新包",
        "View release" to "查看发布页",
        "Later" to "稍后",

        "Transcript History" to "字幕历史记录",
        "No matching transcripts" to "无匹配记录",
        "No transcripts yet" to "暂无字幕记录",
        "Try a different search term." to "换个关键词试试。",
        "Transcripts will appear here as you use captioning." to "开启字幕识别后，历史台词会保存在这里。",
        "Search transcripts…" to "搜索历史台词…",
        "Clear search" to "清空搜索",
        "Clear" to "清空",
        "Copy" to "复制",
        "Back" to "返回",
        "Share transcripts" to "分享字幕记录",
        "Delete entry" to "删除记录",
        "Delete this transcript entry?\n\n\"%s\"" to "确定删除这条字幕记录吗？\n\n“%s”",
        "Cancel" to "取消",

        "Pause captioning" to "暂停字幕",
        "Minimize overlay" to "最小化悬浮窗",
        "Close overlay" to "关闭悬浮窗",
        "Resume captioning" to "恢复字幕",

        "Captioning in progress" to "字幕识别运行中",
        "Listening and translating speech." to "正在监听音频并输出剧情字幕",
        "Pause/Resume" to "暂停/继续",
        "Live Captioning" to "实时字幕监听",
        "Shows when live captioning is active." to "显示实时剧情字幕识别状态",

        "Interface language" to "界面语言",
        "Choose interface language" to "选择界面语言",
        "English" to "English",
        "Switch to English" to "切换为英文",
        "Switch" to "切换",
        "Retry" to "重试",
        "Current" to "当前",

        "Tools and privacy" to "实用工具与隐私设置",
        "Ready to start captioning" to "配置就绪，可随时开启字幕",
        "Setup needed before captioning" to "请先完成权限授予或模型准备",
        "Save transcripts to local history" to "自动保存字幕到本机历史记录",
        "Presets" to "配置预设",
        "Current setup" to "当前配置",
        "Save preset" to "保存预设",
        "Save Pro preset" to "保存备用预设",
        "Use" to "应用",
        "Glossary and phrase replacements" to "自定义术语替换词表",
        "Add Pro" to "添加词条",
        "Add" to "添加",
        "Add glossary entry" to "添加术语替换",
        "Phrase" to "原词（区分整词）",
        "Replacement" to "替换为"
    )

    val ZH_CN_STRINGS: UiStrings = UiStrings(BUILTIN_ZH_CN, isZh = true)
}