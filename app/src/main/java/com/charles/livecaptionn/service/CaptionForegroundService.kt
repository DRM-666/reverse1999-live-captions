package com.charles.livecaptionn.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.charles.livecaptionn.LiveCaptionApp
import com.charles.livecaptionn.MainActivity
import com.charles.livecaptionn.R
import com.charles.livecaptionn.data.TranscriptEntry
import com.charles.livecaptionn.data.applyGlossary
import com.charles.livecaptionn.overlay.OverlayController
import com.charles.livecaptionn.overlay.OverlayFontCatalog
import com.charles.livecaptionn.overlay.OverlayThemeCatalog
import com.charles.livecaptionn.overlay.OverlayUiState
import com.charles.livecaptionn.settings.AudioSource
import com.charles.livecaptionn.settings.CaptionSettings
import com.charles.livecaptionn.settings.LocaleMap
import com.charles.livecaptionn.settings.SttBackend
import com.charles.livecaptionn.speech.AndroidSpeechRecognizerManager
import com.charles.livecaptionn.speech.RecognitionStatus
import com.charles.livecaptionn.speech.SherpaWhisperEngine
import com.charles.livecaptionn.speech.SpeechEngine
import com.charles.livecaptionn.speech.SpeechResult
import com.charles.livecaptionn.speech.StreamingSttEngine
import com.charles.livecaptionn.speech.SystemAudioEngine
import com.charles.livecaptionn.story.ParsedScriptLine
import com.charles.livecaptionn.story.ParsedStoryDocument
import com.charles.livecaptionn.story.StoryContextTracker
import com.charles.livecaptionn.story.StoryMatcher
import com.charles.livecaptionn.story.StoryScriptStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue

@OptIn(FlowPreview::class)
class CaptionForegroundService : Service() {
    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: LiveCaptionApp

    private val captionSessionActive = AtomicBoolean(false)

    /** Bumps when the overlay view is attached so combine() re-runs (was skipping while overlay was null). */
    private val overlayReady = MutableStateFlow(0)

    private var speechEngine: SpeechEngine? = null
    private var overlayController: OverlayController? = null
    private var paused = false
    private var failureMessage: String? = null
    private var bufferedText = ""
    private val lineIdCounter = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile
    private var bufferedLineId: Long = NO_LINE_ID
    @Volatile
    private var historyOnNextTranslate = false
    private val pendingFinalTranslations = ConcurrentLinkedQueue<TranslationRequest>()

