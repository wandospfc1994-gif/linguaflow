package com.linguaflow.app.engine.tts

import com.linguaflow.app.engine.TextToSpeechEngine
import com.linguaflow.app.engine.TtsPlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SmartTextToSpeechEngine(
    private val piperEngine: SherpaPiperTextToSpeechEngine,
    private val nativeEngine: AndroidNativeTtsEngine,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) : TextToSpeechEngine {

    private val _playbackState = MutableStateFlow<TtsPlaybackState>(TtsPlaybackState.Idle)
    override val playbackState: StateFlow<TtsPlaybackState> = _playbackState.asStateFlow()

    private var activeEngine: TextToSpeechEngine = nativeEngine

    init {
        scope.launch {
            piperEngine.playbackState.collect { if (activeEngine == piperEngine) _playbackState.value = it }
        }
        scope.launch {
            nativeEngine.playbackState.collect { if (activeEngine == nativeEngine) _playbackState.value = it }
        }
    }

    override fun isSpeaking(): Boolean = activeEngine.isSpeaking()

    override fun speak(text: String, utteranceId: String, onDone: (() -> Unit)?) {
        // Automatic selection: if Piper model is present locally, use Sherpa Piper; else fallback to Android Native TTS
        activeEngine = if (piperEngine.isPiperModelAvailable()) {
            piperEngine
        } else {
            nativeEngine
        }

        activeEngine.speak(text, utteranceId, onDone)
    }

    override fun stop() {
        activeEngine.stop()
        piperEngine.stop()
        nativeEngine.stop()
        _playbackState.value = TtsPlaybackState.Idle
    }

    override fun setSpeechRate(rate: Float) {
        piperEngine.setSpeechRate(rate)
        nativeEngine.setSpeechRate(rate)
    }

    override fun setPitch(pitch: Float) {
        piperEngine.setPitch(pitch)
        nativeEngine.setPitch(pitch)
    }

    override fun setLocale(locale: java.util.Locale) {
        nativeEngine.setLocale(locale)
    }

    override fun release() {
        piperEngine.release()
        nativeEngine.release()
        _playbackState.value = TtsPlaybackState.Idle
    }
}
