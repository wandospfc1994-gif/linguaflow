package com.linguaflow.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.linguaflow.app.data.local.entity.UserStatsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserStatsDao {
    @Query("SELECT * FROM user_stats WHERE id = 1")
    fun getStats(): Flow<UserStatsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStats(stats: UserStatsEntity)

    @Query("UPDATE user_stats SET totalWordsLearned = totalWordsLearned + :words WHERE id = 1")
    suspend fun addWordsLearned(words: Int)

    @Query("UPDATE user_stats SET conversationsCount = conversationsCount + 1 WHERE id = 1")
    suspend fun incrementConversations()
}
