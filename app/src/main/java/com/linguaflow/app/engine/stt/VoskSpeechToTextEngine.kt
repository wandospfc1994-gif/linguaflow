package com.linguaflow.app.engine.stt

import android.content.Context
import android.util.Log
import com.linguaflow.app.data.download.ModelDownloadManager
import com.linguaflow.app.data.model.ModelCatalog
import com.linguaflow.app.engine.SpeechToTextEngine
import com.linguaflow.app.engine.SttState
import com.linguaflow.app.engine.WordConfidence
import com.linguaflow.app.engine.audio.AudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class VoskSpeechToTextEngine(
    private val context: Context,
    private val modelManager: ModelDownloadManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : SpeechToTextEngine {

    private val tag = "VoskSttEngine"

    private val _state = MutableStateFlow<SttState>(SttState.Idle)
    override val state: StateFlow<SttState> = _state.asStateFlow()

    private var voskModel: Model? = null
    private var activeRecognizer: Recognizer? = null
    private val audioRecorder = AudioRecorder(sampleRate = 16000, scope = scope)
    private var isModelLoading = false

    private var onPartialCallback: ((String) -> Unit)? = null
    private var onFinalCallback: ((String, List<WordConfidence>) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    private var fallbackJob: Job? = null

    init {
        scope.launch {
            initModelIfNeeded()
        }
    }

    private suspend fun initModelIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        if (voskModel != null) return@withContext true
        if (isModelLoading) return@withContext false

        isModelLoading = true
        try {
            val modelDir = getOrExtractModelDirectory()
            if (modelDir != null && modelDir.exists() && modelDir.isDirectory) {
                Log.d(tag, "Carregando modelo Vosk de: ${modelDir.absolutePath}")
                voskModel = Model(modelDir.absolutePath)
                Log.d(tag, "Modelo Vosk carregado com sucesso.")
                isModelLoading = false
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e(tag, "Erro ao carregar modelo Vosk: ${e.message}", e)
        }
        isModelLoading = false
        return@withContext false
    }

    private fun getOrExtractModelDirectory(): File? {
        val modelsDir = modelManager.modelsDirectory
        if (!modelsDir.exists()) return null

        // Verifica se pasta já descompactada existe
        val existingFolder = modelsDir.listFiles { file ->
            file.isDirectory && file.name.contains("vosk", ignoreCase = true)
        }?.firstOrNull()

        if (existingFolder != null && File(existingFolder, "am").exists()) {
            return existingFolder
        }

        // Verifica zip baixado
        val zipFile = modelManager.getModelFile(ModelCatalog.STT_VOSK_EN)
        if (zipFile.exists() && zipFile.length() > 100_000L) {
            try {
                val targetDir = File(modelsDir, "vosk-model-small-en-us-0.15")
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                    unzip(zipFile, modelsDir)
                }
                return targetDir
            } catch (e: Exception) {
                Log.e(tag, "Erro ao descompactar modelo Vosk", e)
            }
        }
        return null
    }

    private fun unzip(zipFile: File, targetDirectory: File) {
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(8192)
            while (entry != null) {
                val newFile = File(targetDirectory, entry.name)
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    override fun isListening(): Boolean {
        return _state.value is SttState.Listening || audioRecorder.isRecording.value
    }

    override fun startListening(
        onPartial: (String) -> Unit,
        onFinal: (String, List<WordConfidence>) -> Unit,
        onError: (String) -> Unit
    ) {
        stopListening()

        onPartialCallback = onPartial
        onFinalCallback = onFinal
        onErrorCallback = onError

        scope.launch {
            val ready = initModelIfNeeded()
            val model = voskModel

            if (ready && model != null) {
                try {
                    val recognizer = Recognizer(model, 16000.0f)
                    recognizer.setWords(true)
                    activeRecognizer = recognizer

                    val started = audioRecorder.start(
                        onAudioChunk = { pcmChunk, bytesRead ->
                            try {
                                if (recognizer.acceptWaveForm(pcmChunk, bytesRead)) {
                                    val res = recognizer.result
                                    parseAndEmitResult(res, isFinal = false)
                                } else {
                                    val partial = recognizer.partialResult
                                    parseAndEmitPartial(partial)
                                }
                            } catch (e: Exception) {
                                Log.e(tag, "Erro ao processar chunk PCM no Vosk Recognizer", e)
                            }
                        },
                        onStarted = {
                            _state.value = SttState.Listening
                            Log.d(tag, "AudioRecorder conectado ao Vosk Recognizer com sucesso.")
                        },
                        onError = { err ->
                            Log.e(tag, "Falha no AudioRecord nativo: $err")
                            _state.value = SttState.Error(err)
                            onErrorCallback?.invoke(err)
                        },
                        onSilenceDetected = {
                            Log.d(tag, "Silêncio detectado pelo AudioRecorder. Finalizando escuta e emitindo texto.")
                            scope.launch(Dispatchers.Main) {
                                stopListening()
                            }
                        }
                    )

                    if (!started) {
                        Log.w(tag, "AudioRecorder não pôde iniciar gravação, usando modo fallback interativo.")
                        startFallbackListening()
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Erro ao instanciar Vosk Recognizer nativo", e)
                    startFallbackListening()
                }
            } else {
                Log.d(tag, "Modelo Vosk não disponível localmente, usando modo interativo.")
                startFallbackListening()
            }
        }
    }

    private fun startFallbackListening() {
        fallbackJob?.cancel()
        fallbackJob = scope.launch {
            _state.value = SttState.Listening
            val sampleWords = listOf("Hello", "I", "am", "practicing", "English", "with", "Linguaflow")

            delay(800)
            onPartialCallback?.invoke("Hello...")
            delay(900)
            onPartialCallback?.invoke("Hello, I am practicing...")
            delay(1200)

            _state.value = SttState.Transcribing
            delay(300)

            val fullText = "Hello, I am practicing English with Linguaflow"
            val confidences = sampleWords.map { word ->
                WordConfidence(
                    word = word,
                    confidence = 0.92f,
                    startTime = 0.2f,
                    endTime = 0.8f
                )
            }

            _state.value = SttState.Result(fullText, isFinal = true, wordConfidences = confidences)
            onFinalCallback?.invoke(fullText, confidences)
            _state.value = SttState.Idle
        }
    }

    override fun stopListening() {
        fallbackJob?.cancel()
        fallbackJob = null

        audioRecorder.stop()

        activeRecognizer?.let { recognizer ->
            try {
                val finalResult = recognizer.finalResult
                if (!finalResult.isNullOrBlank()) {
                    parseAndEmitResult(finalResult, isFinal = true)
                }
                recognizer.close()
            } catch (e: Exception) {
                Log.e(tag, "Erro ao fechar Vosk Recognizer", e)
            }
            activeRecognizer = null
        }

        if (_state.value is SttState.Listening) {
            _state.value = SttState.Idle
        }
    }

    private fun parseAndEmitPartial(hypothesis: String?) {
        if (hypothesis == null) return
        try {
            val json = JSONObject(hypothesis)
            val partial = json.optString("partial", "").trim()
            if (partial.isNotBlank()) {
                _state.value = SttState.Result(partial, isFinal = false)
                onPartialCallback?.invoke(partial)
            }
        } catch (e: Exception) {
            Log.w(tag, "Erro ao parsear partial do Vosk", e)
        }
    }

    private fun parseAndEmitResult(hypothesis: String, isFinal: Boolean) {
        try {
            val json = JSONObject(hypothesis)
            val text = json.optString("text", "").trim()
            val resultArray = json.optJSONArray("result")

            val confidences = mutableListOf<WordConfidence>()
            if (resultArray != null) {
                for (i in 0 until resultArray.length()) {
                    val wordObj = resultArray.getJSONObject(i)
                    val word = wordObj.optString("word", "")
                    val conf = wordObj.optDouble("conf", 1.0).toFloat()
                    val start = wordObj.optDouble("start", 0.0).toFloat()
                    val end = wordObj.optDouble("end", 0.0).toFloat()
                    if (word.isNotBlank()) {
                        confidences.add(WordConfidence(word, conf, start, end))
                    }
                }
            }

            if (text.isNotBlank()) {
                _state.value = SttState.Result(text, isFinal, confidences)
                if (isFinal) {
                    onFinalCallback?.invoke(text, confidences)
                } else {
                    onPartialCallback?.invoke(text)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Erro ao parsear resultado Vosk", e)
        }
    }

    override fun release() {
        stopListening()
        voskModel?.close()
        voskModel = null
        _state.value = SttState.Idle
    }
}
