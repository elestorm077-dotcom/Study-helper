package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.QuestionEntity
import com.example.data.remote.FirebaseHelper
import com.example.data.remote.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.Locale
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val imageBitmap: Bitmap? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class CacheEntry(
    val response: String,
    val timestamp: Long
)

enum class AppLanguage(val code: String, val displayName: String, val locale: Locale) {
    ENGLISH("en", "English", Locale.ENGLISH),
    HINDI("hi", "हिंदी (Hindi)", Locale("hi", "IN")),
    BANGLA("bn", "বাংলা (Bangla)", Locale("bn", "BD"))
}

class StudyViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {
    private val TAG = "StudyViewModel"
    private val database = AppDatabase.getDatabase(application)
    private val questionDao = database.questionDao()

    // Cache for repeating questions (10 minutes lifetime)
    private val responseCache = mutableMapOf<String, CacheEntry>()

    // Preferences for Pro status
    private val prefs = application.getSharedPreferences("study_helper_prefs", Context.MODE_PRIVATE)

    // UI States
    val isFirebaseReady = MutableStateFlow(FirebaseHelper.isFirebaseAvailable(application))

    private val _isLoggedIn = MutableStateFlow(FirebaseHelper.isUserLoggedIn(application))
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _userEmail = MutableStateFlow(FirebaseHelper.getCurrentUserEmail(application))
    val userEmail: StateFlow<String> = _userEmail.asStateFlow()

    private val _selectedLanguage = MutableStateFlow(AppLanguage.ENGLISH)
    val selectedLanguage: StateFlow<AppLanguage> = _selectedLanguage.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _history = MutableStateFlow<List<QuestionEntity>>(emptyList())
    val history: StateFlow<List<QuestionEntity>> = _history.asStateFlow()

    private val _selectedImageUri = MutableStateFlow<Uri?>(null)
    val selectedImageUri: StateFlow<Uri?> = _selectedImageUri.asStateFlow()

    private val _selectedImageBitmap = MutableStateFlow<Bitmap?>(null)
    val selectedImageBitmap: StateFlow<Bitmap?> = _selectedImageBitmap.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _isProUser = MutableStateFlow(prefs.getBoolean("is_pro", false))
    val isProUser: StateFlow<Boolean> = _isProUser.asStateFlow()

    private val _questionsAskedToday = MutableStateFlow(0)
    val questionsAskedToday: StateFlow<Int> = _questionsAskedToday.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // TTS state
    private var tts: TextToSpeech? = null
    private val _isTtsActive = MutableStateFlow(false)
    val isTtsActive: StateFlow<Boolean> = _isTtsActive.asStateFlow()

    init {
        // Initialize TTS
        tts = TextToSpeech(application, this)

        // Initial loading of history & daily count
        refreshHistory()
        updateDailyQuestionCount()

        // Welcome message in selected language
        addSystemWelcomeMessage()
    }

