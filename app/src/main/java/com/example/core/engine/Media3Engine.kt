package com.example.core.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.example.core.codec.DeviceCodecInspector
import com.example.core.model.CompressionConfig
import com.example.core.model.CompressionPreset
import com.example.core.model.EditConfig
import com.example.core.model.JobType
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
import java.util.concurrent.CancellationException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Media3Engine(
    private val storageManager: OutputStorageManager,
    private val mediaCodecEngine: MediaCodecEngine,
    private val thumbnailEngine: ThumbnailEngine
) : MediaEngine {

    override suspend fun compressOrConvert(
        context: Context,
        input: MediaItemInfo,
        config: CompressionConfig,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Result<OutputMediaFile> = withContext(Dispatchers.IO) {
        if (isCancelled()) {
            return@withContext Result.failure(CancellationException("Cancelled before compression started"))
        }

        val durationSec = (input.durationMs / 1000f).coerceAtLeast(1f)

        // 1. Validate Target Size mode feasibility
        if (config.preset == CompressionPreset.TARGET_SIZE) {
            val audioBytes = if (config.removeAudio) 0L else (config.audioBitrateBps.toLong() * durationSec.toLong() / 8L)
            val minFeasibleBytes = audioBytes + (150 * 1024L) // audio + minimal 150KB video stream
            if (config.targetSizeBytes < minFeasibleBytes * 0.70) {
                val minMb = String.format("%.1f", minFeasibleBytes / (1024.0 * 1024.0))
                return@withContext Result.failure(
                    IllegalArgumentException("Target size is too small for this video duration ($durationSec sec). Minimum feasible target: $minMb MB.")
                )
            }
        }

        // 2. Determine container and codec
        val targetVideoCodec = if (config.videoMimeType.contains("hevc") && DeviceCodecInspector.isH265Supported()) {
            MimeTypes.VIDEO_H265
        } else {
            MimeTypes.VIDEO_H264
        }

        val initialBitrate = calculateTargetBitrate(input, config)
        val (targetWidth, targetHeight) = calculateDownscaleResolution(input, config)

        val pass1File = storageManager.createTempFile("compress_pass1", "mp4")

        val pass1Result = runTransformerJob(
            context = context,
            input = input,
            videoBitrateBps = initialBitrate,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            removeAudio = config.removeAudio,
            videoCodecMime = targetVideoCodec,
            rotation = config.rotationDegrees,
            trimStartMs = 0L,
            trimEndMs = 0L,
            outputFile = pass1File,
            onProgress = onProgress,
            isCancelled = isCancelled
        )

        if (!pass1Result.isSuccess) {
            pass1File.delete()
            return@withContext pass1Result
        }

        if (isCancelled()) {
            pass1File.delete()
            return@withContext Result.failure(CancellationException("Cancelled after encoding"))
        }

        var chosenOutputFile = pass1File

        // 3. Target Size Iterative Correction Pass
        // If actual size exceeds target by > 5%, run pass 2 from original source
        if (config.preset == CompressionPreset.TARGET_SIZE && pass1File.length() > (config.targetSizeBytes * 1.05)) {
            val actualSize = pass1File.length()
            val ratio = config.targetSizeBytes.toDouble() / actualSize
            val pass2Bitrate = (initialBitrate * ratio * 0.90).toInt().coerceIn(120_000, 10_000_000)

            val pass2File = storageManager.createTempFile("compress_pass2", "mp4")

            val pass2Result = runTransformerJob(
                context = context,
                input = input, // always encode from original source
                videoBitrateBps = pass2Bitrate,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                removeAudio = config.removeAudio,
                videoCodecMime = targetVideoCodec,
                rotation = config.rotationDegrees,
                trimStartMs = 0L,
                trimEndMs = 0L,
                outputFile = pass2File,
                onProgress = onProgress,
                isCancelled = isCancelled
            )

            if (pass2Result.isSuccess && pass2File.exists() && pass2File.length() > 0) {
                // Pass 2 succeeded: cleanup pass 1 and adopt pass 2
                pass1File.delete()
                chosenOutputFile = pass2File
            } else {
                // Pass 2 failed or cancelled: delete pass 2, preserve valid pass 1 file!
                pass2File.delete()
                // chosenOutputFile remains pass1File
            }
        }

        // Finalize output into MediaStore
        storageManager.finalizeVideoOutput(
            tempFile = chosenOutputFile,
            baseName = input.name,
            jobType = JobType.COMPRESSION,
            containerFormat = "mp4",
            isCancelled = isCancelled
        )
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
            videoBitrateBps = if (input.bitrateBps > 0) input.bitrateBps.toInt() else 3_000_000,
            targetWidth = input.width,
            targetHeight = input.height,
            removeAudio = false,
            videoCodecMime = MimeTypes.VIDEO_H264,
            rotation = config.rotationDegrees,
            trimStartMs = config.trimStartMs,
            trimEndMs = if (config.trimEndMs > 0) config.trimEndMs else input.durationMs,
            outputFile = tempFile,
            onProgress = onProgress,
            isCancelled = isCancelled
        )

        if (result.isSuccess) {
            storageManager.finalizeVideoOutput(
                tempFile = tempFile,
                baseName = input.name,
                jobType = JobType.EDITING,
                containerFormat = "mp4",
                isCancelled = isCancelled
            )
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
        config: ThumbnailConfig,
        isCancelled: () -> Boolean
    ): Result<OutputMediaFile> {
        return thumbnailEngine.generateThumbnail(context, input, config, isCancelled)
    }

    private suspend fun runTransformerJob(
        context: Context,
        input: MediaItemInfo,
        videoBitrateBps: Int,
        targetWidth: Int,
        targetHeight: Int,
        removeAudio: Boolean,
        videoCodecMime: String,
        rotation: Int,
        trimStartMs: Long,
        trimEndMs: Long,
        outputFile: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Result<OutputMediaFile> = withContext(Dispatchers.Main.immediate) {
        if (isCancelled()) {
            return@withContext Result.failure(CancellationException("Processing was cancelled before starting"))
        }

        val completionDeferred = CompletableDeferred<Result<OutputMediaFile>>()
        var transformer: Transformer? = null
        var progressJob: kotlinx.coroutines.Job? = null

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                val finalSize = outputFile.length()
                completionDeferred.complete(
                    Result.success(
                        OutputMediaFile(
                            id = outputFile.name,
                            displayName = outputFile.name,
                            uri = Uri.fromFile(outputFile),
                            path = outputFile.absolutePath,
                            sizeBytes = finalSize,
                            type = JobType.COMPRESSION,
                            mimeType = "video/mp4"
                        )
                    )
                )
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                completionDeferred.complete(Result.failure(exportException))
            }
        }

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

            val effectsList = mutableListOf<Effect>()

            if (targetWidth > 0 && targetHeight > 0 && (targetWidth < input.width || targetHeight < input.height)) {
                effectsList.add(Presentation.createForWidthAndHeight(targetWidth, targetHeight, Presentation.LAYOUT_SCALE_TO_FIT))
            }

            if (rotation != 0) {
                effectsList.add(
                    ScaleAndRotateTransformation.Builder()
                        .setRotationDegrees(rotation.toFloat())
                        .build()
                )
            }

            if (effectsList.isNotEmpty()) {
                editedBuilder.setEffects(Effects(emptyList(), effectsList))
            }

            val encoderSettingsBuilder = VideoEncoderSettings.Builder()
            if (videoBitrateBps > 0) {
                encoderSettingsBuilder.setBitrate(videoBitrateBps)
            }
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(encoderSettingsBuilder.build())
                .build()

            val builtTransformer = Transformer.Builder(context)
                .setEncoderFactory(encoderFactory)
                .setVideoMimeType(videoCodecMime)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(listener)
                .build()

            transformer = builtTransformer
            builtTransformer.start(editedBuilder.build(), outputFile.absolutePath)

            // Scoped progress monitoring tied directly to the caller's coroutine
            progressJob = launch {
                val progressHolder = ProgressHolder()
                while (!completionDeferred.isCompleted) {
                    if (isCancelled()) {
                        builtTransformer.cancel()
                        completionDeferred.complete(Result.failure(CancellationException("Processing was cancelled")))
                        break
                    }

                    val state = builtTransformer.getProgress(progressHolder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress((progressHolder.progress / 100f).coerceIn(0f, 1f))
                    }

                    delay(100)
                }
            }

            completionDeferred.await()
        } catch (e: CancellationException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    progressJob?.cancel()
                } catch (_: Exception) {}
                try {
                    transformer?.removeListener(listener)
                    transformer?.cancel()
                } catch (_: Exception) {}
            }
        }
    }

    private fun calculateTargetBitrate(input: MediaItemInfo, config: CompressionConfig): Int {
        val durationSec = (input.durationMs / 1000f).coerceAtLeast(1f)
        val origBitrate = if (input.bitrateBps > 0) input.bitrateBps.toInt() else 3_000_000

        return when (config.preset) {
            CompressionPreset.ORIGINAL -> origBitrate
            CompressionPreset.HIGH_QUALITY -> (origBitrate * 0.70).toInt().coerceAtLeast(1_500_000)
            CompressionPreset.BALANCED -> (origBitrate * 0.45).toInt().coerceIn(750_000, 2_200_000)
            CompressionPreset.WEB_OPTIMIZED -> 1_200_000
            CompressionPreset.MESSENGER -> 650_000
            CompressionPreset.SMALL_SIZE -> 400_000
            CompressionPreset.TARGET_SIZE -> {
                val audioBits = if (config.removeAudio) 0L else (config.audioBitrateBps.toLong() * durationSec.toLong())
                // Deduct 5% container overhead
                val targetTotalBits = (config.targetSizeBytes * 8L * 0.95).toLong()
                val videoBits = (targetTotalBits - audioBits).coerceAtLeast(200_000L)
                (videoBits / durationSec).toInt().coerceIn(120_000, 10_000_000)
            }
            CompressionPreset.CUSTOM -> config.videoBitrateBps.coerceIn(200_000, 15_000_000)
        }
    }

    private fun calculateDownscaleResolution(input: MediaItemInfo, config: CompressionConfig): Pair<Int, Int> {
        val origW = if (input.width > 0) input.width else 1280
        val origH = if (input.height > 0) input.height else 720

        val maxAllowedW = when (config.preset) {
            CompressionPreset.SMALL_SIZE, CompressionPreset.MESSENGER -> 854
            CompressionPreset.WEB_OPTIMIZED, CompressionPreset.BALANCED -> 1280
            CompressionPreset.CUSTOM -> config.resolutionWidth
            else -> origW
        }

        // Never upscale: keep min(origW, maxAllowedW)
        val targetW = origW.coerceAtMost(maxAllowedW)
        val targetH = ((targetW.toFloat() / origW) * origH).toInt()

        // Ensure even dimensions for video codecs (divisible by 2)
        val evenW = (targetW / 2) * 2
        val evenH = (targetH / 2) * 2

        return Pair(evenW.coerceAtLeast(320), evenH.coerceAtLeast(240))
    }
}
