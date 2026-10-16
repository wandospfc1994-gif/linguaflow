package com.linguaflow.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.linguaflow.app.data.local.entity.ProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress WHERE id = 1")
    fun getProgress(): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress WHERE id = 1")
    suspend fun getProgressDirect(): ProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(progress: ProgressEntity)

    @Query("UPDATE progress SET currentXp = currentXp + :xpAdded WHERE id = 1")
    suspend fun addXp(xpAdded: Int)

    @Query("UPDATE progress SET streakDays = streakDays + 1, completedChallenges = completedChallenges + 1 WHERE id = 1")
    suspend fun incrementStreakAndChallenges()

    @Query("UPDATE progress SET streakDays = :streak, completedChallenges = completedChallenges + 1, currentXp = currentXp + :xp, currentLevel = :level, nextLevelXp = :nextXp, lastActiveDate = :today WHERE id = 1")
    suspend fun updateChallengeProgress(streak: Int, xp: Int, level: String, nextXp: Int, today: String)
}
