package com.linguaflow.app.ui.state

enum class ChatUiState(val label: String) {
    IDLE("Pronto para conversar"),
    LISTENING("Escutando sua voz em inglês..."),
    TRANSCRIBING("Transcrevendo fala (Vosk STT)..."),
    THINKING("Tutor pensando e analisando..."),
    SPEAKING("Tutor falando em inglês (áudio ativo)")
}
