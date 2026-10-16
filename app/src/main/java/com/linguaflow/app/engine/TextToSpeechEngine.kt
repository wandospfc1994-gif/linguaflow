package com.linguaflow.app.engine

import kotlinx.coroutines.flow.StateFlow

sealed class TtsPlaybackState {
    data object Idle : TtsPlaybackState()
    data class Speaking(val utteranceId: String, val text: String) : TtsPlaybackState()
    data class Error(val message: String) : TtsPlaybackState()
}

interface TextToSpeechEngine {
    val playbackState: StateFlow<TtsPlaybackState>
    fun isSpeaking(): Boolean
    fun speak(text: String, utteranceId: String = System.currentTimeMillis().toString(), onDone: (() -> Unit)? = null)
    fun stop()
    fun setSpeechRate(rate: Float)
    fun setPitch(pitch: Float)
    fun release()
}
