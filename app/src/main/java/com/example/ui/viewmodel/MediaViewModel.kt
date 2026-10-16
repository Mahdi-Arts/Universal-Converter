package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.codec.CodecCapability
import com.example.core.codec.DeviceCodecInspector
import com.example.core.engine.Media3Engine
import com.example.core.engine.MediaCodecEngine
import com.example.core.engine.ThumbnailEngine
import com.example.core.media.MediaMetadataInspector
import com.example.core.model.CompressionConfig
import com.example.core.model.CompressionPreset
import com.example.core.model.EditConfig
import com.example.core.model.JobStatus
import com.example.core.model.JobType
import com.example.core.model.MediaItemInfo
import com.example.core.model.OutputMediaFile
import com.example.core.model.ProcessingJob
import com.example.core.model.SplitConfig
import com.example.core.model.ThumbnailConfig
import com.example.core.storage.OutputStorageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MediaViewModel @JvmOverloads constructor(
    application: Application,
    private val storageManager: OutputStorageManager = OutputStorageManager(application),
    private val mediaCodecEngine: MediaCodecEngine = MediaCodecEngine(storageManager),
    private val thumbnailEngine: ThumbnailEngine = ThumbnailEngine(storageManager),
    private val media3Engine: Media3Engine = Media3Engine(storageManager, mediaCodecEngine, thumbnailEngine)
) : AndroidViewModel(application) {

    private val _selectedMedia = MutableStateFlow<MediaItemInfo?>(null)
    val selectedMedia: StateFlow<MediaItemInfo?> = _selectedMedia.asStateFlow()

    private val _isLoadingMedia = MutableStateFlow(false)
    val isLoadingMedia: StateFlow<Boolean> = _isLoadingMedia.asStateFlow()

    private val _isPersianLanguage = MutableStateFlow(false)
    val isPersianLanguage: StateFlow<Boolean> = _isPersianLanguage.asStateFlow()

    private val _isDarkTheme = MutableStateFlow<Boolean?>(null)
    val isDarkTheme: StateFlow<Boolean?> = _isDarkTheme.asStateFlow()

    private val _jobs = MutableStateFlow<List<ProcessingJob>>(emptyList())
    val jobs: StateFlow<List<ProcessingJob>> = _jobs.asStateFlow()

    private val _libraryOutputs = MutableStateFlow<List<OutputMediaFile>>(emptyList())
    val libraryOutputs: StateFlow<List<OutputMediaFile>> = _libraryOutputs.asStateFlow()

    private val _libraryError = MutableStateFlow<String?>(null)
    val libraryError: StateFlow<String?> = _libraryError.asStateFlow()

    private val _codecCapabilities = MutableStateFlow<List<CodecCapability>>(emptyList())
    val codecCapabilities: StateFlow<List<CodecCapability>> = _codecCapabilities.asStateFlow()

    // Concurrency control: heavy transcoding/encoding jobs are serialized to prevent OOM
    private val heavyJobMutex = Mutex()
    internal val activeJobCoroutines = ConcurrentHashMap<String, Job>()
    internal val cancellationTokens = ConcurrentHashMap<String, Boolean>()

    init {
        loadCodecCapabilities()
        refreshLibraryOutputs()
    }

    fun selectMediaUri(uri: Uri) {
        viewModelScope.launch {
            _isLoadingMedia.value = true
            try {
                val result = MediaMetadataInspector.inspect(getApplication(), uri)
                if (result.isSuccess) {
                    _selectedMedia.value = result.getOrNull()
                }
            } finally {
                _isLoadingMedia.value = false
            }
        }
    }

    fun selectMedia(uri: Uri) = selectMediaUri(uri)

    fun clearSelectedMedia() {
        _selectedMedia.value = null
    }

    fun setLanguagePersian(isPersian: Boolean) {
        _isPersianLanguage.value = isPersian
    }

    fun setThemePreference(isDark: Boolean?) {
        _isDarkTheme.value = isDark
    }

    private fun loadCodecCapabilities() {
        viewModelScope.launch {
            val encoders = DeviceCodecInspector.getSupportedEncoders()
            _codecCapabilities.value = encoders
        }
    }

    fun refreshLibraryOutputs() {
        viewModelScope.launch {
            val result = storageManager.queryExportedOutputs()
            if (result.isSuccess) {
                _libraryOutputs.value = result.getOrThrow()
                _libraryError.value = null
            } else {
                _libraryError.value = result.exceptionOrNull()?.localizedMessage ?: "Failed to read library"
            }
        }
    }

    fun cancelJob(jobId: String) {
        cancellationTokens[jobId] = true
        activeJobCoroutines[jobId]?.cancel()
        activeJobCoroutines.remove(jobId)
        _jobs.update { list ->
            list.map {
                if (it.id == jobId && (it.status == JobStatus.PROCESSING || it.status == JobStatus.QUEUED)) {
                    it.copy(status = JobStatus.CANCELLED)
                } else it
            }
        }
        refreshLibraryOutputs()
    }

    fun clearCompletedJobs() {
        _jobs.update { list ->
            list.filter { it.status == JobStatus.PROCESSING || it.status == JobStatus.QUEUED }
        }
    }

    fun deleteExportedOutput(file: OutputMediaFile) {
        viewModelScope.launch {
            val deleted = storageManager.deleteExportedFile(file)
            if (deleted) {
                _libraryOutputs.update { it.filter { item -> item.id != file.id } }
            }
        }
    }

    fun openExportedOutput(file: OutputMediaFile): Result<Unit> {
        return storageManager.openFile(file)
    }

    fun shareExportedOutput(file: OutputMediaFile): Result<Unit> {
        return storageManager.shareFile(file)
    }

    fun openJobOutput(job: ProcessingJob): Result<Unit> {
        val uri = job.outputUri ?: return Result.failure(IllegalStateException("No output URI for this job"))
        val mime = job.currentCodec ?: when (job.type) {
            JobType.AUDIO_EXTRACTION -> "audio/mp4"
            JobType.THUMBNAIL -> "image/png"
            else -> "video/mp4"
        }
        val file = OutputMediaFile(
            id = job.id,
            displayName = job.title,
            uri = uri,
            path = job.outputPath ?: uri.toString(),
            sizeBytes = job.outputSizeBytes,
            type = job.type,
            mimeType = mime
        )
        return storageManager.openFile(file)
    }

    fun shareJobOutput(job: ProcessingJob): Result<Unit> {
        val uri = job.outputUri ?: return Result.failure(IllegalStateException("No output URI for this job"))
        val mime = job.currentCodec ?: when (job.type) {
            JobType.AUDIO_EXTRACTION -> "audio/mp4"
            JobType.THUMBNAIL -> "image/png"
            else -> "video/mp4"
        }
        val file = OutputMediaFile(
            id = job.id,
            displayName = job.title,
            uri = uri,
            path = job.outputPath ?: uri.toString(),
            sizeBytes = job.outputSizeBytes,
            type = job.type,
            mimeType = mime
        )
        return storageManager.shareFile(file)
    }

    // --- Compression & Conversion ---
    fun startCompression(config: CompressionConfig) {
        val input = _selectedMedia.value ?: return
        val jobId = UUID.randomUUID().toString()
        val job = ProcessingJob(
            id = jobId,
            title = "Compress: ${input.name}",
            type = JobType.COMPRESSION,
            status = JobStatus.QUEUED,
            originalSizeBytes = input.sizeBytes,
            targetSizeBytes = if (config.preset == CompressionPreset.TARGET_SIZE) config.targetSizeBytes else 0L,
            currentCodec = config.videoMimeType,
            currentResolution = "${config.resolutionWidth}x${config.resolutionHeight}"
        )

        enqueueJob(job)
        executeJobInternal(jobId, useMutex = true) {
            media3Engine.compressOrConvert(
                context = getApplication(),
                input = input,
                config = config,
                onProgress = { p -> updateJobProgress(jobId, p) },
                isCancelled = { cancellationTokens[jobId] == true }
            )
        }
    }

    fun startFormatConversion(outputMime: String, targetCodec: String) {
        val input = _selectedMedia.value ?: return
        val config = CompressionConfig(
            preset = CompressionPreset.BALANCED,
            videoMimeType = targetCodec
        )
        val jobId = UUID.randomUUID().toString()
        val job = ProcessingJob(
            id = jobId,
            title = "Convert: ${input.name} to $outputMime",
            type = JobType.CONVERSION,
            status = JobStatus.QUEUED,
            originalSizeBytes = input.sizeBytes,
            currentCodec = targetCodec
        )

        enqueueJob(job)
        executeJobInternal(jobId, useMutex = true) {
            media3Engine.compressOrConvert(
                context = getApplication(),
                input = input,
                config = config,
                onProgress = { p -> updateJobProgress(jobId, p) },
                isCancelled = { cancellationTokens[jobId] == true }
            )
        }
    }

    // --- Video Editing ---
    fun startVideoEdit(config: EditConfig) {
        val input = _selectedMedia.value ?: return
        val jobId = UUID.randomUUID().toString()
        val job = ProcessingJob(
            id = jobId,
            title = "Edit: ${input.name}",
            type = JobType.EDITING,
            status = JobStatus.QUEUED,
            originalSizeBytes = input.sizeBytes
        )

        enqueueJob(job)
        executeJobInternal(jobId, useMutex = true) {
            media3Engine.editVideo(
                context = getApplication(),
                input = input,
                config = config,
                onProgress = { p -> updateJobProgress(jobId, p) },
                isCancelled = { cancellationTokens[jobId] == true }
            )
        }
    }

    // --- Audio Extraction ---
    fun startAudioExtraction(asMp3: Boolean, bitrateBps: Int) {
        val input = _selectedMedia.value ?: return
        val jobId = UUID.randomUUID().toString()
        val job = ProcessingJob(
            id = jobId,
            title = "Extract Audio: ${input.name}",
            type = JobType.AUDIO_EXTRACTION,
            status = JobStatus.QUEUED,
            originalSizeBytes = input.sizeBytes
        )

        enqueueJob(job)
        executeJobInternal(jobId, useMutex = true) {
            media3Engine.extractAudio(
                context = getApplication(),
                input = input,
                asMp3 = asMp3,
                bitrateBps = bitrateBps,
                onProgress = { p -> updateJobProgress(jobId, p) },
                isCancelled = { cancellationTokens[jobId] == true }
            )
        }
    }

    // --- Social Splitter ---
    fun startSocialSplit(config: SplitConfig) {
        val input = _selectedMedia.value ?: return
        val jobId = UUID.randomUUID().toString()
        val job = ProcessingJob(
            id = jobId,
            title = "Split: ${input.name}",
            type = JobType.SPLITTING,
            status = JobStatus.QUEUED,
            originalSizeBytes = input.sizeBytes
        )

        enqueueJob(job)
        executeMultiJobInternal(jobId) {
            media3Engine.splitVideo(
                context = getApplication(),
                input = input,
                config = config,
                onPartProgress = { part, total, p ->
                    val overallProgress = ((part - 1) + p) / total.toFloat()
                    updateJobProgress(jobId, overallProgress.coerceIn(0f, 1f))
                },
                isCancelled = { cancellationTokens[jobId] == true }
            )
        }
    }

    // --- Thumbnail Studio ---
    fun generateThumbnail(config: ThumbnailConfig) {
        val input = _selectedMedia.value ?: return
        val jobId = UUID.randomUUID().toString()
        val job = ProcessingJob(
            id = jobId,
            title = "Thumbnail: ${input.name}",
            type = JobType.THUMBNAIL,
            status = JobStatus.QUEUED,
            originalSizeBytes = input.sizeBytes
        )

        enqueueJob(job)
        executeJobInternal(jobId, useMutex = false) {
            thumbnailEngine.generateThumbnail(
                context = getApplication(),
                input = input,
                config = config,
                isCancelled = { cancellationTokens[jobId] == true }
            )
        }
    }

    suspend fun getPreviewFrame(timestampMs: Long): android.graphics.Bitmap? {
        val input = _selectedMedia.value ?: return null
        return thumbnailEngine.extractPreviewFrame(getApplication(), input, timestampMs)
    }

    private fun enqueueJob(job: ProcessingJob) {
        _jobs.update { listOf(job) + it }
    }

    private fun updateJobStatus(jobId: String, status: JobStatus, errorReason: String? = null) {
        _jobs.update { list ->
            list.map {
                if (it.id == jobId) {
                    // Do not allow late callbacks to overwrite an already COMPLETED or CANCELLED job
                    if (it.status == JobStatus.COMPLETED && status != JobStatus.COMPLETED) {
                        it
                    } else if (it.status == JobStatus.CANCELLED && status != JobStatus.CANCELLED) {
                        it
                    } else {
                        it.copy(
                            status = status,
                            errorReason = errorReason ?: it.errorReason
                        )
                    }
                } else it
            }
        }
    }

    private fun updateJobProgress(jobId: String, progress: Float) {
        _jobs.update { list ->
            list.map {
                if (it.id == jobId && it.status == JobStatus.PROCESSING) {
                    it.copy(progress = progress)
                } else it
            }
        }
    }

    private fun executeJobInternal(
        jobId: String,
        useMutex: Boolean,
        action: suspend () -> Result<OutputMediaFile>
    ) {
        cancellationTokens[jobId] = false

        // Launch LAZY to register in activeJobCoroutines BEFORE execution starts, avoiding race conditions
        val coroutineJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (cancellationTokens[jobId] == true) {
                    updateJobStatus(jobId, JobStatus.CANCELLED)
                    return@launch
                }

                if (useMutex) {
                    heavyJobMutex.withLock {
                        if (cancellationTokens[jobId] == true) {
                            updateJobStatus(jobId, JobStatus.CANCELLED)
                            return@withLock
                        }
                        updateJobStatus(jobId, JobStatus.PROCESSING)
                        val result = action()
                        handleJobCompletion(jobId, result)
                    }
                } else {
                    updateJobStatus(jobId, JobStatus.PROCESSING)
                    val result = action()
                    handleJobCompletion(jobId, result)
                }
            } catch (e: CancellationException) {
                updateJobStatus(jobId, JobStatus.CANCELLED)
            } catch (t: Throwable) {
                updateJobStatus(jobId, JobStatus.FAILED, errorReason = t.localizedMessage ?: "Unexpected failure")
            } finally {
                cancellationTokens.remove(jobId)
                activeJobCoroutines.remove(jobId)
                refreshLibraryOutputs()
            }
        }

        activeJobCoroutines[jobId] = coroutineJob
        coroutineJob.start()
    }

    private fun executeMultiJobInternal(
        jobId: String,
        action: suspend () -> Result<List<OutputMediaFile>>
    ) {
        cancellationTokens[jobId] = false

        val coroutineJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (cancellationTokens[jobId] == true) {
                    updateJobStatus(jobId, JobStatus.CANCELLED)
                    return@launch
                }

                heavyJobMutex.withLock {
                    if (cancellationTokens[jobId] == true) {
                        updateJobStatus(jobId, JobStatus.CANCELLED)
                        return@withLock
                    }
                    updateJobStatus(jobId, JobStatus.PROCESSING)
                    val result = action()

                    if (result.isSuccess) {
                        val list = result.getOrThrow()
                        val totalOutputBytes = list.sumOf { it.sizeBytes }
                        _jobs.update { currentJobs ->
                            currentJobs.map {
                                if (it.id == jobId) {
                                    if (it.status == JobStatus.CANCELLED) return@map it
                                    it.copy(
                                        status = JobStatus.COMPLETED,
                                        progress = 1.0f,
                                        outputSizeBytes = totalOutputBytes,
                                        completedAtMs = System.currentTimeMillis()
                                    )
                                } else it
                            }
                        }
                    } else {
                        val ex = result.exceptionOrNull()
                        if (ex is CancellationException || cancellationTokens[jobId] == true) {
                            updateJobStatus(jobId, JobStatus.CANCELLED)
                        } else {
                            val error = ex?.localizedMessage ?: "Splitting failed"
                            updateJobStatus(jobId, JobStatus.FAILED, errorReason = error)
                        }
                    }
                }
            } catch (e: CancellationException) {
                updateJobStatus(jobId, JobStatus.CANCELLED)
            } catch (t: Throwable) {
                updateJobStatus(jobId, JobStatus.FAILED, errorReason = t.localizedMessage ?: "Unexpected failure")
            } finally {
                cancellationTokens.remove(jobId)
                activeJobCoroutines.remove(jobId)
                refreshLibraryOutputs()
            }
        }

        activeJobCoroutines[jobId] = coroutineJob
        coroutineJob.start()
    }

    private fun handleJobCompletion(jobId: String, result: Result<OutputMediaFile>) {
        if (result.isSuccess) {
            val output = result.getOrThrow()
            _jobs.update { list ->
                list.map { job ->
                    if (job.id == jobId) {
                        if (job.status == JobStatus.CANCELLED) return@map job
                        val targetMet = if (job.targetSizeBytes > 0L) {
                            output.sizeBytes <= (job.targetSizeBytes * 1.05)
                        } else null

                        job.copy(
                            status = JobStatus.COMPLETED,
                            progress = 1.0f,
                            outputSizeBytes = output.sizeBytes,
                            outputPath = output.path,
                            outputUri = output.uri,
                            targetAchieved = targetMet,
                            completedAtMs = System.currentTimeMillis()
                        )
                    } else job
                }
            }
        } else {
            val ex = result.exceptionOrNull()
            if (ex is CancellationException || cancellationTokens[jobId] == true) {
                updateJobStatus(jobId, JobStatus.CANCELLED)
            } else {
                val error = ex?.localizedMessage ?: "Operation failed"
                updateJobStatus(jobId, JobStatus.FAILED, errorReason = error)
            }
        }
    }
}
