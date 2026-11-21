package com.charles.livecaptionn.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.charles.livecaptionn.data.SettingsRepository
import com.charles.livecaptionn.settings.CaptionSettings
import com.charles.livecaptionn.speech.RecognitionStatus
import com.charles.livecaptionn.ui.l10n.UiStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val onPauseResume: () -> Unit,
    private val onClose: () -> Unit,
    private val onToggleMinimize: () -> Unit,
    private val onToggleTouchThrough: () -> Unit = {},
    private val uiStrings: () -> UiStrings = { UiStrings.EMPTY }
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density

    private var root: FrameLayout? = null
    private var containerView: LinearLayout? = null
    private var headerView: LinearLayout? = null
    private var statusText: TextView? = null
    private var originalContainer: LinearLayout? = null
    private var originalText: TextView? = null
    private var dividerView: View? = null
    private var translatedText: TextView? = null
    private var body: ScrollView? = null
    private var pauseButton: ImageButton? = null
    private var lockButton: ImageButton? = null
    private var minButton: ImageButton? = null
    private var closeButton: ImageButton? = null
    private var menuToggleDot: TextView? = null
    private var resizeHandleView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var subtitleControlsExpanded: Boolean = false
    private var lastTouchThrough: Boolean = false
    private var lastSubtitleMode: Boolean = true

    fun show(initialX: Int, initialY: Int, widthDp: Int, heightDp: Int) {
        if (root != null) return
        showInternal(initialX, initialY, widthDp, heightDp)
    }

    private fun showInternal(initialX: Int, initialY: Int, widthDp: Int, heightDp: Int) {
        val widthPx = (widthDp * density).roundToInt()
        val heightPx = (heightDp * density).roundToInt()

        val p = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }
        params = p

        val frame = FrameLayout(context)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#40111111"))
            }
            background = bg
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnTouchListener(DragTouchListener())
        }
        containerView = container

        // Compact header: status + controls
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnTouchListener(DragTouchListener())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        headerView = header

        statusText = TextView(context).apply {
            text = "Idle"
            setTextColor(Color.WHITE)
            textSize = 10f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        pauseButton = makeButton(android.R.drawable.ic_media_pause, uiStrings()["Pause captioning"]) { onPauseResume() }
        lockButton = makeButton(android.R.drawable.ic_lock_lock, "锁定触摸穿透") { onToggleTouchThrough() }
        minButton = makeButton(android.R.drawable.arrow_down_float, uiStrings()["Minimize overlay"]) { onToggleMinimize() }
        closeButton = makeButton(android.R.drawable.ic_menu_close_clear_cancel, uiStrings()["Close overlay"]) { onClose() }

        header.addView(statusText)
        header.addView(pauseButton)
        header.addView(lockButton)
        header.addView(minButton)
        header.addView(closeButton)

        // Original speech section (when transcribing + translating with showOriginal)
        val origContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(2), 0, dp(2))
            }
        }

        originalText = TextView(context).apply {
            setTextColor(Color.WHITE)
            text = ""
            setLineSpacing(dp(1).toFloat(), 1.05f)
            setShadowLayer(5f, 0f, 1.5f, Color.BLACK)
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        dividerView = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                setMargins(0, dp(2), 0, dp(2))
            }
        }

        origContainer.addView(originalText)
        origContainer.addView(dividerView)
        originalContainer = origContainer

        // Primary caption text (movie/game subtitle styling with shadow for legibility on 0% bg)
        translatedText = TextView(context).apply {
            setTextColor(Color.WHITE)
            text = "…"
            setLineSpacing(dp(2).toFloat(), 1.12f)
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            setPadding(0, dp(1), 0, dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnTouchListener(DragTouchListener())
        }

        val scrollContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(translatedText)
        }

        body = ScrollView(context).apply {
            addView(scrollContent)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isFillViewport = false
            isVerticalScrollBarEnabled = false
        }

        container.addView(header)
        container.addView(origContainer)
        container.addView(body)
        frame.addView(container)

        // Subtle corner dot in subtitle mode to expand/collapse controls
        val dot = TextView(context).apply {
            text = "⋯"
            setTextColor(Color.argb(190, 255, 255, 255))
            textSize = 12f
            gravity = Gravity.CENTER
            val dotBg = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(95, 0, 0, 0))
            }
            background = dotBg
            layoutParams = FrameLayout.LayoutParams(dp(22), dp(18)).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, dp(2), dp(2), 0)
            }
            setOnClickListener {
                subtitleControlsExpanded = !subtitleControlsExpanded
                applyHeaderAndChromeVisibility(lastSubtitleMode, lastTouchThrough)
            }
        }
        menuToggleDot = dot
        frame.addView(dot)

        // Resize handle at bottom-right corner
        val handleSize = dp(16)
        val resizeHandle = View(context).apply {
            val bg = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(Color.parseColor("#88FFFFFF"))
            }
            background = bg
            layoutParams = FrameLayout.LayoutParams(handleSize, handleSize).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, dp(2), dp(2))
            }
            setOnTouchListener(ResizeTouchListener())
        }
        resizeHandleView = resizeHandle
        frame.addView(resizeHandle)

        root = frame
        try {
            wm.addView(frame, p)
        } catch (t: Throwable) {
            root = null
            params = null
            throw t
        }
    }

    fun update(ui: OverlayUiState) {
        val frame = root ?: return
        val container = containerView ?: return
        val theme = OverlayThemeCatalog.find(ui.themeId)
        val font = OverlayFontCatalog.find(ui.fontId)
        val s = uiStrings()

        lastSubtitleMode = ui.subtitleMode
        lastTouchThrough = ui.touchThrough
        updateWindowTouchableFlag(frame, ui.touchThrough)
        applyHeaderAndChromeVisibility(ui.subtitleMode, ui.touchThrough)

        // Allow full 0%..100% opacity so pure-text game subtitle mode has zero dark box when desired.
        val effectiveOpacity = ui.opacity.coerceIn(0f, 1.0f)
        (container.background as? GradientDrawable)?.setColor(
            Color.argb(
                (effectiveOpacity * 255).roundToInt(),
                Color.red(theme.backgroundRgb),
                Color.green(theme.backgroundRgb),
                Color.blue(theme.backgroundRgb)
            )
        )
        if (ui.subtitleMode) {
            container.setPadding(dp(10), dp(3), dp(10), dp(3))
        } else {
            container.setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        statusText?.setTextColor(theme.textRgb)
        statusText?.typeface = font.typeface
        statusText?.text = buildString {
            append(s[ui.status.displayName])
            val detail = ui.statusDetail?.trim().orEmpty()
            if (detail.isNotEmpty()) {
                append(" · ")
                append(detail)
            }
        }
        pauseButton?.contentDescription = if (ui.status == RecognitionStatus.PAUSED) s["Resume captioning"] else s["Pause captioning"]
        minButton?.contentDescription = s["Minimize overlay"]
        closeButton?.contentDescription = s["Close overlay"]

        val textR = Color.red(theme.textRgb)
        val textG = Color.green(theme.textRgb)
        val textB = Color.blue(theme.textRgb)

        val display = computeOverlayTextDisplay(ui)

        if (display.showOriginal) {
            originalText?.setTextColor(Color.argb(215, textR, textG, textB))
            originalText?.typeface = Typeface.create(font.typeface, Typeface.ITALIC)
            originalText?.text = display.originalText
            originalText?.textSize = display.originalTextSizeSp
            originalText?.gravity = if (ui.subtitleMode) Gravity.CENTER_HORIZONTAL else Gravity.START
            dividerView?.setBackgroundColor(Color.argb(45, textR, textG, textB))
            originalContainer?.visibility = if (ui.minimized) View.GONE else View.VISIBLE
        } else {
            originalContainer?.visibility = View.GONE
        }

        translatedText?.setTextColor(theme.textRgb)
        translatedText?.typeface = font.typeface
        translatedText?.text = display.translatedText
        translatedText?.textSize = display.translatedTextSizeSp
        translatedText?.gravity = if (ui.subtitleMode) Gravity.CENTER_HORIZONTAL else Gravity.START
        translatedText?.visibility = View.VISIBLE
        body?.visibility = if (ui.minimized) View.GONE else View.VISIBLE
        pauseButton?.setImageResource(
            if (ui.status == RecognitionStatus.PAUSED) android.R.drawable.ic_media_play
            else android.R.drawable.ic_media_pause
        )
        body?.post {
            try { body?.fullScroll(View.FOCUS_DOWN) } catch (_: Throwable) { }
        }
    }

    private fun applyHeaderAndChromeVisibility(subtitleMode: Boolean, touchThrough: Boolean) {
        val showFullChrome = !subtitleMode || subtitleControlsExpanded
        headerView?.visibility = if (showFullChrome && !touchThrough) View.VISIBLE else View.GONE
        resizeHandleView?.visibility = if (showFullChrome && !touchThrough) View.VISIBLE else View.GONE
        menuToggleDot?.visibility = if (subtitleMode && !touchThrough) View.VISIBLE else View.GONE
    }

    private fun updateWindowTouchableFlag(frame: FrameLayout, touchThrough: Boolean) {
        val lp = params ?: return
        val hasFlag = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
        if (hasFlag == touchThrough) return
        lp.flags = if (touchThrough) {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        try { wm.updateViewLayout(frame, lp) } catch (_: Throwable) {}
    }

    fun hide() {
        root?.let { view ->
            try { wm.removeViewImmediate(view) } catch (_: Throwable) { }
        }
        root = null
        params = null
        containerView = null
        headerView = null
        statusText = null
        originalContainer = null
        originalText = null
        dividerView = null
        translatedText = null
        body = null
        pauseButton = null
        lockButton = null
        minButton = null
        closeButton = null
        menuToggleDot = null
        resizeHandleView = null
        subtitleControlsExpanded = false
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private fun makeButton(resId: Int, contentDesc: String, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(resId)
        contentDescription = contentDesc
        setBackgroundColor(Color.TRANSPARENT)
        setColorFilter(Color.WHITE)
        val size = dp(24)
        layoutParams = LinearLayout.LayoutParams(size, size).apply {
            setMargins(dp(2), 0, dp(2), 0)
        }
        setOnClickListener { onClick() }
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var startX = 0; private var startY = 0
        private var touchX = 0f; private var touchY = 0f
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (lastTouchThrough) return false
            val lp = params ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = event.rawX; touchY = event.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val view = root ?: return false
                    val dx = (event.rawX - touchX).roundToInt()
                    val dy = (event.rawY - touchY).roundToInt()
                    if (abs(dx) > 4 || abs(dy) > 4) moved = true
                    lp.x = startX + dx
                    lp.y = startY + dy
                    try { wm.updateViewLayout(view, lp) } catch (_: IllegalArgumentException) { return false }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        scope.launch {
                            settingsRepository.update { it.copy(overlayX = lp.x, overlayY = lp.y) }
                        }
                    }
                    return true
                }
            }
            return false
        }
    }

    private inner class ResizeTouchListener : View.OnTouchListener {
        private var startW = 0; private var startH = 0
        private var touchX = 0f; private var touchY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (lastTouchThrough) return false
            val lp = params ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startW = lp.width; startH = lp.height
                    touchX = event.rawX; touchY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val view = root ?: return false
                    val minW = (CaptionSettings.MIN_OVERLAY_WIDTH_DP * density).roundToInt()
                    val minH = (CaptionSettings.MIN_OVERLAY_HEIGHT_DP * density).roundToInt()
                    lp.width = (startW + (event.rawX - touchX).roundToInt()).coerceAtLeast(minW)
                    lp.height = (startH + (event.rawY - touchY).roundToInt()).coerceAtLeast(minH)
                    try { wm.updateViewLayout(view, lp) } catch (_: IllegalArgumentException) { return false }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val wDp = (lp.width / density).roundToInt()
                    val hDp = (lp.height / density).roundToInt()
                    scope.launch {
                        settingsRepository.update {
                            it.copy(overlayWidthDp = wDp, overlayHeightDp = hDp)
                        }
                    }
                    return true
                }
            }
            return false
        }
    }
}
