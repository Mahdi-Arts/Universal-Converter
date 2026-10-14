package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.core.engine.MediaCodecEngine
import com.example.core.model.JobStatus
import com.example.core.model.JobType
import com.example.core.model.MediaItemInfo
import com.example.core.model.ProcessingJob
import com.example.core.model.SplitConfig
import com.example.core.model.SplitMode
import com.example.core.storage.OutputStorageManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun testAppNameString() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Universal Converter", appName)
    }

    @Test
    fun testMediaItemFormatting() {
        val info = MediaItemInfo(
            uri = Uri.parse("content://media/external/video/media/1"),
            name = "sample_video.mp4",
            sizeBytes = 25 * 1024 * 1024L,
            durationMs = 125_000L,
            width = 1920,
            height = 1080
        )

        assertEquals("02:05", info.formattedDuration)
        assertEquals("25.0 MB", info.formattedSize)
        assertEquals("1920x1080", info.resolutionLabel)
    }

    @Test
    fun testJobCompressionSavings() {
        val job = ProcessingJob(
            id = "test-1",
            title = "Compress video",
            type = JobType.COMPRESSION,
            status = JobStatus.COMPLETED,
            originalSizeBytes = 100_000_000L,
            outputSizeBytes = 40_000_000L
        )

        assertEquals(60, job.compressionSavingsPercent)
    }

    @Test
    fun testOutputStorageManagerTempFileCreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storageManager = OutputStorageManager(context)

        val tempFile = storageManager.createTempFile("unit_test", "mp4")
        assertTrue(tempFile.name.startsWith("unit_test_"))
        assertTrue(tempFile.name.endsWith(".mp4"))
        assertTrue(tempFile.parentFile?.name == "universal_temp")

        // Cleanup
        tempFile.delete()
    }

    @Test
    fun testOutputStorageManagerDisplayNameGeneration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storageManager = OutputStorageManager(context)

        val base = "my_holiday_clip.mov"
        assertEquals("my_holiday_clip_compressed.mp4", storageManager.generateTargetDisplayName(base, JobType.COMPRESSION, "mp4"))
        assertEquals("my_holiday_clip_converted.webm", storageManager.generateTargetDisplayName(base, JobType.CONVERSION, "webm"))
        assertEquals("my_holiday_clip_edited.mp4", storageManager.generateTargetDisplayName(base, JobType.EDITING, "mp4"))
        assertEquals("my_holiday_clip_part_001.mp4", storageManager.generateTargetDisplayName("my_holiday_clip_part_001.mp4", JobType.SPLITTING, "mp4"))
        assertEquals("my_holiday_clip_audio.m4a", storageManager.generateTargetDisplayName(base, JobType.AUDIO_EXTRACTION, "m4a"))
        assertEquals("my_holiday_clip_thumb.png", storageManager.generateTargetDisplayName(base, JobType.THUMBNAIL, "png"))
    }

    @Test
    fun testFinalizeEmptyVideoOutputFailsCleanly() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storageManager = OutputStorageManager(context)

        val emptyTemp = storageManager.createTempFile("empty_test", "mp4")
        val result = storageManager.finalizeVideoOutput(
            tempFile = emptyTemp,
            baseName = "empty.mp4",
            jobType = JobType.COMPRESSION
        )

        assertTrue(result.isFailure)
        assertFalse(emptyTemp.exists())
    }

    @Test
    fun testFinalizeMissingVideoOutputFailsCleanly() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storageManager = OutputStorageManager(context)

        val nonExistent = File(context.cacheDir, "non_existent_file.mp4")
        val result = storageManager.finalizeVideoOutput(
            tempFile = nonExistent,
            baseName = "ghost.mp4",
            jobType = JobType.COMPRESSION
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun testCalculateSegmentsFixedDuration() {
        val input = MediaItemInfo(
            uri = Uri.parse("content://media/external/video/media/1"),
            name = "video.mp4",
            sizeBytes = 10_000_000L,
            durationMs = 65_000L,
            width = 1920,
            height = 1080
        )

        val config = SplitConfig(
            mode = SplitMode.FIXED_DURATION,
            durationSeconds = 30
        )
        val segments = MediaCodecEngine.calculateSegments(input, config)
        assertEquals(3, segments.size)
        assertEquals(0L to 30000L, segments[0])
        assertEquals(30000L to 60000L, segments[1])
        assertEquals(60000L to 65000L, segments[2])
    }

    @Test
    fun testCalculateSegmentsEqualParts() {
        val input = MediaItemInfo(
            uri = Uri.parse("content://media/external/video/media/1"),
            name = "video.mp4",
            sizeBytes = 10_000_000L,
            durationMs = 100_000L,
            width = 1920,
            height = 1080
        )

        val config = SplitConfig(
            mode = SplitMode.EQUAL_PARTS,
            equalPartsCount = 4
        )
        val segments = MediaCodecEngine.calculateSegments(input, config)
        assertEquals(4, segments.size)
        assertEquals(0L to 25000L, segments[0])
        assertEquals(25000L to 50000L, segments[1])
        assertEquals(50000L to 75000L, segments[2])
        assertEquals(75000L to 100000L, segments[3])
    }

    @Test
    fun testCalculateSegmentsCustomTimestamps() {
        val input = MediaItemInfo(
            uri = Uri.parse("content://media/external/video/media/1"),
            name = "video.mp4",
            sizeBytes = 10_000_000L,
            durationMs = 60_000L,
            width = 1920,
            height = 1080
        )

        val config = SplitConfig(
            mode = SplitMode.CUSTOM_TIMESTAMPS,
            customTimestampsMs = listOf(15000L, 40000L)
        )
        val segments = MediaCodecEngine.calculateSegments(input, config)
        assertEquals(3, segments.size)
        assertEquals(0L to 15000L, segments[0])
        assertEquals(15000L to 40000L, segments[1])
        assertEquals(40000L to 60000L, segments[2])
    }

    @Test
    fun testCalculateSegmentsEmptyDuration() {
        val input = MediaItemInfo(
            uri = Uri.parse("content://media/external/video/media/1"),
            name = "empty.mp4",
            sizeBytes = 0L,
            durationMs = 0L,
            width = 0,
            height = 0
        )

        val config = SplitConfig(mode = SplitMode.FIXED_DURATION, durationSeconds = 30)
        val segments = MediaCodecEngine.calculateSegments(input, config)
        assertTrue(segments.isEmpty())
    }
}
