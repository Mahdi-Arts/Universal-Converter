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
import com.example.core.model.SplitEngine
import com.example.core.model.SplitMode
import com.example.core.storage.OutputStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

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
                tempFile.delete()
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
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = mapExtractorFlagsToBufferFlags(extractor.sampleFlags)

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)

                if (totalDurationUs > 0) {
                    onProgress((extractor.sampleTime.toFloat() / totalDurationUs).coerceIn(0f, 1f))
                }

                extractor.advance()
            }

            if (isCancelled()) {
                try { muxer.release() } catch (_: Exception) {}
                muxer = null
                tempFile.delete()
                return@withContext Result.failure(CancellationException("Audio extraction cancelled"))
            }

            muxer.stop()
            muxer.release()
            muxer = null

            storageManager.finalizeAudioOutput(
                tempFile = tempFile,
                baseName = input.name,
                jobType = JobType.AUDIO_EXTRACTION,
                audioFormat = "m4a",
                isCancelled = isCancelled
            )
        } catch (e: Exception) {
            try { muxer?.release() } catch (_: Exception) {}
            tempFile.delete()
            Result.failure(e)
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    suspend fun fastSplitVideo(
        context: Context,
        input: MediaItemInfo,
        config: SplitConfig,
        onPartProgress: (currentPart: Int, totalParts: Int, progress: Float) -> Unit,
        isCancelled: () -> Boolean
    ): Result<List<OutputMediaFile>> = withContext(Dispatchers.IO) {
        // Enforce honest feature support: Explicitly reject Precise Split if requested
        if (config.engine == SplitEngine.PRECISE_RE_ENCODE) {
            return@withContext Result.failure(
                UnsupportedOperationException("Precise Split (Re-encode) is planned for the next release. Please select Fast Split (Stream Copy).")
            )
        }

        val segments = calculateSegments(input, config)
        if (segments.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No valid split segments calculated. Please check video duration."))
        }

        val successfulOutputs = mutableListOf<OutputMediaFile>()
        val totalParts = segments.size
        var firstError: Throwable? = null

        for ((index, segment) in segments.withIndex()) {
            if (isCancelled()) break

            val (startMs, endMs) = segment
            val partNum = String.format("%03d", index + 1)
            val cleanName = input.name.substringBeforeLast(".")
            val partBaseName = "${cleanName}_part_$partNum.mp4"
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

            if (partResult.isSuccess && tempFile.exists() && tempFile.length() > 0) {
                val finalResult = storageManager.finalizeVideoOutput(
                    tempFile = tempFile,
                    baseName = partBaseName,
                    jobType = JobType.SPLITTING,
                    containerFormat = "mp4",
                    isCancelled = isCancelled
                )
                if (finalResult.isSuccess) {
                    successfulOutputs.add(finalResult.getOrThrow())
                } else {
                    tempFile.delete()
                    if (firstError == null) firstError = finalResult.exceptionOrNull()
                }
            } else {
                tempFile.delete()
                if (firstError == null) firstError = partResult.exceptionOrNull() ?: IllegalStateException("Segment $partNum failed")
            }
        }

        if (isCancelled()) {
            // Previous successful parts are preserved
            if (successfulOutputs.isEmpty()) {
                return@withContext Result.failure(CancellationException("Splitting cancelled"))
            } else {
                return@withContext Result.success(successfulOutputs)
            }
        }

        if (successfulOutputs.isEmpty() && firstError != null) {
            return@withContext Result.failure(firstError)
        }

        Result.success(successfulOutputs)
    }

    private suspend fun extractSegmentStreamCopy(
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
            var maxInputSize = 1024 * 1024

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val muxerTrack = muxer.addTrack(format)
                    trackMap[i] = muxerTrack
                    if (mime.startsWith("video/")) {
                        videoTrackIndex = i
                    }
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        val trackBufferSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                        if (trackBufferSize > maxInputSize) {
                            maxInputSize = trackBufferSize
                        }
                    }
                }
            }

            if (trackMap.isEmpty()) {
                return Result.failure(IllegalStateException("No valid video or audio tracks found in source"))
            }

            // CRITICAL: Select tracks BEFORE seeking so MediaExtractor seeks selected tracks correctly
            for (track in trackMap.keys) {
                extractor.selectTrack(track)
            }

            // Seek to nearest prior sync keyframe for decodable boundary
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            // Determine actual start time from the keyframe position to avoid broken starting frames
            var actualStartUs = extractor.sampleTime
            if (actualStartUs < 0) actualStartUs = startUs

            muxer.start()

            val buffer = ByteBuffer.allocateDirect(maxInputSize.coerceAtLeast(512 * 1024))
            val bufferInfo = MediaCodec.BufferInfo()
            val segmentDurationUs = (endUs - actualStartUs).coerceAtLeast(1L)
            var initialPtsOffsetUs = -1L

            while (!isCancelled()) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break

                val sampleTime = extractor.sampleTime
                if (sampleTime > endUs) break

                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                if (initialPtsOffsetUs == -1L) {
                    initialPtsOffsetUs = sampleTime
                }

                // Recalculate PTS starting from 0 for muxed container
                val pts = (sampleTime - initialPtsOffsetUs).coerceAtLeast(0L)

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = pts
                bufferInfo.flags = mapExtractorFlagsToBufferFlags(extractor.sampleFlags)

                val muxerTrack = trackMap[trackIndex]
                if (muxerTrack != null) {
                    muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                }

                val currentProg = ((sampleTime - actualStartUs).toFloat() / segmentDurationUs).coerceIn(0f, 1f)
                onProgress(currentProg)

                extractor.advance()
            }

            if (isCancelled()) {
                return Result.failure(CancellationException("Segment cancelled during extraction"))
            }

            muxer.stop()
            muxer.release()
            muxer = null

            return Result.success(Unit)
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun mapExtractorFlagsToBufferFlags(sampleFlags: Int): Int {
        var flags = 0
        if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0
        ) {
            flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return flags
    }

    companion object {
        internal fun calculateSegments(input: MediaItemInfo, config: SplitConfig): List<Pair<Long, Long>> {
            val totalDurationMs = input.durationMs
            if (totalDurationMs <= 0) return emptyList()

            val list = mutableListOf<Pair<Long, Long>>()

            when (config.mode) {
                SplitMode.FIXED_DURATION -> {
                    // Prevent infinite loop: enforce step of at least 1 second
                    val segMs = (config.durationSeconds.coerceAtLeast(1)) * 1000L
                    var cur = 0L
                    while (cur < totalDurationMs) {
                        val next = (cur + segMs).coerceAtMost(totalDurationMs)
                        if (next <= cur) break // safety guard
                        list.add(Pair(cur, next))
                        cur = next
                    }
                }
                SplitMode.EQUAL_PARTS -> {
                    val parts = config.equalPartsCount.coerceIn(2, 50)
                    val partDuration = (totalDurationMs / parts).coerceAtLeast(500L)
                    for (i in 0 until parts) {
                        val start = i * partDuration
                        val end = if (i == parts - 1) totalDurationMs else ((i + 1) * partDuration).coerceAtMost(totalDurationMs)
                        if (end > start) {
                            list.add(Pair(start, end))
                        }
                    }
                }
                SplitMode.MAX_SIZE -> {
                    val inputSizeMb = (input.sizeBytes / (1024.0 * 1024.0)).coerceAtLeast(1.0)
                    val targetMb = config.maxSizeMb.coerceAtLeast(5)
                    val estimatedParts = ((inputSizeMb / targetMb).toInt() + 1).coerceIn(2, 50)
                    val partDuration = (totalDurationMs / estimatedParts).coerceAtLeast(500L)
                    var cur = 0L
                    while (cur < totalDurationMs) {
                        val next = (cur + partDuration).coerceAtMost(totalDurationMs)
                        if (next <= cur) break
                        list.add(Pair(cur, next))
                        cur = next
                    }
                }
                SplitMode.CUSTOM_TIMESTAMPS -> {
                    // Filter out non-increasing and duplicate markers
                    val sorted = config.customTimestampsMs
                        .distinct()
                        .sorted()
                        .filter { it in 500L until totalDurationMs }
                    var last = 0L
                    for (ts in sorted) {
                        if (ts > last) {
                            list.add(Pair(last, ts))
                            last = ts
                        }
                    }
                    if (last < totalDurationMs) {
                        list.add(Pair(last, totalDurationMs))
                    }
                }
            }

            return list.filter { (start, end) -> end > start }
        }
    }
}
