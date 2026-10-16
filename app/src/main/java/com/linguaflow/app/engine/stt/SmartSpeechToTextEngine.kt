package com.linguaflow.app.engine.stt

import com.linguaflow.app.engine.SpeechToTextEngine
import com.linguaflow.app.engine.SttState
import com.linguaflow.app.engine.WordConfidence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SmartSpeechToTextEngine(
    private val whisperEngine: SherpaWhisperSpeechToTextEngine,
    private val voskEngine: VoskSpeechToTextEngine,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) : SpeechToTextEngine {

    private val _state = MutableStateFlow<SttState>(SttState.Idle)
    override val state: StateFlow<SttState> = _state.asStateFlow()

    private var activeEngine: SpeechToTextEngine = voskEngine

    init {
        scope.launch {
            whisperEngine.state.collect { if (activeEngine == whisperEngine) _state.value = it }
        }
        scope.launch {
            voskEngine.state.collect { if (activeEngine == voskEngine) _state.value = it }
        }
    }

    override fun isListening(): Boolean = activeEngine.isListening()

    override fun startListening(
        onPartial: (String) -> Unit,
        onFinal: (String, List<WordConfidence>) -> Unit,
        onError: (String) -> Unit
    ) {
        // Automatic graceful selection: if Whisper ONNX is available locally, use Fase B; else use Fase A (Vosk)
        activeEngine = if (whisperEngine.isWhisperModelAvailable()) {
            whisperEngine
        } else {
            voskEngine
        }

        activeEngine.startListening(
            onPartial = onPartial,
            onFinal = onFinal,
            onError = { err ->
                // If Whisper failed, gracefully attempt fallback to Vosk
                if (activeEngine == whisperEngine) {
                    activeEngine = voskEngine
                    voskEngine.startListening(onPartial, onFinal, onError)
                } else {
                    onError(err)
                }
            }
        )
    }

    override fun stopListening() {
        activeEngine.stopListening()
        whisperEngine.stopListening()
        voskEngine.stopListening()
        _state.value = SttState.Idle
    }

    override fun release() {
        whisperEngine.release()
        voskEngine.release()
        _state.value = SttState.Idle
    }
}
