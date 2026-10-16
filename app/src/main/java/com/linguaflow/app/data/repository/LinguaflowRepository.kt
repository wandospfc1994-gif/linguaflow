package com.linguaflow.app.data.repository

import com.linguaflow.app.data.local.dao.ConversationDao
import com.linguaflow.app.data.local.dao.MessageDao
import com.linguaflow.app.data.local.dao.ProgressDao
import com.linguaflow.app.data.local.dao.UserStatsDao
import com.linguaflow.app.data.local.entity.ConversationEntity
import com.linguaflow.app.data.local.entity.MessageEntity
import com.linguaflow.app.data.local.entity.ProgressEntity
import com.linguaflow.app.data.local.entity.UserStatsEntity
import com.linguaflow.app.data.model.CourseTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LinguaflowRepository(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val progressDao: ProgressDao,
    private val userStatsDao: UserStatsDao
) {
    fun getMessages(conversationId: Long = 1L): Flow<List<MessageEntity>> =
        messageDao.getMessagesForConversation(conversationId)

    suspend fun sendMessage(content: String, conversationId: Long = 1L): Long {
        val userMsgId = messageDao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                sender = "user",
                content = content
            )
        )
        // Add words learned and XP for practice
        val wordsCount = content.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        userStatsDao.addWordsLearned(wordsCount.coerceAtLeast(1))
        addXp(15)
        return userMsgId
    }

    suspend fun saveAssistantMessage(content: String, conversationId: Long = 1L, translation: String? = null): Long {
        return messageDao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                sender = "assistant",
                content = content,
                translation = translation
            )
        )
    }

    suspend fun clearMessages(conversationId: Long = 1L) =
        messageDao.clearMessages(conversationId)

    fun getProgress(): Flow<ProgressEntity?> = progressDao.getProgress()

    suspend fun initProgressIfNeeded() {
        val current = progressDao.getProgress().firstOrNull()
        if (current == null) {
            val initialXp = 50
            val (level, nextXp) = CourseTrack.calculateLevelForXp(initialXp)
            progressDao.upsertProgress(
                ProgressEntity(
                    id = 1,
                    currentLevel = level,
                    currentXp = initialXp,
                    nextLevelXp = nextXp,
                    streakDays = 3,
                    completedChallenges = 2,
                    lastActiveDate = getTodayDateString()
                )
            )
        }
    }

    suspend fun addXp(amount: Int) {
        val current = progressDao.getProgressDirect()
        val currentXp = current?.currentXp ?: 50
        val newXp = currentXp + amount
        val (newLevel, nextLevelXp) = CourseTrack.calculateLevelForXp(newXp)

        if (current != null) {
            progressDao.upsertProgress(
                current.copy(
                    currentXp = newXp,
                    currentLevel = newLevel,
                    nextLevelXp = nextLevelXp
                )
            )
        } else {
            progressDao.upsertProgress(
                ProgressEntity(
                    id = 1,
                    currentLevel = newLevel,
                    currentXp = newXp,
                    nextLevelXp = nextLevelXp
                )
            )
        }
    }

    suspend fun completeDailyChallenge(xpReward: Int = 100, wordsLearned: Int = 12) {
        val today = getTodayDateString()
        val current = progressDao.getProgressDirect()
        val currentStreak = current?.streakDays ?: 1
        val lastDate = current?.lastActiveDate ?: ""

        val newStreak = if (lastDate == today) {
            currentStreak
        } else {
            currentStreak + 1
        }

        val currentXp = current?.currentXp ?: 50
        val newXp = currentXp + xpReward
        val (newLevel, nextLevelXp) = CourseTrack.calculateLevelForXp(newXp)

        progressDao.updateChallengeProgress(
            streak = newStreak,
            xp = xpReward,
            level = newLevel,
            nextXp = nextLevelXp,
            today = today
        )
        userStatsDao.addWordsLearned(wordsLearned)
    }

    fun getStats(): Flow<UserStatsEntity?> = userStatsDao.getStats()

    suspend fun initStatsIfNeeded() {
        val current = userStatsDao.getStats().firstOrNull()
        if (current == null) {
            userStatsDao.upsertStats(
                UserStatsEntity(
                    id = 1,
                    totalWordsLearned = 42,
                    totalSpeakingMinutes = 18,
                    conversationsCount = 5,
                    accuracyPercentage = 94
                )
            )
        }
    }

    fun getConversations(): Flow<List<ConversationEntity>> =
        conversationDao.getAllConversations()

    suspend fun createInitialConversationIfNeeded() {
        val current = conversationDao.getConversationById(1L).firstOrNull()
        if (current == null) {
            val conv = ConversationEntity(
                id = 1L,
                title = "Prática Básica de Conversação",
                targetLanguage = "Inglês",
                userLevel = "A1",
                summary = "Conversa inicial de boas-vindas e prática de saudações em inglês."
            )
            conversationDao.insertConversation(conv)

            // Seed a welcome assistant message
            messageDao.insertMessage(
                MessageEntity(
                    conversationId = 1L,
                    sender = "assistant",
                    content = "Hello! Welcome to Linguaflow AI. How can I help you practice English today?",
                    translation = "Olá! Bem-vindo ao Linguaflow AI. Como posso te ajudar a praticar inglês hoje?"
                )
            )
        }
    }

    private fun getTodayDateString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date())
    }
}
