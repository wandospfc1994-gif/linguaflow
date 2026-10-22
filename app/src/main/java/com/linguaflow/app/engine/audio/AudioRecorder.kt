package com.linguaflow.app.engine.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Gerenciador nativo de captura de áudio PCM 16kHz Mono para motores STT (Vosk/Whisper).
 * Inclui detecção de atividade de voz (RMS-based VAD) e parada automática por silêncio
 * para evitar congelamento de threads ou loop infinito na interface de escuta.
 */
class AudioRecorder(
    private val sampleRate: Int = 16000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val tag = "AudioRecorder"

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    companion object {
        // Limiares de energia RMS para detecção de voz e silêncio
        private const val SPEECH_RMS_THRESHOLD = 650.0
        private const val SILENCE_RMS_THRESHOLD = 380.0
        private const val SILENCE_TIMEOUT_MS = 1400L
        private const val MAX_RECORDING_DURATION_MS = 14000L
    }

    /**
     * Inicia a captura contínua de áudio PCM 16kHz mono.
     *
     * @param onAudioChunk Callback para transmissão contínua de buffers PCM ao STT.
     * @param onStarted Invocado na Main Thread quando o hardware inicia efetivamente a gravação.
     * @param onError Invocado em caso de falha de permissão ou hardware nativo.
     * @param onSilenceDetected Invocado quando silêncio sustentado após fala é detectado (VAD).
     */
    @SuppressLint("MissingPermission")
    suspend fun start(
        onAudioChunk: (ByteArray, Int) -> Unit,
        onStarted: () -> Unit = {},
        onError: (String) -> Unit = {},
        onSilenceDetected: () -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        if (_isRecording.value) {
            Log.w(tag, "AudioRecorder já está com captura ativa. Ignorando chamada redundante.")
            return@withContext true
        }

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                val msg = "Configuração de áudio PCM (16kHz mono 16-bit) não suportada pelo hardware do dispositivo."
                Log.e(tag, "Erro crítico de buffer AudioRecord: $msg (minBufferSize=$minBufferSize)")
                withContext(Dispatchers.Main) { onError(msg) }
                return@withContext false
            }

            val bufferSize = maxOf(minBufferSize * 2, 4096)

            // Inicialização robusta: tenta VOICE_RECOGNITION, com fallback para MIC nativo
            var record: AudioRecord? = null
            val audioSources = listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )

            for (source in audioSources) {
                try {
                    val candidate = AudioRecord(
                        source,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )
                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        record = candidate
                        Log.d(tag, "AudioRecord inicializado com sucesso usando AudioSource=$source (bufferSize=$bufferSize).")
                        break
                    } else {
                        Log.w(tag, "AudioRecord falhou no AudioSource=$source (state=${candidate.state}). Tentando fonte alternativa...")
                        candidate.release()
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Exceção ao instanciar AudioRecord para AudioSource=$source", e)
                }
            }

            if (record == null) {
                val msg = "Falha ao inicializar o hardware de áudio do microfone em todas as fontes nativas."
                Log.e(tag, msg)
                withContext(Dispatchers.Main) { onError(msg) }
                return@withContext false
            }

            audioRecord = record

            try {
                record.startRecording()
            } catch (e: Exception) {
                Log.e(tag, "Exceção ao chamar startRecording no AudioRecord", e)
                cleanUp()
                withContext(Dispatchers.Main) { onError("Não foi possível iniciar a gravação: ${e.localizedMessage}") }
                return@withContext false
            }

            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                val msg = "Microfone bloqueado ou ocupado por outro processo do sistema (recordingState=${record.recordingState})."
                Log.e(tag, msg)
                cleanUp()
                withContext(Dispatchers.Main) { onError(msg) }
                return@withContext false
            }

            _isRecording.value = true

            withContext(Dispatchers.Main) {
                onStarted()
            }

            recordingJob = scope.launch {
                val buffer = ByteArray(bufferSize)
                Log.d(tag, "Thread de leitura contínua de PCM 16kHz iniciada com VAD ativo.")

                var hasDetectedSpeech = false
                var silenceStartTime = 0L
                val startTime = System.currentTimeMillis()

                try {
                    while (isActive && _isRecording.value) {
                        val activeRecord = audioRecord ?: break
                        if (activeRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            Log.w(tag, "AudioRecord saiu do estado nativo de gravação (state=${activeRecord.recordingState}).")
                            break
                        }

                        val bytesRead = activeRecord.read(buffer, 0, buffer.size)

                        if (bytesRead > 0) {
                            // Envia chunk PCM para o decodificador de fala (Vosk/Whisper)
                            onAudioChunk(buffer.copyOf(bytesRead), bytesRead)

                            // Cálculo de RMS para Voice Activity Detection
                            val rms = calculateRms(buffer, bytesRead)
                            val now = System.currentTimeMillis()

                            if (!hasDetectedSpeech && rms >= SPEECH_RMS_THRESHOLD) {
                                hasDetectedSpeech = true
                                silenceStartTime = 0L
                                Log.d(tag, "Voz do usuário detectada pelo VAD (RMS: ${rms.toInt()}).")
                            }

                            if (hasDetectedSpeech) {
                                if (rms < SILENCE_RMS_THRESHOLD) {
                                    if (silenceStartTime == 0L) {
                                        silenceStartTime = now
                                    } else if (now - silenceStartTime >= SILENCE_TIMEOUT_MS) {
                                        Log.d(tag, "Silêncio contínuo após fala detectado (${now - silenceStartTime}ms). Finalizando captura automaticamente.")
                                        withContext(Dispatchers.Main) {
                                            onSilenceDetected()
                                        }
                                        break
                                    }
                                } else {
                                    // Continua emitindo voz
                                    silenceStartTime = 0L
                                }
                            }

                            // Limite máximo de segurança para fala contínua
                            if (now - startTime >= MAX_RECORDING_DURATION_MS) {
                                Log.d(tag, "Tempo limite de escuta alcançado (14s). Concluindo captura.")
                                withContext(Dispatchers.Main) {
                                    onSilenceDetected()
                                }
                                break
                            }

                        } else if (bytesRead < 0) {
                            val errorDesc = when (bytesRead) {
                                AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
                                AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
                                AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
                                AudioRecord.ERROR -> "ERROR"
                                else -> "CÓDIGO_$bytesRead"
                            }
                            Log.e(tag, "Falha na leitura nativa do AudioRecord: $errorDesc")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Exceção crítica durante a leitura contínua da thread de áudio", e)
                } finally {
                    Log.d(tag, "Encerrando thread de captura e liberando recursos.")
                    cleanUp()
                }
            }

            true
        } catch (e: SecurityException) {
            Log.e(tag, "Permissão RECORD_AUDIO não concedida para o AudioRecord", e)
            withContext(Dispatchers.Main) { onError("Permissão de microfone negada.") }
            cleanUp()
            false
        } catch (e: Exception) {
            Log.e(tag, "Erro inesperado ao configurar o AudioRecord", e)
            withContext(Dispatchers.Main) { onError("Erro no hardware de áudio: ${e.localizedMessage}") }
            cleanUp()
            false
        }
    }

    /**
     * Calcula o Root Mean Square (RMS) da amplitude do sinal PCM de 16 bits.
     */
    private fun calculateRms(buffer: ByteArray, bytesRead: Int): Double {
        if (bytesRead < 2) return 0.0
        var sum = 0.0
        val sampleCount = bytesRead / 2
        for (i in 0 until bytesRead - 1 step 2) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val sampleShort = sample.toShort()
            sum += sampleShort * sampleShort
        }
        return sqrt(sum / sampleCount)
    }

    fun stop() {
        Log.d(tag, "Solicitada parada do AudioRecorder.")
        _isRecording.value = false
        recordingJob?.cancel()
        recordingJob = null
        cleanUp()
    }

    private fun cleanUp() {
        try {
            audioRecord?.let { record ->
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            }
        } catch (e: Exception) {
            Log.e(tag, "Erro ao liberar recursos do hardware AudioRecord", e)
        } finally {
            audioRecord = null
            _isRecording.value = false
        }
    }
}
