package com.example.data.remote

import com.example.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface GeminiApiService {
    @POST("v1beta/models/gemini-3.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}

object RetrofitClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    val service: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiApiService::class.java)
    }

    suspend fun explainHomework(
        prompt: String,
        imageBase64: String? = null,
        language: String = "en"
    ): String {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return "Error: Gemini API Key is missing. Please add your key in the Secrets Panel."
        }

        val parts = mutableListOf<Part>()
        parts.add(Part(text = prompt))
        if (imageBase64 != null) {
            parts.add(Part(inlineData = InlineData(mimeType = "image/jpeg", data = imageBase64)))
        }

        // Setup the search grounding tool:
        // "tools": [{"googleSearch": {}}]
        val toolsList = listOf(
            mapOf("googleSearch" to emptyMap<String, Any>())
        )

        val systemInstructionText = when (language.lowercase()) {
            "hi" -> "आप एक विशेषज्ञ अध्ययन सहायक (Study Helper) हैं। छात्रों को उनके होमवर्क प्रश्नों के स्पष्ट और चरण-दर-चरण समाधान प्रदान करें। आपकी पूरी प्रतिक्रिया केवल हिंदी (Hindi) में होनी चाहिए। यदि प्रश्न में कोई जटिल गणितीय समीकरण या वैज्ञानिक अवधारणा है, तो उसे अत्यंत सरल और समझने योग्य तरीके से समझाएं।"
            "bn" -> "আপনি একজন বিশেষজ্ঞ শিক্ষা সহায়ক (Study Helper)। শিক্ষার্থীদের তাদের বাড়ির কাজের প্রশ্নের পরিষ্কার এবং ধাপে ধাপে সমাধান প্রদান করুন। আপনার পুরো উত্তরটি শুধুমাত্র বাংলা (Bangla) ভাষায় হতে হবে। যদি প্রশ্নে কোনো জটিল গাণিতিক সমীকরণ বা বৈজ্ঞানিক ধারণা থাকে, তবে তা অত্যন্ত সহজ এবং বোধগম্য উপায়ে বুঝিয়ে দিন।"
            else -> "You are an expert study assistant (Study Helper). Provide students with clear, friendly, and step-by-step explanations for their homework questions. Use bullet points and paragraphs to make explanations highly readable. If appropriate, write the answer in English. Do not skip any steps, and explain the core concepts clearly."
        }

        val request = GenerateContentRequest(
            contents = listOf(Content(parts = parts)),
            tools = toolsList,
            systemInstruction = Content(parts = listOf(Part(text = systemInstructionText))),
            generationConfig = GenerationConfig(temperature = 0.5f)
        )

        var attempt = 0
        val maxRetries = 3
        var delayMs = 2000L

        while (true) {
            try {
                val response = service.generateContent(apiKey, request)
                return response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: "No explanation generated. Please try again with a different question."
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 429) {
                    if (attempt < maxRetries) {
                        attempt++
                        android.util.Log.w("GeminiService", "Received 429, retrying attempt $attempt after $delayMs ms...")
                        kotlinx.coroutines.delay(delayMs)
                        delayMs *= 2
                        continue
                    } else {
                        return "Too many requests right now — please wait a minute and try again."
                    }
                }
                return "Network error: ${e.message() ?: "HTTP error ${e.code()}"}. Check your connection and try again."
            } catch (e: Exception) {
                return "Network error: ${e.message ?: "Unknown error"}. Check your connection and try again."
            }
        }
    }
}
