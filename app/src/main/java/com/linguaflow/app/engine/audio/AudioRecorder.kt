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
                val msg = "Configuração de áudio (16kHz mono 16-bit) incompatível com o hardware."
                Log.e(tag, msg)
                withContext(Dispatchers.Main) { onError(msg) }
                return@withContext false
            }

            val bufferSize = (minBufferSize * 2).coerceAtLeast(3200)

            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                val msg = "Falha ao inicializar o hardware AudioRecord."
                Log.e(tag, msg)
                withContext(Dispatchers.Main) { onError(msg) }
                return@withContext false
            }

            audioRecord = record
            record.startRecording()

            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
                record.release()
                audioRecord = null
                val msg = "Microfone ocupado por outro processo ou indisponível."
                Log.e(tag, msg)
                withContext(Dispatchers.Main) { onError(msg) }
                return@withContext false
            }

            _isRecording.value = true

            // Confirmação segura: o estado "Escutando" só é disparado APÓS confirmação de gravação do hardware
            withContext(Dispatchers.Main) {
                onStarted()
            }

            recordingJob = scope.launch {
                val buffer = ByteArray(bufferSize)
                try {
                    while (isActive && _isRecording.value && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val bytesRead = record.read(buffer, 0, buffer.size)
                        if (bytesRead > 0) {
                            onAudioChunk(buffer, bytesRead)
                        } else if (bytesRead < 0) {
                            Log.w(tag, "Erro na leitura de fluxo do AudioRecord: $bytesRead")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Exceção durante captura de áudio", e)
                } finally {
                    cleanUp()
                }
            }

            true
        } catch (e: SecurityException) {
            Log.e(tag, "Permissão RECORD_AUDIO não concedida para AudioRecord", e)
            withContext(Dispatchers.Main) { onError("Permissão de microfone não concedida.") }
            false
        } catch (e: Exception) {
            Log.e(tag, "Erro inesperado ao iniciar AudioRecord", e)
            withContext(Dispatchers.Main) { onError("Erro na inicialização do microfone: ${e.localizedMessage}") }
            false
        }
    }

    fun stop() {
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
            Log.w(tag, "Erro ao liberar recursos do AudioRecord", e)
        } finally {
            audioRecord = null
            _isRecording.value = false
        }
    }
}
