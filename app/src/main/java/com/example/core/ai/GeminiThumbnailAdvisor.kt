package com.example.core.ai

import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiThumbnailAdvisor {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun suggestCatchyTitles(topicOrFilename: String, isPersian: Boolean): List<String> = withContext(Dispatchers.IO) {
        val apiKey = try {
            val field = BuildConfig::class.java.getField("GEMINI_API_KEY")
            field.get(null) as? String ?: ""
        } catch (e: Exception) {
            ""
        }

        if (apiKey.isNullOrBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext getOfflineTitleSuggestions(isPersian)
        }

        val prompt = if (isPersian) {
            "لطفاً بر اساس این موضوع یا نام ویدیو: '$topicOrFilename'، ۳ عنوان کوتاه، بسیار جذاب و کلیکی برای کاور (تامبنیل) ویدیو به زبان فارسی پیشنهاد بده. فقط عناوین را خط به خط بنویس بدون هیچ متن اضافی."
        } else {
            "Based on the video topic or filename '$topicOrFilename', suggest 3 short, catchy, high-CTR YouTube/Instagram thumbnail titles. Output ONLY the 3 titles, one per line, with no extra text or numbers."
        }

        try {
            val responseText = callGeminiApi(apiKey, prompt)
            val lines = responseText.lines()
                .map { it.trim().removePrefix("-").removePrefix("•").trim() }
                .filter { it.isNotBlank() }
            if (lines.isNotEmpty()) lines.take(3) else getOfflineTitleSuggestions(isPersian)
        } catch (e: Exception) {
            getOfflineTitleSuggestions(isPersian)
        }
    }

    suspend fun suggestColorPalettes(isPersian: Boolean): List<String> = withContext(Dispatchers.IO) {
        listOf(
            if (isPersian) "زرد نئونی و مشکی عمیق (جذب نگاه فوق‌العاده)" else "Neon Yellow & Dark Slate (High Contrast)",
            if (isPersian) "فیروزه‌ای درخشان و بنفش سایبرپانک" else "Electric Cyan & Cyberpunk Violet",
            if (isPersian) "قرمز پررنگ، سفید و خاکستری متالیک" else "Bold Crimson, White & Metallic Gray",
            if (isPersian) "نارنجی خورشیدی و سرمه‌ای شیک" else "Sunset Orange & Midnight Navy"
        )
    }

    private fun getOfflineTitleSuggestions(isPersian: Boolean): List<String> {
        return if (isPersian) {
            listOf(
                "این ویدیو را نباید از دست بدهید!",
                "آموزش و ترفندهای شگفت‌انگیز",
                "رازهایی که تاکنون نمی‌دانستید"
            )
        } else {
            listOf(
                "WATCH THIS BEFORE IT'S TOO LATE!",
                "The Ultimate Guide You Needed",
                "Secrets Nobody Told You About"
            )
        }
    }

    private fun callGeminiApi(apiKey: String, prompt: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"

        val requestJson = JSONObject().apply {
            val contents = JSONArray().apply {
                put(JSONObject().apply {
                    val parts = JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    }
                    put("parts", parts)
                })
            }
            put("contents", contents)
        }

        val request = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("API error: ${response.code}")
            }
            val bodyStr = response.body?.string() ?: ""
            val json = JSONObject(bodyStr)
            val candidates = json.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            if (parts.length() == 0) return ""
            return parts.getJSONObject(0).optString("text", "")
        }
    }
}
