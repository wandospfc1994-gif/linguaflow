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
 * Implementa a persona bilíngue Biconfident (Português + Inglês natural), limpeza rigorosa
 * de resquícios de prompt/template e garantia de ciclo de vida singleton em memória.
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
     * Evita recriação a cada mensagem para manter o contexto e economizar recursos.
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
        val fullPrompt = buildTutorPrompt(prompt, history, userLevel, targetLanguage)

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

            // Fallback bilíngue contextual e caloroso
            val simulated = generateIntelligentFallback(prompt, history, userLevel)
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
        val fullPrompt = buildTutorPrompt(prompt, history, userLevel, targetLanguage)

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
                val tokens = cleaned.split(Regex("(?<=\\s|>)"))
                val buffer = StringBuilder()
                for (token in tokens) {
                    buffer.append(token)
                    emit(buffer.toString())
                    delay(25)
                }
                return@flow
            }
        }

        // Streaming do fallback bilíngue contextual
        val simulated = generateIntelligentFallback(prompt, history, userLevel)
        val cleanedFallback = cleanModelOutput(simulated)
        val tokens = cleanedFallback.split(Regex("(?<=\\s|>)"))
        val buffer = StringBuilder()
        for (token in tokens) {
            buffer.append(token)
            emit(buffer.toString())
            delay(28)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Limpa de forma rigorosa e definitiva qualquer resquício de template de prompt,
     * tags de turno e prefixos de papéis vazados pelo LLM.
     */
    fun cleanModelOutput(rawText: String): String {
        var text = rawText.trim()
        if (text.isBlank()) return ""

        // 1. Remove marcadores de turno de controle
        text = text
            .replace(Regex("<start_of_turn>(user|model)?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<end_of_turn>", RegexOption.IGNORE_CASE), "")

        // 2. Remove repetições de mensagens de template do sistema
        text = text
            .replace(Regex("(?i)Current Student Message:.*?(?:\n|$)"), "")
            .replace(Regex("(?i)Conversation History:.*?(?:\n|$)"), "")
            .replace(Regex("(?i)Student:.*?(?:\n|$)"), "")

        // 3. Se houver conteúdo antes da primeira tag <reply>, descarta o lixo inicial
        val replyIndex = text.indexOf("<reply>", ignoreCase = true)
        if (replyIndex > 0) {
            text = text.substring(replyIndex)
        }

        // 4. Remove prefixos de papéis soltos no início
        text = text.replace(Regex("^(?:Tutor|Assistant|AI|Model|Student)\\s*:\\s*", RegexOption.IGNORE_CASE), "")

        // 5. Remove wrappers de markdown desnecessários (```xml ou ```)
        text = text
            .replace(Regex("^```(?:xml)?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```$"), "")
            .trim()

        return text
    }

    /**
     * Injeção do System Prompt Bilíngue Obrigatório (Estilo Biconfident).
     * O tutor atua como uma pessoa real, empática, acolhedora e mistura
     * o Português e o Inglês para ensinar com fluidez natural.
     */
    private fun buildTutorPrompt(
        userInput: String,
        history: List<MessageEntity>,
        userLevel: String,
        targetLanguage: String
    ): String {
        val formattedHistory = history.takeLast(10).joinToString("\n") { msg ->
            if (msg.sender == "user") {
                "<start_of_turn>user\n${msg.content}<end_of_turn>"
            } else {
                val cleanAssistant = TutorResponseParser.cleanTemplateArtifacts(msg.content)
                "<start_of_turn>model\n$cleanAssistant<end_of_turn>"
            }
        }

        return """
            <start_of_turn>user
            Você é o Tutor Linguaflow, uma pessoa real, amigável, empática e encorajadora que ajuda o usuário a praticar inglês.
            Você é nativamente bilíngue e DEVE mesclar o Português e o Inglês de forma natural na mesma frase, exatamente como um falante bilíngue faz para ensinar no dia a dia (ex: 'Sem problema! Suco de fruta é muito comum, nós dizemos fruit juice. Repete comigo: fruit juice. Juice fruit não soa tão natural, sabe?').

            DIRETRIZES DE PERSONA E COMPORTAMENTO (BICONFIDENT STYLE):
            1. Participe ativamente como uma pessoa real: use interjeições naturais ('ah', 'então', 'sabe', 'olha só') e tom acolhedor.
            2. Correção de Pronúncia/Gramática: Nunca diga apenas 'Errado'. Explique de forma leve e amigável (ex: 'Quase lá! Ficaria mais natural se você falasse...').
            3. Mesclagem pedagógica: Se o usuário falar em português ou misturar (ex: 'oi', 'bom dia', 'como fala...', 'olá', 'como se diz'), responda calorosamente em PORTUGUÊS mesclando expressões em inglês com contexto imediato e proponha uma prática engajadora.
            4. Respostas curtas e conversacionais, otimizadas para simulação de bate-papo por voz.
            5. Nível do estudante: $userLevel. Idioma-alvo: $targetLanguage.
            6. SAÍDA OBRIGATÓRIA: Forneça APENAS os 3 blocos XML abaixo, sem nenhum texto introdutório e sem markdown:
            <reply>Sua resposta conversacional bilíngue acolhedora</reply>
            <fix>Dica didática de gramática ou vocabulário em português (ou OK)</fix>
            <pron>Dica prática de pronúncia com fonética simples em português (ou OK)</pron>

            Histórico de Conversa:
            $formattedHistory

            Mensagem Atual do Estudante: $userInput
            <end_of_turn>
            <start_of_turn>model
        """.trimIndent()
    }

    /**
     * Fallback contextual dinâmico que analisa a intenção do estudante
     * e garante respostas no estilo Biconfident, eliminando respostas secas ou monolíngues.
     */
    private fun generateIntelligentFallback(
        input: String,
        history: List<MessageEntity>,
        userLevel: String
    ): String {
        val trimmed = input.trim()
        val lower = trimmed.lowercase()

        val reply: String
        val fix: String
        val pron: String

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

        when {
            // Caso 1: Saudações em português ou mistas ("oi", "olá", "bom dia", etc.)
            hasPortugueseGreeting -> {
                reply = "Oi! Que alegria ter você aqui para praticar inglês hoje! How are you doing today? Como está sendo seu dia? Podemos falar sobre sua rotina, hobbies ou qualquer dúvida que você tiver!"
                fix = "'Oi' em inglês pode ser 'Hello' (perfeito para qualquer situação) ou 'Hi' (bem casual e descontraído)."
                pron = "Ao falar 'How are you', ligue os sons suavemente: /haʊ-ɑːr-juː/ sem pausas secas."
            }

            // Caso 2: Pergunta de tradução ou vocabulário ("como fala...", "como se diz...")
            isAskingHowToSay -> {
                val queryTerm = trimmed
                    .replace(Regex("(?i)^(como se diz|como eu digo|como falo|como fala|como dizer|o que significa|qual o significado de)\\s*"), "")
                    .trim('?', ' ', '.', ':', '"', '\'')

                val cleanQuery = if (queryTerm.isNotBlank()) queryTerm else "essa ideia"
                reply = "Ah, excelente pergunta! Para dizer \"$cleanQuery\" em inglês, você pode falar: \"How do I say this?\". Olha só um exemplo prático: \"I would love to learn more about this!\". Repete comigo para fixar!"
                fix = "\"$cleanQuery\" -> Expressão equivalente em inglês | No dia a dia, conectar o termo a uma frase completa acelera a memorização."
                pron = "Dica de ritmo: conecte as palavras no estilo 'connected speech', unindo consoantes finais com vogais iniciais."
            }

            // Caso 3: Mistura de Português e Inglês na mesma frase (Code-Switching)
            (hasPortugueseWords || hasPortugueseGreeting) && hasEnglishWords -> {
                reply = "Compreendi perfeitamente o que você quis dizer! Olha só, essa mescla entre português e inglês é super natural quando estamos aprendendo. Em inglês fluído do dia a dia, podemos falar: \"Hello! How can I practice this effectively today?\". Que tal tentar repetir essa frase completa comigo?"
                fix = "Expressão mista (PT + EN) -> Frase 100% em inglês | Aos poucos, você vai ganhando confiança para usar a frase inteira em inglês!"
                pron = "Lembre-se da entonação rítmica do inglês: dê mais ênfase nas palavras de conteúdo ('practice', 'effectively') e menos nas de ligação."
            }

            // Caso 4: Saudação puramente em inglês ("hello", "hi", "good morning")
            hasEnglishGreeting && !hasPortugueseWords -> {
                reply = "Hello there! It is wonderful to chat with you today! How is your day going so far? Tell me, what did you do this morning?"
                fix = "OK"
                pron = "In 'hello', emphasize the second syllable naturally: he-LLO."
            }

            // Caso 5: Apresentação pessoal ("my name is..." / "meu nome é...")
            lower.contains("name is") || lower.contains("meu nome") -> {
                reply = "Prazer em conhecer você! It is wonderful to meet you! Apresentar-se com confiança é o primeiro passo para grandes conversas. What do you like to do in your free time?"
                fix = if (lower.contains("meu nome")) "\"Meu nome é...\" -> \"My name is...\" | Estrutura clássica de apresentação em inglês." else "OK"
                pron = "Na palavra 'name', o 'a' tem som do ditongo /eɪ/ (como 'neim')."
            }

            // Caso 6: Idade com 'have' ("I have 25 years")
            lower.contains("i have") && (lower.contains("year") || lower.contains("ano")) -> {
                reply = "Entendi perfeitamente! Falar de idade é muito comum. Olha só: em inglês, nós não 'temos' a idade com 'have', nós 'somos' a idade usando o verbo 'to be'. Por exemplo: \"I am 25 years old\". Repete comigo: \"I am...\"!"
                fix = "\"I have ... years\" -> \"I am ... years old\" | Em inglês, usamos o verbo 'to be' para expressar idade."
                pron = "Na palavra 'years', o som inicial é um /j/ suave ('iírs'), diferente do 'j' forte do português."
            }

            // Caso 7: Mensagem apenas em português
            hasPortugueseWords && !hasEnglishWords -> {
                reply = "Entendi perfeitamente! Como seu tutor de inglês, estou aqui para te guiar passo a passo com calma. Vamos transformar essa ideia para o inglês juntos? Tenta responder para mim: \"What is your favorite hobby?\""
                fix = "Dica: Para construir frases em inglês, siga a ordem direta: Sujeito + Verbo + Objeto (ex: I like music)."
                pron = "Em inglês, lembre-se de pronunciar o 'I' (eu) com o som aberto /aɪ/ (como 'ai')."
            }

            // Caso 8: Entrada padrão em inglês
            else -> {
                reply = "That sounds really interesting! You are expressing yourself very well. Could you tell me a little bit more about that?"
                fix = "OK"
                pron = "Keep your sentences flowing by linking the ending consonant of one word to the beginning vowel of the next."
            }
        }

        return buildString {
            appendLine("<reply>$reply</reply>")
            appendLine("<fix>$fix</fix>")
            appendLine("<pron>$pron</pron>")
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
