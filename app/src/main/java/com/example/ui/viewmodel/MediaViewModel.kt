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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MediaViewModel(application: Application) : AndroidViewModel(application) {

    val storageManager = OutputStorageManager(application)
    private val mediaCodecEngine = MediaCodecEngine(storageManager)
    private val thumbnailEngine = ThumbnailEngine(storageManager)
    private val media3Engine = Media3Engine(storageManager, mediaCodecEngine, thumbnailEngine)

    private val _selectedMedia = MutableStateFlow<MediaItemInfo?>(null)
    val selectedMedia: StateFlow<MediaItemInfo?> = _selectedMedia.asStateFlow()

    private val _isLoadingMedia = MutableStateFlow(false)
    val isLoadingMedia: StateFlow<Boolean> = _isLoadingMedia.asStateFlow()

    private val _jobs = MutableStateFlow<List<ProcessingJob>>(emptyList())
    val jobs: StateFlow<List<ProcessingJob>> = _jobs.asStateFlow()

    private val _libraryOutputs = MutableStateFlow<List<OutputMediaFile>>(emptyList())
    val libraryOutputs: StateFlow<List<OutputMediaFile>> = _libraryOutputs.asStateFlow()

    private val _codecCapabilities = MutableStateFlow<List<CodecCapability>>(emptyList())
    val codecCapabilities: StateFlow<List<CodecCapability>> = _codecCapabilities.asStateFlow()

    private val _isPersianLanguage = MutableStateFlow(false)
    val isPersianLanguage: StateFlow<Boolean> = _isPersianLanguage.asStateFlow()

    private val _isDarkTheme = MutableStateFlow<Boolean?>(null) // null = system default
    val isDarkTheme: StateFlow<Boolean?> = _isDarkTheme.asStateFlow()

    private val cancellationTokens = ConcurrentHashMap<String, Boolean>()

    init {
        loadDeviceCapabilities()
        refreshLibraryOutputs()
    }

    fun setLanguagePersian(persian: Boolean) {
        _isPersianLanguage.value = persian
    }

    fun setThemePreference(dark: Boolean?) {
        _isDarkTheme.value = dark
    }

    fun selectMediaUri(uri: Uri) {
        viewModelScope.launch {
            _isLoadingMedia.value = true
            val result = MediaMetadataInspector.inspect(getApplication(), uri)
            _isLoadingMedia.value = false
            if (result.isSuccess) {
                _selectedMedia.value = result.getOrNull()
            }
        }
    }

    fun clearSelectedMedia() {
        _selectedMedia.value = null
    }

    fun loadDeviceCapabilities() {
        viewModelScope.launch(Dispatchers.Default) {
            val encoders = DeviceCodecInspector.getSupportedEncoders()
            _codecCapabilities.value = encoders
        }
    }

    fun refreshLibraryOutputs() {
        viewModelScope.launch {
            val outputs = storageManager.listExportedOutputs()
            _libraryOutputs.value = outputs
        }
    }

    fun cancelJob(jobId: String) {
        cancellationTokens[jobId] = true
        _jobs.update { list ->
            list.map { if (it.id == jobId && it.status == JobStatus.PROCESSING) it.copy(status = JobStatus.CANCELLED) else it }
        }
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

    fun openExportedOutput(file: OutputMediaFile) {
        storageManager.openFile(file)
    }

    fun shareExportedOutput(file: OutputMediaFile) {
        storageManager.shareFile(file)
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
            currentCodec = config.videoMimeType,
            currentResolution = "${config.resolutionWidth}x${config.resolutionHeight}"
        )

        enqueueJob(job)

        viewModelScope.launch {
            updateJobStatus(jobId, JobStatus.PROCESSING)
            cancellationTokens[jobId] = false

            val result = media3Engine.compressOrConvert(
                context = getApplication(),
                input = input,
                config = config,
                onProgress = { p -> updateJobProgress(jobId, p) },
                isCancelled = { cancellationTokens[jobId] == true }
            )

            handleJobCompletion(jobId, result)
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

        viewModelScope.launch {
            updateJobStatus(jobId, JobStatus.PROCESSING)
            cancellationTokens[jobId] = false

            val result = media3Engine.compressOrConvert(
                context = getApplication(),
                input = input,
                config = config,
                onProgress = { p -> updateJobProgress(jobId, p) },
                isCancelled = { cancellationTokens[jobId] == true }
            )

            handleJobCompletion(jobId, result)
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

        viewModelScope.launch {
            updateJobStatus(jobId, JobStatus.PROCESSING)
            cancellationTokens[jobId] = false

            val result = media3Engine.editVideo(
                context = getApplication(),
                input = input,
                config = config,
                onProgress = { p -> updateJobProgress(jobId, p) },
                isCancelled = { cancellationTokens[jobId] == true }
            )

            handleJobCompletion(jobId, result)
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

        viewModelScope.launch {
            updateJobStatus(jobId, JobStatus.PROCESSING)
            cancellationTokens[jobId] = false

            val result = media3Engine.extractAudio(
                context = getApplication(),
                input = input,
                asMp3 = asMp3,
                bitrateBps = bitrateBps,
                onProgress = { p -> updateJobProgress(jobId, p) },
                isCancelled = { cancellationTokens[jobId] == true }
            )

            handleJobCompletion(jobId, result)
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

        viewModelScope.launch {
            updateJobStatus(jobId, JobStatus.PROCESSING)
            cancellationTokens[jobId] = false

            val result = media3Engine.splitVideo(
                context = getApplication(),
                input = input,
                config = config,
                onPartProgress = { part, total, p ->
                    val overallProgress = ((part - 1) + p) / total.toFloat()
                    updateJobProgress(jobId, overallProgress.coerceIn(0f, 1f))
                },
                isCancelled = { cancellationTokens[jobId] == true }
            )

            if (result.isSuccess) {
                val list = result.getOrThrow()
                val totalOutputBytes = list.sumOf { it.sizeBytes }
                _jobs.update { currentJobs ->
                    currentJobs.map {
                        if (it.id == jobId) {
                            it.copy(
                                status = JobStatus.COMPLETED,
                                progress = 1.0f,
                                outputSizeBytes = totalOutputBytes,
                                completedAtMs = System.currentTimeMillis()
                            )
                        } else it
                    }
                }
                refreshLibraryOutputs()
            } else {
                val error = result.exceptionOrNull()?.localizedMessage ?: "Splitting failed"
                _jobs.update { currentJobs ->
                    currentJobs.map {
                        if (it.id == jobId) it.copy(status = JobStatus.FAILED, errorReason = error) else it
                    }
                }
            }
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

        viewModelScope.launch {
            updateJobStatus(jobId, JobStatus.PROCESSING)
            val result = thumbnailEngine.generateThumbnail(getApplication(), input, config)
            handleJobCompletion(jobId, result)
        }
    }

    suspend fun getPreviewFrame(timestampMs: Long): android.graphics.Bitmap? {
        val input = _selectedMedia.value ?: return null
        return thumbnailEngine.extractPreviewFrame(getApplication(), input, timestampMs)
    }

    private fun enqueueJob(job: ProcessingJob) {
        _jobs.update { listOf(job) + it }
    }

    private fun updateJobStatus(jobId: String, status: JobStatus) {
        _jobs.update { list ->
            list.map { if (it.id == jobId) it.copy(status = status) else it }
        }
    }

    private fun updateJobProgress(jobId: String, progress: Float) {
        _jobs.update { list ->
            list.map { if (it.id == jobId) it.copy(progress = progress) else it }
        }
    }

    private fun handleJobCompletion(jobId: String, result: Result<OutputMediaFile>) {
        if (result.isSuccess) {
            val output = result.getOrThrow()
            _jobs.update { list ->
                list.map {
                    if (it.id == jobId) {
                        it.copy(
                            status = JobStatus.COMPLETED,
                            progress = 1.0f,
                            outputSizeBytes = output.sizeBytes,
                            outputPath = output.path,
                            outputUri = output.uri,
                            completedAtMs = System.currentTimeMillis()
                        )
                    } else it
                }
            }
            refreshLibraryOutputs()
        } else {
            val error = result.exceptionOrNull()?.localizedMessage ?: "Operation failed"
            _jobs.update { list ->
                list.map {
                    if (it.id == jobId) it.copy(status = JobStatus.FAILED, errorReason = error) else it
                }
            }
        }
    }
}
