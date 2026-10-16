package com.linguaflow.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey
    val id: Int = 1,
    val currentLevel: String = "A1",
    val currentXp: Int = 50,
    val nextLevelXp: Int = 500,
    val streakDays: Int = 1,
    val completedChallenges: Int = 0,
    val lastActiveDate: String = ""
)
