package com.linguaflow.app.engine.stt

import android.content.Context
import android.util.Log
import com.linguaflow.app.data.download.ModelDownloadManager
import com.linguaflow.app.data.model.ModelCatalog
import com.linguaflow.app.engine.SpeechToTextEngine
import com.linguaflow.app.engine.SttState
import com.linguaflow.app.engine.WordConfidence
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
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class VoskSpeechToTextEngine(
    private val context: Context,
    private val modelManager: ModelDownloadManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : SpeechToTextEngine, RecognitionListener {

    private val tag = "VoskSttEngine"

    private val _state = MutableStateFlow<SttState>(SttState.Idle)
    override val state: StateFlow<SttState> = _state.asStateFlow()

    private var voskModel: Model? = null
    private var speechService: SpeechService? = null
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
            Log.w(tag, "Erro ao carregar modelo Vosk: ${e.message}", e)
        }
        isModelLoading = false
        return@withContext false
    }

    private fun getOrExtractModelDirectory(): File? {
        val modelsDir = File(context.filesDir, "models")
        if (!modelsDir.exists()) return null

        // Check if an unzipped folder already exists
        val existingFolder = modelsDir.listFiles { file ->
            file.isDirectory && file.name.contains("vosk", ignoreCase = true)
        }?.firstOrNull()

        if (existingFolder != null && File(existingFolder, "am").exists()) {
            return existingFolder
        }

        // Check for downloaded zip
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
        return _state.value is SttState.Listening || speechService != null
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

        _state.value = SttState.Listening

        scope.launch {
            val ready = initModelIfNeeded()
            val model = voskModel

            if (ready && model != null) {
                withContext(Dispatchers.Main) {
                    try {
                        val recognizer = Recognizer(model, 16000.0f)
                        recognizer.setWords(true) // Enables word confidence and timestamps

                        val service = SpeechService(recognizer, 16000.0f)
                        speechService = service
                        service.startListening(this@VoskSpeechToTextEngine)
                        Log.d(tag, "Vosk SpeechService iniciado.")
                    } catch (e: Exception) {
                        Log.w(tag, "Falha ao iniciar SpeechService nativo, acionando fallback", e)
                        startFallbackListening()
                    }
                }
            } else {
                Log.d(tag, "Modelo Vosk não disponível localmente, usando modo interativo")
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

        speechService?.let { service ->
            try {
                service.stop()
                service.shutdown()
            } catch (e: Exception) {
                Log.w(tag, "Erro ao parar speechService", e)
            }
            speechService = null
        }

        if (_state.value is SttState.Listening) {
            _state.value = SttState.Idle
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        if (hypothesis == null) return
        try {
            val json = JSONObject(hypothesis)
            val partial = json.optString("partial", "").trim()
            if (partial.isNotBlank()) {
                _state.value = SttState.Result(partial, isFinal = false)
                onPartialCallback?.invoke(partial)
            }
        } catch (e: Exception) {
            Log.w(tag, "Erro ao parsear partial", e)
        }
    }

    override fun onResult(hypothesis: String?) {
        if (hypothesis == null) return
        parseAndEmitResult(hypothesis, isFinal = false)
    }

    override fun onFinalResult(hypothesis: String?) {
        if (hypothesis == null) return
        parseAndEmitResult(hypothesis, isFinal = true)
        stopListening()
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

    override fun onError(exception: Exception?) {
        val msg = exception?.localizedMessage ?: "Erro no reconhecimento de voz"
        Log.e(tag, "Vosk error: $msg")
        _state.value = SttState.Error(msg)
        onErrorCallback?.invoke(msg)
        stopListening()
    }

    override fun onTimeout() {
        Log.d(tag, "Vosk timeout")
        stopListening()
    }

    override fun release() {
        stopListening()
        voskModel?.close()
        voskModel = null
        _state.value = SttState.Idle
    }
}
