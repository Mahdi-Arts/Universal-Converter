package com.example.core.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.example.core.model.MediaItemInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MediaMetadataInspector {

    suspend fun inspect(context: Context, uri: Uri): Result<MediaItemInfo> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        try {
            // Retrieve display name and size from content resolver
            var name = "video.mp4"
            var sizeBytes = 0L

            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = cursor.getString(nameIndex) ?: name
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1) {
                        sizeBytes = cursor.getLong(sizeIndex)
                    }
                }
            }

            if (sizeBytes == 0L) {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    sizeBytes = pfd.statSize
                }
            }

            retriever.setDataSource(context, uri)

            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L

            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val width = widthStr?.toIntOrNull() ?: 0

            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val height = heightStr?.toIntOrNull() ?: 0

            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val rotation = rotationStr?.toIntOrNull() ?: 0

            val bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            var bitrate = bitrateStr?.toLongOrNull() ?: 0L
            if (bitrate == 0L && durationMs > 0 && sizeBytes > 0) {
                bitrate = (sizeBytes * 8 * 1000) / durationMs
            }

            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "video/mp4"

            // Inspect track codecs using MediaExtractor
            var videoCodec: String? = null
            var audioCodec: String? = null
            var hasAudio = false
            var frameRate = 30f

            try {
                extractor.setDataSource(context, uri, null)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val trackMime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (trackMime.startsWith("video/")) {
                        videoCodec = trackMime
                        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            frameRate = try {
                                format.getFloat(MediaFormat.KEY_FRAME_RATE)
                            } catch (e: Exception) {
                                format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                            }
                        }
                    } else if (trackMime.startsWith("audio/")) {
                        audioCodec = trackMime
                        hasAudio = true
                    }
                }
            } catch (e: Exception) {
                // MediaExtractor fallback
            }

            Result.success(
                MediaItemInfo(
                    uri = uri,
                    name = name,
                    sizeBytes = sizeBytes,
                    durationMs = durationMs,
                    width = width,
                    height = height,
                    rotation = rotation,
                    mimeType = mimeType,
                    videoCodec = videoCodec ?: "H.264",
                    audioCodec = audioCodec,
                    bitrateBps = bitrate,
                    frameRate = frameRate,
                    hasAudio = hasAudio
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {}
            try {
                extractor.release()
            } catch (e: Exception) {}
        }
    }
}
