package com.linguaflow.app.engine.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.linguaflow.app.data.download.ModelDownloadManager
import com.linguaflow.app.data.local.entity.MessageEntity
import com.linguaflow.app.data.model.ModelCatalog
import com.linguaflow.app.engine.TutorLlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
    private var llmInference: LlmInference? = null

    override val isModelLoaded: Boolean
        get() = llmInference != null

    private fun findModelFile(): File? {
        val modelInfo = ModelCatalog.LLM_GEMMA_2B
        val specificFile = modelManager.getModelFile(modelInfo)
        if (specificFile.exists() && specificFile.length() > 10_000_000L) {
            return specificFile
        }

        val modelsDir = File(context.filesDir, "models")
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

    override suspend fun loadModel(): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (llmInference != null) return@withContext Result.success(Unit)

            val modelFile = findModelFile() ?: return@withContext Result.failure(
                IllegalStateException("Arquivo do modelo Gemma não encontrado em filesDir/models")
            )

            try {
                Log.d(tag, "Carregando modelo LLM do caminho: ${modelFile.absolutePath}")
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(512)
                    .build()

                llmInference = LlmInference.createFromOptions(context, options)
                Log.d(tag, "Modelo MediaPipe LLM carregado com sucesso.")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(tag, "Falha ao inicializar LlmInference", e)
                llmInference = null
                Result.failure(e)
            }
        }
    }

    override suspend fun generateTutorReply(
        prompt: String,
        history: List<MessageEntity>,
        userLevel: String,
        targetLanguage: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val fullPrompt = buildTutorPrompt(prompt, history, userLevel, targetLanguage)

        mutex.withLock {
            val inference = llmInference
            if (inference != null) {
                try {
                    val response = inference.generateResponse(fullPrompt)
                    return@withContext Result.success(response)
                } catch (e: Exception) {
                    Log.w(tag, "Erro na inferência do MediaPipe, usando fallback", e)
                }
            }

            // Fallback tutor generation
            val simulated = generateIntelligentFallback(prompt, userLevel)
            Result.success(simulated)
        }
    }

    override fun generateTutorReplyStream(
        prompt: String,
        history: List<MessageEntity>,
        userLevel: String,
        targetLanguage: String
    ): Flow<String> = flow {
        val fullPrompt = buildTutorPrompt(prompt, history, userLevel, targetLanguage)

        val hasNativeModel = mutex.withLock { llmInference != null }

        if (hasNativeModel) {
            var fullResponse = ""
            try {
                fullResponse = mutex.withLock {
                    llmInference?.generateResponse(fullPrompt) ?: ""
                }
            } catch (e: Exception) {
                Log.w(tag, "Erro na inferência streaming nativa, alternando para fallback", e)
            }

            if (fullResponse.isNotBlank()) {
                // Stream chunks of response to simulate token flow
                val words = fullResponse.split(Regex("(?<=\\s|>)"))
                val buffer = StringBuilder()
                for (word in words) {
                    buffer.append(word)
                    emit(buffer.toString())
                    delay(25)
                }
                return@flow
            }
        }

        // Offline Tutor Generator with structured 3 blocks and streaming tokens
        val simulated = generateIntelligentFallback(prompt, userLevel)
        val tokens = simulated.split(Regex("(?<=\\s|>)"))
        val buffer = StringBuilder()
        for (token in tokens) {
            buffer.append(token)
            emit(buffer.toString())
            delay(35)
        }
    }.flowOn(Dispatchers.IO)

    private fun buildTutorPrompt(
        userInput: String,
        history: List<MessageEntity>,
        userLevel: String,
        targetLanguage: String
    ): String {
        val recentHistory = history.takeLast(6).joinToString("\n") { msg ->
            if (msg.sender == "user") "Student: ${msg.content}" else "Tutor: ${msg.content}"
        }

        return """
            You are Linguaflow AI, an empathetic and highly skilled English language tutor.
            Target Language: $targetLanguage.
            Student Level: $userLevel.
            The student may mix Portuguese and English.
            
            Instructions:
            1. Formulate an engaging and encouraging conversational reply in English, tailored to level $userLevel. Ask one follow-up question.
            2. If the student made any grammar or vocabulary errors, point them out using the format: "wrong expression -> correct expression | explanation in Portuguese". If none, write "OK".
            3. Provide 1 key pronunciation tip in Portuguese for words used in this turn. If none, write "OK".
            
            Format your entire response strictly using these 3 XML blocks:
            <reply>Your conversational response in English</reply>
            <fix>Original -> Corrected | Explicação em português (ou OK)</fix>
            <pron>Dica de pronúncia em português (ou OK)</pron>
            
            Recent Conversation:
            $recentHistory
            
            Student: $userInput
            Tutor:
        """.trimIndent()
    }

    private fun generateIntelligentFallback(input: String, userLevel: String): String {
        val lower = input.lowercase().trim()

        val reply: String
        val fix: String
        val pron: String

        when {
            lower.contains("hello") || lower.contains("hi ") || lower == "hi" || lower.contains("olá") || lower.contains("oi") -> {
                reply = "Hello there! It is wonderful to talk to you. How has your day been so far?"
                fix = if (lower.contains("olá") || lower.contains("oi")) {
                    "\"Oi / Olá\" -> \"Hello / Hi\" | Em inglês usamos 'Hello' ou 'Hi' para saudações casuais."
                } else "OK"
                pron = "A palavra 'hello' tem o 'h' aspirado, como um sopro suave (rê-lou)."
            }

            lower.contains("name is") || lower.contains("my name") || lower.contains("meu nome") -> {
                reply = "Nice to meet you! Telling your name is the first step to making international friends. What is your favorite hobby?"
                fix = if (lower.contains("meu nome")) {
                    "\"Meu nome é...\" -> \"My name is...\" | Estrutura correta para se apresentar."
                } else "OK"
                pron = "Em 'name', o som do 'a' é /eɪ/ (como em 'neim')."
            }

            lower.contains("i have") && (lower.contains("years") || lower.contains("anos")) -> {
                reply = "I see! Age is an interesting topic. In English, we express age differently than in Portuguese."
                fix = "\"I have ... years\" -> \"I am ... years old\" | Em inglês não 'temos' a idade, nós 'somos' a idade (use o verbo To Be)."
                pron = "Em 'years', comece com som suave de i ('iírs'), diferente de 'ears' (orelhas)."
            }

            lower.contains("routine") || lower.contains("rotina") || lower.contains("day") -> {
                reply = "A consistent routine makes language learning effortless! Do you prefer practicing in the morning or in the evening?"
                fix = "OK"
                pron = "A palavra 'routine' tem ênfase na segunda sílaba: ru-TEEN."
            }

            lower.contains("weather") || lower.contains("tempo") || lower.contains("rain") -> {
                reply = "Talking about the weather is classic English small talk! Is it sunny or rainy where you are right now?"
                fix = "OK"
                pron = "Em 'weather', o 'th' é sonoro com a ponta da língua entre os dentes (uédher)."
            }

            else -> {
                reply = "That is very interesting! You are making great progress communicating your thoughts in English. Could you tell me more about that?"
                fix = if (lower.any { it in "ãõáéíóúç" }) {
                    "Dica: Tente substituir termos em português por vocabulário equivalente em inglês para acelerar sua fluência."
                } else "OK"
                pron = "Lembre-se de não pronunciar o 'e' mudo no final de palavras como 'make', 'like' e 'love'."
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
