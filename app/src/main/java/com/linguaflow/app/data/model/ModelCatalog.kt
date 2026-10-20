package com.linguaflow.app.data.model

object ModelCatalog {
    const val GEMMA_PUBLIC_URL =
        "https://huggingface.co/litert-community/Gemma2-2B-IT/resolve/main/gemma2-2b-it-gpu-int4.bin"

    const val GEMMA_VERIFIED_MIRROR_URL =
        "https://huggingface.co/a8nova/gemma-2b-it-cpu-int4/resolve/main/gemma-2b-it-cpu-int4.bin"

    val STT_VOSK_EN = ModelInfo(
        id = "stt_vosk_en",
        name = "Vosk STT Inglês (Fase A)",
        type = ModelType.STT,
        fileName = "vosk-model-small-en-us-0.15.zip",
        url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
        approximateSizeBytes = 41_943_040L, // ~40 MB
        expectedSha256 = "c04d0dd22ebcf06e6fb1c4e72ce5e82b3d8704ceba1583d33682914101e14946",
        description = "Reconhecimento de fala offline ultra-rápido em inglês (~40MB). Download direto público e gratuito.",
        isRequired = true,
        requiresAuth = false
    )

    val LLM_GEMMA_2B = ModelInfo(
        id = "llm_gemma_2b",
        name = "Gemma 2B CPU/GPU (Modelo Leve Edge AI)",
        type = ModelType.LLM,
        fileName = "gemma-2b-it-cpu-int4.bin",
        url = GEMMA_PUBLIC_URL,
        approximateSizeBytes = 1_346_559_040L, // ~1.25 GB
        expectedSha256 = "",
        description = "Inteligência Artificial offline para tutoria, conversação natural e correções gramaticais. Download público direto sem token.",
        isRequired = true,
        requiresAuth = false
    )

    val STT_WHISPER_EN = ModelInfo(
        id = "stt_whisper_en",
        name = "Whisper Tiny ONNX (Sherpa Fase B)",
        type = ModelType.STT,
        fileName = "sherpa-onnx-whisper-tiny.en.tar.bz2",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2",
        approximateSizeBytes = 78_000_000L, // ~75 MB
        expectedSha256 = "2f6a7d1a29367290c506193ff7bf5001ff98eb9894e622b7a9f73fce81cfdf7e",
        description = "Reconhecedor neural Whisper ONNX quantizado com alta precisão e processamento de áudio local.",
        isRequired = false,
        requiresAuth = false
    )

    val TTS_PIPER_EN = ModelInfo(
        id = "tts_piper_en",
        name = "Piper TTS Voz Natural (Sherpa Fase B)",
        type = ModelType.TTS,
        fileName = "en_US-lessac-medium.onnx",
        url = "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/en/en_US/lessac/medium/en_US-lessac-medium.onnx",
        approximateSizeBytes = 63_000_000L, // ~60 MB
        expectedSha256 = "e1136b35fb291f08e4ba438b939ba64eef26be4fbfd72ee4da82855f4c5415ee",
        description = "Síntese de voz neural humanizada com Piper e fonemização espeak-ng integrada.",
        isRequired = false,
        requiresAuth = false
    )

    val allModels: List<ModelInfo> = listOf(STT_VOSK_EN, LLM_GEMMA_2B, STT_WHISPER_EN, TTS_PIPER_EN)

    fun getModelById(id: String): ModelInfo? = allModels.find { it.id == id }

    fun createGemmaModelWithUrl(url: String, requiresAuth: Boolean): ModelInfo {
        return LLM_GEMMA_2B.copy(
            url = url,
            requiresAuth = requiresAuth,
            name = if (requiresAuth) "Gemma 2B (Repositório Oficial HF)" else "Gemma 2B (Mirror Público Direto)"
        )
    }
}
