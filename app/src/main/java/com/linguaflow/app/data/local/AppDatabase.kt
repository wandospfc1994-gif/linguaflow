package com.linguaflow.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.linguaflow.app.data.local.dao.ConversationDao
import com.linguaflow.app.data.local.dao.MessageDao
import com.linguaflow.app.data.local.dao.ProgressDao
import com.linguaflow.app.data.local.dao.UserStatsDao
import com.linguaflow.app.data.local.entity.ConversationEntity
import com.linguaflow.app.data.local.entity.MessageEntity
import com.linguaflow.app.data.local.entity.ProgressEntity
import com.linguaflow.app.data.local.entity.UserStatsEntity

@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        ProgressEntity::class,
        UserStatsEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun progressDao(): ProgressDao
    abstract fun userStatsDao(): UserStatsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "linguaflow.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
