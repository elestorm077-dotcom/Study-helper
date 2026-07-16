package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestionDao {
    @Query("SELECT * FROM questions ORDER BY timestamp DESC")
    fun getAllQuestionsFlow(): Flow<List<QuestionEntity>>

    @Query("SELECT * FROM questions ORDER BY timestamp DESC")
    suspend fun getAllQuestions(): List<QuestionEntity>

    @Query("SELECT * FROM questions WHERE isSynced = 0")
    suspend fun getUnsyncedQuestions(): List<QuestionEntity>

    @Query("SELECT COUNT(*) FROM questions WHERE timestamp >= :startOfDay AND timestamp <= :endOfDay")
    suspend fun getQuestionCountForDay(startOfDay: Long, endOfDay: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestion(question: QuestionEntity)

    @Update
    suspend fun updateQuestion(question: QuestionEntity)

    @Delete
    suspend fun deleteQuestion(question: QuestionEntity)

    @Query("DELETE FROM questions")
    suspend fun clearAll()
}
