package com.example.core.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.core.model.JobType
import com.example.core.model.OutputMediaFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CancellationException

class OutputStorageManager(private val context: Context) {

    private val folderName = "Universal Converter"

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
    ): Result<OutputMediaFile> = withContext(Dispatchers.IO) {
        if (isCancelled()) {
            tempFile.delete()
            return@withContext Result.failure(CancellationException("Operation cancelled before finalization"))
        }

        if (!tempFile.exists() || tempFile.length() <= 0L) {
            tempFile.delete()
            return@withContext Result.failure(IllegalStateException("Encoded temporary file is missing or empty"))
        }

        val finalSizeBytes = tempFile.length()
        val extension = if (containerFormat.equals("webm", ignoreCase = true)) "webm" else "mp4"
        val containerMime = if (extension == "webm") "video/webm" else "video/mp4"

        val targetDisplayName = generateTargetDisplayName(baseName, jobType, extension)

        var insertedUri: Uri? = null
        val resolver = context.contentResolver

        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, targetDisplayName)
                put(MediaStore.Video.Media.MIME_TYPE, containerMime)
                put(MediaStore.Video.Media.SIZE, finalSizeBytes)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$folderName")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            insertedUri = resolver.insert(collection, contentValues)

            if (insertedUri != null) {
                // Write to MediaStore
                resolver.openOutputStream(insertedUri)?.use { outStream ->
                    FileInputStream(tempFile).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                } ?: throw IllegalStateException("Failed to open MediaStore output stream for $targetDisplayName")

                if (isCancelled()) {
                    resolver.delete(insertedUri, null, null)
                    tempFile.delete()
                    return@withContext Result.failure(CancellationException("Cancelled during output transfer"))
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val finishValues = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    resolver.update(insertedUri, finishValues, null, null)
                }

                tempFile.delete()

                Result.success(
                    OutputMediaFile(
                        id = insertedUri.toString(),
                        displayName = targetDisplayName,
                        uri = insertedUri,
                        path = insertedUri.toString(),
                        sizeBytes = finalSizeBytes,
                        type = jobType,
                        mimeType = containerMime
                    )
                )
            } else {
                // Fallback for Android 7-9 or if MediaStore insert returned null
                val fallbackFile = writeToAppExternalDir(tempFile, Environment.DIRECTORY_MOVIES, targetDisplayName)
                tempFile.delete()
                val fileUri = Uri.fromFile(fallbackFile)
                Result.success(
                    OutputMediaFile(
                        id = fileUri.toString(),
                        displayName = targetDisplayName,
                        uri = fileUri,
                        path = fallbackFile.absolutePath,
                        sizeBytes = finalSizeBytes,
                        type = jobType,
                        mimeType = containerMime
                    )
                )
            }
        } catch (e: Exception) {
            insertedUri?.let { uri ->
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            }
            tempFile.delete()
            Result.failure(e)
        }
    }

    suspend fun finalizeAudioOutput(
        tempFile: File,
        baseName: String,
        jobType: JobType = JobType.AUDIO_EXTRACTION,
        audioFormat: String = "m4a",
        isCancelled: () -> Boolean = { false }
    ): Result<OutputMediaFile> = withContext(Dispatchers.IO) {
        if (isCancelled()) {
            tempFile.delete()
            return@withContext Result.failure(CancellationException("Operation cancelled before finalization"))
        }

        if (!tempFile.exists() || tempFile.length() <= 0L) {
            tempFile.delete()
            return@withContext Result.failure(IllegalStateException("Extracted audio temp file is missing or empty"))
        }

        val finalSizeBytes = tempFile.length()
        val extension = if (audioFormat.equals("mp3", ignoreCase = true)) "mp3" else "m4a"
        val containerMime = if (extension == "mp3") "audio/mpeg" else "audio/mp4"

        val targetDisplayName = generateTargetDisplayName(baseName, jobType, extension)

        var insertedUri: Uri? = null
        val resolver = context.contentResolver

        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, targetDisplayName)
                put(MediaStore.Audio.Media.MIME_TYPE, containerMime)
                put(MediaStore.Audio.Media.SIZE, finalSizeBytes)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$folderName")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            insertedUri = resolver.insert(collection, contentValues)

            if (insertedUri != null) {
                resolver.openOutputStream(insertedUri)?.use { outStream ->
                    FileInputStream(tempFile).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                } ?: throw IllegalStateException("Failed to open MediaStore audio stream")

                if (isCancelled()) {
                    resolver.delete(insertedUri, null, null)
                    tempFile.delete()
                    return@withContext Result.failure(CancellationException("Cancelled during audio transfer"))
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val finishValues = ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                    }
                    resolver.update(insertedUri, finishValues, null, null)
                }

                tempFile.delete()

                Result.success(
                    OutputMediaFile(
                        id = insertedUri.toString(),
                        displayName = targetDisplayName,
                        uri = insertedUri,
                        path = insertedUri.toString(),
                        sizeBytes = finalSizeBytes,
                        type = jobType,
                        mimeType = containerMime
                    )
                )
            } else {
                val fallbackFile = writeToAppExternalDir(tempFile, Environment.DIRECTORY_MUSIC, targetDisplayName)
                tempFile.delete()
                val fileUri = Uri.fromFile(fallbackFile)
                Result.success(
                    OutputMediaFile(
                        id = fileUri.toString(),
                        displayName = targetDisplayName,
                        uri = fileUri,
                        path = fallbackFile.absolutePath,
                        sizeBytes = finalSizeBytes,
                        type = jobType,
                        mimeType = containerMime
                    )
                )
            }
        } catch (e: Exception) {
            insertedUri?.let { uri ->
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            }
            tempFile.delete()
            Result.failure(e)
        }
    }

    suspend fun finalizeImageOutput(
        tempFile: File,
        baseName: String,
        imageFormat: String = "png",
        isCancelled: () -> Boolean = { false }
    ): Result<OutputMediaFile> = withContext(Dispatchers.IO) {
        if (isCancelled()) {
            tempFile.delete()
            return@withContext Result.failure(CancellationException("Operation cancelled before finalization"))
        }

        if (!tempFile.exists() || tempFile.length() <= 0L) {
            tempFile.delete()
            return@withContext Result.failure(IllegalStateException("Thumbnail file is missing or empty"))
        }

        val finalSizeBytes = tempFile.length()
        val extension = when (imageFormat.lowercase()) {
            "webp" -> "webp"
            "jpeg", "jpg" -> "jpg"
            else -> "png"
        }
        val containerMime = when (extension) {
            "webp" -> "image/webp"
            "jpg" -> "image/jpeg"
            else -> "image/png"
        }

        val targetDisplayName = generateTargetDisplayName(baseName, JobType.THUMBNAIL, extension)

        var insertedUri: Uri? = null
        val resolver = context.contentResolver

        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, targetDisplayName)
                put(MediaStore.Images.Media.MIME_TYPE, containerMime)
                put(MediaStore.Images.Media.SIZE, finalSizeBytes)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$folderName")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            insertedUri = resolver.insert(collection, contentValues)

            if (insertedUri != null) {
                resolver.openOutputStream(insertedUri)?.use { outStream ->
                    FileInputStream(tempFile).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                } ?: throw IllegalStateException("Failed to open MediaStore image stream")

                if (isCancelled()) {
                    resolver.delete(insertedUri, null, null)
                    tempFile.delete()
                    return@withContext Result.failure(CancellationException("Cancelled during image transfer"))
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val finishValues = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    resolver.update(insertedUri, finishValues, null, null)
                }

                tempFile.delete()

                Result.success(
                    OutputMediaFile(
                        id = insertedUri.toString(),
                        displayName = targetDisplayName,
                        uri = insertedUri,
                        path = insertedUri.toString(),
                        sizeBytes = finalSizeBytes,
                        type = JobType.THUMBNAIL,
                        mimeType = containerMime
                    )
                )
            } else {
                val fallbackFile = writeToAppExternalDir(tempFile, Environment.DIRECTORY_PICTURES, targetDisplayName)
                tempFile.delete()
                val fileUri = Uri.fromFile(fallbackFile)
                Result.success(
                    OutputMediaFile(
                        id = fileUri.toString(),
                        displayName = targetDisplayName,
                        uri = fileUri,
                        path = fallbackFile.absolutePath,
                        sizeBytes = finalSizeBytes,
                        type = JobType.THUMBNAIL,
                        mimeType = containerMime
                    )
                )
            }
        } catch (e: Exception) {
            insertedUri?.let { uri ->
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            }
            tempFile.delete()
            Result.failure(e)
        }
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

    private fun writeToAppExternalDir(sourceTemp: File, directoryType: String, displayName: String): File {
        val targetDir = File(context.getExternalFilesDir(directoryType), folderName).apply {
            if (!exists()) mkdirs()
        }
        val destFile = File(targetDir, displayName)
        FileInputStream(sourceTemp).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
        return destFile
    }

    suspend fun listExportedOutputs(): List<OutputMediaFile> = withContext(Dispatchers.IO) {
        val list = mutableListOf<OutputMediaFile>()
        val resolver = context.contentResolver

        // 1. Query Videos
        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.MIME_TYPE
            )
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            } else null
            val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf("%$folderName%")
            } else null

            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Video"
                    val size = cursor.getLong(sizeCol)
                    val date = cursor.getLong(dateCol) * 1000
                    val duration = cursor.getLong(durCol)
                    val mime = cursor.getString(mimeCol) ?: "video/mp4"
                    val contentUri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())

                    val jobType = when {
                        name.contains("_part_") -> JobType.SPLITTING
                        name.contains("_edited") -> JobType.EDITING
                        name.contains("_converted") -> JobType.CONVERSION
                        else -> JobType.COMPRESSION
                    }

                    list.add(
                        OutputMediaFile(
                            id = id.toString(),
                            displayName = name,
                            uri = contentUri,
                            path = contentUri.toString(),
                            sizeBytes = size,
                            durationMs = duration,
                            dateAddedMs = date,
                            type = jobType,
                            mimeType = mime
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        // 2. Query Audio
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.MIME_TYPE
            )
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
            } else null
            val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf("%$folderName%")
            } else null

            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Audio"
                    val size = cursor.getLong(sizeCol)
                    val date = cursor.getLong(dateCol) * 1000
                    val duration = cursor.getLong(durCol)
                    val mime = cursor.getString(mimeCol) ?: "audio/mp4"
                    val contentUri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())

                    list.add(
                        OutputMediaFile(
                            id = id.toString(),
                            displayName = name,
                            uri = contentUri,
                            path = contentUri.toString(),
                            sizeBytes = size,
                            durationMs = duration,
                            dateAddedMs = date,
                            type = JobType.AUDIO_EXTRACTION,
                            mimeType = mime
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        // 3. Query Images (Thumbnails)
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.MIME_TYPE
            )
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            } else null
            val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf("%$folderName%")
            } else null

            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Thumbnail"
                    val size = cursor.getLong(sizeCol)
                    val date = cursor.getLong(dateCol) * 1000
                    val mime = cursor.getString(mimeCol) ?: "image/png"
                    val contentUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())

                    list.add(
                        OutputMediaFile(
                            id = id.toString(),
                            displayName = name,
                            uri = contentUri,
                            path = contentUri.toString(),
                            sizeBytes = size,
                            durationMs = 0L,
                            dateAddedMs = date,
                            type = JobType.THUMBNAIL,
                            mimeType = mime
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        list.sortedByDescending { it.dateAddedMs }
    }

    suspend fun deleteExportedFile(outputFile: OutputMediaFile): Boolean = withContext(Dispatchers.IO) {
        try {
            val rows = context.contentResolver.delete(outputFile.uri, null, null)
            if (rows > 0) return@withContext true
            // If file:// URI fallback
            if (outputFile.uri.scheme == "file") {
                val f = File(outputFile.uri.path ?: "")
                if (f.exists()) return@withContext f.delete()
            }
            false
        } catch (_: Exception) {
            false
        }
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
            val chooser = Intent.createChooser(intent, "Share media").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
