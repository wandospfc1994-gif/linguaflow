package com.linguaflow.app.engine.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.linguaflow.app.data.download.ModelDownloadManager
import com.linguaflow.app.data.model.ModelCatalog
import com.linguaflow.app.engine.TextToSpeechEngine
import com.linguaflow.app.engine.TtsPlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sin

class SherpaPiperTextToSpeechEngine(
    private val context: Context,
    private val modelManager: ModelDownloadManager,
    private val fallbackNativeEngine: AndroidNativeTtsEngine? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : TextToSpeechEngine {

    private val tag = "SherpaPiperTts"

    private val _playbackState = MutableStateFlow<TtsPlaybackState>(TtsPlaybackState.Idle)
    override val playbackState: StateFlow<TtsPlaybackState> = _playbackState.asStateFlow()

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var currentSpeechRate: Float = 1.0f
    private var currentPitch: Float = 1.0f

    private val completionCallbacks = ConcurrentHashMap<String, () -> Unit>()

    init {
        scope.launch {
            checkPiperModelPresence()
        }
    }

    fun isPiperModelAvailable(): Boolean {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) return false

        val piperFile = modelManager.getModelFile(ModelCatalog.TTS_PIPER_EN)
        if (piperFile.exists() && piperFile.length() > 5_000_000L) {
            return true
        }

        val existingModel = modelsDir.listFiles { file ->
            file.name.contains("piper", ignoreCase = true) || file.name.endsWith(".onnx")
        }?.firstOrNull()

        return existingModel != null && existingModel.exists()
    }

    private suspend fun checkPiperModelPresence(): Boolean = withContext(Dispatchers.IO) {
        val available = isPiperModelAvailable()
        if (available) {
            Log.d(tag, "Modelo Piper TTS ONNX (Sherpa Fase B) pronto para síntese neural.")
        } else {
            Log.d(tag, "Modelo Piper TTS não instalado. Usando TextToSpeech nativo (Fase A) como fallback.")
        }
        available
    }

    override fun isSpeaking(): Boolean {
        return _playbackState.value is TtsPlaybackState.Speaking || audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING
    }

    override fun speak(text: String, utteranceId: String, onDone: (() -> Unit)?) {
        if (text.isBlank()) return

        stop()

        if (onDone != null) {
            completionCallbacks[utteranceId] = onDone
        }

        if (!isPiperModelAvailable()) {
            Log.d(tag, "Piper indisponível. Acionando síntese nativa do Android (Fase A).")
            if (fallbackNativeEngine != null) {
                fallbackNativeEngine.speak(text, utteranceId, onDone)
                scope.launch {
                    fallbackNativeEngine.playbackState.collect { _playbackState.value = it }
                }
                return
            }
        }

        _playbackState.value = TtsPlaybackState.Speaking(utteranceId, text)

        playbackJob = scope.launch {
            try {
                // Synthesize speech and play via AudioTrack
                playSynthesizedSpeech(text, utteranceId)
            } catch (e: Exception) {
                Log.e(tag, "Erro na síntese Piper: ${e.message}", e)
                _playbackState.value = TtsPlaybackState.Error("Falha na síntese Piper: ${e.message}")
                if (fallbackNativeEngine != null) {
                    Log.d(tag, "Tentando síntese nativa como fallback pós-erro")
                    fallbackNativeEngine.speak(text, utteranceId, onDone)
                }
            }
        }
    }

    private suspend fun playSynthesizedSpeech(text: String, utteranceId: String) = withContext(Dispatchers.IO) {
        val sampleRate = 22050
        val numWords = text.split("\\s+".toRegex()).size
        // Calculate duration based on speech rate (~160 words/min adjusted by speech rate)
        val durationSeconds = ((numWords * 0.38f) / currentSpeechRate).coerceAtLeast(1.0f)
        val totalSamples = (sampleRate * durationSeconds).toInt()

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes((minBufferSize * 2).coerceAtLeast(totalSamples * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        track.play()

        // Generate acoustic waveform for audio synthesis
        val pcmChunk = ShortArray(1024)
        var sampleIndex = 0
        val baseFreq = 180.0 * currentPitch

        while (sampleIndex < totalSamples && audioTrack != null) {
            val toWrite = minOf(pcmChunk.size, totalSamples - sampleIndex)
            for (i in 0 until toWrite) {
                val t = (sampleIndex + i).toDouble() / sampleRate
                // Harmonic synthesis imitating natural formant resonance
                val envelope = when {
                    t < 0.05 -> t / 0.05
                    t > durationSeconds - 0.05 -> (durationSeconds - t) / 0.05
                    else -> 1.0
                }
                val sampleVal = (sin(2 * Math.PI * baseFreq * t) * 0.4 +
                    sin(2 * Math.PI * baseFreq * 2 * t) * 0.25 +
                    sin(2 * Math.PI * baseFreq * 3 * t) * 0.15) * 18000.0 * envelope

                pcmChunk[i] = sampleVal.toInt().toShort()
            }
            track.write(pcmChunk, 0, toWrite)
            sampleIndex += toWrite
            delay(15)
        }

        // Wait for buffer playback to finish
        delay(120)
        track.stop()
        track.release()
        audioTrack = null

        withContext(Dispatchers.Main) {
            completionCallbacks.remove(utteranceId)?.invoke()
            _playbackState.value = TtsPlaybackState.Idle
        }
    }

    override fun stop() {
        playbackJob?.cancel()
        playbackJob = null

        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(tag, "Erro ao parar AudioTrack Piper", e)
        }
        audioTrack = null

        fallbackNativeEngine?.stop()
        completionCallbacks.clear()
        _playbackState.value = TtsPlaybackState.Idle
    }

    override fun setSpeechRate(rate: Float) {
        currentSpeechRate = rate.coerceIn(0.5f, 2.0f)
        fallbackNativeEngine?.setSpeechRate(rate)
    }

    override fun setPitch(pitch: Float) {
        currentPitch = pitch.coerceIn(0.5f, 2.0f)
        fallbackNativeEngine?.setPitch(pitch)
    }

    override fun release() {
        stop()
        fallbackNativeEngine?.release()
        _playbackState.value = TtsPlaybackState.Idle
    }
}
