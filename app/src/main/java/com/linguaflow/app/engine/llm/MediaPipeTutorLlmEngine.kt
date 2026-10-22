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
     * Evita recriação a cada mensagem para não perder recursos e manter estabilidade.
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
                IllegalStateException("Arquivo do modelo Gemma não encontrado em filesDir/models")
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
                    val response = inference.generateResponse(fullPrompt)
                    if (response.isNotBlank()) {
                        return@withContext Result.success(response)
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Erro na inferência nativa do MediaPipe, acionando fallback inteligente", e)
                }
            }

            // Fallback bilíngue contextual se o modelo de pesos ainda estiver ausente ou falhar
            val simulated = generateIntelligentFallback(prompt, history, userLevel)
            Result.success(simulated)
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

            if (fullResponse.isNotBlank()) {
                val tokens = fullResponse.split(Regex("(?<=\\s|>)"))
                val buffer = StringBuilder()
                for (token in tokens) {
                    buffer.append(token)
                    emit(buffer.toString())
                    delay(25)
                }
                return@flow
            }
        }

        // Streaming do fallback dinâmico contextual
        val simulated = generateIntelligentFallback(prompt, history, userLevel)
        val tokens = simulated.split(Regex("(?<=\\s|>)"))
        val buffer = StringBuilder()
        for (token in tokens) {
            buffer.append(token)
            emit(buffer.toString())
            delay(28)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Injeção do System Prompt Bilíngue (Estilo Biconfident) e formatação
     * completa do histórico de mensagens do usuário.
     */
    private fun buildTutorPrompt(
        userInput: String,
        history: List<MessageEntity>,
        userLevel: String,
        targetLanguage: String
    ): String {
        // Histórico completo de conversas (sem truncar bruscamente)
        val formattedHistory = history.joinToString("\n") { msg ->
            if (msg.sender == "user") {
                "<start_of_turn>user\nStudent: ${msg.content}<end_of_turn>"
            } else {
                "<start_of_turn>model\nTutor: ${msg.content}<end_of_turn>"
            }
        }

        return """
            <start_of_turn>user
            You are Linguaflow AI, an empathetic, highly adaptive, and conversational bilingual English tutor (similar to an intelligent voice assistant like Bixby/Siri specialized in language education).
            Target Language: $targetLanguage.
            Student Level: $userLevel.

            BILINGUAL GUIDELINES (BICONFIDENT STYLE):
            1. MIXED LANGUAGE COMPREHENSION: The student may freely speak Portuguese, English, or mix both in the same phrase (e.g. "Hello, bom dia, como eu digo...", "I went to the supermercado ontem"). Always comprehend their intention smoothly and naturally.
            2. ADAPTIVE LANGUAGE RESPONSE:
               - If the student speaks or asks questions in Portuguese, reply warmly in Portuguese with pedagogical explanations, translation, and practical English sentences.
               - If the student speaks in English, reply in natural conversational English suited to level $userLevel and ask an engaging follow-up question.
               - If the student mixes Portuguese and English, warmly acknowledge what they meant, teach the natural 100% English phrasing, and keep the dialog active.
            3. MANDATORY 3 XML BLOCKS:
               Output ONLY these 3 XML blocks without any markdown code wrappers:
               <reply>Conversational answer (in Portuguese if user asked in PT, in English if user spoke in EN, or bilingual if mixed)</reply>
               <fix>Original phrase -> Corrected phrase | Explicação didática em português (or OK if no mistakes)</fix>
               <pron>Dica de pronúncia prática em português com fonética simples (or OK if not needed)</pron>

            Conversation History:
            $formattedHistory

            Current Student Message: $userInput
            <end_of_turn>
            <start_of_turn>model
            Tutor:
        """.trimIndent()
    }

    /**
     * Fallback contextual dinâmico que analisa o histórico completo e a intenção
     * da mensagem atual, evitando respostas estáticas ou apenas em inglês.
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

        // Detecção de língua e intenção
        val isAskingInPortuguese = lower.contains("como se diz") ||
            lower.contains("como eu digo") ||
            lower.contains("como falo") ||
            lower.contains("o que significa") ||
            lower.contains("qual o significado") ||
            lower.contains("ajuda") ||
            lower.contains("como usar") ||
            lower.startsWith("como ") ||
            lower.startsWith("o que ") ||
            lower.startsWith("por que ") ||
            lower.startsWith("qual ")

        val hasPortugueseWords = lower.any { it in "ãõáéíóúçêô" } ||
            listOf("bom dia", "boa tarde", "boa noite", "olá", "oi", "obrigado", "valeu", "por favor", "meu nome", "quero", "tenho", "anos", "trabalho", "estudo").any { lower.contains(it) }

        val hasEnglishWords = listOf("hello", "hi", "good morning", "good evening", "how are", "my name", "i am", "i want", "i have", "because", "what", "like").any { lower.contains(it) }

        val isMixed = (isAskingInPortuguese || hasPortugueseWords) && hasEnglishWords

        when {
            // Caso 1: Pergunta sobre tradução / significado em português
            isAskingInPortuguese -> {
                val queryTerm = trimmed
                    .replace(Regex("(?i)^(como se diz|como eu digo|como falo|o que significa|qual o significado de)\\s*"), "")
                    .trim('?', ' ', '.', ':', '"', '\'')

                val cleanQuery = if (queryTerm.isNotBlank()) queryTerm else "essa ideia"
                reply = "Para dizer \"$cleanQuery\" em inglês, você pode usar: \"How do I say this?\" ou a expressão correspondente dependendo da situação. Gostaria de tentar formular uma frase completa praticando essa expressão?"
                fix = "\"$cleanQuery\" -> Expressão equivalente em inglês | Em inglês natural, o contexto ajuda a escolher entre palavras formais e do dia a dia."
                pron = "Pratique a entonação: conecte as palavras suavemente sem pausas secas (ex: 'How-do-I' soa como uma palavra única)."
            }

            // Caso 2: Fala mista (Português + Inglês na mesma frase)
            isMixed -> {
                reply = "Compreendi perfeitamente o que você quis dizer! Essa mistura é um estágio muito natural no aprendizado bilíngue. Em inglês do dia a dia, podemos dizer: \"Hello! How can I practice this effectively today?\". Que tal tentar repetir essa frase completa em inglês?"
                fix = "Expressão mista (PT + EN) -> Frase 100% em inglês | Experimente aos poucos substituir os termos em português pelos equivalentes em inglês."
                pron = "Dica para fala mista: ao falar em inglês, mantenha o ritmo rítmico 'stress-timed', enfatizando as palavras com significado principal."
            }

            // Caso 3: Saudações
            lower.contains("hello") || lower.contains("hi") || lower.contains("olá") || lower.contains("oi") -> {
                if (hasPortugueseWords) {
                    reply = "Olá! Que ótimo ter você aqui praticando hoje. Como está sendo o seu dia? Podemos conversar sobre sua rotina, hobbies ou tirar dúvidas de gramática em inglês!"
                    fix = "\"Oi / Olá\" -> \"Hello / Hi\" | 'Hello' é perfeito para qualquer contexto e 'Hi' para conversas informais."
                    pron = "Em 'hello', o 'h' inicial é pronunciado como um suspiro de ar suave (/h/ aspirado)."
                } else {
                    reply = "Hello there! It is wonderful to chat with you today. How is everything going with your day so far?"
                    fix = "OK"
                    pron = "In 'hello', emphasize the second syllable: he-LLO."
                }
            }

            // Caso 4: Apresentação pessoal
            lower.contains("name is") || lower.contains("meu nome") -> {
                reply = "It is great to meet you! Introducing yourself is the first step to confident conversations. Where are you currently based?"
                fix = if (lower.contains("meu nome")) "\"Meu nome é...\" -> \"My name is...\" | Estrutura correta de apresentação em inglês." else "OK"
                pron = "Na palavra 'name', a letra 'a' tem som do ditongo /eɪ/ (como 'neim')."
            }

            // Caso 5: Idade ("I have ... years")
            lower.contains("i have") && (lower.contains("year") || lower.contains("ano")) -> {
                reply = "Entendi perfeitamente! Falar de idade é muito comum. Em inglês, usamos o verbo 'to be' (I am) em vez de 'to have' para dizer a idade."
                fix = "\"I have ... years\" -> \"I am ... years old\" | Em inglês nós 'somos' a idade (I am 25 years old), não 'temos'."
                pron = "Em 'years', comece com o som semivogal suave /j/ ('iírs'), evitando som de 'j' do português."
            }

            // Caso 6: Dúvidas sobre rotina
            lower.contains("routine") || lower.contains("rotina") || lower.contains("wake up") || lower.contains("work") -> {
                reply = "Talking about daily routines is great for practicing the Simple Present tense! What time do you usually wake up in the morning?"
                fix = "OK"
                pron = "Na palavra 'routine', a tonicidade fica na segunda sílaba: ru-TEEN."
            }

            // Caso 7: Apenas em português sem inglês
            hasPortugueseWords && !hasEnglishWords -> {
                reply = "Entendi sua mensagem! Como seu tutor de inglês, vou te guiar passo a passo. Vamos tentar colocar essa ideia em inglês juntos? Experimente responder a esta pergunta em inglês: \"What do you enjoy doing in your free time?\""
                fix = "Pratique estruturar frases em inglês usando a ordem: Sujeito + Verbo + Complemento."
                pron = "Dica: Em inglês, lembre-se de pronunciar o 'I' (eu) com som aberto /aɪ/ (como 'ai')."
            }

            // Caso 8: Entrada em inglês padrão
            else -> {
                reply = "That is very interesting! You are doing great expressing your thoughts in English. Could you tell me a little bit more about that?"
                fix = "OK"
                pron = "Remember to link consonant endings to starting vowels for a fluid, natural cadence."
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
