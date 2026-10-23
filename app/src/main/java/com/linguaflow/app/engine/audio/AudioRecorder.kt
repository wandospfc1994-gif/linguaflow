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

/**
 * Gerenciador simplificado e robusto de captura de áudio PCM 16kHz Mono.
 * Fornece parada manual imediata ao toque do usuário, evitando qualquer travamento
 * de threads ou bloqueio da interface do usuário.
 */
class AudioRecorder(
    private val sampleRate: Int = 16000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val tag = "AudioRecorder"

    private companion object {
        const val SPEECH_RMS_THRESHOLD = 600.0
        const val SILENCE_TIMEOUT_MS = 1800L
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    @Volatile
    var totalBytesRecorded: Long = 0L
        private set

    /**
     * Inicia a captura de áudio PCM 16kHz mono.
     */
    @SuppressLint("MissingPermission")
    suspend fun start(
        onAudioChunk: (ByteArray, Int) -> Unit,
        onStarted: () -> Unit = {},
        onError: (String) -> Unit = {},
        onSilenceDetected: () -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        if (_isRecording.value) {
            Log.w(tag, "AudioRecorder já está gravando. Ignorando chamada repetida.")
            return@withContext true
        }

        totalBytesRecorded = 0L

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufferSize <= 0) {
                val msg = "Configuração de áudio não suportada pelo hardware do dispositivo."
                Log.e(tag, msg)
                withContext(Dispatchers.Main) { onError(msg) }
                return@withContext false
            }

            val bufferSize = maxOf(minBufferSize * 2, 4096)

            // Tenta VOICE_RECOGNITION ou MIC nativo
            var record: AudioRecord? = null
            val sources = listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )

            for (src in sources) {
                try {
                    val candidate = AudioRecord(src, sampleRate, channelConfig, audioFormat, bufferSize)
                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        record = candidate
                        Log.d(tag, "AudioRecord inicializado com sucesso usando source $src.")
                        break
                    } else {
                        candidate.release()
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Tentativa de inicializar AudioRecord com source $src falhou: ${e.message}")
                }
            }

            if (record == null) {
                val msg = "Não foi possível inicializar o hardware do microfone."
                Log.e(tag, msg)
                withContext(Dispatchers.Main) { onError(msg) }
                return@withContext false
            }

            audioRecord = record

            try {
                record.startRecording()
            } catch (e: Exception) {
                Log.e(tag, "Exceção ao iniciar gravação nativa: ${e.message}", e)
                cleanUp()
                withContext(Dispatchers.Main) { onError("Falha ao iniciar microfone: ${e.localizedMessage}") }
                return@withContext false
            }

            _isRecording.value = true

            withContext(Dispatchers.Main) {
                onStarted()
            }

            recordingJob = scope.launch {
                val buffer = ByteArray(bufferSize)
                Log.d(tag, "Loop de leitura PCM 16kHz iniciado.")

                // Detecção simples de silêncio (VAD por energia): depois que o usuário falou,
                // ~1,8 s de silêncio dispara onSilenceDetected uma única vez.
                var heardSpeech = false
                var silenceStartMs = 0L
                var silenceFired = false

                try {
                    while (isActive && _isRecording.value) {
                        val active = audioRecord ?: break
                        val read = active.read(buffer, 0, buffer.size)

                        if (read > 0) {
                            totalBytesRecorded += read
                            onAudioChunk(buffer.copyOf(read), read)

                            if (!silenceFired) {
                                val now = System.currentTimeMillis()
                                if (rms(buffer, read) >= SPEECH_RMS_THRESHOLD) {
                                    heardSpeech = true
                                    silenceStartMs = 0L
                                } else if (heardSpeech) {
                                    if (silenceStartMs == 0L) {
                                        silenceStartMs = now
                                    } else if (now - silenceStartMs >= SILENCE_TIMEOUT_MS) {
                                        silenceFired = true
                                        onSilenceDetected()
                                    }
                                }
                            }
                        } else if (read < 0) {
                            Log.w(tag, "Leitura do AudioRecord retornou código de erro: $read")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Exceção na thread de leitura de áudio", e)
                } finally {
                    cleanUp()
                }
            }

            true
        } catch (e: SecurityException) {
            Log.e(tag, "Permissão RECORD_AUDIO negada", e)
            withContext(Dispatchers.Main) { onError("Permissão de microfone necessária.") }
            cleanUp()
            false
        } catch (e: Exception) {
            Log.e(tag, "Erro inesperado ao inicializar gravação", e)
            withContext(Dispatchers.Main) { onError("Erro no microfone: ${e.localizedMessage}") }
            cleanUp()
            false
        }
    }

    /**
     * Parada manual e imediata da gravação. Libera recursos nativos e encerra jobs.
     */
    fun stop() {
        Log.d(tag, "Parada manual do AudioRecorder solicitada. Total gravado: $totalBytesRecorded bytes.")
        _isRecording.value = false
        recordingJob?.cancel()
        recordingJob = null
        cleanUp()
    }

    /** Energia RMS de um bloco PCM 16-bit little-endian. */
    private fun rms(buf: ByteArray, bytes: Int): Double {
        val samples = bytes / 2
        if (samples == 0) return 0.0
        var sum = 0.0
        var i = 0
        while (i + 1 < bytes) {
            val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sum += sample.toDouble() * sample
            i += 2
        }
        return Math.sqrt(sum / samples)
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
            Log.w(tag, "Aviso ao liberar AudioRecord: ${e.message}")
        } finally {
            audioRecord = null
            _isRecording.value = false
        }
    }
}
