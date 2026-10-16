package com.linguaflow.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conversationId: Long = 1L,
    val sender: String, // "user" or "assistant"
    val content: String,
    val translation: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
