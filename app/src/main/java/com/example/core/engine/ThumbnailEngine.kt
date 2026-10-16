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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
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
        var tempFile: File? = null
        val intermediateBitmaps = mutableListOf<Bitmap>()
        var isPublishedSuccessfully = false

        try {
            retriever.setDataSource(context, input.uri)
            currentCoroutineContext().ensureActive()
            if (isCancelled()) throw CancellationException("Cancelled during frame extraction")

            val timeUs = (config.frameTimestampMs * 1000L).coerceAtLeast(0L)
            val rawBitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@withContext Result.failure(IllegalStateException("Could not extract frame from video at ${config.frameTimestampMs}ms"))

            intermediateBitmaps.add(rawBitmap)

            currentCoroutineContext().ensureActive()
            if (isCancelled()) {
                throw CancellationException("Cancelled before crop")
            }

            // 1. Crop to ratio if requested
            val cropped = cropBitmap(rawBitmap, config.cropRatio)
            if (cropped != rawBitmap) {
                intermediateBitmaps.add(cropped)
            }

            currentCoroutineContext().ensureActive()
            if (isCancelled()) {
                throw CancellationException("Cancelled before filter")
            }

            // 2. Apply filters (Brightness, Contrast, Saturation)
            val filtered = applyFilters(cropped, config.brightness, config.contrast, config.saturation)
            if (filtered != cropped && filtered != rawBitmap) {
                intermediateBitmaps.add(filtered)
            }

            currentCoroutineContext().ensureActive()
            if (isCancelled()) {
                throw CancellationException("Cancelled before text overlay")
            }

            // 3. Draw Text Overlay
            val finalBitmap = if (config.textOverlay != null && config.textOverlay.text.isNotBlank()) {
                val withText = drawTextOverlay(filtered, config.textOverlay)
                if (withText != filtered && withText != cropped && withText != rawBitmap) {
                    intermediateBitmaps.add(withText)
                }
                withText
            } else {
                filtered
            }

            currentCoroutineContext().ensureActive()
            if (isCancelled()) {
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

            if (!compressedSuccessfully) {
                return@withContext Result.failure(IllegalStateException("Bitmap compression returned false for format $ext"))
            }

            currentCoroutineContext().ensureActive()
            if (isCancelled()) {
                throw CancellationException("Cancelled before finalization")
            }

            val pubResult = storageManager.finalizeImageOutput(temp, input.name, ext, isCancelled)
            if (pubResult.isSuccess) {
                isPublishedSuccessfully = true
            }
            pubResult
        } catch (e: CancellationException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            withContext(NonCancellable) {
                try {
                    retriever.release()
                } catch (_: Exception) {}

                // Free all intermediate bitmaps guaranteed in finally
                for (b in intermediateBitmaps) {
                    if (!b.isRecycled) {
                        try {
                            b.recycle()
                        } catch (_: Exception) {}
                    }
                }
                intermediateBitmaps.clear()

                // If not successfully published, clean up temp file
                if (!isPublishedSuccessfully) {
                    tempFile?.let {
                        if (it.exists()) {
                            try {
                                it.delete()
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
    }

    /**
     * Preview frame extraction for UI preview rendering.
     * CRITICAL: The returned Bitmap is owned by the UI Composable caller and MUST NOT be recycled here.
     */
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
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun cropBitmap(source: Bitmap, cropRatio: CropRatio): Bitmap {
        val targetRatio = cropRatio.ratio ?: return source
        val currentRatio = source.width.toFloat() / source.height.toFloat()

        val (targetWidth, targetHeight, startX, startY) = if (currentRatio > targetRatio) {
            val newWidth = (source.height * targetRatio).toInt().coerceAtMost(source.width)
            val x = (source.width - newWidth) / 2
            listOf(newWidth, source.height, x, 0)
        } else {
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

        if (saturation != 1f) {
            val satMatrix = ColorMatrix()
            satMatrix.setSaturation(saturation)
            cm.postConcat(satMatrix)
        }

        if (contrast != 1f || brightness != 0f) {
            val scale = contrast
            val translate = brightness * 255f
            val cbMatrix = ColorMatrix(
                floatArrayOf(
                    scale, 0f, 0f, 0f, translate,
                    0f, scale, 0f, 0f, translate,
                    0f, 0f, scale, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(cbMatrix)
        }

        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    private fun drawTextOverlay(source: Bitmap, overlay: TextOverlay): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(source, 0f, 0f, null)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = overlay.textColor.toInt()
            textSize = (overlay.fontSizeSp * (source.width / 400f)).coerceIn(18f, 120f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = overlay.outlineColor.toInt()
            textSize = paint.textSize
            typeface = paint.typeface
            textAlign = Paint.Align.CENTER
            style = Paint.Style.STROKE
            strokeWidth = paint.textSize * 0.15f
        }

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 0, 0, 0)
            style = Paint.Style.FILL
        }

        val x = (source.width * overlay.positionXPercent).coerceIn(10f, source.width - 10f)
        val y = (source.height * overlay.positionYPercent).coerceIn(paint.textSize, source.height - 20f)

        val textBounds = Rect()
        paint.getTextBounds(overlay.text, 0, overlay.text.length, textBounds)

        val padding = 16f
        val bgRect = android.graphics.RectF(
            x - (textBounds.width() / 2f) - padding,
            y - textBounds.height() - padding,
            x + (textBounds.width() / 2f) + padding,
            y + padding
        )
        canvas.drawRoundRect(bgRect, 8f, 8f, bgPaint)

        canvas.drawText(overlay.text, x, y, strokePaint)
        canvas.drawText(overlay.text, x, y, paint)

        return output
    }
}
