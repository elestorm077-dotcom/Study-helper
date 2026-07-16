package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "questions")
data class QuestionEntity(
    @PrimaryKey val id: String,
    val questionText: String,
    val imageUrl: String?,
    val responseText: String,
    val language: String,
    val timestamp: Long,
    val isSynced: Boolean = false
)
