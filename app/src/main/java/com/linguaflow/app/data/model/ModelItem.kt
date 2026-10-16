package com.linguaflow.app.data.model

enum class ModelType(val label: String) {
    STT("Reconhecimento de Voz (STT)"),
    LLM("Inteligência Artificial (LLM)"),
    TTS("Síntese de Voz (TTS)")
}

data class ModelInfo(
    val id: String,
    val name: String,
    val type: ModelType,
    val fileName: String,
    val url: String,
    val approximateSizeBytes: Long,
    val expectedSha256: String,
    val description: String,
    val isRequired: Boolean = true
) {
    val formattedSize: String
        get() {
            val mb = approximateSizeBytes.toDouble() / (1024 * 1024)
            return if (mb >= 1000) {
                String.format("%.1f GB", mb / 1024)
            } else {
                String.format("%.0f MB", mb)
            }
        }
}

sealed class ModelDownloadState {
    data object NotInstalled : ModelDownloadState()
    data class Downloading(
        val progressPercent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedText: String = ""
    ) : ModelDownloadState()
    data class Verifying(val message: String = "Verificando integridade SHA-256...") : ModelDownloadState()
    data class Installed(val fileSizeBytes: Long) : ModelDownloadState()
    data class Error(val message: String) : ModelDownloadState()
}
