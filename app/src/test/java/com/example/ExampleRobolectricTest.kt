package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.model.CompressionPreset
import com.example.core.model.CropRatio
import com.example.core.model.JobStatus
import com.example.core.model.JobType
import com.example.core.model.MediaItemInfo
import com.example.core.model.ProcessingJob
import com.example.core.model.SplitConfig
import com.example.core.model.SplitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
            uri = android.net.Uri.EMPTY,
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
    fun testCropRatioValues() {
        assertEquals(16f / 9f, CropRatio.RATIO_16_9.ratio!!, 0.001f)
        assertEquals(9f / 16f, CropRatio.RATIO_9_16.ratio!!, 0.001f)
        assertEquals(1f, CropRatio.RATIO_1_1.ratio!!, 0.001f)
        assertEquals(null, CropRatio.ORIGINAL.ratio)
    }

    @Test
    fun testSplitCalculations() {
        val splitConfig = SplitConfig(
            mode = SplitMode.FIXED_DURATION,
            durationSeconds = 30
        )
        val durationMs = 90_000L
        val segDurationMs = splitConfig.durationSeconds * 1000L
        val partsCount = (durationMs / segDurationMs).toInt()
        assertEquals(3, partsCount)
    }
}
