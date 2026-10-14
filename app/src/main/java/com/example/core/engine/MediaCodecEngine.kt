package com.example.core.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.example.core.model.JobType
import com.example.core.model.MediaItemInfo
import com.example.core.model.OutputMediaFile
import com.example.core.model.SplitConfig
import com.example.core.model.SplitMode
import com.example.core.storage.OutputStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

class MediaCodecEngine(private val storageManager: OutputStorageManager) {

    suspend fun fastAudioDemux(
        context: Context,
        input: MediaItemInfo,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Result<OutputMediaFile> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        val tempFile = storageManager.createTempFile("fast_audio", "m4a")

        try {
            extractor.setDataSource(context, input.uri, null)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                return@withContext Result.failure(IllegalStateException("No audio track found in the video"))
            }

            extractor.selectTrack(audioTrackIndex)
            muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            val maxBufferSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
            } else {
                256 * 1024
            }
            val buffer = ByteBuffer.allocateDirect(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()
            val totalDurationUs = input.durationMs * 1000L

            while (!isCancelled()) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)

                if (totalDurationUs > 0) {
                    onProgress((extractor.sampleTime.toFloat() / totalDurationUs).coerceIn(0f, 1f))
                }

                extractor.advance()
            }

            if (isCancelled()) {
                tempFile.delete()
                return@withContext Result.failure(IllegalStateException("Audio extraction cancelled"))
            }

            muxer.stop()
            muxer.release()
            muxer = null

            storageManager.finalizeAudioOutput(tempFile, input.name, "audio/mp4")
        } catch (e: Exception) {
            tempFile.delete()
            Result.failure(e)
        } finally {
            try { extractor.release() } catch (e: Exception) {}
            try { muxer?.release() } catch (e: Exception) {}
        }
    }

    suspend fun fastSplitVideo(
        context: Context,
        input: MediaItemInfo,
        config: SplitConfig,
        onPartProgress: (currentPart: Int, totalParts: Int, progress: Float) -> Unit,
        isCancelled: () -> Boolean
    ): Result<List<OutputMediaFile>> = withContext(Dispatchers.IO) {
        val successfulOutputs = mutableListOf<OutputMediaFile>()
        val segments = calculateSegments(input, config)

        if (segments.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No split segments calculated"))
        }

        val totalParts = segments.size

        for ((index, segment) in segments.withIndex()) {
            if (isCancelled()) break

            val (startMs, endMs) = segment
            val partNum = String.format("%03d", index + 1)
            val cleanName = input.name.substringBeforeLast(".")
            val partBaseName = "${cleanName}_part_$partNum"
            val tempFile = storageManager.createTempFile("split_$partNum", "mp4")

            val partResult = extractSegmentStreamCopy(
                context = context,
                inputUri = input.uri,
                startUs = startMs * 1000L,
                endUs = endMs * 1000L,
                outputFile = tempFile,
                onProgress = { segProgress ->
                    onPartProgress(index + 1, totalParts, segProgress)
                },
                isCancelled = isCancelled
            )

            if (partResult.isSuccess) {
                val finalResult = storageManager.finalizeVideoOutput(tempFile, "$partBaseName.mp4")
                if (finalResult.isSuccess) {
                    successfulOutputs.add(finalResult.getOrThrow().copy(displayName = "$partBaseName.mp4"))
                }
            } else {
                tempFile.delete()
                // Partial preservation rule: Return what succeeded with error notice if needed
                if (successfulOutputs.isEmpty()) {
                    return@withContext Result.failure(partResult.exceptionOrNull() ?: IllegalStateException("Segment $partNum failed"))
                }
            }
        }

        Result.success(successfulOutputs)
    }

    private fun extractSegmentStreamCopy(
        context: Context,
        inputUri: android.net.Uri,
        startUs: Long,
        endUs: Long,
        outputFile: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Result<Unit> {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            extractor.setDataSource(context, inputUri, null)
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackMap = mutableMapOf<Int, Int>()
            var videoTrackIndex = -1

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val muxerTrack = muxer.addTrack(format)
                    trackMap[i] = muxerTrack
                    if (mime.startsWith("video/")) videoTrackIndex = i
                }
            }

            if (trackMap.isEmpty()) {
                return Result.failure(IllegalStateException("No valid video or audio tracks found"))
            }

            // Seek to start sync frame
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            for (track in trackMap.keys) {
                extractor.selectTrack(track)
            }

            muxer.start()

            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            val segmentDurationUs = (endUs - startUs).coerceAtLeast(1L)
            var initialTimeOffsetUs = -1L

            while (!isCancelled()) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break

                val sampleTime = extractor.sampleTime
                if (sampleTime > endUs) break

                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                if (initialTimeOffsetUs == -1L && sampleTime >= startUs) {
                    initialTimeOffsetUs = sampleTime
                }

                val pts = if (initialTimeOffsetUs != -1L) (sampleTime - initialTimeOffsetUs).coerceAtLeast(0L) else 0L

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = pts
                bufferInfo.flags = extractor.sampleFlags

                val muxerTrack = trackMap[trackIndex]
                if (muxerTrack != null && sampleTime >= startUs) {
                    muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                }

                val currentProg = if (sampleTime >= startUs) {
                    ((sampleTime - startUs).toFloat() / segmentDurationUs).coerceIn(0f, 1f)
                } else 0f
                onProgress(currentProg)

                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            muxer = null

            return Result.success(Unit)
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            try { extractor.release() } catch (e: Exception) {}
            try { muxer?.release() } catch (e: Exception) {}
        }
    }

    private fun calculateSegments(input: MediaItemInfo, config: SplitConfig): List<Pair<Long, Long>> {
        val totalDurationMs = input.durationMs
        if (totalDurationMs <= 0) return emptyList()

        val list = mutableListOf<Pair<Long, Long>>()

        when (config.mode) {
            SplitMode.FIXED_DURATION -> {
                val segMs = config.durationSeconds * 1000L
                var cur = 0L
                while (cur < totalDurationMs) {
                    val next = (cur + segMs).coerceAtMost(totalDurationMs)
                    list.add(Pair(cur, next))
                    cur = next
                }
            }
            SplitMode.EQUAL_PARTS -> {
                val parts = config.equalPartsCount.coerceAtLeast(2)
                val partDuration = totalDurationMs / parts
                for (i in 0 until parts) {
                    val start = i * partDuration
                    val end = if (i == parts - 1) totalDurationMs else (i + 1) * partDuration
                    list.add(Pair(start, end))
                }
            }
            SplitMode.MAX_SIZE -> {
                val inputSizeMb = (input.sizeBytes / (1024.0 * 1024.0)).coerceAtLeast(1.0)
                val targetMb = config.maxSizeMb.coerceAtLeast(5)
                val estimatedParts = (inputSizeMb / targetMb).toInt() + 1
                val partDuration = totalDurationMs / estimatedParts
                var cur = 0L
                while (cur < totalDurationMs) {
                    val next = (cur + partDuration).coerceAtMost(totalDurationMs)
                    list.add(Pair(cur, next))
                    cur = next
                }
            }
            SplitMode.CUSTOM_TIMESTAMPS -> {
                val sorted = config.customTimestampsMs.sorted().filter { it in 1 until totalDurationMs }
                var last = 0L
                for (ts in sorted) {
                    list.add(Pair(last, ts))
                    last = ts
                }
                if (last < totalDurationMs) {
                    list.add(Pair(last, totalDurationMs))
                }
            }
        }

        return list
    }
}
