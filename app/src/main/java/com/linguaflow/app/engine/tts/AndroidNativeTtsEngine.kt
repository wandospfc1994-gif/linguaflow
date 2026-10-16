package com.linguaflow.app.engine.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.linguaflow.app.engine.TextToSpeechEngine
import com.linguaflow.app.engine.TtsPlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class AndroidNativeTtsEngine(
    private val context: Context
) : TextToSpeechEngine, TextToSpeech.OnInitListener {

    private val tag = "AndroidNativeTtsEngine"

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var currentSpeechRate: Float = 1.0f
    private var currentPitch: Float = 1.0f

    private val _playbackState = MutableStateFlow<TtsPlaybackState>(TtsPlaybackState.Idle)
    override val playbackState: StateFlow<TtsPlaybackState> = _playbackState.asStateFlow()

    private val completionCallbacks = ConcurrentHashMap<String, () -> Unit>()

    init {
        initTts()
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(tag, "Erro ao instanciar TextToSpeech", e)
            _playbackState.value = TtsPlaybackState.Error("Não foi possível inicializar TextToSpeech: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { engine ->
                var result = engine.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(tag, "Locale.US indisponível, tentando Locale.UK")
                    result = engine.setLanguage(Locale.UK)
                }

                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(tag, "Locale em inglês não suportado totalmente pelo mecanismo padrão")
                }

                engine.setSpeechRate(currentSpeechRate)
                engine.setPitch(currentPitch)

                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        val id = utteranceId ?: ""
                        _playbackState.value = TtsPlaybackState.Speaking(id, "")
                    }

                    override fun onDone(utteranceId: String?) {
                        val id = utteranceId ?: ""
                        completionCallbacks.remove(id)?.invoke()
                        if (tts?.isSpeaking != true) {
                            _playbackState.value = TtsPlaybackState.Idle
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        val id = utteranceId ?: ""
                        completionCallbacks.remove(id)
                        _playbackState.value = TtsPlaybackState.Error("Erro na reprodução do áudio")
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        val id = utteranceId ?: ""
                        completionCallbacks.remove(id)
                        _playbackState.value = TtsPlaybackState.Error("Erro na reprodução (código $errorCode)")
                    }
                })

                isInitialized = true
                Log.d(tag, "TextToSpeech inicializado com sucesso para idioma inglês.")
            }
        } else {
            isInitialized = false
            _playbackState.value = TtsPlaybackState.Error("Falha na inicialização do TextToSpeech (status: $status)")
            Log.e(tag, "Falha na inicialização do TTS: $status")
        }
    }

    override fun isSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }

    override fun speak(text: String, utteranceId: String, onDone: (() -> Unit)?) {
        if (text.isBlank()) return

        if (!isInitialized || tts == null) {
            initTts()
        }

        if (onDone != null) {
            completionCallbacks[utteranceId] = onDone
        }

        _playbackState.value = TtsPlaybackState.Speaking(utteranceId, text)

        val engine = tts
        if (engine == null) {
            _playbackState.value = TtsPlaybackState.Error("Mecanismo de voz não disponível")
            return
        }

        // Sentence-by-sentence splitting for natural cadence
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }

        if (sentences.size <= 1) {
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } else {
            // Queue sentence by sentence
            sentences.forEachIndexed { index, sentence ->
                val isLast = index == sentences.lastIndex
                val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val currentId = if (isLast) utteranceId else "${utteranceId}_part_$index"
                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, currentId)
                }
                engine.speak(sentence, queueMode, params, currentId)
            }
        }
    }

    override fun stop() {
        completionCallbacks.clear()
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(tag, "Erro ao parar TTS", e)
        }
        _playbackState.value = TtsPlaybackState.Idle
    }

    override fun setSpeechRate(rate: Float) {
        currentSpeechRate = rate.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(currentSpeechRate)
    }

    override fun setPitch(pitch: Float) {
        currentPitch = pitch.coerceIn(0.5f, 2.0f)
        tts?.setPitch(currentPitch)
    }

    override fun release() {
        stop()
        try {
            tts?.shutdown()
            tts = null
            isInitialized = false
        } catch (e: Exception) {
            Log.w(tag, "Erro ao desligar TTS", e)
        }
        _playbackState.value = TtsPlaybackState.Idle
    }
}
