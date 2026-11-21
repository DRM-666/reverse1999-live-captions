package com.charles.livecaptionn.settings

import com.charles.livecaptionn.BuildConfig

data class CaptionSettings(
    val sourceLanguageCode: String = "en",
    val targetLanguageCode: String = "en",
    val autoDetectSource: Boolean = false,
    val textSizeSp: Float = 14f,
    val overlayOpacity: Float = 0.25f,
    val showOriginal: Boolean = true,
    val serverBaseUrl: String = DEFAULT_BASE_URL,
    val overlayX: Int = 40,
    val overlayY: Int = 260,
    val overlayMinimized: Boolean = false,
    val audioSource: AudioSource = AudioSource.SYSTEM,
    val sttBackend: SttBackend = SttBackend.LOCAL_WHISPER,
    val translationBackend: TranslationBackend = TranslationBackend.ML_KIT,
    val sttBaseUrl: String = DEFAULT_STT_URL,
    val overlayWidthDp: Int = DEFAULT_OVERLAY_WIDTH_DP,
    val overlayHeightDp: Int = DEFAULT_OVERLAY_HEIGHT_DP,
    val overlaySubtitleMode: Boolean = true,
    val overlayTouchThrough: Boolean = false,
    val overlayMaxLines: Int = 2,
    /** Pro perk. Defaults render identically to the app's original look. */
    val overlayThemeId: String = "default",
    val overlayFontId: String = "default",
    /** Interface (UI) language, defaulting to English until the user opts in
     *  to an on-device ML Kit translation. Not the caption source language. */
    val uiLanguageCode: String = "en",
    /** True once the first-launch onboarding has been completed. */
    val onboardingComplete: Boolean = false,
    /** Whether completed captions should be persisted to local history. */
    val saveHistory: Boolean = true,
    /** Whether the user acknowledged/dismissed the borderline hardware warning. */
    val borderlineWarningDismissed: Boolean = false,
    /** Optional simulated compatibility tier for manual testing (e.g. PASSED, BORDERLINE, UNSUPPORTED). */
    val simulatedCompatibilityTier: String? = null,
    /** Optional LLM context correction (uses Chinese story context + OpenAI-compatible API). */
    val llmCorrectionEnabled: Boolean = false,
    val llmApiBaseUrl: String = DEFAULT_LLM_BASE_URL,
    val llmApiKey: String = "",
    val llmModel: String = DEFAULT_LLM_MODEL,
    /** Selected 1-based chapter in fetched UTTU story (0 = all chapters). */
    val activeStoryChapter: Int = 1
) {
    companion object {
        val DEFAULT_BASE_URL: String = BuildConfig.DEFAULT_TRANSLATE_URL
        val DEFAULT_STT_URL: String = BuildConfig.DEFAULT_STT_URL
        const val DEFAULT_LLM_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        const val DEFAULT_LLM_MODEL = "qwen-turbo"
        const val DEFAULT_OVERLAY_WIDTH_DP = 440
        const val DEFAULT_OVERLAY_HEIGHT_DP = 76
        const val MIN_OVERLAY_WIDTH_DP = 160
        const val MIN_OVERLAY_HEIGHT_DP = 36
    }
}
