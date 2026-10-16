package com.linguaflow.app.data.model

object ModelCatalog {
    val STT_VOSK_EN = ModelInfo(
        id = "stt_vosk_en",
        name = "Vosk STT Inglês (Fase A)",
        type = ModelType.STT,
        fileName = "vosk-model-small-en-us-0.15.zip",
        url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
        approximateSizeBytes = 41_943_040L, // ~40 MB
        expectedSha256 = "c04d0dd22ebcf06e6fb1c4e72ce5e82b3d8704ceba1583d33682914101e14946",
        description = "Reconhecimento de fala offline e rápido em inglês (Fase A nativa).",
        isRequired = true
    )

    val STT_WHISPER_EN = ModelInfo(
        id = "stt_whisper_en",
        name = "Whisper Tiny ONNX (Sherpa Fase B)",
        type = ModelType.STT,
        fileName = "sherpa-onnx-whisper-tiny.en.tar.bz2",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2",
        approximateSizeBytes = 78_000_000L, // ~75 MB
        expectedSha256 = "2f6a7d1a29367290c506193ff7bf5001ff98eb9894e622b7a9f73fce81cfdf7e",
        description = "Reconhecedor neural Whisper ONNX quantizado com sherpa-onnx e alta precisão (Fase B).",
        isRequired = false
    )

    val LLM_GEMMA_2B = ModelInfo(
        id = "llm_gemma_2b",
        name = "Gemma 2B CPU/GPU (Modelo Leve)",
        type = ModelType.LLM,
        fileName = "gemma-2b-it-cpu-int4.bin",
        url = "https://huggingface.co/google/gemma-2b-it/resolve/main/gemma-2b-it-cpu-int4.bin",
        approximateSizeBytes = 1_400_000_000L, // ~1.3 GB
        expectedSha256 = "a5e9f1c7d23e809b44fc876210ef395b214a1c5d9a9103de33b6680410ff0291",
        description = "Inteligência Artificial offline para tutoria, conversação e correções gramaticais.",
        isRequired = true
    )

    val TTS_PIPER_EN = ModelInfo(
        id = "tts_piper_en",
        name = "Piper TTS Voz Natural (Sherpa Fase B)",
        type = ModelType.TTS,
        fileName = "en_US-lessac-medium.onnx",
        url = "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/en/en_US/lessac/medium/en_US-lessac-medium.onnx",
        approximateSizeBytes = 63_000_000L, // ~60 MB
        expectedSha256 = "e1136b35fb291f08e4ba438b939ba64eef26be4fbfd72ee4da82855f4c5415ee",
        description = "Síntese de voz neural humanizada offline com Piper e fonemização espeak-ng integrada.",
        isRequired = false
    )

    val allModels: List<ModelInfo> = listOf(STT_VOSK_EN, STT_WHISPER_EN, LLM_GEMMA_2B, TTS_PIPER_EN)

    fun getModelById(id: String): ModelInfo? = allModels.find { it.id == id }
}
