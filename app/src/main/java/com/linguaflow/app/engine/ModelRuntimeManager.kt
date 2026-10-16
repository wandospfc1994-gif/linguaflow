package com.linguaflow.app.engine

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ActiveEngine {
    NONE,
    LLM,
    STT,
    TTS
}

data class DeviceMemoryStatus(
    val totalRamMb: Long,
    val availRamMb: Long,
    val isLowRam: Boolean,
    val isSystemLowMemory: Boolean
)

data class ModelRecommendation(
    val useLightweightMode: Boolean,
    val recommendedStt: String,
    val recommendedTts: String,
    val recommendedLlm: String,
    val explanation: String
)

class ModelRuntimeManager(
    private val context: Context
) {
    private val tag = "ModelRuntimeManager"
    private val memoryMutex = Mutex()

    private val _activeEngine = MutableStateFlow(ActiveEngine.NONE)
    val activeEngine: StateFlow<ActiveEngine> = _activeEngine.asStateFlow()

    private val _currentUnloadCallback = MutableStateFlow<(() -> Unit)?>(null)

    fun getDeviceMemoryStatus(): DeviceMemoryStatus {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)

        val totalMb = memoryInfo.totalMem / (1024 * 1024)
        val availMb = memoryInfo.availMem / (1024 * 1024)
        val isLowRam = totalMb < 4096 // Less than 4 GB of RAM

        return DeviceMemoryStatus(
            totalRamMb = totalMb,
            availRamMb = availMb,
            isLowRam = isLowRam,
            isSystemLowMemory = memoryInfo.lowMemory
        )
    }

    fun isLowRamDevice(): Boolean {
        return getDeviceMemoryStatus().isLowRam
    }

    fun getRecommendation(): ModelRecommendation {
        val memory = getDeviceMemoryStatus()
        return if (memory.isLowRam) {
            ModelRecommendation(
                useLightweightMode = true,
                recommendedStt = "Vosk (Fase A - Ultra Leve)",
                recommendedTts = "Android Nativo (Fase A - Zero RAM)",
                recommendedLlm = "Gemma 2B Quantizado (Carregamento sob demanda)",
                explanation = "Aparelho com ${memory.totalRamMb} MB de RAM detectado (< 4 GB). O modo econômico poupa bateria e evita encerramentos por falta de memória."
            )
        } else {
            ModelRecommendation(
                useLightweightMode = false,
                recommendedStt = "Whisper ONNX (Fase B) ou Vosk (Fase A)",
                recommendedTts = "Piper Neural TTS (Fase B) ou Nativo",
                recommendedLlm = "Gemma 2B MediaPipe Edge AI",
                explanation = "Aparelho com ${memory.totalRamMb} MB de RAM. Suporta modelos neurais da Fase B com alta fidelidade."
            )
        }
    }

    suspend fun <T> runWithModelLock(
        engine: ActiveEngine,
        onUnloadCurrent: (() -> Unit)? = null,
        block: suspend () -> T
    ): T {
        return memoryMutex.withLock {
            val previousEngine = _activeEngine.value
            if (previousEngine != ActiveEngine.NONE && previousEngine != engine) {
                Log.d(tag, "Alternando motor de IA ativo: de $previousEngine para $engine. Descarregando motor anterior.")
                try {
                    _currentUnloadCallback.value?.invoke()
                } catch (e: Exception) {
                    Log.w(tag, "Erro ao descarregar motor anterior $previousEngine", e)
                }
            }

            _activeEngine.value = engine
            _currentUnloadCallback.value = onUnloadCurrent

            try {
                block()
            } finally {
                // Keep engine set or release if needed
                Log.d(tag, "Execução do motor $engine concluída sob lock de memória.")
            }
        }
    }

    suspend fun releaseActiveEngine() {
        memoryMutex.withLock {
            val engine = _activeEngine.value
            if (engine != ActiveEngine.NONE) {
                Log.d(tag, "Liberando motor ativo $engine da memória.")
                try {
                    _currentUnloadCallback.value?.invoke()
                } catch (e: Exception) {
                    Log.w(tag, "Erro ao descarregar motor $engine", e)
                }
                _activeEngine.value = ActiveEngine.NONE
                _currentUnloadCallback.value = null
            }
        }
    }
}