    /** Mic partials arrive faster than 400ms; cancel+restart jobs never finish. Debounce coalesces to one translate. */
    private val translateRequests = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var currentAudioSource = AudioSource.MIC
    private var storyContextTracker: StoryContextTracker? = null
    @Volatile
    private var lastFinalEnglishLine: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Must happen here, not in startFlow(): if the process was killed while
        // the notification was still showing, Android can deliver ACTION_STOP
        // (from the notification's "Stop" action) as the very first intent to a
        // freshly-created service instance, going straight to stopFlow() without
        // ACTION_START ever running. Initializing here guarantees `app` is set
        // no matter which action arrives first.
        app = application as LiveCaptionApp
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // System restarted our sticky service with a null intent
            // (e.g., after process death). We cannot proceed without an
            // action, so stop cleanly to avoid foreground crash.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                // CRITICAL: call startForeground synchronously here, BEFORE
                // any conditional / async work. Android's 5-second deadline
                // after startForegroundService() is enforced strictly (and
                // tighter on slower devices like the Fire tablet, which was
                // crashing during cold-start DataStore reads). Any early-exit
                // path below must therefore use stopForeground + stopSelf
                // instead of raw stopSelf, which previously crashed the app.
                val audioSource = intent.getStringExtra(EXTRA_AUDIO_SOURCE)
                    ?.let { runCatching { AudioSource.valueOf(it) }.getOrNull() }
                    ?: AudioSource.MIC
                currentAudioSource = audioSource
                if (enterForeground(audioSource)) startFlow()
            }
            ACTION_STOP -> stopFlow()
            ACTION_PAUSE_RESUME -> togglePause()
            ACTION_TOGGLE_MINIMIZE -> toggleMinimized()
            ACTION_TOGGLE_TOUCH_THROUGH -> toggleTouchThrough()
        }
        return START_STICKY
    }

    private fun enterForeground(audioSource: AudioSource): Boolean {
        val wantsMicrophone = audioSource != AudioSource.SYSTEM
        val hasMicPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        // Android throws a SecurityException from startForeground() itself if
        // FOREGROUND_SERVICE_TYPE_MICROPHONE is requested without RECORD_AUDIO
        // actually granted. MainActivity checks this before the initial launch,
        // but startFlow()'s audioSource-mismatch re-entry can call this again
        // mid-session — fail closed instead of crashing with the OS exception.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when {
                audioSource == AudioSource.SYSTEM -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                wantsMicrophone && hasMicPermission -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else -> 0
            }
        } else {
            0
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(), type)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
            if (wantsMicrophone && !hasMicPermission) {
                failAndStop("Microphone permission not granted. Allow microphone access and try again.")
                false
            } else true
        } catch (t: Throwable) {
            Log.e("CaptionService", "Unable to enter foreground", t)
            failAndStop("Captioning could not start: ${t.message ?: "foreground service permission denied"}")
            false
        }
    }

    private fun startFlow() {
        if (!captionSessionActive.compareAndSet(false, true)) return
        failureMessage = null
        if (!Settings.canDrawOverlays(this)) {
            Log.e("CaptionService", "Overlay permission missing.")
            failAndStop("Overlay permission is required. Enable it in system settings and try again.")
            return
        }

        serviceScope.launch {
            // Always start a fresh session un-minimized; stale overlayMinimized=true from a
            // prior run was hiding the caption body entirely.
            app.container.settingsRepository.update { it.copy(overlayMinimized = false) }
            val settings = app.container.settingsRepository.settingsFlow.first()
            Log.d("CaptionService", "startFlow settings minimized=${settings.overlayMinimized} w=${settings.overlayWidthDp}dp h=${settings.overlayHeightDp}dp audio=${settings.audioSource}")
            // If the activity sent the wrong audio source in the intent extra
            // (legacy intent, settings changed between launch and service start),
            // re-enter foreground with the correct service type.
            if (settings.audioSource != currentAudioSource) {
                currentAudioSource = settings.audioSource
                if (!enterForeground(currentAudioSource)) return@launch
            }
            val sttLanguageCode = settings.sourceLanguageCode.ifBlank { "en" }
            val localeForSpeechRec = LocaleMap.bcp47(sttLanguageCode)

            val premium = app.container.premiumRepository.state.first()
            if (com.charles.livecaptionn.translation.ProStartGate.requiresPro(
                    backend = settings.translationBackend,
                    sourceLanguageCode = settings.sourceLanguageCode,
                    targetLanguageCode = settings.targetLanguageCode,
                    hasPro = premium.hasPro
                )
            ) {
                failAndStop("This ML Kit language requires Pro. Choose a free language or upgrade.")
                return@launch
            }
            if (settings.sttBackend == SttBackend.LOCAL_VOSK) {
                val model = app.container.voskRegistry.installedModelFor(sttLanguageCode)
                if (model == null) {
                    failAndStop("No on-device model installed for '$sttLanguageCode'. Download one first.")
                    return@launch
                }
            }

            app.container.runtimeStore.update {
                // Clear a reason left over from an earlier failed start.
                it.copy(running = true, paused = false, status = RecognitionStatus.LISTENING, lastError = null)
            }

            // Prewarm the active translation backend so the first spoken
            // word doesn't stall waiting on a 30 MB ML Kit model download.
            launch(Dispatchers.IO) {
                val s = app.container.settingsRepository.settingsFlow.first()
                runCatching {
                    app.container.translationRepository.prewarm(
                        s.sourceLanguageCode,
                        s.targetLanguageCode
                    )
                }
            }

            launch(Dispatchers.IO) {
                translateRequests
                    .debounce(TRANSLATE_DEBOUNCE_MS)
                    .collect {
                        while (true) {
                            val final = pendingFinalTranslations.poll() ?: break
                            translateSnapshot(final)
                        }
                        val partial = synchronized(this@CaptionForegroundService) {
                            TranslationRequest(bufferedText, bufferedLineId, historyOnNextTranslate)
                                .also { historyOnNextTranslate = false }
                        }
                        if (partial.text.isNotBlank()) translateSnapshot(partial)
                    }
            }

            // Load once per session so file selection never interrupts a live recognizer.
            val debugLog = app.container.debugLog
            val parsedDoc = withContext(Dispatchers.IO) {
                runCatching { StoryScriptStore(applicationContext).loadParsed() }.getOrElse {
                    debugLog.log("Script", "loadParsed threw: ${it.message}")
                    null
                }
            }
            val uttuBundle = app.container.uttuStoryRepository.currentBundle()
            val persistChapter: (Int) -> Unit = { newChapter ->
                debugLog.log("Chapter", "Auto-advanced activeStoryChapter -> $newChapter")
                serviceScope.launch {
                    app.container.settingsRepository.update { it.copy(activeStoryChapter = newChapter) }
                }
            }
            val storyMatcher = withContext(Dispatchers.IO) {
                when {
                    parsedDoc != null && parsedDoc.hasEnglish ->
                        StoryMatcher.fromDocument(
                            doc = parsedDoc,
                            startChapter = settings.activeStoryChapter,
                            onChapterChanged = persistChapter
                        )
                    uttuBundle != null && uttuBundle.languageCode == "en" -> {
                        val enLines = uttuBundle.chapters.sortedBy { it.number }.flatMap { ch ->
                            ch.lines.map { line ->
                                ParsedScriptLine(
                                    chapterNumber = ch.number,
                                    chapterTitle = ch.title,
                                    english = com.charles.livecaptionn.story.UttuStoryRepository.stripSpeakerPrefix(line)
                                )
                            }
                        }
                        val enDoc = ParsedStoryDocument(
                            lines = enLines,
                            chapters = uttuBundle.chapters,
                            hasEnglish = enLines.isNotEmpty(),
                            hasChinese = false
                        )
                        StoryMatcher.fromDocument(
                            doc = enDoc,
                            startChapter = settings.activeStoryChapter,
                            onChapterChanged = persistChapter
                        )
                    }
                    else -> null
                }
            }
            storyContextTracker = withContext(Dispatchers.IO) {
                StoryContextTracker.fromSources(
                    bundle = uttuBundle,
                    parsedDoc = parsedDoc,
                    activeChapter = settings.activeStoryChapter,
                    onChapterChanged = persistChapter
                )
            }
            lastFinalEnglishLine = null

            debugLog.log(
                "Session",
                "START audio=${settings.audioSource} stt=${settings.sttBackend} " +
                    "src=${settings.sourceLanguageCode} tgt=${settings.targetLanguageCode} " +
                    "startCh=${settings.activeStoryChapter} " +
                    "parsedChapters=${parsedDoc?.chapters?.size ?: 0} parsedLines=${parsedDoc?.lines?.size ?: 0} " +
                    "matcherLines=${storyMatcher?.indexedLineCount ?: 0} matcherActiveCh=${storyMatcher?.activeChapter ?: -1}"
            )

            val useEnglishCaptions = settings.sourceLanguageCode == "en" && settings.targetLanguageCode == "en"
            val onSpeechResult = { result: SpeechResult ->
                val spoken = result.text.trim()
                val outcome = if (useEnglishCaptions && result.isFinal && spoken.isNotEmpty()) {
                    storyMatcher?.matchDetailed(spoken)
                } else {
                    null
                }
                val match = outcome?.text
                val transcript = match ?: spoken
                if (result.isFinal && spoken.isNotEmpty()) {
                    if (outcome != null) {
                        val scoreStr = String.format(java.util.Locale.US, "%.2f", outcome.score)
                        val bestStr = String.format(java.util.Locale.US, "%.2f", outcome.bestCandidateScore)
                        debugLog.log(
                            "Match",
                            "${outcome.tier} score=$scoreStr ch=${storyMatcher?.activeChapter} cursor=${storyMatcher?.cursor} " +
                                "raw='${spoken.take(70)}' -> match='${match?.take(70) ?: "NONE"}' " +
                                "(best=$bestStr near='${outcome.bestCandidatePreview.take(55)}')"
                        )
                    } else {
                        debugLog.log("Match", "SKIPPED useEn=$useEnglishCaptions raw='${spoken.take(70)}'")
                    }
                }
                Log.d("CaptionService", "onSpeechResult final=${result.isFinal} matched=${match != null} text='$transcript'")
                val candidateId = lineIdCounter.incrementAndGet()
                app.container.runtimeStore.update {
                    it.copy(
                        originalText = transcript,
                        // Partials overwrite the open line; finals close it so the next
                        // utterance starts fresh.
                        transcriptLines = recordTranscriptResult(
                            lines = it.transcriptLines,
                            originalText = transcript,
                            isFinal = result.isFinal,
                            newLineId = candidateId
                        ),
                        status = RecognitionStatus.PROCESSING,
                        lastError = null
                    )
                }
                queueTranslation(transcript, candidateId, result.isFinal, skipLlm = match != null)
            }

            val mediaProjection = if (currentAudioSource == AudioSource.SYSTEM) {
                val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val data = MediaProjectionHolder.data
                if (data == null) {
                    Log.e("CaptionService", "MediaProjection data missing.")
                    failAndStop("System-audio capture permission is missing. Start again and allow capture.")
                    return@launch
                }
                mpManager.getMediaProjection(MediaProjectionHolder.resultCode, data)
                    ?: run {
                        failAndStop("System-audio capture could not be initialized. Try again.")
                        return@launch
                    }
            } else {
                null
            }

            val errorSink: (String?) -> Unit = { msg ->
                if (msg != null) debugLog.log("Error", msg)
                app.container.runtimeStore.update { it.copy(lastError = msg) }
            }

            // Routing:
            //   LOCAL_WHISPER → Silero VAD + Sherpa-ONNX Whisper (multi-accent, BGM-tolerant)
            //   LOCAL_VOSK    → streaming Vosk for both mic and system audio
            //   REMOTE_WHISPER:
            //       system    → legacy batch SystemAudioEngine (posts WAVs to remote Whisper)
            //       mic       → AndroidSpeechRecognizerManager (Google recognizer; kept as fallback)
            when (settings.sttBackend) {
                SttBackend.LOCAL_WHISPER -> {
                    val engine = SherpaWhisperEngine(
                        context = this@CaptionForegroundService,
                        audioSource = currentAudioSource,
                        mediaProjection = mediaProjection,
                        whisperClient = app.container.localWhisperClient,
                        scope = serviceScope,
                        onResult = onSpeechResult,
                        onError = errorSink,
                        onLog = { msg -> debugLog.log("Whisper", msg) }
                    )
                    speechEngine = engine
                    observeEngineStatus(engine.status)
                    engine.start()
                }
                SttBackend.LOCAL_VOSK -> {
                    val engine = StreamingSttEngine(
                        context = this@CaptionForegroundService,
                        audioSource = currentAudioSource,
                        mediaProjection = mediaProjection,
                        languageCode = sttLanguageCode,
                        localSttClient = app.container.localVoskClient,
                        scope = serviceScope,
                        onResult = onSpeechResult,
                        onError = errorSink
                    )
                    speechEngine = engine
                    observeEngineStatus(engine.status)
                    engine.start()
                }
                SttBackend.REMOTE_WHISPER -> {
                    when (currentAudioSource) {
                        AudioSource.SYSTEM -> {
                            val engine = SystemAudioEngine(
                                context = this@CaptionForegroundService,
                                projection = mediaProjection!!,
                                sttUrl = settings.sttBaseUrl,
                                languageCode = sttLanguageCode,
                                sourceLanguageCode = sttLanguageCode,
                                sttBackend = settings.sttBackend,
                                localSttClient = app.container.localVoskClient,
                                scope = serviceScope,
                                onResult = onSpeechResult,
                                onSttError = errorSink
                            )
                            speechEngine = engine
                            observeEngineStatus(engine.status)
                            engine.start()
                        }
                        AudioSource.MIC -> {
                            val engine = AndroidSpeechRecognizerManager(
                                this@CaptionForegroundService, onSpeechResult, onError = errorSink
                            )
                            engine.setLanguage(localeForSpeechRec)
                            speechEngine = engine
                            observeEngineStatus(engine.status)
                            engine.start()
                        }
                    }
                }
            }

            showOverlay()
            observeOverlayUpdates()
        }
    }

    private fun observeEngineStatus(statusFlow: StateFlow<RecognitionStatus>) {
        serviceScope.launch {
            statusFlow.collectLatest { status ->
                app.container.runtimeStore.update { it.copy(status = status) }
            }
        }
    }

    /**
     * Drive the overlay from a single combined stream so rapid runtime + settings changes
     * cannot apply out-of-order (stale coroutines overwriting newer translation text).
     */
    private fun observeOverlayUpdates() {
        serviceScope.launch {
            combine(
                app.container.runtimeStore.state,
                app.container.settingsRepository.settingsFlow,
                app.container.premiumRepository.state,
                overlayReady
            ) { runtime, settings, premium, _ -> Triple(runtime, settings, premium.hasPro) }
                .collectLatest { (runtime, settings, hasPro) ->
                    val overlay = overlayController ?: return@collectLatest
                    val ui = buildOverlayUi(runtime, settings, hasPro)
                    Log.d(
                        "CaptionService",
                        "overlay update lines=${runtime.transcriptLines.size} " +
                            "orig='${runtime.originalText.take(40)}' " +
                            "trans='${runtime.translatedText.take(40)}' " +
                            "body='${ui.transcriptText.take(60)}'"
                    )
                    overlay.update(ui)
                }
        }
    }

    private fun buildOverlayUi(
        runtime: CaptionRuntimeState,
        settings: CaptionSettings,
        hasPro: Boolean
    ): OverlayUiState {
        val maxLines = if (settings.overlaySubtitleMode) settings.overlayMaxLines.coerceIn(1, 6) else MAX_TRANSCRIPT_LINES
        return OverlayUiState(
            originalText = runtime.originalText,
            translatedText = runtime.translatedText.ifBlank { runtime.originalText },
            transcriptText = overlayTranscriptText(runtime, maxVisibleLines = maxLines),
            status = runtime.status,
            statusDetail = runtime.lastError,
            textSizeSp = settings.textSizeSp,
            opacity = settings.overlayOpacity,
            // For same-language captions, the transcript already is the original;
            // a second view otherwise duplicates it when scrollback has multiple lines.
            showOriginal = settings.showOriginal && settings.sourceLanguageCode != settings.targetLanguageCode,
            minimized = settings.overlayMinimized,
            subtitleMode = settings.overlaySubtitleMode,
            touchThrough = settings.overlayTouchThrough,
            maxLines = maxLines,
            // Defensive fallback: a lapsed subscription must never leave a stale
            // Pro theme/font rendering — always re-check entitlement here, not
            // just at the point the setting was saved.
            themeId = if (hasPro) settings.overlayThemeId else OverlayThemeCatalog.FREE_THEME_ID,
            fontId = if (hasPro) settings.overlayFontId else OverlayFontCatalog.FREE_FONT_ID
        )
    }

    private fun queueTranslation(text: String, lineId: Long, isFinal: Boolean = false, skipLlm: Boolean = false) {
        synchronized(this) {
            bufferedText = text
            bufferedLineId = lineId
            if (isFinal) {
                pendingFinalTranslations.add(TranslationRequest(text, lineId, saveHistory = true, skipLlm = skipLlm))
                // Finals are retained in the queue; do not translate the same
                // immutable snapshot again through the mutable partial slot.
                bufferedText = ""
                historyOnNextTranslate = false
            }
        }
        translateRequests.tryEmit(Unit)
    }

    private suspend fun translateSnapshot(request: TranslationRequest) {
        val captionSettings = app.container.settingsRepository.settingsFlow.first()
        val sameLanguage = captionSettings.sourceLanguageCode == captionSettings.targetLanguageCode
        val rawOrTranslated = if (sameLanguage) {
            request.text
        } else {
            app.container.translationRepository.translate(
                text = request.text,
                sourceCode = captionSettings.sourceLanguageCode,
                targetCode = captionSettings.targetLanguageCode,
                autoDetect = currentAudioSource == AudioSource.SYSTEM || captionSettings.autoDetectSource
            )
        }
        if (rawOrTranslated.isBlank()) return
        val initialDisplay = applyGlossary(rawOrTranslated, app.container.glossary.list())
        publishLineDisplay(request.lineId, initialDisplay)

        // Optional LLM context correction runs AFTER immediate display; skipped when StoryMatcher already hit.
        var finalForHistory = initialDisplay
        if (sameLanguage &&
            !request.skipLlm &&
            captionSettings.sourceLanguageCode == "en" &&
            request.saveHistory &&
            captionSettings.llmCorrectionEnabled &&
            captionSettings.llmApiKey.isNotBlank()
        ) {
            val tracker = storyContextTracker
            val window = tracker?.currentWindow().orEmpty()
            val result = app.container.llmCorrector.correct(
                rawEnglish = request.text,
                previousEnglish = lastFinalEnglishLine,
                windowLines = window,
                baseUrl = captionSettings.llmApiBaseUrl,
                apiKey = captionSettings.llmApiKey,
                model = captionSettings.llmModel
            )
            tracker?.advanceIfMatched(result.matchedLine)
            if (result.corrected && result.text.isNotBlank()) {
                val refined = applyGlossary(result.text, app.container.glossary.list())
                finalForHistory = refined
                publishLineDisplay(request.lineId, refined)
            }
            lastFinalEnglishLine = finalForHistory
        } else if (sameLanguage && request.saveHistory) {
            lastFinalEnglishLine = finalForHistory
        }

        if (request.saveHistory && captionSettings.saveHistory) {
            app.container.transcriptHistory.add(
                TranscriptEntry(
                    originalText = request.text,
                    translatedText = finalForHistory,
                    sourceLanguage = if (currentAudioSource == AudioSource.SYSTEM) "auto" else captionSettings.sourceLanguageCode,
                    targetLanguage = captionSettings.targetLanguageCode
                )
            )
        }
    }

    private fun publishLineDisplay(lineId: Long, text: String) {
        app.container.runtimeStore.update {
            val current = it.transcriptLines.any { line -> line.id == lineId }
            it.copy(
                translatedText = if (current) text else it.translatedText,
                transcriptLines = updateTranscriptTranslation(it.transcriptLines, lineId, text),
                status = if (paused) RecognitionStatus.PAUSED else RecognitionStatus.LISTENING
            )
        }
    }

    private data class TranslationRequest(
        val text: String,
        val lineId: Long,
        val saveHistory: Boolean,
        val skipLlm: Boolean = false
    )

    private fun showOverlay() {
        if (overlayController != null) return
        serviceScope.launch {
            val settings = app.container.settingsRepository.settingsFlow.first()
            overlayController = OverlayController(
                context = this@CaptionForegroundService,
                scope = serviceScope,
                settingsRepository = app.container.settingsRepository,
                onPauseResume = { togglePause() },
                onClose = { stopFlow() },
                onToggleMinimize = { toggleMinimized() },
                onToggleTouchThrough = { toggleTouchThrough() },
                uiStrings = { app.container.uiLocalization.latestStrings() }
            ).apply {
                show(settings.overlayX, settings.overlayY, settings.overlayWidthDp, settings.overlayHeightDp)
            }
            val runtime = app.container.runtimeStore.state.value
            val hasPro = app.container.premiumRepository.state.first().hasPro
            overlayController?.update(buildOverlayUi(runtime, settings, hasPro))
            overlayReady.value = overlayReady.value + 1
        }
    }

    private fun togglePause() {
        paused = !paused
        if (paused) speechEngine?.pause() else speechEngine?.resume()
        app.container.runtimeStore.update {
            it.copy(
                paused = paused,
                status = if (paused) RecognitionStatus.PAUSED else RecognitionStatus.LISTENING
            )
        }
    }

    private fun toggleMinimized() {
        serviceScope.launch {
            app.container.settingsRepository.update { it.copy(overlayMinimized = !it.overlayMinimized) }
        }
    }

    private fun toggleTouchThrough() {
        serviceScope.launch {
            app.container.settingsRepository.update { it.copy(overlayTouchThrough = !it.overlayTouchThrough) }
        }
    }

    private fun releaseModelsAsync() {
        app.container.appScope.launch(Dispatchers.IO) {
            runCatching { app.container.localWhisperClient.release() }
            runCatching { app.container.localVoskClient.release() }
        }
    }

    private fun stopFlow() {
        runCatching { app.container.debugLog.log("Session", "STOP") }
        captionSessionActive.set(false)
        try { speechEngine?.stop() } catch (_: Throwable) {}
        speechEngine = null
        storyContextTracker = null
        lastFinalEnglishLine = null
        overlayController?.hide()
        overlayController = null
        overlayReady.value = 0
        MediaProjectionHolder.clear()
        releaseModelsAsync()
        val error = failureMessage
        failureMessage = null
        app.container.runtimeStore.update {
            if (error == null) CaptionRuntimeState()
            else CaptionRuntimeState(status = RecognitionStatus.ERROR, lastError = error)
        }
        serviceScope.cancel()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun failAndStop(message: String) {
        captionSessionActive.set(false)
        failureMessage = message
        try { speechEngine?.stop() } catch (_: Throwable) {}
        speechEngine = null
        storyContextTracker = null
        lastFinalEnglishLine = null
        overlayController?.hide()
        overlayController = null
        MediaProjectionHolder.clear()
        releaseModelsAsync()
        app.container.runtimeStore.update {
            it.copy(running = false, paused = false, status = RecognitionStatus.ERROR, lastError = message)
        }
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { stopFlow() } catch (_: Throwable) {}
    }

    private fun buildNotification(): Notification {
        createNotificationChannel()
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseIntent = PendingIntent.getService(
            this, 2,
            Intent(this, CaptionForegroundService::class.java).apply { action = ACTION_PAUSE_RESUME },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val lockIntent = PendingIntent.getService(
            this, 4,
            Intent(this, CaptionForegroundService::class.java).apply { action = ACTION_TOGGLE_TOUCH_THROUGH },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 3,
            Intent(this, CaptionForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val uiStrings = app.container.uiLocalization.latestStrings()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(uiStrings["Captioning in progress"])
            .setContentText(uiStrings["Listening and translating speech."])
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pending)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, uiStrings["Pause/Resume"], pauseIntent)
            .addAction(android.R.drawable.ic_lock_lock, "穿透锁定", lockIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, uiStrings["Stop"], stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "caption.action.START"
        const val ACTION_STOP = "caption.action.STOP"
        const val ACTION_PAUSE_RESUME = "caption.action.PAUSE_RESUME"
        const val ACTION_TOGGLE_MINIMIZE = "caption.action.TOGGLE_MINIMIZE"
        const val ACTION_TOGGLE_TOUCH_THROUGH = "caption.action.TOGGLE_TOUCH_THROUGH"
        const val EXTRA_AUDIO_SOURCE = "caption.extra.AUDIO_SOURCE"

        private const val CHANNEL_ID = "caption_channel"
        private const val NOTIF_ID = 2001
        private const val TRANSLATE_DEBOUNCE_MS = 450L
        private const val NO_LINE_ID = -1L
    }
}
