package com.example.core.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.R
import com.example.core.model.JobType
import com.example.core.model.OutputMediaFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException

/**
 * Lifecycle stages for media publication.
 */
enum class PublicationStage {
    PREPARING,
    COPYING,
    VALIDATING,
    PUBLISHING,
    COMPLETED
}

/**
 * Storage sink delegate to allow dependency injection and deterministic testing of I/O failures.
 */
interface StorageSinkDelegate {
    fun insertMediaStoreRecord(collection: Uri, values: ContentValues): Uri?
    fun openOutputStream(uri: Uri): OutputStream?
    fun updateMediaStoreRecord(uri: Uri, values: ContentValues): Int
    fun deleteMediaStoreRecord(uri: Uri): Int
    fun queryMediaStore(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor?
}

class DefaultStorageSinkDelegate(private val context: Context) : StorageSinkDelegate {
    override fun insertMediaStoreRecord(collection: Uri, values: ContentValues): Uri? {
        return context.contentResolver.insert(collection, values)
    }

    override fun openOutputStream(uri: Uri): OutputStream? {
        return context.contentResolver.openOutputStream(uri)
    }

    override fun updateMediaStoreRecord(uri: Uri, values: ContentValues): Int {
        return context.contentResolver.update(uri, values, null, null)
    }

    override fun deleteMediaStoreRecord(uri: Uri): Int {
        return context.contentResolver.delete(uri, null, null)
    }

    override fun queryMediaStore(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        return context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
    }
}

/**
 * Validates generated media structure before publication.
 */
interface MediaOutputValidator {
    fun validate(file: File, jobType: JobType, mimeType: String): Result<Unit>
}

class DefaultMediaOutputValidator(private val context: Context) : MediaOutputValidator {
    override fun validate(file: File, jobType: JobType, mimeType: String): Result<Unit> {
        if (!file.exists() || file.length() <= 0L) {
            return Result.failure(IllegalStateException("File is missing or has zero size"))
        }

        return try {
            when {
                mimeType.startsWith("image/") || jobType == JobType.THUMBNAIL -> {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, options)
                    if (options.outWidth > 0 && options.outHeight > 0) {
                        Result.success(Unit)
                    } else {
                        Result.failure(IllegalStateException("Invalid image: unable to decode dimensions"))
                    }
                }
                mimeType.startsWith("audio/") || jobType == JobType.AUDIO_EXTRACTION -> {
                    validateAudio(file)
                }
                else -> {
                    validateVideo(file)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun validateVideo(file: File): Result<Unit> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLongOrNull() ?: 0L

            if (hasVideo == "yes" || duration > 0L) {
                Result.success(Unit)
            } else {
                // Secondary check using MediaExtractor in case retriever returns empty on certain containers
                validateWithExtractor(file, isVideo = true)
            }
        } catch (_: Exception) {
            validateWithExtractor(file, isVideo = true)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun validateAudio(file: File): Result<Unit> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLongOrNull() ?: 0L

            if (hasAudio == "yes" || duration > 0L) {
                Result.success(Unit)
            } else {
                validateWithExtractor(file, isVideo = false)
            }
        } catch (_: Exception) {
            validateWithExtractor(file, isVideo = false)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun validateWithExtractor(file: File, isVideo: Boolean): Result<Unit> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            if (extractor.trackCount <= 0) {
                return Result.failure(IllegalStateException("No tracks found in media file"))
            }
            val prefix = if (isVideo) "video/" else "audio/"
            var found = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith(prefix)) {
                    found = true
                    break
                }
            }
            if (found) Result.success(Unit) else Result.failure(IllegalStateException("No valid track found"))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }
}

class OutputStorageManager(
    private val context: Context,
    private val sinkDelegate: StorageSinkDelegate = DefaultStorageSinkDelegate(context),
    private val mediaValidator: MediaOutputValidator = DefaultMediaOutputValidator(context)
) {
    val folderName = "Universal Converter"

    fun createTempFile(prefix: String, extension: String): File {
        val tempDir = File(context.cacheDir, "universal_temp").apply {
            if (!exists()) mkdirs()
        }
        val cleanExt = extension.removePrefix(".")
        return File.createTempFile("${prefix}_${System.currentTimeMillis()}_", ".$cleanExt", tempDir)
    }

    suspend fun finalizeVideoOutput(
        tempFile: File,
        baseName: String,
        jobType: JobType = JobType.COMPRESSION,
        containerFormat: String = "mp4",
        isCancelled: () -> Boolean = { false }
    ): Result<OutputMediaFile> {
        val extension = if (containerFormat.equals("webm", ignoreCase = true)) "webm" else "mp4"
        val mime = if (extension == "webm") "video/webm" else "video/mp4"
        val directoryType = Environment.DIRECTORY_MOVIES
        return executePublicationPipeline(
            tempFile = tempFile,
            baseName = baseName,
            jobType = jobType,
            extension = extension,
            mimeType = mime,
            directoryType = directoryType,
            mediaTypeUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            qCollectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            },
            isCancelled = isCancelled
        )
    }

