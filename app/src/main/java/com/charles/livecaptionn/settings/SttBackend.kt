package com.charles.livecaptionn.settings

enum class SttBackend(val label: String) {
    LOCAL_WHISPER("Local Whisper"),
    LOCAL_VOSK("On-device (streaming)"),
    REMOTE_WHISPER("Remote Whisper");

    companion object {
        fun fromName(name: String): SttBackend =
            entries.firstOrNull { it.name == name } ?: LOCAL_WHISPER
    }
}
