package com.example.core.model

import android.net.Uri

data class MediaItemInfo(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int = 0,
    val mimeType: String = "video/mp4",
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val bitrateBps: Long = 0,
    val frameRate: Float = 30f,
    val hasAudio: Boolean = true
) {
    val formattedDuration: String
        get() {
            val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val hours = minutes / 60
            return if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes % 60, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
        }

    val formattedSize: String
        get() {
            val kb = sizeBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format("%.2f GB", gb)
                mb >= 1.0 -> String.format("%.1f MB", mb)
                else -> String.format("%.0f KB", kb)
            }
        }

    val resolutionLabel: String
        get() = if (width > 0 && height > 0) "${width}x${height}" else "Unknown"
}

enum class CompressionPreset {
    ORIGINAL,
    HIGH_QUALITY,
    BALANCED,
    WEB_OPTIMIZED,
    MESSENGER,
    SMALL_SIZE,
    TARGET_SIZE,
    CUSTOM
}

data class CompressionConfig(
    val preset: CompressionPreset = CompressionPreset.BALANCED,
    val targetSizeBytes: Long = 15 * 1024 * 1024L, // 15MB default for target size mode
    val resolutionWidth: Int = 1280,
    val resolutionHeight: Int = 720,
    val videoBitrateBps: Int = 2_000_000,
    val frameRate: Int = 30,
    val videoMimeType: String = "video/avc", // H.264 default
    val audioMimeType: String = "audio/mp4a-latm", // AAC default
    val audioBitrateBps: Int = 128_000,
    val audioSampleRate: Int = 44100,
    val audioChannelCount: Int = 2,
    val removeAudio: Boolean = false,
    val preserveMetadata: Boolean = true,
    val rotationDegrees: Int = 0
)

enum class CropRatio(val label: String, val ratio: Float?) {
    ORIGINAL("Original", null),
    RATIO_16_9("16:9 Landscape", 16f / 9f),
    RATIO_9_16("9:16 Vertical", 9f / 16f),
    RATIO_1_1("1:1 Square", 1f)
}

data class EditConfig(
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val cropRatio: CropRatio = CropRatio.ORIGINAL,
    val rotationDegrees: Int = 0, // 0, 90, 180, 270
    val speedMultiplier: Float = 1.0f // 0.25f to 4.0f
)

enum class SplitMode {
    FIXED_DURATION,
    EQUAL_PARTS,
    MAX_SIZE,
    CUSTOM_TIMESTAMPS
}

enum class SplitEngine {
    FAST_STREAM_COPY,
    PRECISE_RE_ENCODE
}

data class SplitConfig(
    val mode: SplitMode = SplitMode.FIXED_DURATION,
    val durationSeconds: Int = 30, // 15, 30, 60 or custom
    val equalPartsCount: Int = 2,
    val maxSizeMb: Int = 15,
    val customTimestampsMs: List<Long> = emptyList(),
    val engine: SplitEngine = SplitEngine.FAST_STREAM_COPY
)

data class TextOverlay(
    val text: String = "",
    val textColor: Long = 0xFFFFFFFF,
    val outlineColor: Long = 0xFF000000,
    val hasOutline: Boolean = true,
    val hasShadow: Boolean = true,
    val fontSizeSp: Float = 28f,
    val positionXPercent: Float = 0.5f, // 0.0 to 1.0
    val positionYPercent: Float = 0.8f
)

enum class ThumbnailFormat {
    PNG,
    JPEG,
    WEBP
}

data class ThumbnailConfig(
    val frameTimestampMs: Long = 0L,
    val cropRatio: CropRatio = CropRatio.RATIO_16_9,
    val textOverlay: TextOverlay? = null,
    val brightness: Float = 0f, // -100 to +100
    val contrast: Float = 1f,   // 0.5 to 2.0
    val saturation: Float = 1f, // 0.0 to 2.0
    val format: ThumbnailFormat = ThumbnailFormat.PNG
)

enum class JobType {
    COMPRESSION,
    CONVERSION,
    EDITING,
    AUDIO_EXTRACTION,
    SPLITTING,
    THUMBNAIL
}

enum class JobStatus {
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class ProcessingJob(
    val id: String,
    val title: String,
    val type: JobType,
    val status: JobStatus = JobStatus.QUEUED,
    val progress: Float = 0f, // 0.0 to 1.0
    val originalSizeBytes: Long = 0L,
    val targetSizeBytes: Long = 0L,
    val outputSizeBytes: Long = 0L,
    val outputPath: String? = null,
    val outputUri: Uri? = null,
    val errorReason: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val completedAtMs: Long? = null,
    val currentCodec: String? = null,
    val currentResolution: String? = null,
    val actualOutputBitrateBps: Long? = null,
    val targetAchieved: Boolean? = null,
    val retryCount: Int = 0
) {
    val compressionSavingsPercent: Int
        get() {
            if (originalSizeBytes <= 0 || outputSizeBytes <= 0) return 0
            val diff = originalSizeBytes - outputSizeBytes
            return ((diff.toDouble() / originalSizeBytes) * 100).toInt().coerceIn(-100, 99)
        }
}

data class OutputMediaFile(
    val id: String,
    val displayName: String,
    val uri: Uri,
    val path: String,
    val sizeBytes: Long,
    val durationMs: Long = 0L,
    val dateAddedMs: Long = System.currentTimeMillis(),
    val type: JobType,
    val mimeType: String = "video/mp4"
)
