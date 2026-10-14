package com.example.core.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.core.codec.DeviceCodecInspector
import com.example.core.model.CompressionConfig
import com.example.core.model.CompressionPreset
import com.example.core.model.CropRatio
import com.example.core.model.EditConfig
import com.example.core.model.MediaItemInfo
import com.example.core.model.OutputMediaFile
import com.example.core.model.SplitConfig
import com.example.core.model.ThumbnailConfig
import com.example.core.storage.OutputStorageManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class Media3Engine(
    private val storageManager: OutputStorageManager,
    private val mediaCodecEngine: MediaCodecEngine,
    private val thumbnailEngine: ThumbnailEngine
) : MediaEngine {

    private val mainHandler = Handler(Looper.getMainLooper())

    override suspend fun compressOrConvert(
        context: Context,
        input: MediaItemInfo,
        config: CompressionConfig,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Result<OutputMediaFile> = withContext(Dispatchers.IO) {
        val effectiveBitrate = calculateTargetBitrate(input, config)
        val tempFile = storageManager.createTempFile("compress", "mp4")

        val pass1Result = runTransformerJob(
            context = context,
            input = input,
            videoBitrateBps = effectiveBitrate,
            removeAudio = config.removeAudio,
            videoMimeType = config.videoMimeType,
            audioMimeType = config.audioMimeType,
            rotation = config.rotationDegrees,
            trimStartMs = 0L,
            trimEndMs = 0L,
            outputFile = tempFile,
            onProgress = onProgress,
            isCancelled = isCancelled
        )

        if (!pass1Result.isSuccess) {
            tempFile.delete()
            return@withContext pass1Result
        }

        // Target Size Correction Pass (Requirement: If result is significantly larger than target, reduce bitrate & re-encode)
        if (config.preset == CompressionPreset.TARGET_SIZE && tempFile.length() > config.targetSizeBytes * 1.08) {
            val pass2File = storageManager.createTempFile("compress_pass2", "mp4")
            val ratio = config.targetSizeBytes.toDouble() / tempFile.length()
            val pass2Bitrate = (effectiveBitrate * ratio * 0.90).toInt().coerceAtLeast(150_000)

            val pass2Result = runTransformerJob(
                context = context,
                input = input,
                videoBitrateBps = pass2Bitrate,
                removeAudio = config.removeAudio,
                videoMimeType = config.videoMimeType,
                audioMimeType = config.audioMimeType,
                rotation = config.rotationDegrees,
                trimStartMs = 0L,
                trimEndMs = 0L,
                outputFile = pass2File,
                onProgress = onProgress,
                isCancelled = isCancelled
            )

            tempFile.delete()

            if (pass2Result.isSuccess) {
                return@withContext storageManager.finalizeVideoOutput(pass2File, input.name, config.videoMimeType)
            }
        }

        storageManager.finalizeVideoOutput(tempFile, input.name, config.videoMimeType)
    }

    override suspend fun editVideo(
        context: Context,
        input: MediaItemInfo,
        config: EditConfig,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Result<OutputMediaFile> = withContext(Dispatchers.IO) {
        val tempFile = storageManager.createTempFile("edited", "mp4")

        val result = runTransformerJob(
            context = context,
            input = input,
            videoBitrateBps = 0, // Auto
            removeAudio = false,
            videoMimeType = "video/avc",
            audioMimeType = "audio/mp4a-latm",
            rotation = config.rotationDegrees,
            trimStartMs = config.trimStartMs,
            trimEndMs = if (config.trimEndMs > 0) config.trimEndMs else input.durationMs,
            outputFile = tempFile,
            onProgress = onProgress,
            isCancelled = isCancelled
        )

        if (result.isSuccess) {
            storageManager.finalizeVideoOutput(tempFile, input.name, "video/mp4")
        } else {
            tempFile.delete()
            result
        }
    }

    override suspend fun extractAudio(
        context: Context,
        input: MediaItemInfo,
        asMp3: Boolean,
        bitrateBps: Int,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Result<OutputMediaFile> {
        // Fast stream copy audio extraction without video re-encoding
        return mediaCodecEngine.fastAudioDemux(context, input, onProgress, isCancelled)
    }

    override suspend fun splitVideo(
        context: Context,
        input: MediaItemInfo,
        config: SplitConfig,
        onPartProgress: (currentPart: Int, totalParts: Int, progress: Float) -> Unit,
        isCancelled: () -> Boolean
    ): Result<List<OutputMediaFile>> {
        return mediaCodecEngine.fastSplitVideo(context, input, config, onPartProgress, isCancelled)
    }

    override suspend fun generateThumbnail(
        context: Context,
        input: MediaItemInfo,
        config: ThumbnailConfig
    ): Result<OutputMediaFile> {
        return thumbnailEngine.generateThumbnail(context, input, config)
    }

    private suspend fun runTransformerJob(
        context: Context,
        input: MediaItemInfo,
        videoBitrateBps: Int,
        removeAudio: Boolean,
        videoMimeType: String,
        audioMimeType: String,
        rotation: Int,
        trimStartMs: Long,
        trimEndMs: Long,
        outputFile: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Result<OutputMediaFile> = withContext(Dispatchers.Main) {
        val completionDeferred = CompletableDeferred<Result<OutputMediaFile>>()

        try {
            val mediaItemBuilder = MediaItem.Builder().setUri(input.uri)

            if (trimStartMs > 0 || (trimEndMs > 0 && trimEndMs < input.durationMs)) {
                val clipping = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(trimStartMs)
                    .setEndPositionMs(if (trimEndMs > 0) trimEndMs else input.durationMs)
                    .build()
                mediaItemBuilder.setClippingConfiguration(clipping)
            }

            val mediaItem = mediaItemBuilder.build()
            val editedBuilder = EditedMediaItem.Builder(mediaItem)

            if (removeAudio) {
                editedBuilder.setRemoveAudio(true)
            }

            if (rotation != 0) {
                val effects = listOf<Effect>(
                    ScaleAndRotateTransformation.Builder()
                        .setRotationDegrees(rotation.toFloat())
                        .build()
                )
                editedBuilder.setEffects(Effects(emptyList(), effects))
            }

            val transformerBuilder = Transformer.Builder(context)
                .setVideoMimeType(if (videoMimeType.contains("hevc") && DeviceCodecInspector.isH265Supported()) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)

            val transformer = transformerBuilder.build()

            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    completionDeferred.complete(
                        Result.success(
                            OutputMediaFile(
                                id = outputFile.name,
                                displayName = outputFile.name,
                                uri = android.net.Uri.fromFile(outputFile),
                                path = outputFile.absolutePath,
                                sizeBytes = outputFile.length(),
                                type = com.example.core.model.JobType.COMPRESSION
                            )
                        )
                    )
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    completionDeferred.complete(Result.failure(exportException))
                }
            })

            transformer.start(editedBuilder.build(), outputFile.absolutePath)

            CoroutineScope(Dispatchers.Default).launch {
                val progressHolder = ProgressHolder()
                while (!completionDeferred.isCompleted) {
                    if (isCancelled()) {
                        withContext(Dispatchers.Main) { transformer.cancel() }
                        completionDeferred.complete(Result.failure(IllegalStateException("Processing was cancelled")))
                        break
                    }

                    withContext(Dispatchers.Main) {
                        val state = transformer.getProgress(progressHolder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress((progressHolder.progress / 100f).coerceIn(0f, 1f))
                        }
                    }

                    delay(120)
                }
            }

        } catch (e: Exception) {
            completionDeferred.complete(Result.failure(e))
        }

        completionDeferred.await()
    }


    private fun calculateTargetBitrate(input: MediaItemInfo, config: CompressionConfig): Int {
        val durationSec = (input.durationMs / 1000f).coerceAtLeast(1f)
        val origBitrate = if (input.bitrateBps > 0) input.bitrateBps.toInt() else 3_000_000

        return when (config.preset) {
            CompressionPreset.ORIGINAL -> origBitrate
            CompressionPreset.HIGH_QUALITY -> (origBitrate * 0.75).toInt().coerceAtLeast(1_500_000)
            CompressionPreset.BALANCED -> (origBitrate * 0.50).toInt().coerceIn(800_000, 2_500_000)
            CompressionPreset.WEB_OPTIMIZED -> 1_200_000
            CompressionPreset.MESSENGER -> 650_000
            CompressionPreset.SMALL_SIZE -> 400_000
            CompressionPreset.TARGET_SIZE -> {
                val audioBits = if (config.removeAudio) 0L else (config.audioBitrateBps.toLong() * durationSec.toLong())
                val targetTotalBits = config.targetSizeBytes * 8L
                val videoBits = (targetTotalBits - audioBits).coerceAtLeast(200_000L)
                (videoBits / durationSec).toInt().coerceIn(180_000, 10_000_000)
            }
            CompressionPreset.CUSTOM -> config.videoBitrateBps
        }
    }
}
