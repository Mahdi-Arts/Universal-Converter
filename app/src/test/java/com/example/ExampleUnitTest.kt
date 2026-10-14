package com.example

import com.example.core.ai.GeminiThumbnailAdvisor
import com.example.core.model.CompressionConfig
import com.example.core.model.CompressionPreset
import com.example.core.model.CropRatio
import com.example.core.model.JobStatus
import com.example.core.model.JobType
import com.example.core.model.ProcessingJob
import com.example.core.model.ThumbnailConfig
import com.example.core.model.ThumbnailFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testCompressionConfigDefaults() {
        val config = CompressionConfig()
        assertEquals(CompressionPreset.BALANCED, config.preset)
        assertEquals("video/avc", config.videoMimeType)
        assertEquals("audio/mp4a-latm", config.audioMimeType)
        assertFalse(config.removeAudio)
        assertTrue(config.preserveMetadata)
    }

    @Test
    fun testThumbnailConfigDefaults() {
        val config = ThumbnailConfig(
            frameTimestampMs = 5000L,
            format = ThumbnailFormat.PNG
        )
        assertEquals(5000L, config.frameTimestampMs)
        assertEquals(ThumbnailFormat.PNG, config.format)
        assertEquals(0f, config.brightness, 0.001f)
        assertEquals(1f, config.contrast, 0.001f)
    }

    @Test
    fun testProcessingJobStateTransitions() {
        val job = ProcessingJob(
            id = "job-42",
            title = "Test",
            type = JobType.EDITING,
            status = JobStatus.QUEUED
        )
        assertEquals(JobStatus.QUEUED, job.status)
        assertEquals(0f, job.progress, 0.001f)

        val updated = job.copy(status = JobStatus.PROCESSING, progress = 0.5f)
        assertEquals(JobStatus.PROCESSING, updated.status)
        assertEquals(0.5f, updated.progress, 0.001f)
    }

    @Test
    fun testCropRatioValues() {
        assertEquals(16f / 9f, CropRatio.RATIO_16_9.ratio!!, 0.001f)
        assertEquals(9f / 16f, CropRatio.RATIO_9_16.ratio!!, 0.001f)
        assertEquals(1f, CropRatio.RATIO_1_1.ratio!!, 0.001f)
        assertEquals(null, CropRatio.ORIGINAL.ratio)
    }

    @Test
    fun testGeminiThumbnailAdvisorSuggestions() = runBlocking {
        val enTitles = GeminiThumbnailAdvisor.suggestCatchyTitles("my_vacation_vlog.mp4", isPersian = false)
        assertTrue(enTitles.isNotEmpty())
        assertTrue(enTitles[0].contains("my vacation vlog", ignoreCase = true))

        val faTitles = GeminiThumbnailAdvisor.suggestCatchyTitles("amoozesh_film.mp4", isPersian = true)
        assertTrue(faTitles.isNotEmpty())

        val faPalettes = GeminiThumbnailAdvisor.getSuggestedColorPalettes(isPersian = true)
        assertEquals(4, faPalettes.size)

        val enPalettes = GeminiThumbnailAdvisor.getSuggestedColorPalettes(isPersian = false)
        assertEquals(4, enPalettes.size)
    }
}
