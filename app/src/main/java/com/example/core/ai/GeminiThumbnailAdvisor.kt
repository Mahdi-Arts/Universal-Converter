package com.example.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GeminiThumbnailAdvisor: Manages thumbnail title and palette recommendations.
 *
 * In accordance with offline-first security guidelines, client-side direct API calls
 * are disabled in this release to prevent sensitive key packaging or unauthenticated exposure in the APK.
 * All suggestions provided are high-CTR, built-in offline creative presets that work 100% locally
 * without uploading frames, videos, or private media metadata.
 */
object GeminiThumbnailAdvisor {

    // Online cloud AI is disabled in client app until a secure backend proxy is established
    val isOnlineAiAvailable: Boolean = false

    suspend fun suggestCatchyTitles(topicOrFilename: String, isPersian: Boolean): List<String> =
        getThumbnailTitleSuggestions(topicOrFilename, isPersian)

    suspend fun getThumbnailTitleSuggestions(topicOrFilename: String, isPersian: Boolean): List<String> = withContext(Dispatchers.Default) {
        val cleanTopic = topicOrFilename.substringBeforeLast(".")
            .replace(Regex("[_\\-\\d]+"), " ")
            .trim()
            .ifEmpty { if (isPersian) "ویدیو" else "Video" }

        if (isPersian) {
            listOf(
                "۵ ترفند مهم درباره $cleanTopic که باید بدانید",
                "رازهای ناگفته درباره $cleanTopic!",
                "آموزش کامل و کاربردی $cleanTopic",
                "نکات طلایی برای موفقیت در $cleanTopic"
            )
        } else {
            listOf(
                "5 Crucial Facts About $cleanTopic You Must Know",
                "The Ultimate Practical Guide to $cleanTopic",
                "Top Mistakes to Avoid with $cleanTopic",
                "What Nobody Told You About $cleanTopic"
            )
        }
    }

    suspend fun getSuggestedColorPalettes(isPersian: Boolean): List<Pair<String, String>> = withContext(Dispatchers.Default) {
        if (isPersian) {
            listOf(
                "کنتراست بالا (اکشن)" to "زرد نئونی #FFE600 + مشکی خالص #000000",
                "مدرن و فناوری" to "فیروزه‌ای الکتریک #00E5FF + سرمه‌ای تیره #0A1128",
                "جذاب و احساسی" to "قرمز آتشی #FF1744 + سفید درخشان #FFFFFF",
                "شیک و حرفه‌ای" to "نارنجی خورشیدی #FF9100 + خاکستری دودی #1E293B"
            )
        } else {
            listOf(
                "High Action & Contrast" to "Neon Yellow #FFE600 + Pitch Black #000000",
                "Modern Tech" to "Electric Cyan #00E5FF + Dark Navy #0A1128",
                "High Impact" to "Crimson Flame #FF1744 + Pure White #FFFFFF",
                "Sleek Studio" to "Sunset Orange #FF9100 + Slate Gray #1E293B"
            )
        }
    }
}
