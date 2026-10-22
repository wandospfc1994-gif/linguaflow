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

    @SuppressLint("MissingPermission")
    suspend fun start(
        onAudioChunk: (ByteArray, Int) -> Unit,
        onStarted: () -> Unit = {},
        onError: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        if (_isRecording.value) {
            Log.w(tag, "AudioRecorder já está em gravação ativa.")
            return@withContext true
        }

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                val msg = "Configuração de áudio PCM (16kHz mono 16-bit) não suportada pelo hardware do dispositivo."
                Log.e(tag, "Erro de buffer AudioRecord: $msg (minBufferSize=$minBufferSize)")
                withContext(Dispatchers.Main) { onError(msg) }
                return@withContext false
            }

            val bufferSize = maxOf(minBufferSize * 2, 4096)

            // Tenta inicializar com VOICE_RECOGNITION, com fallback automático para MIC
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
                        Log.d(tag, "AudioRecord inicializado com sucesso usando AudioSource $source (bufferSize=$bufferSize).")
                        break
                    } else {
                        Log.w(tag, "AudioRecord falhou na inicialização com AudioSource $source (state=${candidate.state}). Tentando próximo...")
                        candidate.release()
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Exceção ao instanciar AudioRecord com AudioSource $source", e)
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
                Log.d(tag, "Thread de leitura contínua de PCM 16kHz iniciada com buffer de ${buffer.size} bytes.")

                try {
                    while (isActive && _isRecording.value) {
                        val activeRecord = audioRecord ?: break
                        if (activeRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            Log.w(tag, "AudioRecord saiu do estado de gravação nativo (recordingState=${activeRecord.recordingState}).")
                            break
                        }

                        val bytesRead = activeRecord.read(buffer, 0, buffer.size)

                        if (bytesRead > 0) {
                            // Repassa bytes PCM continuamente para o motor STT
                            onAudioChunk(buffer.copyOf(bytesRead), bytesRead)
                        } else if (bytesRead < 0) {
                            val errorDesc = when (bytesRead) {
                                AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
                                AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
                                AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
                                AudioRecord.ERROR -> "ERROR"
                                else -> "CÓDIGO_$bytesRead"
                            }
                            Log.e(tag, "Falha na leitura do AudioRecord: $errorDesc")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Exceção crítica durante a leitura contínua da thread de áudio", e)
                } finally {
                    Log.d(tag, "Finalizando thread de captura de áudio.")
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