    private fun addSystemWelcomeMessage() {
        val welcomeText = when (_selectedLanguage.value) {
            AppLanguage.HINDI -> "नमस्ते! मैं आपका स्टडी हेल्पर हूँ। आप मुझसे होमवर्क का कोई भी प्रश्न पूछ सकते हैं। आप फ़ोटो भी अपलोड कर सकते हैं!"
            AppLanguage.BANGLA -> "হ্যালো! আমি আপনার স্টাডি হেল্পার। আপনি আমাকে হোমওয়ার্কের যেকোনো প্রশ্ন জিজ্ঞাসা করতে পারেন। এমনকি ফটোও আপলোড করতে পারেন!"
            else -> "Hello! I am your AI Study Helper. Upload a photo of a homework question, type it, or speak it to get step-by-step explanations!"
        }
        _messages.value = listOf(ChatMessage(text = welcomeText, isUser = false))
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            updateTtsLocale()
        } else {
            Log.e(TAG, "TextToSpeech initialization failed.")
        }
    }

    private fun updateTtsLocale() {
        val locale = _selectedLanguage.value.locale
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "TTS Language is not supported: ${locale.displayLanguage}")
        }
    }

    fun setLanguage(language: AppLanguage) {
        _selectedLanguage.value = language
        updateTtsLocale()
        // Reset conversations to show greetings in the correct language
        addSystemWelcomeMessage()
    }

    fun selectImage(uri: Uri?, context: Context) {
        _selectedImageUri.value = uri
        if (uri != null) {
            viewModelScope.launch {
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it)
                        }
                    }
                    _selectedImageBitmap.value = bitmap
                } catch (e: Exception) {
                    _errorMessage.value = "Failed to load selected image"
                }
            }
        } else {
            _selectedImageBitmap.value = null
        }
    }

    fun clearSelectedImage() {
        _selectedImageUri.value = null
        _selectedImageBitmap.value = null
    }

    // Auth Actions
    fun handleAuthChange() {
        val app = getApplication<Application>()
        _isLoggedIn.value = FirebaseHelper.isUserLoggedIn(app)
        _userEmail.value = FirebaseHelper.getCurrentUserEmail(app)
        refreshHistory()
    }

    fun logout() {
        val app = getApplication<Application>()
        FirebaseHelper.getAuth(app)?.signOut()
        handleAuthChange()
        _messages.value = emptyList()
        addSystemWelcomeMessage()
    }

    // Refresh history from Room (and sync from Firestore if logged in)
    fun refreshHistory() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            // Always read first from local DB
            val localHistory = withContext(Dispatchers.IO) {
                questionDao.getAllQuestions()
            }
            _history.value = localHistory

            // If logged in, fetch from Firestore and merge
            if (FirebaseHelper.isUserLoggedIn(app)) {
                val firestoreHistory = withContext(Dispatchers.IO) {
                    FirebaseHelper.fetchQuestionHistoryFromFirestore(app)
                }
                if (firestoreHistory.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        for (q in firestoreHistory) {
                            questionDao.insertQuestion(q)
                        }
                    }
                    val updatedLocalHistory = withContext(Dispatchers.IO) {
                        questionDao.getAllQuestions()
                    }
                    _history.value = updatedLocalHistory
                }
            }
        }
    }

    private fun updateDailyQuestionCount() {
        viewModelScope.launch {
            val start = getStartOfToday()
            val end = getEndOfToday()
            val count = withContext(Dispatchers.IO) {
                questionDao.getQuestionCountForDay(start, end)
            }
            _questionsAskedToday.value = count
        }
    }

    fun askQuestion(text: String) {
        val promptText = text.trim()
        if (promptText.isEmpty() && _selectedImageBitmap.value == null) {
            return
        }

        // Daily Limit Check (Max 5 questions per day for free users)
        if (!_isProUser.value && _questionsAskedToday.value >= 5) {
            _errorMessage.value = "LIMIT_EXCEEDED"
            return
        }

        val questionText = if (promptText.isNotEmpty()) promptText else "[Uploaded Image Question]"
        val userBitmap = _selectedImageBitmap.value

        // Add User Message
        val userMsg = ChatMessage(text = questionText, isUser = true, imageBitmap = userBitmap)
        _messages.value = _messages.value + userMsg

        // Clear image input
        val uriStr = _selectedImageUri.value?.toString()
        clearSelectedImage()

        _isGenerating.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            // Compress bitmap if exists
            val base64Image = if (userBitmap != null) {
                withContext(Dispatchers.IO) {
                    val out = ByteArrayOutputStream()
                    userBitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
                    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                }
            } else {
                null
            }

            // Call Gemini with caching (10 minutes lifetime)
            val languageCode = _selectedLanguage.value.code
            val cacheKey = "$languageCode|$promptText|${base64Image ?: ""}"
            val currentTime = System.currentTimeMillis()
            val cachedEntry = responseCache[cacheKey]
            val explanation = if (cachedEntry != null && (currentTime - cachedEntry.timestamp) < 10 * 60 * 1000) {
                Log.d(TAG, "Cache hit for query!")
                cachedEntry.response
            } else {
                val apiResponse = withContext(Dispatchers.IO) {
                    RetrofitClient.explainHomework(promptText, base64Image, languageCode)
                }
                // Only cache successful API responses
                if (!apiResponse.startsWith("Network error:") &&
                    !apiResponse.startsWith("Error:") &&
                    !apiResponse.contains("Too many requests")) {
                    responseCache[cacheKey] = CacheEntry(apiResponse, currentTime)
                }
                apiResponse
            }

            _isGenerating.value = false

            // Save response message
            val botMsg = ChatMessage(text = explanation, isUser = false)
            _messages.value = _messages.value + botMsg

            // Store in Local Room DB
            val questionId = UUID.randomUUID().toString()
            val entity = QuestionEntity(
                id = questionId,
                questionText = questionText,
                imageUrl = uriStr,
                responseText = explanation,
                language = languageCode,
                timestamp = System.currentTimeMillis(),
                isSynced = false
            )

            withContext(Dispatchers.IO) {
                questionDao.insertQuestion(entity)
            }

            // Sync with Firestore in the background if logged in
            val app = getApplication<Application>()
            if (FirebaseHelper.isUserLoggedIn(app)) {
                viewModelScope.launch(Dispatchers.IO) {
                    val success = FirebaseHelper.saveQuestionToFirestore(app, entity)
                    if (success) {
                        questionDao.insertQuestion(entity.copy(isSynced = true))
                    }
                }
            }

            // Refresh UI States
            updateDailyQuestionCount()
            refreshHistory()
        }
    }

    // Pro upgrade action
    fun upgradeToPro() {
        _isProUser.value = true
        prefs.edit().putBoolean("is_pro", true).apply()
        _errorMessage.value = null
    }

    fun downgradeToFree() {
        // Toggle for testing limits
        _isProUser.value = false
        prefs.edit().putBoolean("is_pro", false).apply()
        updateDailyQuestionCount()
    }

    // TTS controls
    fun speak(text: String) {
        if (_isTtsActive.value) {
            stopSpeaking()
        } else {
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "StudyHelperSpeech")
            if (result == TextToSpeech.SUCCESS) {
                _isTtsActive.value = true
            }
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        _isTtsActive.value = false
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    private fun getStartOfToday(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun getEndOfToday(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        return calendar.timeInMillis
    }

    override fun onCleared() {
        tts?.stop()
        tts?.shutdown()
        super.onCleared()
    }
}
