package com.linguaflow.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_stats")
data class UserStatsEntity(
    @PrimaryKey
    val id: Int = 1,
    val totalWordsLearned: Int = 42,
    val totalSpeakingMinutes: Int = 18,
    val conversationsCount: Int = 3,
    val accuracyPercentage: Int = 94
)
