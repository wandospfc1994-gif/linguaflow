package com.linguaflow.app.di

import android.content.Context
import com.linguaflow.app.data.download.ModelDownloadManager
import com.linguaflow.app.data.local.AppDatabase
import com.linguaflow.app.data.repository.LinguaflowRepository
import com.linguaflow.app.engine.SpeechToTextEngine
import com.linguaflow.app.engine.TextToSpeechEngine
import com.linguaflow.app.engine.TutorLlmEngine
import com.linguaflow.app.engine.llm.MediaPipeTutorLlmEngine
import com.linguaflow.app.engine.stt.SherpaWhisperSpeechToTextEngine
import com.linguaflow.app.engine.stt.SmartSpeechToTextEngine
import com.linguaflow.app.engine.stt.VoskSpeechToTextEngine
import com.linguaflow.app.engine.tts.AndroidNativeTtsEngine
import com.linguaflow.app.engine.tts.SherpaPiperTextToSpeechEngine
import com.linguaflow.app.engine.tts.SmartTextToSpeechEngine

interface AppContainer {
    val repository: LinguaflowRepository
    val modelManager: ModelDownloadManager
    val ttsEngine: TextToSpeechEngine
    val tutorLlm: TutorLlmEngine
    val sttEngine: SpeechToTextEngine
    val runtimeManager: com.linguaflow.app.engine.ModelRuntimeManager
}

class DefaultAppContainer(private val context: Context) : AppContainer {
    private val database: AppDatabase by lazy {
        AppDatabase.getDatabase(context)
    }

    override val runtimeManager: com.linguaflow.app.engine.ModelRuntimeManager by lazy {
        com.linguaflow.app.engine.ModelRuntimeManager(context)
    }

    override val repository: LinguaflowRepository by lazy {
        LinguaflowRepository(
            messageDao = database.messageDao(),
            conversationDao = database.conversationDao(),
            progressDao = database.progressDao(),
            userStatsDao = database.userStatsDao()
        )
    }

    override val modelManager: ModelDownloadManager by lazy {
        ModelDownloadManager(context)
    }

    private val nativeTtsEngine: AndroidNativeTtsEngine by lazy {
        AndroidNativeTtsEngine(context)
    }

    private val sherpaPiperTtsEngine: SherpaPiperTextToSpeechEngine by lazy {
        SherpaPiperTextToSpeechEngine(
            context = context,
            modelManager = modelManager,
            fallbackNativeEngine = nativeTtsEngine
        )
    }

    override val ttsEngine: TextToSpeechEngine by lazy {
        SmartTextToSpeechEngine(
            piperEngine = sherpaPiperTtsEngine,
            nativeEngine = nativeTtsEngine
        )
    }

    override val tutorLlm: TutorLlmEngine by lazy {
        MediaPipeTutorLlmEngine(context, modelManager)
    }

    private val voskSttEngine: VoskSpeechToTextEngine by lazy {
        VoskSpeechToTextEngine(context, modelManager)
    }

    private val sherpaWhisperSttEngine: SherpaWhisperSpeechToTextEngine by lazy {
        SherpaWhisperSpeechToTextEngine(
            context = context,
            modelManager = modelManager,
            fallbackVoskEngine = voskSttEngine
        )
    }

    override val sttEngine: SpeechToTextEngine by lazy {
        SmartSpeechToTextEngine(
            whisperEngine = sherpaWhisperSttEngine,
            voskEngine = voskSttEngine
        )
    }
}
