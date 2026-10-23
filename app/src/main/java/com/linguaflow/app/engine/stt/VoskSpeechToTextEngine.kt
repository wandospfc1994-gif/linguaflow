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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * STT offline com Vosk (modelo pequeno em inglês).
 *
 * Regras desta versão (corrigem o travamento do microfone):
 *  - NUNCA inventa texto. Se não reconheceu nada, devolve texto vazio e a UI avisa o usuário.
 *  - stopListening() é idempotente: só a primeira chamada de cada sessão dispara onFinal.
 *    (Antes, cada chamada extra injetava "Hello! How are you?" por cima da fala real.)
 *  - O Recognizer só é usado dentro de recognizerLock, então fechar o recognizer nunca
 *    acontece no meio de acceptWaveForm (evita crash nativo).
 *  - Frases já finalizadas são acumuladas, então o texto parcial não perde o começo da fala.
 */
class VoskSpeechToTextEngine(
    private val context: Context,
    private val modelManager: ModelDownloadManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : SpeechToTextEngine {

    private val tag = "VoskSttEngine"

    private val _state = MutableStateFlow<SttState>(SttState.Idle)
    override val state: StateFlow<SttState> = _state.asStateFlow()

    private var voskModel: Model? = null
    private val modelMutex = Mutex()
    private val audioRecorder = AudioRecorder(sampleRate = 16000, scope = scope)

    private val recognizerLock = Any()
    private var activeRecognizer: Recognizer? = null
    private var committedText = ""
    private var lastPartial = ""

    @Volatile
    private var sessionId = 0

    @Volatile
    private var sessionActive = false

    @Volatile
    private var onPartialCallback: ((String) -> Unit)? = null

    @Volatile
    private var onFinalCallback: ((String, List<WordConfidence>) -> Unit)? = null

    init {
        scope.launch { initModelIfNeeded() }
    }

    private suspend fun initModelIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        modelMutex.withLock {
            if (voskModel != null) return@withLock true
            try {
                val modelDir = getOrExtractModelDirectory()
                if (modelDir != null && modelDir.isDirectory) {
                    Log.d(tag, "Carregando modelo Vosk de: ${modelDir.absolutePath}")
                    voskModel = Model(modelDir.absolutePath)
                    Log.d(tag, "Modelo Vosk carregado.")
                }
            } catch (e: Throwable) {
                Log.e(tag, "Erro ao carregar modelo Vosk: ${e.message}", e)
            }
            voskModel != null
        }
    }

    private fun getOrExtractModelDirectory(): File? {
        val modelsDir = modelManager.modelsDirectory
        if (!modelsDir.exists()) return null

        val existingFolder = modelsDir.listFiles { file ->
            file.isDirectory && file.name.contains("vosk", ignoreCase = true) && File(file, "am").exists()
        }?.firstOrNull()
        if (existingFolder != null) return existingFolder

        val zipFile = modelManager.getModelFile(ModelCatalog.STT_VOSK_EN)
        if (zipFile.exists() && zipFile.length() > 100_000L) {
            try {
                val targetDir = File(modelsDir, "vosk-model-small-en-us-0.15")
                // Extração anterior interrompida deixa a pasta pela metade: refaz do zero.
                if (targetDir.exists() && !File(targetDir, "am").exists()) {
                    targetDir.deleteRecursively()
                }
                if (!targetDir.exists()) {
                    unzip(zipFile, modelsDir)
                }
                return targetDir.takeIf { File(it, "am").exists() }
            } catch (e: Exception) {
                Log.e(tag, "Erro ao descompactar modelo Vosk", e)
            }
        }
        return null
    }

    private fun unzip(zipFile: File, targetDirectory: File) {
        val rootPath = targetDirectory.canonicalPath + File.separator
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(8192)
            while (entry != null) {
                val newFile = File(targetDirectory, entry.name)
                // Proteção contra zip-slip (entradas com "../").
                require(newFile.canonicalPath.startsWith(rootPath)) { "Entrada de zip inválida: ${entry.name}" }
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

    override fun isListening(): Boolean = sessionActive

    override fun startListening(
        onPartial: (String) -> Unit,
        onFinal: (String, List<WordConfidence>) -> Unit,
        onError: (String) -> Unit
    ) {
        // Encerra qualquer sessão anterior SEM disparar callbacks (o bug do texto falso vinha daqui).
        endSession(emitFinal = false)

        onPartialCallback = onPartial
        onFinalCallback = onFinal
        committedText = ""
        lastPartial = ""
        sessionActive = true
        val mySession = ++sessionId

        scope.launch {
            val ready = initModelIfNeeded()
            val model = voskModel

            // O usuário pode ter parado (ou iniciado outra sessão) enquanto o modelo carregava.
            if (mySession != sessionId) return@launch

            if (!ready || model == null) {
                sessionActive = false
                val msg = "Modelo de voz (Vosk) não está instalado. Baixe-o na tela de modelos."
                _state.value = SttState.Error(msg)
                onError(msg)
                return@launch
            }

            val recognizer = try {
                Recognizer(model, 16000.0f).also { it.setWords(true) }
            } catch (e: Throwable) {
                Log.e(tag, "Erro ao criar Recognizer do Vosk", e)
                sessionActive = false
                val msg = "Falha ao iniciar o reconhecimento de voz: ${e.message}"
                _state.value = SttState.Error(msg)
                onError(msg)
                return@launch
            }

            synchronized(recognizerLock) { activeRecognizer = recognizer }

            val started = audioRecorder.start(
                onAudioChunk = { pcm, bytes -> handleChunk(recognizer, pcm, bytes) },
                onStarted = {
                    if (mySession == sessionId) _state.value = SttState.Listening
                },
                onError = { err ->
                    Log.e(tag, "Falha no AudioRecord: $err")
                    endSession(emitFinal = false)
                    _state.value = SttState.Error(err)
                    onError(err)
                },
                onSilenceDetected = {
                    Log.d(tag, "Silêncio detectado: finalizando escuta.")
                    scope.launch(Dispatchers.Main) {
                        if (mySession == sessionId) stopListening()
                    }
                }
            )

            if (!started) {
                // O próprio AudioRecorder já chamou onError; só garante que não sobrou estado.
                endSession(emitFinal = false)
            } else if (mySession != sessionId) {
                // Parou durante a inicialização do microfone.
                audioRecorder.stop()
            }
        }
    }

    /** Processa um bloco PCM. Tudo que toca o Recognizer fica sob lock. */
    private fun handleChunk(recognizer: Recognizer, pcm: ByteArray, bytes: Int) {
        synchronized(recognizerLock) {
            if (activeRecognizer !== recognizer) return
            try {
                if (recognizer.acceptWaveForm(pcm, bytes)) {
                    val text = JSONObject(recognizer.result).optString("text", "").trim()
                    lastPartial = ""
                    if (text.isNotBlank()) {
                        committedText = join(committedText, text)
                        onPartialCallback?.invoke(committedText)
                    }
                } else {
                    val partial = JSONObject(recognizer.partialResult).optString("partial", "").trim()
                    lastPartial = partial
                    if (partial.isNotBlank()) {
                        onPartialCallback?.invoke(join(committedText, partial))
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Erro ao processar bloco PCM no Vosk", e)
            }
        }
    }

    override fun stopListening() {
        endSession(emitFinal = true)
    }

    /**
     * Encerra a sessão atual. Idempotente: só a chamada que encontra a sessão ativa
     * pode emitir o resultado final (e apenas quando emitFinal = true).
     */
    private fun endSession(emitFinal: Boolean) {
        val wasActive = sessionActive
        sessionActive = false
        sessionId++ // invalida coroutines de início ainda pendentes

        audioRecorder.stop()

        var finalText = ""
        synchronized(recognizerLock) {
            val recognizer = activeRecognizer
            activeRecognizer = null
            if (recognizer != null) {
                val tail = try {
                    JSONObject(recognizer.finalResult).optString("text", "").trim()
                } catch (e: Exception) {
                    Log.w(tag, "Erro ao obter finalResult do Vosk", e)
                    ""
                }
                finalText = join(committedText, tail.ifBlank { lastPartial })
                try {
                    recognizer.close()
                } catch (e: Exception) {
                    Log.w(tag, "Erro ao fechar Recognizer", e)
                }
            }
            committedText = ""
            lastPartial = ""
        }

        val callback = onFinalCallback
        _state.value = SttState.Idle
        if (emitFinal && wasActive) {
            callback?.invoke(finalText.trim(), emptyList())
        }
    }

    private fun join(a: String, b: String): String =
        listOf(a, b).filter { it.isNotBlank() }.joinToString(" ")

    override fun release() {
        endSession(emitFinal = false)
        voskModel?.close()
        voskModel = null
        _state.value = SttState.Idle
    }
}
