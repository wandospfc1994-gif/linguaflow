package com.linguaflow.app.engine

import kotlinx.coroutines.flow.StateFlow

data class WordConfidence(
    val word: String,
    val confidence: Float,
    val startTime: Float = 0f,
    val endTime: Float = 0f
)

sealed class SttState {
    data object Idle : SttState()
    data object Listening : SttState()
    data object Transcribing : SttState()
    data class Result(
        val text: String,
        val isFinal: Boolean,
        val wordConfidences: List<WordConfidence> = emptyList()
    ) : SttState()
    data class Error(val message: String) : SttState()
}

interface SpeechToTextEngine {
    val state: StateFlow<SttState>
    fun startListening(
        onPartial: (String) -> Unit = {},
        onFinal: (String, List<WordConfidence>) -> Unit = { _, _ -> },
        onError: (String) -> Unit = {}
    )
    fun stopListening()
    fun isListening(): Boolean
    fun release()
}
