package com.example

import com.example.core.model.CompressionConfig
import com.example.core.model.CompressionPreset
import com.example.core.model.JobStatus
import com.example.core.model.JobType
import com.example.core.model.ProcessingJob
import com.example.core.model.ThumbnailConfig
import com.example.core.model.ThumbnailFormat
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
}
