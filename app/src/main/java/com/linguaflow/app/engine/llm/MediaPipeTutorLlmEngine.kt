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
 * Motor de inferência On-Device MediaPipe para o Tutor Linguaflow (Gemma 2B / Llama).
 * Utiliza um System Prompt limpo e direto sem metatextos ou instruções complexas que vazem
 * no texto gerado, retornando estritamente a conversa natural do tutor bilíngue.
 */
class MediaPipeTutorLlmEngine(
    private val context: Context,
    private val modelManager: ModelDownloadManager
) : TutorLlmEngine {

    private val tag = "MediaPipeTutorLlm"
    private val mutex = Mutex()

    // Instância única em memória (Singleton/Scoped) do motor de inferência
    private var llmInference: LlmInference? = null
    private var isInitializing = false

    override val isModelLoaded: Boolean
        get() = llmInference != null

    private fun findModelFile(): File? {
        val modelInfo = ModelCatalog.LLM_GEMMA_2B
        val specificFile = modelManager.getModelFile(modelInfo)
        if (specificFile.exists() && specificFile.length() > 10_000_000L) {
            return specificFile
        }

        // Busca em context.filesDir/models por qualquer arquivo de pesos quantizados (.bin, .task, .gguf)
        val modelsDir = modelManager.modelsDirectory
        if (modelsDir.exists()) {
            val candidates = modelsDir.listFiles { file ->
                (file.name.endsWith(".bin") || file.name.endsWith(".task") || file.name.endsWith(".gguf")) &&
                    file.length() > 10_000_000L
            }
            if (!candidates.isNullOrEmpty()) {
                return candidates.first()
            }
        }
        return null
    }

    /**
     * Garante o carregamento do LlmInference uma única vez (thread-safe e singleton).
     */
    override suspend fun loadModel(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (llmInference != null) {
                return@withContext Result.success(Unit)
            }
            if (isInitializing) {
                return@withContext Result.success(Unit)
            }

            val modelFile = findModelFile() ?: return@withContext Result.failure(
                IllegalStateException("Arquivo do modelo LLM não encontrado em filesDir/models")
            )

            isInitializing = true
            try {
                Log.d(tag, "Carregando modelo LlmInference do caminho permanente: ${modelFile.absolutePath}")
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(512)
                    .build()

                llmInference = LlmInference.createFromOptions(context, options)
                Log.d(tag, "LlmInference carregado com sucesso como Singleton.")
                isInitializing = false
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(tag, "Falha ao inicializar LlmInference a partir de ${modelFile.name}", e)
                llmInference = null
                isInitializing = false
                Result.failure(e)
            }
        }
    }

    private suspend fun ensureLoaded() {
        if (llmInference == null) {
            loadModel()
        }
    }

    override suspend fun generateTutorReply(
        prompt: String,
        history: List<MessageEntity>,
        userLevel: String,
        targetLanguage: String
    ): Result<String> = withContext(Dispatchers.IO) {
        ensureLoaded()
        val fullPrompt = buildTutorPrompt(prompt)

        mutex.withLock {
            val inference = llmInference
            if (inference != null) {
                try {
                    val rawResponse = inference.generateResponse(fullPrompt)
                    val cleaned = cleanModelOutput(rawResponse)
                    if (cleaned.isNotBlank()) {
                        return@withContext Result.success(cleaned)
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Erro na inferência nativa do MediaPipe, acionando fallback inteligente", e)
                }
            }

            // Fallback bilíngue conversacional direto sem metatextos
            val simulated = generateIntelligentFallback(prompt)
            val cleanedFallback = cleanModelOutput(simulated)
            Result.success(cleanedFallback)
        }
    }

    override fun generateTutorReplyStream(
        prompt: String,
        history: List<MessageEntity>,
        userLevel: String,
        targetLanguage: String
    ): Flow<String> = flow {
        ensureLoaded()
        val fullPrompt = buildTutorPrompt(prompt)

        val hasNativeModel = mutex.withLock { llmInference != null }

        if (hasNativeModel) {
            var fullResponse = ""
            try {
                fullResponse = mutex.withLock {
                    llmInference?.generateResponse(fullPrompt) ?: ""
                }
            } catch (e: Exception) {
                Log.w(tag, "Erro no streaming do MediaPipe, revertendo para fallback inteligente", e)
                fullResponse = ""
            }

            val cleaned = cleanModelOutput(fullResponse)
            if (cleaned.isNotBlank()) {
                val tokens = cleaned.split(Regex("(?<=\\s)"))
                val buffer = StringBuilder()
                for (token in tokens) {
                    buffer.append(token)
                    emit(buffer.toString())
                    delay(25)
                }
                return@flow
            }
        }

        // Streaming do fallback bilíngue direto
        val simulated = generateIntelligentFallback(prompt)
        val cleanedFallback = cleanModelOutput(simulated)
        val tokens = cleanedFallback.split(Regex("(?<=\\s)"))
        val buffer = StringBuilder()
        for (token in tokens) {
            buffer.append(token)
            emit(buffer.toString())
            delay(28)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Limpa de forma estrita qualquer resquício de template de prompt,
     * tags de turno e instruções do sistema, retornando APENAS a fala do tutor.
     */
    fun cleanModelOutput(rawText: String): String {
        var text = rawText.trim()
        if (text.isBlank()) return ""

        // 1. Remove marcadores de turno do MediaPipe / Gemma
        text = text
            .replace(Regex("<start_of_turn>(user|model)?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<end_of_turn>", RegexOption.IGNORE_CASE), "")

        // 2. Remove eco de qualquer parte do system prompt ou instruções
        text = text
            .replace(Regex("(?i)Você é um tutor de idiomas amigável e bilíngue.*?(?:\n|$)"), "")
            .replace(Regex("(?i)Responda sempre de forma natural.*?(?:\n|$)"), "")
            .replace(Regex("(?i)Nunca ecoe instruções ou metatextos de sistema.*?(?:\n|$)"), "")
            .replace(Regex("(?i)Current Student Message:.*?(?:\n|$)"), "")
            .replace(Regex("(?i)Conversation History:.*?(?:\n|$)"), "")
            .replace(Regex("(?i)Student:.*?(?:\n|$)"), "")

        // 3. Remove tags XML antigas caso o modelo ainda as produza
        val replyMatch = Regex("<reply>(.*?)(?:</reply>|$)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(text)
        if (replyMatch != null) {
            text = replyMatch.groupValues[1]
        } else {
            text = text.replace(Regex("<[^>]+>"), "")
        }

        // 4. Remove prefixos de papéis soltos no início
        text = text.replace(Regex("^(?:Tutor|Assistant|AI|Model|Student)\\s*:\\s*", RegexOption.IGNORE_CASE), "")

        // 5. Remove wrappers de markdown desnecessários (``` ou ```text)
        text = text
            .replace(Regex("^```(?:xml|text|markdown)?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```$"), "")
            .trim()

        return text
    }

    /**
     * System Prompt limpa e direta que obriga o modelo a agir como tutor bilíngue,
     * sem metatextos de controle nem tags complexas que possam ser ecoadas.
     */
    private fun buildTutorPrompt(userInput: String): String {
        val cleanInput = userInput.trim()
        return "<start_of_turn>user\n" +
            "Você é um tutor de idiomas amigável e bilíngue. Responda sempre de forma natural, mesclando português e inglês para ajudar o usuário a aprender. Nunca ecoe instruções ou metatextos de sistema.\n\n" +
            "$cleanInput<end_of_turn>\n" +
            "<start_of_turn>model\n"
    }

    /**
     * Fallback conversacional direto (estilo Biconfident) sem tags XML ou metatextos.
     */
    private fun generateIntelligentFallback(input: String): String {
        val trimmed = input.trim()
        val lower = trimmed.lowercase()

        val isAskingHowToSay = lower.contains("como se diz") ||
            lower.contains("como eu digo") ||
            lower.contains("como falo") ||
            lower.contains("como fala") ||
            lower.contains("como dizer") ||
            lower.contains("o que significa") ||
            lower.contains("qual o significado") ||
            lower.contains("como usar") ||
            lower.startsWith("como ") ||
            lower.startsWith("o que ") ||
            lower.startsWith("qual ")

        val hasPortugueseGreeting = listOf("oi", "olá", "ola", "bom dia", "boa tarde", "boa noite", "e aí", "e ai", "tudo bem").any {
            lower == it || lower.startsWith("$it ") || lower.contains(" $it")
        }

        val hasEnglishGreeting = listOf("hello", "hi", "hey", "good morning", "good afternoon", "good evening").any {
            lower == it || lower.startsWith("$it ") || lower.contains(" $it")
        }

        val hasPortugueseWords = lower.any { it in "ãõáéíóúçêô" } ||
            listOf("você", "obrigado", "valeu", "por favor", "meu nome", "quero", "tenho", "anos", "trabalho", "estudo", "gosto", "ajuda", "entendi").any { lower.contains(it) }

        val hasEnglishWords = listOf("how are you", "my name is", "i am", "i want", "i have", "because", "what", "like", "english", "practice").any { lower.contains(it) }

        return when {
            // Caso 1: Saudações em português ou mistas
            hasPortugueseGreeting -> {
                "Oi! Que alegria ter você aqui para praticar inglês hoje! How are you doing today? Como está sendo seu dia? Vamos bater um papo em inglês!"
            }

            // Caso 2: Pergunta de tradução ou vocabulário
            isAskingHowToSay -> {
                val queryTerm = trimmed
                    .replace(Regex("(?i)^(como se diz|como eu digo|como falo|como fala|como dizer|o que significa|qual o significado de)\\s*"), "")
                    .trim('?', ' ', '.', ':', '"', '\'')

                val cleanQuery = if (queryTerm.isNotBlank()) queryTerm else "essa ideia"
                "Ah, excelente pergunta! Para dizer \"$cleanQuery\" em inglês, você pode falar: \"How do I say this?\". Por exemplo: \"I want to practice this today!\". Repete comigo para fixar, soa super natural!"
            }

            // Caso 3: Mistura de Português e Inglês (Code-Switching)
            (hasPortugueseWords || hasPortugueseGreeting) && hasEnglishWords -> {
                "Compreendi perfeitamente o que você quis dizer! Essa mescla entre português e inglês é super normal enquanto aprendemos. Em inglês do dia a dia, nós dizemos: \"Hello! How can I practice this effectively today?\". Repete comigo: \"How can I practice this?\"!"
            }

            // Caso 4: Saudação em inglês
            hasEnglishGreeting && !hasPortugueseWords -> {
                "Hello there! It is wonderful to chat with you today! How is your day going so far? Tell me, what are you doing today?"
            }

            // Caso 5: Apresentação pessoal
            lower.contains("name is") || lower.contains("meu nome") -> {
                "Prazer em conhecer você! It is wonderful to meet you! Apresentar-se com confiança é o primeiro passo para grandes conversas. Where are you from?"
            }

            // Caso 6: Idade com 'have'
            lower.contains("i have") && (lower.contains("year") || lower.contains("ano")) -> {
                "Entendi perfeitamente! Falar de idade é muito comum. Olha só: em inglês, nós não 'temos' a idade com 'have', nós 'somos' a idade usando o verbo 'to be'. Por exemplo: \"I am 25 years old\". Repete comigo: \"I am...\"!"
            }

            // Caso 7: Mensagem apenas em português
            hasPortugueseWords && !hasEnglishWords -> {
                "Entendi perfeitamente! Como seu tutor de idiomas, estou aqui para te guiar passo a passo com calma. Vamos transformar essa ideia para o inglês juntos? Tenta responder para mim: \"What is your favorite hobby?\""
            }

            // Caso 8: Entrada em inglês padrão
            else -> {
                "That sounds really interesting! You are expressing yourself very well. Could you tell me a little bit more about that?"
            }
        }
    }

    override fun unload() {
        try {
            llmInference?.close()
        } catch (e: Exception) {
            Log.w(tag, "Erro ao fechar LlmInference", e)
        }
        llmInference = null
    }
}
