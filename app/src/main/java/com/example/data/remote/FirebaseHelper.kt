package com.example.data.remote

import android.content.Context
import android.util.Log
import com.example.data.local.QuestionEntity
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.UUID

object FirebaseHelper {
    private const val TAG = "FirebaseHelper"

    fun isFirebaseAvailable(context: Context): Boolean {
        return try {
            val apps = FirebaseApp.getApps(context)
            if (apps.isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Firebase is not available: ${e.message}")
            false
        }
    }

    fun getAuth(context: Context): FirebaseAuth? {
        return if (isFirebaseAvailable(context)) {
            try {
                FirebaseAuth.getInstance()
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    fun getFirestore(context: Context): FirebaseFirestore? {
        return if (isFirebaseAvailable(context)) {
            try {
                FirebaseFirestore.getInstance()
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    fun getCurrentUserId(context: Context): String {
        val auth = getAuth(context)
        return auth?.currentUser?.uid ?: "offline_student_id"
    }

    fun getCurrentUserEmail(context: Context): String {
        val auth = getAuth(context)
        return auth?.currentUser?.email ?: "offline_student@example.com"
    }

    fun isUserLoggedIn(context: Context): Boolean {
        val auth = getAuth(context)
        return auth?.currentUser != null
    }

    suspend fun saveQuestionToFirestore(context: Context, question: QuestionEntity): Boolean {
        val firestore = getFirestore(context) ?: return false
        val userId = getCurrentUserId(context)
        if (userId == "offline_student_id") return false

        return try {
            val docRef = firestore.collection("users")
                .document(userId)
                .collection("questions")
                .document(question.id)

            val data = mapOf(
                "id" to question.id,
                "questionText" to question.questionText,
                "imageUrl" to question.imageUrl,
                "responseText" to question.responseText,
                "language" to question.language,
                "timestamp" to question.timestamp
            )

            docRef.set(data).await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save question to Firestore: ${e.message}")
            false
        }
    }

    suspend fun fetchQuestionHistoryFromFirestore(context: Context): List<QuestionEntity> {
        val firestore = getFirestore(context) ?: return emptyList()
        val userId = getCurrentUserId(context)
        if (userId == "offline_student_id") return emptyList()

        return try {
            val snapshot = firestore.collection("users")
                .document(userId)
                .collection("questions")
                .orderBy("timestamp")
                .get()
                .await()

            snapshot.documents.mapNotNull { doc ->
                val id = doc.getString("id") ?: doc.id
                val questionText = doc.getString("questionText") ?: ""
                val imageUrl = doc.getString("imageUrl")
                val responseText = doc.getString("responseText") ?: ""
                val language = doc.getString("language") ?: "en"
                val timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()
                
                QuestionEntity(
                    id = id,
                    questionText = questionText,
                    imageUrl = imageUrl,
                    responseText = responseText,
                    language = language,
                    timestamp = timestamp,
                    isSynced = true
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch history from Firestore: ${e.message}")
            emptyList()
        }
    }
}
