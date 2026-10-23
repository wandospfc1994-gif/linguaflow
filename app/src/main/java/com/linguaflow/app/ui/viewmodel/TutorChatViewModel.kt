package com.linguaflow.app.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.linguaflow.app.data.local.entity.MessageEntity
import com.linguaflow.app.data.model.TutorFeedback
import com.linguaflow.app.data.repository.LinguaflowRepository
import com.linguaflow.app.engine.SpeechToTextEngine
import com.linguaflow.app.engine.SttState
import com.linguaflow.app.engine.TextToSpeechEngine
import com.linguaflow.app.engine.TtsPlaybackState
import com.linguaflow.app.engine.TutorLlmEngine
import com.linguaflow.app.engine.llm.TutorResponseParser
import com.linguaflow.app.ui.state.ChatUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel central da tela de conversação com o Tutor IA bilíngue.
 * Gerencia a máquina de estados (IDLE, LISTENING, TRANSCRIBING, THINKING, SPEAKING),
 * adição imediata de mensagens na UI, parser de feedback didático e reprodução nativa TTS.
 */
class TutorChatViewModel(
    private val repository: LinguaflowRepository,
    val tutorLlm: TutorLlmEngine,
    val ttsEngine: TextToSpeechEngine,
    val sttEngine: SpeechToTextEngine
) : ViewModel() {

    private val tag = "TutorChatViewModel"

    private val _uiState = MutableStateFlow(ChatUiState.IDLE)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _streamingRawText = MutableStateFlow("")
    val streamingRawText: StateFlow<String> = _streamingRawText.asStateFlow()

    private val _speechRate = MutableStateFlow(1.0f)
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val messages: StateFlow<List<MessageEntity>> = repository.getMessages(1L)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playbackState: StateFlow<TtsPlaybackState> = ttsEngine.playbackState
    val sttState: StateFlow<SttState> = sttEngine.state

    init {
        // Inicialização única do modelo LLM como Singleton / Scoped
        initializeLlmOnce()

        // Sincronização de reprodução TTS com prevenção de eco (desativa microfone enquanto tutor fala)
        viewModelScope.launch {
            playbackState.collect { state ->
                if (state is TtsPlaybackState.Speaking) {
                    _uiState.value = ChatUiState.SPEAKING
                    sttEngine.stopListening()
                } else if (_uiState.value == ChatUiState.SPEAKING) {
                    _uiState.value = ChatUiState.IDLE
                }
            }
        }

        // Sincronização de escuta STT
        viewModelScope.launch {
            sttState.collect { state ->
                when (state) {
                    is SttState.Listening -> _uiState.value = ChatUiState.LISTENING
                    is SttState.Transcribing -> _uiState.value = ChatUiState.TRANSCRIBING
                    is SttState.Idle -> {
                        if (_uiState.value == ChatUiState.LISTENING || _uiState.value == ChatUiState.TRANSCRIBING) {
                            _uiState.value = ChatUiState.IDLE
                        }
                    }
                    is SttState.Error -> {
                        _errorMessage.value = state.message
                        _uiState.value = ChatUiState.IDLE
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Carrega os pesos do LlmInference uma única vez na inicialização,
     * garantindo contexto contínuo e eliminando recriações repetitivas.
     */
    private fun initializeLlmOnce() {
        viewModelScope.launch {
            try {
                Log.d(tag, "Iniciando carregamento prévio do LlmInference no ViewModel...")
                tutorLlm.loadModel()
            } catch (e: Exception) {
                Log.w(tag, "Aviso ao carregar LLM no ViewModel (fallback bilíngue ativo): ${e.message}")
            }
        }
    }

    fun onInputTextChanged(text: String) {
        _inputText.value = text
    }

    fun setSpeechRate(rate: Float) {
        _speechRate.value = rate
        ttsEngine.setSpeechRate(rate)
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    /**
     * Envia mensagem do usuário, persiste imediatamente no repositório/UI,
     * executa inferência do tutor com limpeza de template e dispara o TTS nativo.
     */
    fun sendMessage(textToSend: String) {
        val trimmed = textToSend.trim()
        if (trimmed.isBlank() || _uiState.value == ChatUiState.THINKING) return

        // Interrompe qualquer escuta ou áudio anterior para focar na nova mensagem
        sttEngine.stopListening()
        ttsEngine.stop()

        // 1. Limpa caixa de texto e define estado para THINKING
        _inputText.value = ""
        _uiState.value = ChatUiState.THINKING
        _streamingRawText.value = ""

        viewModelScope.launch {
            try {
                // 2. Adiciona a mensagem do usuário imediatamente ao Room/UI
                repository.sendMessage(trimmed, 1L)

                val fullOutput = StringBuilder()
                val currentHistory = messages.value

                // 3. Streaming da resposta do tutor com prompt bilíngue e histórico completo
                tutorLlm.generateTutorReplyStream(
                    prompt = trimmed,
                    history = currentHistory,
                    userLevel = "A1",
                    targetLanguage = "Inglês"
                ).collect { chunk ->
                    // Limpa resquícios antes de repassar ao estado de streaming visual
                    val cleanChunk = TutorResponseParser.cleanTemplateArtifacts(chunk)
                    _streamingRawText.value = cleanChunk
                    fullOutput.clear().append(chunk)
                }

                // 4. Parser rigoroso com limpeza de tags
                val finalFeedback: TutorFeedback = TutorResponseParser.parse(fullOutput.toString())
                val cleanReply = finalFeedback.reply
                if (cleanReply.isBlank()) {
                    throw IllegalStateException("O tutor não gerou uma resposta. Tente de novo.")
                }

                val storedMetadata = buildString {
                    append("<reply>$cleanReply</reply>")
                    if (finalFeedback.hasCorrections) {
                        append("<fix>${finalFeedback.corrections}</fix>")
                    }
                    if (finalFeedback.hasPronunciationTip) {
                        append("<pron>${finalFeedback.pronunciationTip}</pron>")
                    }
                }

                // 5. Salva a mensagem do tutor limpa no banco de dados
                val newMsgId = repository.saveAssistantMessage(
                    content = cleanReply,
                    conversationId = 1L,
                    translation = storedMetadata
                )

                _streamingRawText.value = ""
                _uiState.value = ChatUiState.SPEAKING

                // 6. Aciona o TextToSpeech (TTS) nativo instantaneamente com o texto limpo gerado
                ttsEngine.speak(
                    text = cleanReply,
                    utteranceId = newMsgId.toString(),
                    onDone = {
                        _uiState.value = ChatUiState.IDLE
                    }
                )

            } catch (e: Exception) {
                Log.e(tag, "Erro no processamento da mensagem do tutor", e)
                _errorMessage.value = e.localizedMessage ?: "Erro ao gerar a resposta do tutor."
                _streamingRawText.value = ""
                _uiState.value = ChatUiState.IDLE
            }
        }
    }

    /**
     * Inicia a escuta do microfone com verificação de eco e callbacks para atualização da UI.
     */
    fun startListening(onError: (String) -> Unit = {}) {
        if (_uiState.value == ChatUiState.SPEAKING) {
            onError("O microfone está desativado enquanto o tutor fala para evitar eco.")
            return
        }

        sttEngine.startListening(
            onPartial = { partial ->
                _inputText.value = partial
            },
            onFinal = { text, _ ->
                if (text.isBlank()) {
                    // Nunca inventar texto: avisa que a fala não foi reconhecida.
                    _errorMessage.value = "Não consegui entender sua voz. Fale perto do microfone e tente de novo."
                } else {
                    _inputText.value = text
                }
                _uiState.value = ChatUiState.IDLE
            },
            onError = { err ->
                Log.e(tag, "Erro no motor STT: $err")
                _errorMessage.value = err
                _uiState.value = ChatUiState.IDLE
                onError(err)
            }
        )
    }

    /**
     * Interrompe a escuta do microfone e restabelece o estado IDLE imediatamente.
     */
    fun stopListening() {
        sttEngine.stopListening()
        _uiState.value = ChatUiState.IDLE
    }

    fun playTtsForMessage(message: MessageEntity) {
        val id = message.id.toString()
        val currentPlayback = playbackState.value
        if (currentPlayback is TtsPlaybackState.Speaking && currentPlayback.utteranceId == id) {
            ttsEngine.stop()
        } else {
            // Garante que o texto enviado ao TTS não contenha tags XML
            val cleanSpeech = TutorResponseParser.cleanTemplateArtifacts(message.content)
            ttsEngine.speak(cleanSpeech, utteranceId = id)
        }
    }

    fun stopTts() {
        ttsEngine.stop()
    }

    fun clearChat() {
        stopTts()
        stopListening()
        viewModelScope.launch {
            repository.clearMessages(1L)
            repository.createInitialConversationIfNeeded()
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopTts()
        stopListening()
    }

    companion object {
        fun provideFactory(
            repository: LinguaflowRepository,
            tutorLlm: TutorLlmEngine,
            ttsEngine: TextToSpeechEngine,
            sttEngine: SpeechToTextEngine
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return TutorChatViewModel(repository, tutorLlm, ttsEngine, sttEngine) as T
            }
        }
    }
}
