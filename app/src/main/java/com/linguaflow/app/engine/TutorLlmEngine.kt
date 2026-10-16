package com.linguaflow.app.engine

import com.linguaflow.app.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

interface TutorLlmEngine {
    val isModelLoaded: Boolean
    suspend fun loadModel(): Result<Unit>
    suspend fun generateTutorReply(
        prompt: String,
        history: List<MessageEntity>,
        userLevel: String = "A1",
        targetLanguage: String = "Inglês"
    ): Result<String>
    fun generateTutorReplyStream(
        prompt: String,
        history: List<MessageEntity>,
        userLevel: String = "A1",
        targetLanguage: String = "Inglês"
    ): Flow<String>
    fun unload()
}
