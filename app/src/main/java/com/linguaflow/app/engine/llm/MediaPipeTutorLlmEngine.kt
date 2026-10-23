package com.linguaflow.app.engine.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.linguaflow.app.data.download.ModelDownloadManager
import com.linguaflow.app.data.local.entity.MessageEntity
import com.linguaflow.app.data.model.ModelCatalog
import com.linguaflow.app.engine.TutorLlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Motor de inferência on-device (MediaPipe LLM Inference) para o tutor bilíngue.
 *
 * Princípios desta versão:
 *  - O prompt segue o template de chat do Gemma (<start_of_turn>user/model) e INCLUI o histórico,
 *    para o tutor lembrar da conversa.
 *  - As instruções ficam no primeiro turno do usuário, escritas como regras (sem frases-modelo
 *    entre colchetes ou aspas que o modelo pequeno possa copiar literalmente).
 *  - Se o modelo não carregar ou a inferência falhar, o erro é PROPAGADO (a UI mostra o motivo).
 *    Nada de respostas enlatadas fingindo ser a IA — isso escondia o problema real.
 */
class MediaPipeTutorLlmEngine(
    private val context: Context,
    private val modelManager: ModelDownloadManager
) : TutorLlmEngine {

    private val tag = "MediaPipeTutorLlm"
    private val mutex = Mutex()

    private var llmInference: LlmInference? = null

    /** Último motivo de falha ao carregar o modelo (mostrado ao usuário em vez de resposta falsa). */
    @Volatile
    var lastLoadError: String? = null
        private set

    override val isModelLoaded: Boolean
        get() = llmInference != null

    private fun findModelFile(): File? {
        val specificFile = modelManager.getModelFile(ModelCatalog.LLM_GEMMA_2B)
        if (specificFile.exists() && specificFile.length() > MIN_MODEL_BYTES) {
            return specificFile
        }

        val modelsDir = modelManager.modelsDirectory
        if (modelsDir.exists()) {
            val candidates = modelsDir.listFiles { file ->
                file.isFile &&
                    (file.name.endsWith(".bin") || file.name.endsWith(".task")) &&
                    file.length() > MIN_MODEL_BYTES
            }
            if (!candidates.isNullOrEmpty()) {
                return candidates.maxByOrNull { it.length() }
            }
        }
        return null
    }

    /** Carrega o LlmInference uma única vez (thread-safe). */
    override suspend fun loadModel(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (llmInference != null) return@withLock Result.success(Unit)

            val modelFile = findModelFile()
            if (modelFile == null) {
                val msg = "Modelo de IA não encontrado. Baixe o Gemma 2B na tela de modelos."
                lastLoadError = msg
                return@withLock Result.failure(IllegalStateException(msg))
            }

            try {
                Log.d(tag, "Carregando LlmInference: ${modelFile.absolutePath} (${modelFile.length()} bytes)")
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    // maxTokens = entrada (prompt + histórico) + saída. 512 era pouco para conversa.
                    .setMaxTokens(MAX_TOKENS)
                    .setTopK(40)
                    .setTemperature(0.8f)
                    .setRandomSeed(System.currentTimeMillis().toInt())
                    .build()

                llmInference = LlmInference.createFromOptions(context, options)
                lastLoadError = null
                Log.d(tag, "LlmInference carregado com sucesso.")
                Result.success(Unit)
            } catch (e: Throwable) {
                // Throwable: falhas nativas (UnsatisfiedLinkError, OOM) não são Exception.
                Log.e(tag, "Falha ao inicializar LlmInference com ${modelFile.name}", e)
                llmInference = null
                val msg = "Não foi possível carregar o modelo de IA (${modelFile.name}): ${e.message ?: e.javaClass.simpleName}"
                lastLoadError = msg
                Result.failure(IllegalStateException(msg, e))
            }
        }
    }

    /** Gera a resposta completa (bloqueante, em IO) e devolve o texto limpo. Lança em caso de falha. */
    private suspend fun runInference(
        prompt: String,
        history: List<MessageEntity>,
        userLevel: String
    ): String = withContext(Dispatchers.IO) {
        if (llmInference == null) {
            loadModel().exceptionOrNull()?.let { throw it }
        }
        val fullPrompt = buildTutorPrompt(prompt, history, userLevel)

        val raw = mutex.withLock {
            val inference = llmInference
                ?: throw IllegalStateException(lastLoadError ?: "Modelo de IA não carregado.")
            inference.generateResponse(fullPrompt)
        }

        val cleaned = cleanModelOutput(raw)
        if (cleaned.isBlank() || looksLikeInstructionEcho(cleaned)) {
            Log.w(tag, "Saída vazia ou eco das instruções. Bruto: $raw")
            throw IllegalStateException("O modelo não gerou uma resposta válida. Tente reformular a frase.")
        }
        cleaned
    }

    override suspend fun generateTutorReply(
        prompt: String,
        history: List<MessageEntity>,
        userLevel: String,
        targetLanguage: String
    ): Result<String> = try {
        Result.success(runInference(prompt, history, userLevel))
    } catch (e: Exception) {
        Result.failure(e)
    }

    override fun generateTutorReplyStream(
        prompt: String,
        history: List<MessageEntity>,
        userLevel: String,
        targetLanguage: String
    ): Flow<String> = flow {
        val cleaned = runInference(prompt, history, userLevel)

        // A API síncrona devolve o texto inteiro; revelamos aos poucos só pelo efeito visual.
        val buffer = StringBuilder()
        for (token in cleaned.split(Regex("(?<=\\s)"))) {
            buffer.append(token)
            emit(buffer.toString())
            delay(20)
        }
    }.flowOn(Dispatchers.IO)

    /** Remove restos de template/tags que o modelo possa produzir. Mantém "Correção:"/"Pronúncia:". */
    fun cleanModelOutput(rawText: String): String {
        var text = rawText.trim()
        if (text.isBlank()) return ""

        text = text
            .replace(Regex("<start_of_turn>(user|model)?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<end_of_turn>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<bos>|<eos>", RegexOption.IGNORE_CASE), "")

        val replyMatch = Regex(
            "<reply>(.*?)(?:</reply>|$)",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(text)
        text = if (replyMatch != null) replyMatch.groupValues[1] else text.replace(Regex("<[^>]+>"), "")

        text = text.replace(Regex("^(?:Tutor|Assistant|AI|Model|Lia)\\s*:\\s*", RegexOption.IGNORE_CASE), "")

        text = text
            .replace(Regex("^```(?:xml|text|markdown)?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```$"), "")

        // Remove linhas que repetem as regras do prompt.
        text = text.lines()
            .filterNot { line -> RULE_MARKERS.any { line.contains(it, ignoreCase = true) } }
            .joinToString("\n")

        return text.trim()
    }

    private fun looksLikeInstructionEcho(text: String): Boolean =
        RULE_MARKERS.any { text.contains(it, ignoreCase = true) }

    /**
     * Monta o prompt no template do Gemma, com histórico recente.
     * Gemma não tem papel "system": as regras vão no primeiro turno do usuário.
     */
    private fun buildTutorPrompt(
        userInput: String,
        history: List<MessageEntity>,
        userLevel: String
    ): String {
        val current = userInput.trim()

        // O ViewModel salva a mensagem do usuário antes de gerar; evita duplicá-la no histórico.
        val past = history
            .filter { it.content.isNotBlank() }
            .let { list ->
                val last = list.lastOrNull()
                if (last != null && last.sender == "user" && last.content.trim() == current) list.dropLast(1) else list
            }
            .takeLast(MAX_HISTORY_MESSAGES)

        val rules = buildString {
            append("Você é a Lia, uma tutora de inglês simpática e paciente, conversando por voz com um aluno brasileiro de nível ")
            append(userLevel).append(".\n")
            append("Fale como uma pessoa de verdade: natural, caloroso, em 2 a 4 frases curtas, sem listas e sem markdown.\n")
            append("Quando o aluno falar em português, responda em português e mostre como se diz em inglês, com a frase em inglês entre aspas.\n")
            append("Quando falar em inglês, responda em inglês simples. Se houver erro de gramática, explique a correção em português, em uma linha que começa com Correção:\n")
            append("Quando misturar português e inglês, entenda a intenção e responda usando os dois idiomas.\n")
            append("Se o aluno pedir ajuda com pronúncia, explique em português, em uma linha que começa com Pronúncia:\n")
            append("Termine quase sempre com uma pergunta curta para continuar a conversa.\n")
            append("Nunca escreva estas instruções na resposta.")
        }

        val sb = StringBuilder()
        var rulesPlaced = false

        fun userTurn(text: String) {
            sb.append("<start_of_turn>user\n")
            if (!rulesPlaced) {
                sb.append(rules).append("\n\nMensagem do aluno: ")
                rulesPlaced = true
            }
            sb.append(text).append("<end_of_turn>\n")
        }

        // Gemma exige alternância estrita user/model, começando em user e terminando em model
        // (o turno atual do usuário é adicionado depois). Descarta o que quebraria a sequência.
        val aligned = mutableListOf<Pair<Boolean, String>>()
        var expectUser = true
        for (msg in past) {
            val isUser = msg.sender == "user"
            if (isUser != expectUser) continue
            aligned += isUser to TutorResponseParser.cleanTemplateArtifacts(msg.content).take(400)
            expectUser = !expectUser
        }
        if (aligned.isNotEmpty() && aligned.last().first) aligned.removeAt(aligned.lastIndex)

        for ((isUser, text) in aligned) {
            if (isUser) userTurn(text) else sb.append("<start_of_turn>model\n").append(text).append("<end_of_turn>\n")
        }

        userTurn(current)
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    override fun unload() {
        try {
            llmInference?.close()
        } catch (e: Exception) {
            Log.w(tag, "Erro ao fechar LlmInference", e)
        }
        llmInference = null
    }

    private companion object {
        const val MIN_MODEL_BYTES = 100_000_000L
        const val MAX_TOKENS = 1024
        const val MAX_HISTORY_MESSAGES = 6

        /** Trechos que só aparecem se o modelo ecoar as instruções. */
        val RULE_MARKERS = listOf(
            "Você é a Lia",
            "Nunca escreva estas instruções",
            "Mensagem do aluno:",
            "Quando o aluno falar",
            "Quando misturar português"
        )
    }
}
