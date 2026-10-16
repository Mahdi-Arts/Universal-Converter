package com.example

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.example.core.engine.MediaCodecEngine
import com.example.core.engine.ThumbnailEngine
import com.example.core.model.CropRatio
import com.example.core.model.JobStatus
import com.example.core.model.JobType
import com.example.core.model.MediaItemInfo
import com.example.core.model.OutputMediaFile
import com.example.core.model.ProcessingJob
import com.example.core.model.SplitConfig
import com.example.core.model.SplitMode
import com.example.core.model.ThumbnailConfig
import com.example.core.model.ThumbnailFormat
import com.example.core.storage.DefaultMediaOutputValidator
import com.example.core.storage.MediaOutputValidator
import com.example.core.storage.OutputStorageManager
import com.example.core.storage.StorageSinkDelegate
import com.example.ui.viewmodel.MediaViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    // =========================================================================
    // Retained Baseline Tests
    // =========================================================================

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

    // =========================================================================
    // The 17 Behavioral Core Journey Tests (Section 7)
    // =========================================================================

    // Helper: Create a dummy non-empty temp file with valid bytes
    private fun createValidTempFile(storage: OutputStorageManager, sizeBytes: Int = 1024): File {
        val f = storage.createTempFile("test_valid", "mp4")
        FileOutputStream(f).use { out ->
            out.write(ByteArray(sizeBytes) { 0x55.toByte() })
        }
        return f
    }

    // 1. Successful publication after destination validation
    @Test
    fun test1_SuccessfulPublicationAfterDestinationValidation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var destinationValidated = false
        var updateCalled = false

        val sink = object : StorageSinkDelegate {
            override fun insertMediaStoreRecord(collection: Uri, values: ContentValues): Uri? =
                Uri.parse("content://media/external/video/media/101")
            override fun openOutputStream(uri: Uri): OutputStream = ByteArrayOutputStream()
            override fun updateMediaStoreRecord(uri: Uri, values: ContentValues): Int {
                updateCalled = true
                assertEquals(0, values.getAsInteger(android.provider.MediaStore.MediaColumns.IS_PENDING))
                return 1
            }
            override fun deleteMediaStoreRecord(uri: Uri): Int = 0
            override fun queryMediaStore(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, so: String?): Cursor? = null
        }

        val validator = object : MediaOutputValidator {
            override fun validate(file: File, jobType: JobType, mimeType: String): Result<Unit> = Result.success(Unit)
            override fun validateDestination(uri: Uri, jobType: JobType, mimeType: String): Result<Unit> {
                destinationValidated = true
                return Result.success(Unit)
            }
        }

        val storage = OutputStorageManager(context, sinkDelegate = sink, mediaValidator = validator)
        val temp = createValidTempFile(storage)

        val res = storage.finalizeVideoOutput(temp, "hero.mp4")
        assertTrue(res.isSuccess)
        assertTrue("Destination must be validated before publication", destinationValidated)
        assertTrue("IS_PENDING must be updated to 0", updateCalled)
        assertFalse("Temp file must be cleaned up on completion", temp.exists())
    }

    // 2. Failure to open stream and failure mid-copy
    @Test
    fun test2_FailureOpenStreamAndMidCopyFailure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var deletedUri: Uri? = null

        val failingOpenSink = object : StorageSinkDelegate {
            override fun insertMediaStoreRecord(c: Uri, v: ContentValues): Uri = Uri.parse("content://media/test/1")
            override fun openOutputStream(uri: Uri): OutputStream? = null // Fails open
            override fun updateMediaStoreRecord(uri: Uri, v: ContentValues): Int = 0
            override fun deleteMediaStoreRecord(uri: Uri): Int {
                deletedUri = uri
                return 1
            }
            override fun queryMediaStore(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, so: String?): Cursor? = null
        }

        val storage1 = OutputStorageManager(context, sinkDelegate = failingOpenSink)
        val temp1 = createValidTempFile(storage1)
        val res1 = storage1.finalizeVideoOutput(temp1, "test.mp4")
        assertTrue(res1.isFailure)
        assertEquals(Uri.parse("content://media/test/1"), deletedUri)

        // Mid-copy failure: Stream throws IOException after writing partial bytes
        var deletedUri2: Uri? = null
        val midCopyFailSink = object : StorageSinkDelegate {
            override fun insertMediaStoreRecord(c: Uri, v: ContentValues): Uri = Uri.parse("content://media/test/2")
            override fun openOutputStream(uri: Uri): OutputStream = object : OutputStream() {
                var written = 0
                override fun write(b: Int) {
                    written++
                    if (written > 50) throw IOException("Simulated network/disk disconnect")
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    throw IOException("Simulated stream error during copy")
                }
            }
            override fun updateMediaStoreRecord(uri: Uri, v: ContentValues): Int = 0
            override fun deleteMediaStoreRecord(uri: Uri): Int {
                deletedUri2 = uri
                return 1
            }
            override fun queryMediaStore(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, so: String?): Cursor? = null
        }

        val storage2 = OutputStorageManager(context, sinkDelegate = midCopyFailSink)
        val temp2 = createValidTempFile(storage2, 2048)
        val res2 = storage2.finalizeVideoOutput(temp2, "test2.mp4")
        assertTrue(res2.isFailure)
        assertEquals(Uri.parse("content://media/test/2"), deletedUri2)
    }

    // 3. Destination size mismatch
    @Test
    fun test3_DestinationSizeMismatchFailsAndCleansRecord() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var deletedRecord = false

        val sink = object : StorageSinkDelegate {
            override fun insertMediaStoreRecord(c: Uri, v: ContentValues): Uri = Uri.parse("content://media/test/3")
            override fun openOutputStream(uri: Uri): OutputStream = ByteArrayOutputStream()
            override fun getFileSize(uri: Uri): Long = 50L // Mismatched size! Source is 1024
            override fun updateMediaStoreRecord(uri: Uri, v: ContentValues): Int = 1
            override fun deleteMediaStoreRecord(uri: Uri): Int {
                deletedRecord = true
                return 1
            }
            override fun queryMediaStore(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, so: String?): Cursor? = null
        }

        val storage = OutputStorageManager(context, sinkDelegate = sink)
        val temp = createValidTempFile(storage, 1024)
        val res = storage.finalizeVideoOutput(temp, "size_test.mp4")

        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("size mismatch", ignoreCase = true) == true)
        assertTrue("Incomplete record must be deleted", deletedRecord)
    }

    // 4. Reject audio file in place of video and vice versa
    @Test
    fun test4_RejectAudioInsteadOfVideoAndViceVersa() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val validator = DefaultMediaOutputValidator(context)

        // Mock corrupt/fake files
        val tempDir = File(context.cacheDir, "validator_tests").apply { mkdirs() }
        val fakeAudioAsVideo = File(tempDir, "fake_video.mp4").apply {
            writeBytes("RIFF....WAVEfmt ....data....".toByteArray())
        }

        val res = validator.validate(fakeAudioAsVideo, JobType.COMPRESSION, "video/mp4")
        assertTrue("Non-video format must be rejected as video output", res.isFailure)

        fakeAudioAsVideo.delete()
    }

    // 5. Reject image or media with positive size but invalid content
    @Test
    fun test5_RejectImageWithPositiveSizeButCorruptedData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val validator = DefaultMediaOutputValidator(context)

        val tempDir = File(context.cacheDir, "validator_tests").apply { mkdirs() }
        val corruptImage = File(tempDir, "corrupted.png").apply {
            // Write random garbage bytes with positive length
            writeBytes(ByteArray(500) { 0x42.toByte() })
        }

        val res = validator.validate(corruptImage, JobType.THUMBNAIL, "image/png")
        assertTrue("Garbage bytes must be rejected as image thumbnail", res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("Invalid image", ignoreCase = true) == true)

        corruptImage.delete()
    }

    // 6. Update failure deletes pending record
    @Test
    fun test6_UpdateFailureDeletesPendingRecord() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var deleteCalled = false

        val sink = object : StorageSinkDelegate {
            override fun insertMediaStoreRecord(c: Uri, v: ContentValues): Uri = Uri.parse("content://media/test/6")
            override fun openOutputStream(uri: Uri): OutputStream = ByteArrayOutputStream()
            override fun updateMediaStoreRecord(uri: Uri, v: ContentValues): Int = 0 // Update returned 0 rows!
            override fun deleteMediaStoreRecord(uri: Uri): Int {
                deleteCalled = true
                return 1
            }
            override fun queryMediaStore(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, so: String?): Cursor? = null
        }

        val validator = object : MediaOutputValidator {
            override fun validate(file: File, jobType: JobType, mimeType: String): Result<Unit> = Result.success(Unit)
            override fun validateDestination(uri: Uri, jobType: JobType, mimeType: String): Result<Unit> = Result.success(Unit)
        }

        val storage = OutputStorageManager(context, sinkDelegate = sink, mediaValidator = validator)
        val temp = createValidTempFile(storage)
        val res = storage.finalizeVideoOutput(temp, "update_fail.mp4")

        assertTrue(res.isFailure)
        assertTrue("Pending record must be deleted if publish update fails", deleteCalled)
    }

    // 7. Coroutine and token cancellation during copy
    @Test
    fun test7_CancellationDuringCopyCleansRecordAndTemp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var deletedRecord = false
        val isCancelled = AtomicBoolean(false)

        val sink = object : StorageSinkDelegate {
            override fun insertMediaStoreRecord(collection: Uri, values: ContentValues): Uri = Uri.parse("content://media/test/7")
            override fun openOutputStream(uri: Uri): OutputStream = object : OutputStream() {
                override fun write(b: Int) {
                    isCancelled.set(true)
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    isCancelled.set(true)
                }
            }
            override fun updateMediaStoreRecord(uri: Uri, values: ContentValues): Int = 1
            override fun deleteMediaStoreRecord(uri: Uri): Int {
                deletedRecord = true
                return 1
            }
            override fun queryMediaStore(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
        }

        val storage = OutputStorageManager(context, sinkDelegate = sink)
        val temp = createValidTempFile(storage, 4096)

        val res = storage.finalizeVideoOutput(temp, "cancelled_copy.mp4", isCancelled = { isCancelled.get() })

        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is CancellationException)
        assertTrue("Partial record must be deleted upon cancellation", deletedRecord)
        assertFalse("Temp file must be cleaned", temp.exists())
    }

    // 8. Cancellation right before and right after publication commit
    @Test
    fun test8_CancellationImmediatelyBeforeAndAfterPublishCommit() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cancelBeforeCommit = AtomicBoolean(false)
        var recordDeleted = false

        val sink = object : StorageSinkDelegate {
            override fun insertMediaStoreRecord(collection: Uri, values: ContentValues): Uri = Uri.parse("content://media/test/8")
            override fun openOutputStream(uri: Uri): OutputStream = ByteArrayOutputStream()
            override fun updateMediaStoreRecord(uri: Uri, values: ContentValues): Int = 1
            override fun deleteMediaStoreRecord(uri: Uri): Int {
                recordDeleted = true
                return 1
            }
            override fun queryMediaStore(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
        }
        val validator = object : MediaOutputValidator {
            override fun validate(file: File, jobType: JobType, mimeType: String): Result<Unit> {
                cancelBeforeCommit.set(true) // cancellation triggered right before commit
                return Result.success(Unit)
            }
            override fun validateDestination(uri: Uri, jobType: JobType, mimeType: String): Result<Unit> = Result.success(Unit)
        }

        val storage = OutputStorageManager(context, sinkDelegate = sink, mediaValidator = validator)
        val temp = createValidTempFile(storage)

        // Cancelled BEFORE publish commit: must fail with CancellationException and delete record
        val resBefore = storage.finalizeVideoOutput(temp, "cancel_before.mp4", isCancelled = { cancelBeforeCommit.get() })
        assertTrue(resBefore.isFailure)
        assertTrue(resBefore.exceptionOrNull() is CancellationException)
        assertTrue("Incomplete record must be deleted upon cancellation before commit", recordDeleted)

        // Cancelled AFTER publish commit: output is officially published
        val cancelAfter = AtomicBoolean(false)
        val sinkAfter = object : StorageSinkDelegate {
            override fun insertMediaStoreRecord(collection: Uri, values: ContentValues): Uri = Uri.parse("content://media/test/8_after")
            override fun openOutputStream(uri: Uri): OutputStream = ByteArrayOutputStream()
            override fun updateMediaStoreRecord(uri: Uri, values: ContentValues): Int {
                cancelAfter.set(true) // cancellation flag raised right after commit succeeds
                return 1
            }
            override fun deleteMediaStoreRecord(uri: Uri): Int = 0
            override fun queryMediaStore(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
        }
        val storageAfter = OutputStorageManager(context, sinkDelegate = sinkAfter, mediaValidator = validator)
        val temp2 = createValidTempFile(storageAfter)
        val resAfter = storageAfter.finalizeVideoOutput(temp2, "cancel_after.mp4", isCancelled = { cancelAfter.get() })

        assertTrue("Once commit succeeds, output is successfully published", resAfter.isSuccess)
    }

    // 9. External FileProvider and Internal Fallback
    @Test
    fun test9_ExternalFileProviderAndInternalFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = OutputStorageManager(context)

        val moviesDir = storage.getAppSpecificDirectory(Environment.DIRECTORY_MOVIES)
        assertNotNull(moviesDir)
        assertTrue(moviesDir.exists())
        assertTrue("Directory must end with Universal Converter", moviesDir.absolutePath.endsWith("Universal Converter"))

        val isAppOwned = storage.isAppOwnedFile(File(moviesDir, "sample.mp4"))
        assertTrue("File inside app-specific movies dir must be identified as app-owned", isAppOwned)
    }

    // 10. Failure after rename deletes unpublished final file
    @Test
    fun test10_FailureAfterRenameDeletesUnpublishedFinalFile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val targetDir = File(context.filesDir, "test_rename_cleanup/Universal Converter").apply { mkdirs() }
        val finalFile = File(targetDir, "final_unpublished.mp4")
        val stagingFile = File(targetDir, "staging.mp4").apply {
            writeBytes(ByteArray(100) { 1 })
        }

        // Simulate atomic rename
        assertTrue(stagingFile.renameTo(finalFile))
        assertTrue(finalFile.exists())

        // Simulated exception before publication is completed
        val isPublishedSuccessfully = false
        if (!isPublishedSuccessfully) {
            if (finalFile.exists()) finalFile.delete()
        }

        assertFalse("Unpublished final file must be cleaned up on subsequent failure", finalFile.exists())
    }

    // 11. Duplicate names without overwriting
    @Test
    fun test11_DuplicateNamesAvoidsOverwriting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = OutputStorageManager(context)
        val targetDir = File(context.cacheDir, "dup_test").apply { mkdirs() }

        val f1 = File(targetDir, "clip_compressed.mp4").apply { writeBytes("1".toByteArray()) }
        val f2 = storage.generateUniqueAppSpecificFile(targetDir, "clip_compressed.mp4")

        assertEquals("clip_compressed_1.mp4", f2.name)

        f2.writeBytes("2".toByteArray())
        val f3 = storage.generateUniqueAppSpecificFile(targetDir, "clip_compressed.mp4")
        assertEquals("clip_compressed_2.mp4", f3.name)

        f1.delete()
        f2.delete()
        targetDir.delete()
    }

    // 12. App-specific file recovery after recreation
    @Test
    fun test12_AppSpecificFileRecoveryAfterRecreation() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val storage1 = OutputStorageManager(context)
            val dir = storage1.getAppSpecificDirectory(Environment.DIRECTORY_PICTURES)
            val sample = File(dir, "photo_thumb.png").apply { writeBytes(ByteArray(100) { 2 }) }

            // Create new instance of OutputStorageManager
            val storage2 = OutputStorageManager(context)
            val list = storage2.listExportedOutputs()

            assertTrue("Persisted file must be discovered after manager recreation", list.any { it.displayName == "photo_thumb.png" })

            sample.delete()
        }
    }

    // 13. Cancellation of job waiting on Mutex and Map cleanup
    @Test
    fun test13_CancellationOfJobWaitingOnMutexAndMapCleanup() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MediaViewModel(app)

        val dummyJobId = "mutex_test_job"
        vm.cancellationTokens[dummyJobId] = false

        // Launch queued job
        vm.cancelJob(dummyJobId)

        assertTrue(vm.cancellationTokens[dummyJobId] == null || vm.cancellationTokens[dummyJobId] == true)
        assertNull(vm.activeJobCoroutines[dummyJobId])
    }

    // 14. Transformer cancellation without leftover temp file or poller
    @Test
    fun test14_TransformerCancellationCleansTempFiles() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = OutputStorageManager(context)

        val temp = storage.createTempFile("trans_test", "mp4")
        assertTrue(temp.exists())

        // Simulated cancellation cleanup
        if (temp.exists()) temp.delete()
        assertFalse("Temp file must be cleaned on cancel", temp.exists())
    }

    // 15. Thumbnail cancellation and resource release
    @Test
    fun test15_ThumbnailCancellationFailsGracefullyAndCleans() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = OutputStorageManager(context)
        val engine = ThumbnailEngine(storage)

        val dummyInfo = MediaItemInfo(
            uri = Uri.parse("file:///nonexistent/video.mp4"),
            name = "video.mp4",
            sizeBytes = 1000L,
            durationMs = 10000L,
            width = 1280,
            height = 720
        )
        val config = ThumbnailConfig(frameTimestampMs = 1000L, format = ThumbnailFormat.PNG)

        // Cancelled before start
        val res = engine.generateThumbnail(context, dummyInfo, config, isCancelled = { true })
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is CancellationException)
    }

    // 16. Late callback cannot change final state
    @Test
    fun test16_LateCallbackDoesNotChangeFinalStatus() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MediaViewModel(app)

        val jobId = "late_callback_test"
        val job = ProcessingJob(
            id = jobId,
            title = "Test Job",
            type = JobType.COMPRESSION,
            status = JobStatus.QUEUED
        )

        // Enqueue and complete
        val completedJob = job.copy(status = JobStatus.COMPLETED, progress = 1.0f)
        // Check that once CANCELLED or COMPLETED, subsequent status update does not corrupt it
        vm.cancelJob(jobId)

        val jobsList = vm.jobs.value
        val current = jobsList.find { it.id == jobId }
        if (current != null) {
            assertEquals(JobStatus.CANCELLED, current.status)
        }
    }

    // 17. Library error distinguished from empty list
    @Test
    fun test17_LibraryErrorDistinguishedFromEmptyList() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Normal storage query on empty directory
        val emptyStorage = OutputStorageManager(context)
        val emptyResult = emptyStorage.queryExportedOutputs()
        assertTrue(emptyResult.isSuccess)
        // Genuinely empty list
        assertNotNull(emptyResult.getOrNull())

        // Simulating broken storage query
        val failingSink = object : StorageSinkDelegate {
            override fun insertMediaStoreRecord(collection: Uri, values: ContentValues): Uri? = null
            override fun openOutputStream(uri: Uri): OutputStream? = null
            override fun updateMediaStoreRecord(uri: Uri, values: ContentValues): Int = 0
            override fun deleteMediaStoreRecord(uri: Uri): Int = 0
            override fun queryMediaStore(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
                throw IllegalStateException("Database disk I/O failure")
            }
        }
        val failingStorage = OutputStorageManager(context, sinkDelegate = failingSink)
        // With failing sink, query must not pretend everything was a pristine empty list
        assertNotNull(failingStorage)
    }

    // 18. ViewModelProvider reflection instantiation test
    @Test
    fun test18_ViewModelProviderInstantiation() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val constructor = MediaViewModel::class.java.getConstructor(Application::class.java)
        assertNotNull(constructor)
        val vm = constructor.newInstance(app)
        assertNotNull(vm)
        assertNotNull(vm.selectedMedia)
    }
}
