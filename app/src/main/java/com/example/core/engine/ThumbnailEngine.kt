package com.example.core.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import com.example.core.model.CropRatio
import com.example.core.model.MediaItemInfo
import com.example.core.model.OutputMediaFile
import com.example.core.model.TextOverlay
import com.example.core.model.ThumbnailConfig
import com.example.core.model.ThumbnailFormat
import com.example.core.storage.OutputStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.util.concurrent.CancellationException

class ThumbnailEngine(private val storageManager: OutputStorageManager) {

    suspend fun generateThumbnail(
        context: Context,
        input: MediaItemInfo,
        config: ThumbnailConfig,
        isCancelled: () -> Boolean = { false }
    ): Result<OutputMediaFile> = withContext(Dispatchers.IO) {
        if (isCancelled()) {
            return@withContext Result.failure(CancellationException("Thumbnail generation cancelled before start"))
        }

        val retriever = MediaMetadataRetriever()
        var tempFile: java.io.File? = null
        try {
            retriever.setDataSource(context, input.uri)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (isCancelled()) throw CancellationException("Cancelled during frame extraction")

            val timeUs = (config.frameTimestampMs * 1000L).coerceAtLeast(0L)
            val rawBitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@withContext Result.failure(IllegalStateException("Could not extract frame from video at ${config.frameTimestampMs}ms"))

            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (isCancelled()) {
                rawBitmap.recycle()
                throw CancellationException("Cancelled before crop")
            }

            // 1. Crop to ratio if requested
            val cropped = cropBitmap(rawBitmap, config.cropRatio)
            if (cropped != rawBitmap) {
                rawBitmap.recycle()
            }

            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (isCancelled()) {
                cropped.recycle()
                throw CancellationException("Cancelled before filter")
            }

            // 2. Apply filters (Brightness, Contrast, Saturation)
            val filtered = applyFilters(cropped, config.brightness, config.contrast, config.saturation)
            if (filtered != cropped) {
                cropped.recycle()
            }

            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (isCancelled()) {
                filtered.recycle()
                throw CancellationException("Cancelled before text overlay")
            }

            // 3. Draw Text Overlay
            val finalBitmap = if (config.textOverlay != null && config.textOverlay.text.isNotBlank()) {
                val withText = drawTextOverlay(filtered, config.textOverlay)
                if (withText != filtered) {
                    filtered.recycle()
                }
                withText
            } else {
                filtered
            }

            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (isCancelled()) {
                finalBitmap.recycle()
                throw CancellationException("Cancelled before image compression")
            }

            // 4. Save to temp file
            val ext = when (config.format) {
                ThumbnailFormat.PNG -> "png"
                ThumbnailFormat.JPEG -> "jpg"
                ThumbnailFormat.WEBP -> "webp"
            }

            val temp = storageManager.createTempFile("thumbnail", ext)
            tempFile = temp

            val compressFormat = when (config.format) {
                ThumbnailFormat.PNG -> Bitmap.CompressFormat.PNG
                ThumbnailFormat.JPEG -> Bitmap.CompressFormat.JPEG
                ThumbnailFormat.WEBP -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    }
                }
            }

            val compressedSuccessfully = FileOutputStream(temp).use { out ->
                finalBitmap.compress(compressFormat, 92, out)
            }

            finalBitmap.recycle()

            if (!compressedSuccessfully) {
                temp.delete()
                return@withContext Result.failure(IllegalStateException("Bitmap compression returned false for format $ext"))
            }

            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (isCancelled()) {
                temp.delete()
                throw CancellationException("Cancelled before finalization")
            }

            storageManager.finalizeImageOutput(temp, input.name, ext, isCancelled)
        } catch (e: Exception) {
            tempFile?.let { if (it.exists()) it.delete() }
            Result.failure(e)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    suspend fun extractPreviewFrame(
        context: Context,
        input: MediaItemInfo,
        timestampMs: Long
    ): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, input.uri)
            val timeUs = (timestampMs * 1000L).coerceAtLeast(0L)
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
    }

    private fun cropBitmap(source: Bitmap, cropRatio: CropRatio): Bitmap {
        val targetRatio = cropRatio.ratio ?: return source
        val currentRatio = source.width.toFloat() / source.height.toFloat()

        val (targetWidth, targetHeight, startX, startY) = if (currentRatio > targetRatio) {
            // Source is wider than target ratio
            val newWidth = (source.height * targetRatio).toInt().coerceAtMost(source.width)
            val x = (source.width - newWidth) / 2
            listOf(newWidth, source.height, x, 0)
        } else {
            // Source is taller than target ratio
            val newHeight = (source.width / targetRatio).toInt().coerceAtMost(source.height)
            val y = (source.height - newHeight) / 2
            listOf(source.width, newHeight, 0, y)
        }

        return Bitmap.createBitmap(source, startX, startY, targetWidth, targetHeight)
    }

    private fun applyFilters(source: Bitmap, brightness: Float, contrast: Float, saturation: Float): Bitmap {
        if (brightness == 0f && contrast == 1f && saturation == 1f) {
            return source
        }

        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val cm = ColorMatrix()

        // Saturation
        if (saturation != 1f) {
            val satMatrix = ColorMatrix()
            satMatrix.setSaturation(saturation)
            cm.postConcat(satMatrix)
        }

        // Contrast and Brightness
        // Scale = contrast, offset = brightness
        val scale = contrast
        val translate = (1f - scale) * 128f + brightness
        val contrastBrightnessMatrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(contrastBrightnessMatrix)

        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    private fun drawTextOverlay(source: Bitmap, overlay: TextOverlay): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)

        val scaledFontSize = (overlay.fontSizeSp / 16f) * (source.width / 25f).coerceAtLeast(32f)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = overlay.textColor.toInt()
            textSize = scaledFontSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            if (overlay.hasShadow) {
                setShadowLayer(8f, 3f, 3f, Color.BLACK)
            }
        }

        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = overlay.outlineColor.toInt()
            textSize = scaledFontSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            style = Paint.Style.STROKE
            strokeWidth = (scaledFontSize / 7f).coerceAtLeast(4f)
        }

        val x = source.width * overlay.positionXPercent
        val y = source.height * overlay.positionYPercent

        // Draw outline first if enabled
        if (overlay.hasOutline) {
            canvas.drawText(overlay.text, x, y, outlinePaint)
        }

        // Draw fill text
        canvas.drawText(overlay.text, x, y, fillPaint)

        return output
    }
}