    suspend fun finalizeAudioOutput(
        tempFile: File,
        baseName: String,
        jobType: JobType = JobType.AUDIO_EXTRACTION,
        audioFormat: String = "m4a",
        isCancelled: () -> Boolean = { false }
    ): Result<OutputMediaFile> {
        val extension = if (audioFormat.equals("mp3", ignoreCase = true)) "mp3" else "m4a"
        val mime = if (extension == "mp3") "audio/mpeg" else "audio/mp4"
        val directoryType = Environment.DIRECTORY_MUSIC
        return executePublicationPipeline(
            tempFile = tempFile,
            baseName = baseName,
            jobType = jobType,
            extension = extension,
            mimeType = mime,
            directoryType = directoryType,
            mediaTypeUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            qCollectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            },
            isCancelled = isCancelled
        )
    }

    suspend fun finalizeImageOutput(
        tempFile: File,
        baseName: String,
        imageFormat: String = "png",
        isCancelled: () -> Boolean = { false }
    ): Result<OutputMediaFile> {
        val extension = when (imageFormat.lowercase()) {
            "webp" -> "webp"
            "jpeg", "jpg" -> "jpg"
            else -> "png"
        }
        val mime = when (extension) {
            "webp" -> "image/webp"
            "jpg" -> "image/jpeg"
            else -> "image/png"
        }
        val directoryType = Environment.DIRECTORY_PICTURES
        return executePublicationPipeline(
            tempFile = tempFile,
            baseName = baseName,
            jobType = JobType.THUMBNAIL,
            extension = extension,
            mimeType = mime,
            directoryType = directoryType,
            mediaTypeUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            qCollectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            },
            isCancelled = isCancelled
        )
    }

    /**
     * Unified, robust publication pipeline across Video, Audio, and Image:
     * PREPARING -> COPYING -> VALIDATING -> PUBLISHING -> COMPLETED
     */
    private suspend fun executePublicationPipeline(
        tempFile: File,
        baseName: String,
        jobType: JobType,
        extension: String,
        mimeType: String,
        directoryType: String,
        mediaTypeUri: Uri,
        qCollectionUri: Uri,
        isCancelled: () -> Boolean
    ): Result<OutputMediaFile> = withContext(Dispatchers.IO) {
        var stage = PublicationStage.PREPARING
        var insertedMediaStoreUri: Uri? = null
        var stagingAppSpecificFile: File? = null
        var isPublishedSuccessfully = false

        try {
            // Stage 1: PREPARING
            currentCoroutineContext().ensureActive()
            if (isCancelled()) {
                throw CancellationException("Operation cancelled before publication")
            }

            if (!tempFile.exists() || tempFile.length() <= 0L) {
                throw IllegalStateException("Temporary media file is missing or zero-length")
            }

            val sourceSizeBytes = tempFile.length()
            val targetDisplayName = generateTargetDisplayName(baseName, jobType, extension)

            val useMediaStore = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

            if (useMediaStore) {
                // MediaStore (Android 10+)
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, targetDisplayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.SIZE, sourceSizeBytes)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$directoryType/$folderName")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val inserted = sinkDelegate.insertMediaStoreRecord(qCollectionUri, contentValues)
                    ?: throw IllegalStateException("MediaStore insert returned null for $targetDisplayName")
                insertedMediaStoreUri = inserted

                // Stage 2: COPYING
                stage = PublicationStage.COPYING
                val outStream = sinkDelegate.openOutputStream(inserted)
                    ?: throw IllegalStateException("Failed to open MediaStore output stream for $targetDisplayName")

                copyBoundedWithCancellation(tempFile, outStream, isCancelled)

                // Stage 3: VALIDATING
                stage = PublicationStage.VALIDATING
                currentCoroutineContext().ensureActive()
                if (isCancelled()) {
                    throw CancellationException("Cancelled during validation stage")
                }

                val valResult = mediaValidator.validate(tempFile, jobType, mimeType)
                if (valResult.isFailure) {
                    throw IllegalStateException("Media validation failed: ${valResult.exceptionOrNull()?.message}")
                }

                // Stage 4: PUBLISHING
                stage = PublicationStage.PUBLISHING
                currentCoroutineContext().ensureActive()
                if (isCancelled()) {
                    throw CancellationException("Cancelled before final publish")
                }

                val finishValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                val updatedRows = sinkDelegate.updateMediaStoreRecord(inserted, finishValues)
                if (updatedRows <= 0) {
                    throw IllegalStateException("Failed to publish media: MediaStore update returned 0 rows")
                }

                isPublishedSuccessfully = true
                stage = PublicationStage.COMPLETED

                Result.success(
                    OutputMediaFile(
                        id = inserted.toString(),
                        displayName = targetDisplayName,
                        uri = inserted,
                        path = inserted.toString(),
                        sizeBytes = sourceSizeBytes,
                        type = jobType,
                        mimeType = mimeType
                    )
                )
            } else {
                // App-Specific Storage with FileProvider (Android 7-9 or fallback)
                val targetDir = getAppSpecificDirectory(directoryType)
                val finalFile = generateUniqueAppSpecificFile(targetDir, targetDisplayName)
                val stagingFile = File(targetDir, "${finalFile.name}.staging_${System.currentTimeMillis()}")
                stagingAppSpecificFile = stagingFile

                // Stage 2: COPYING
                stage = PublicationStage.COPYING
                val outStream = FileOutputStream(stagingFile)
                copyBoundedWithCancellation(tempFile, outStream, isCancelled)

                // Stage 3: VALIDATING
                stage = PublicationStage.VALIDATING
                currentCoroutineContext().ensureActive()
                if (isCancelled()) {
                    throw CancellationException("Cancelled during validation stage")
                }

                val valResult = mediaValidator.validate(stagingFile, jobType, mimeType)
                if (valResult.isFailure) {
                    throw IllegalStateException("Media validation failed: ${valResult.exceptionOrNull()?.message}")
                }

                // Stage 4: PUBLISHING
                stage = PublicationStage.PUBLISHING
                currentCoroutineContext().ensureActive()
                if (isCancelled()) {
                    throw CancellationException("Cancelled before atomic rename")
                }

                if (!stagingFile.renameTo(finalFile)) {
                    throw IllegalStateException("Failed to finalize output file: atomic rename failed")
                }

                val contentUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    finalFile
                )

                isPublishedSuccessfully = true
                stage = PublicationStage.COMPLETED

                Result.success(
                    OutputMediaFile(
                        id = contentUri.toString(),
                        displayName = finalFile.name,
                        uri = contentUri,
                        path = finalFile.absolutePath,
                        sizeBytes = sourceSizeBytes,
                        type = jobType,
                        mimeType = mimeType
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            withContext(NonCancellable) {
                // If publication was not completed successfully, rollback and cleanup partial destinations
                if (!isPublishedSuccessfully) {
                    insertedMediaStoreUri?.let { uri ->
                        try {
                            sinkDelegate.deleteMediaStoreRecord(uri)
                        } catch (_: Exception) {}
                    }
                    stagingAppSpecificFile?.let { staging ->
                        try {
                            if (staging.exists()) staging.delete()
                        } catch (_: Exception) {}
                    }
                }
                // Temp file is always safely removed once reader/writer is finished
                try {
                    if (tempFile.exists()) tempFile.delete()
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun copyBoundedWithCancellation(
        sourceFile: File,
        outputStream: OutputStream,
        isCancelled: () -> Boolean
    ) {
        val buffer = ByteArray(64 * 1024)
        var totalBytesWritten = 0L
        val expectedLength = sourceFile.length()

        FileInputStream(sourceFile).use { inStream ->
            outputStream.use { outStream ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (isCancelled()) {
                        throw CancellationException("Cancelled during output transfer")
                    }
                    val read = inStream.read(buffer)
                    if (read == -1) break
                    outStream.write(buffer, 0, read)
                    totalBytesWritten += read
                }
                outStream.flush()
            }
        }

        if (totalBytesWritten != expectedLength) {
            throw IllegalStateException("Destination size mismatch: wrote $totalBytesWritten bytes, expected $expectedLength bytes")
        }
    }

    internal fun getAppSpecificDirectory(directoryType: String): File {
        val external = context.getExternalFilesDir(directoryType)
        val base = if (external != null && (external.canWrite() || external.mkdirs())) {
            external
        } else {
            File(context.filesDir, directoryType)
        }
        val appFolder = File(base, folderName)
        if (!appFolder.exists()) appFolder.mkdirs()
        return appFolder
    }

    internal fun generateUniqueAppSpecificFile(targetDir: File, baseDisplayName: String): File {
        val nameWithoutExt = baseDisplayName.substringBeforeLast(".")
        val ext = baseDisplayName.substringAfterLast(".", "")
        val dotExt = if (ext.isNotEmpty()) ".$ext" else ""

        var candidate = File(targetDir, baseDisplayName)
        var counter = 1
        while (candidate.exists()) {
            candidate = File(targetDir, "${nameWithoutExt}_$counter$dotExt")
            counter++
        }
        return candidate
    }

    internal fun generateTargetDisplayName(baseName: String, jobType: JobType, extension: String): String {
        val clean = baseName.substringBeforeLast(".")
        return when (jobType) {
            JobType.COMPRESSION -> "${clean}_compressed.$extension"
            JobType.CONVERSION -> "${clean}_converted.$extension"
            JobType.EDITING -> "${clean}_edited.$extension"
            JobType.SPLITTING -> if (clean.contains("_part_")) "$clean.$extension" else "${clean}_split.$extension"
            JobType.AUDIO_EXTRACTION -> "${clean}_audio.$extension"
            JobType.THUMBNAIL -> "${clean}_thumb.$extension"
        }
    }

    suspend fun listExportedOutputs(): List<OutputMediaFile> = withContext(Dispatchers.IO) {
        val list = mutableListOf<OutputMediaFile>()
        val seenIds = mutableSetOf<String>()

        // 1. MediaStore query (Android 10+ with strict folder filter, or legacy with strict folder matching)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryMediaStoreCategory(
                uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                isVideo = true,
                isAudio = false,
                isImage = false,
                list = list,
                seenIds = seenIds
            )
            queryMediaStoreCategory(
                uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                isVideo = false,
                isAudio = true,
                isImage = false,
                list = list,
                seenIds = seenIds
            )
            queryMediaStoreCategory(
                uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                isVideo = false,
                isAudio = false,
                isImage = true,
                list = list,
                seenIds = seenIds
            )
        }

        // 2. Query App-Specific Storage (covers Android 7-9, fallback, and app-specific files)
        val directories = listOf(
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_PICTURES
        )
        for (dirType in directories) {
            scanAppSpecificFolder(dirType, list, seenIds)
        }

        list.sortedByDescending { it.dateAddedMs }
    }

    private fun queryMediaStoreCategory(
        uri: Uri,
        isVideo: Boolean,
        isAudio: Boolean,
        isImage: Boolean,
        list: MutableList<OutputMediaFile>,
        seenIds: MutableSet<String>
    ) {
        try {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.MIME_TYPE
            )
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%$folderName%")

            sinkDelegate.queryMediaStore(
                uri,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Media"
                    val size = cursor.getLong(sizeCol)
                    val date = cursor.getLong(dateCol) * 1000
                    val mime = cursor.getString(mimeCol) ?: if (isVideo) "video/mp4" else if (isAudio) "audio/mp4" else "image/png"
                    val contentUri = Uri.withAppendedPath(uri, id.toString())
                    val idKey = contentUri.toString()

                    if (seenIds.add(idKey)) {
                        val jobType = inferJobType(name, isVideo, isAudio, isImage)
                        list.add(
                            OutputMediaFile(
                                id = idKey,
                                displayName = name,
                                uri = contentUri,
                                path = idKey,
                                sizeBytes = size,
                                durationMs = 0L,
                                dateAddedMs = date,
                                type = jobType,
                                mimeType = mime
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun scanAppSpecificFolder(
        directoryType: String,
        list: MutableList<OutputMediaFile>,
        seenIds: MutableSet<String>
    ) {
        try {
            val targetDir = getAppSpecificDirectory(directoryType)
            val files = targetDir.listFiles() ?: return

            for (file in files) {
                if (!file.isFile || file.name.contains(".staging_") || file.length() <= 0L) continue

                val contentUri = try {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                } catch (_: Exception) {
                    continue
                }

                val idKey = contentUri.toString()
                if (seenIds.add(idKey)) {
                    val isVideo = directoryType == Environment.DIRECTORY_MOVIES
                    val isAudio = directoryType == Environment.DIRECTORY_MUSIC
                    val isImage = directoryType == Environment.DIRECTORY_PICTURES
                    val jobType = inferJobType(file.name, isVideo, isAudio, isImage)
                    val mime = when {
                        isVideo -> if (file.name.endsWith(".webm", ignoreCase = true)) "video/webm" else "video/mp4"
                        isAudio -> if (file.name.endsWith(".mp3", ignoreCase = true)) "audio/mpeg" else "audio/mp4"
                        else -> if (file.name.endsWith(".webp", ignoreCase = true)) "image/webp" else if (file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".jpeg", ignoreCase = true)) "image/jpeg" else "image/png"
                    }

                    list.add(
                        OutputMediaFile(
                            id = idKey,
                            displayName = file.name,
                            uri = contentUri,
                            path = file.absolutePath,
                            sizeBytes = file.length(),
                            durationMs = 0L,
                            dateAddedMs = file.lastModified(),
                            type = jobType,
                            mimeType = mime
                        )
                    )
                }
            }
        } catch (_: Exception) {}
    }

    private fun inferJobType(name: String, isVideo: Boolean, isAudio: Boolean, isImage: Boolean): JobType {
        return when {
            isImage -> JobType.THUMBNAIL
            isAudio -> JobType.AUDIO_EXTRACTION
            name.contains("_part_") -> JobType.SPLITTING
            name.contains("_edited") -> JobType.EDITING
            name.contains("_converted") -> JobType.CONVERSION
            else -> JobType.COMPRESSION
        }
    }

    suspend fun deleteExportedFile(outputFile: OutputMediaFile): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = outputFile.uri
            // 1. If content URI belongs to our FileProvider
            if (uri.authority == "${context.packageName}.fileprovider") {
                val f = File(outputFile.path)
                if (f.exists() && isAppOwnedFile(f)) {
                    return@withContext f.delete()
                }
            }

            // 2. Try deleting via ContentResolver
            val rows = sinkDelegate.deleteMediaStoreRecord(uri)
            if (rows > 0) return@withContext true

            // 3. Fallback to physical file deletion if file is within app-owned folders
            val f = File(outputFile.path)
            if (f.exists() && isAppOwnedFile(f)) {
                return@withContext f.delete()
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun isAppOwnedFile(file: File): Boolean {
        val path = file.canonicalPath
        val externalFiles = context.getExternalFilesDir(null)?.canonicalPath
        val internalFiles = context.filesDir.canonicalPath
        val cacheFiles = context.cacheDir.canonicalPath

        return (externalFiles != null && path.startsWith(externalFiles)) ||
                path.startsWith(internalFiles) ||
                path.startsWith(cacheFiles)
    }

    fun openFile(outputFile: OutputMediaFile): Result<Unit> {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(outputFile.uri, outputFile.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun shareFile(outputFile: OutputMediaFile): Result<Unit> {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = outputFile.mimeType
                putExtra(Intent.EXTRA_STREAM, outputFile.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, context.getString(R.string.action_share)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
