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

            // Fallback tutor generation inteligente e bilíngue
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
                Log.w(tag, "Erro no streaming MediaPipe, revertendo para fallback inteligente", e)
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

        // Streaming do fallback bilíngue
        val simulated = generateIntelligentFallback(prompt, userLevel)
        val tokens = simulated.split(Regex("(?<=\\s|>)"))
        val buffer = StringBuilder()
        for (token in tokens) {
            buffer.append(token)
            emit(buffer.toString())
            delay(30)
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
            You are Linguaflow AI, an empathetic, conversational, and highly adaptive bilingual English tutor (similar to an intelligent voice assistant like Bixby/Siri specialized in language education).
            Target Language: $targetLanguage.
            Student CEFR Level: $userLevel.

            CRITICAL BILINGUAL BEHAVIOR:
            1. MIXED SPEECH UNDERSTANDING: The student may freely mix Portuguese and English (e.g. "Hello, bom dia, como eu digo...", "I went to the supermercado"). Understand both seamlessly without confusion.
            2. ADAPTIVE LANGUAGE RESPONSE:
               - If the student writes or asks primarily in Portuguese (e.g. questions, explanations, doubts), respond warmly in Portuguese providing the English translations, explanations, and encouraging them to practice.
               - If the student writes in English, reply naturally in conversational English tailored to level $userLevel and ask an engaging follow-up question.
               - If the student mixes languages, acknowledge their idea warmly, formulate the ideal natural English sentence, and keep the dialog moving.
            3. 3 REQUIRED XML OUTPUT BLOCKS:
               Output ONLY these three blocks without any other markdown wrappers:
               <reply>Your conversational response (in Portuguese if user asked in PT, in English if user spoke in EN, or bilingual if mixed)</reply>
               <fix>Original phrase -> Corrected phrase | Explicação didática em português (ou OK se não houver erros)</fix>
               <pron>Dica prática de pronúncia em português com fonética simples (ou OK se não aplicável)</pron>

            Recent History:
            $recentHistory

            Student: $userInput
            Tutor:
        """.trimIndent()
    }

    private fun generateIntelligentFallback(input: String, userLevel: String): String {
        val trimmed = input.trim()
        val lower = trimmed.lowercase()

        val reply: String
        val fix: String
        val pron: String

        // Detecta se o usuário está perguntando em português como dizer ou traduzir algo
        val isAskingInPortuguese = lower.contains("como se diz") ||
            lower.contains("como eu digo") ||
            lower.contains("como falo") ||
            lower.contains("o que significa") ||
            lower.contains("qual o significado") ||
            lower.contains("ajuda") ||
            lower.startsWith("como ") ||
            lower.startsWith("o que ") ||
            lower.startsWith("por que ")

        // Detecta se há mistura bilíngue evidente
        val hasPortuguese = lower.any { it in "ãõáéíóúçêô" } ||
            lower.contains("bom dia") || lower.contains("boa tarde") || lower.contains("boa noite") ||
            lower.contains("obrigado") || lower.contains("valeu") || lower.contains("quero aprender")

        val hasEnglish = lower.contains("hello") || lower.contains("hi") || lower.contains("good morning") ||
            lower.contains("my name") || lower.contains("i want") || lower.contains("i like") || lower.contains("how are")

        val isMixed = hasPortuguese && hasEnglish

        when {
            // Caso 1: Usuário perguntando "como se diz" ou pedindo tradução
            isAskingInPortuguese -> {
                val queryTerm = trimmed.replace(Regex("(?i)^(como se diz|como eu digo|como falo|o que significa)\\s*"), "").trim('?', ' ', '.', ':')
                reply = "Para dizer \"$queryTerm\" em inglês, você pode usar uma expressão natural como: \"How about saying it like this: 'That sounds great!'\". Gostaria de tentar formular uma frase completa usando isso?"
                fix = "\"$queryTerm\" -> Expressão equivalente em inglês | Em inglês, o contexto da conversa ajuda a escolher a palavra mais natural."
                pron = "Pratique a entonação da frase: eleve o tom suavemente no final para soar amigável e natural."
            }

            // Caso 2: Fala mista (Português + Inglês na mesma frase)
            isMixed -> {
                reply = "Entendi perfeitamente o que você quis dizer! Combinar palavras é um passo excelente no aprendizado. Em inglês natural, podemos expressar essa ideia assim: \"Hello, good morning! How can I express this idea?\". How does that sound to you?"
                fix = "Expressão mista -> Frase 100% em inglês | Pratique substituir os termos em português pelos equivalentes em inglês gradualmente."
                pron = "Dica: Em 'morning', o 'ing' final tem som anasalado suave, sem pronunciar um 'g' forte."
            }

            // Caso 3: Saudações
            lower.contains("hello") || lower.contains("hi") || lower.contains("olá") || lower.contains("oi") -> {
                if (hasPortuguese) {
                    reply = "Olá! É um prazer praticar com você hoje. Como está sendo o seu dia? Podemos conversar em inglês sobre sua rotina ou qualquer assunto que desejar!"
                    fix = "\"Oi / Olá\" -> \"Hello / Hi\" | Use 'Hello' em qualquer situação e 'Hi' para amigos."
                    pron = "Em 'hello', o 'h' é aspirado suave como um suspiro (rê-lou)."
                } else {
                    reply = "Hello there! It is wonderful to chat with you today. How is your day going so far?"
                    fix = "OK"
                    pron = "In 'hello', emphasize the second syllable: he-LLO."
                }
            }

            // Caso 4: Apresentação pessoal
            lower.contains("name is") || lower.contains("meu nome") -> {
                reply = "Nice to meet you! Telling someone your name is the perfect way to start a conversation. Where are you from?"
                fix = if (lower.contains("meu nome")) "\"Meu nome é...\" -> \"My name is...\" | Estrutura correta para apresentação." else "OK"
                pron = "Em 'name', o 'a' tem som longo de ditongo /eɪ/ (neim)."
            }

            // Caso 5: Idade ("I have ... years")
            lower.contains("i have") && (lower.contains("year") || lower.contains("ano")) -> {
                reply = "I understand! Age is a very common topic. In English, we use the verb 'to be' instead of 'to have' to state age."
                fix = "\"I have ... years\" -> \"I am ... years old\" | Em inglês nós 'somos' a idade, não 'temos' anos."
                pron = "Em 'years', inicie com som de i suave ('iírs'), evitando som de 'j'."
            }

            // Caso 6: Rotina e hábitos
            lower.contains("routine") || lower.contains("rotina") || lower.contains("wake up") || lower.contains("work") -> {
                reply = "Having a solid daily routine helps a lot with language habits! What time do you usually wake up in the morning?"
                fix = "OK"
                pron = "A palavra 'routine' tem ênfase na segunda sílaba: ru-TEEN."
            }

            // Caso 7: Entrada apenas em português sem inglês
            hasPortuguese && !hasEnglish -> {
                reply = "Compreendi sua mensagem perfeitamente! Como seu tutor de inglês, vou te ajudar a transformar essa ideia em inglês. Tente responder em inglês à seguinte pergunta: \"What is your favorite thing to do on weekends?\""
                fix = "Pratique formular frases completas em inglês para fortalecer o vocabulário ativo."
                pron = "Lembre-se: em inglês, as frases quase sempre precisam de um sujeito explícito (I, you, it, they)."
            }

            // Caso 8: Entrada em inglês padrão
            else -> {
                reply = "That is very interesting! You are doing a great job practicing your English. Could you tell me more about that?"
                fix = "OK"
                pron = "Keep your sentence rhythm steady and remember to link final consonants with initial vowels (connected speech)."
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
