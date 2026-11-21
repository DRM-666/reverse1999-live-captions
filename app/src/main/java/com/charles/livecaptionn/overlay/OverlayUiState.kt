package com.charles.livecaptionn.overlay

import com.charles.livecaptionn.speech.RecognitionStatus

data class OverlayUiState(
    val originalText: String = "",
    val translatedText: String = "",
    val transcriptText: String = "",
    val status: RecognitionStatus = RecognitionStatus.IDLE,
    /** Shown under status when STT/network fails (system-audio path). */
    val statusDetail: String? = null,
    val textSizeSp: Float = 14f,
    val opacity: Float = 0.25f,
    val showOriginal: Boolean = true,
    val minimized: Boolean = false,
    val subtitleMode: Boolean = true,
    val touchThrough: Boolean = false,
    val maxLines: Int = 2,
    /** Callers are responsible for falling back to the default theme/font when
     * the current entitlement doesn't include Pro (see [OverlayThemeCatalog.FREE_THEME_ID]). */
    val themeId: String = OverlayThemeCatalog.FREE_THEME_ID,
    val fontId: String = OverlayFontCatalog.FREE_FONT_ID
)
