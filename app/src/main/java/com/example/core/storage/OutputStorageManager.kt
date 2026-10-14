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
import java.io.InputStream
import java.io.OutputStream

class OutputStorageManager(private val context: Context) {

    private val folderName = "Universal Converter"

    fun createTempFile(prefix: String, extension: String): File {
        val tempDir = File(context.cacheDir, "universal_temp").apply {
            if (!exists()) mkdirs()
        }
        return File.createTempFile("${prefix}_${System.currentTimeMillis()}_", ".$extension", tempDir)
    }

    suspend fun finalizeVideoOutput(
        tempFile: File,
        baseName: String,
        mimeType: String = "video/mp4"
    ): Result<OutputMediaFile> = withContext(Dispatchers.IO) {
        try {
            if (!tempFile.exists() || tempFile.length() <= 0) {
                return@withContext Result.failure(IllegalStateException("Encoded temporary file is missing or empty"))
            }

            val cleanBaseName = baseName.substringBeforeLast(".")
            val extension = if (mimeType == "video/webm") "webm" else "mp4"
            val targetDisplayName = "${cleanBaseName}_compressed.$extension"

            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, targetDisplayName)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
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

            val uri = resolver.insert(collection, contentValues)
                ?: return@withContext Result.failure(IllegalStateException("Failed to create MediaStore entry"))

            resolver.openOutputStream(uri)?.use { outStream ->
                FileInputStream(tempFile).use { inStream ->
                    inStream.copyTo(outStream)
                }
            } ?: return@withContext Result.failure(IllegalStateException("Failed to write output to MediaStore"))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            // Cleanup temp file
            tempFile.delete()

            Result.success(
                OutputMediaFile(
                    id = uri.toString(),
                    displayName = targetDisplayName,
                    uri = uri,
                    path = uri.toString(),
                    sizeBytes = tempFile.length(),
                    type = JobType.COMPRESSION
                )
            )
        } catch (e: Exception) {
            tempFile.delete()
            Result.failure(e)
        }
    }

    suspend fun finalizeAudioOutput(
        tempFile: File,
        baseName: String,
        mimeType: String = "audio/mp4"
    ): Result<OutputMediaFile> = withContext(Dispatchers.IO) {
        try {
            if (!tempFile.exists() || tempFile.length() <= 0) {
                return@withContext Result.failure(IllegalStateException("Extracted audio temp file is missing or empty"))
            }

            val cleanBaseName = baseName.substringBeforeLast(".")
            val extension = if (mimeType.contains("mpeg")) "mp3" else "m4a"
            val targetDisplayName = "${cleanBaseName}_audio.$extension"

            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, targetDisplayName)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
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

            val uri = resolver.insert(collection, contentValues)
                ?: return@withContext Result.failure(IllegalStateException("Failed to create MediaStore audio entry"))

            resolver.openOutputStream(uri)?.use { outStream ->
                FileInputStream(tempFile).use { inStream ->
                    inStream.copyTo(outStream)
                }
            } ?: return@withContext Result.failure(IllegalStateException("Failed to write audio output"))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            tempFile.delete()

            Result.success(
                OutputMediaFile(
                    id = uri.toString(),
                    displayName = targetDisplayName,
                    uri = uri,
                    path = uri.toString(),
                    sizeBytes = tempFile.length(),
                    type = JobType.AUDIO_EXTRACTION
                )
            )
        } catch (e: Exception) {
            tempFile.delete()
            Result.failure(e)
        }
    }

    suspend fun finalizeImageOutput(
        tempFile: File,
        baseName: String,
        mimeType: String = "image/png"
    ): Result<OutputMediaFile> = withContext(Dispatchers.IO) {
        try {
            val extension = when {
                mimeType.contains("webp") -> "webp"
                mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
                else -> "png"
            }
            val cleanBaseName = baseName.substringBeforeLast(".")
            val targetDisplayName = "${cleanBaseName}_thumb.$extension"

            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, targetDisplayName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
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

            val uri = resolver.insert(collection, contentValues)
                ?: return@withContext Result.failure(IllegalStateException("Failed to create MediaStore image entry"))

            resolver.openOutputStream(uri)?.use { outStream ->
                FileInputStream(tempFile).use { inStream ->
                    inStream.copyTo(outStream)
                }
            } ?: return@withContext Result.failure(IllegalStateException("Failed to write image output"))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            tempFile.delete()

            Result.success(
                OutputMediaFile(
                    id = uri.toString(),
                    displayName = targetDisplayName,
                    uri = uri,
                    path = uri.toString(),
                    sizeBytes = tempFile.length(),
                    type = JobType.THUMBNAIL
                )
            )
        } catch (e: Exception) {
            tempFile.delete()
            Result.failure(e)
        }
    }

    suspend fun listExportedOutputs(): List<OutputMediaFile> = withContext(Dispatchers.IO) {
        val list = mutableListOf<OutputMediaFile>()
        val resolver = context.contentResolver

        // Query Videos
        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DURATION
            )
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            } else {
                null
            }
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

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Video"
                    val size = cursor.getLong(sizeCol)
                    val date = cursor.getLong(dateCol) * 1000
                    val duration = cursor.getLong(durCol)
                    val contentUri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())

                    list.add(
                        OutputMediaFile(
                            id = id.toString(),
                            displayName = name,
                            uri = contentUri,
                            path = contentUri.toString(),
                            sizeBytes = size,
                            durationMs = duration,
                            dateAddedMs = date,
                            type = JobType.COMPRESSION
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Ignore query error
        }

        // Query Audio
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DURATION
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

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Audio"
                    val size = cursor.getLong(sizeCol)
                    val date = cursor.getLong(dateCol) * 1000
                    val duration = cursor.getLong(durCol)
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
                            type = JobType.AUDIO_EXTRACTION
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        list.sortedByDescending { it.dateAddedMs }
    }

    suspend fun deleteExportedFile(outputFile: OutputMediaFile): Boolean = withContext(Dispatchers.IO) {
        try {
            val rows = context.contentResolver.delete(outputFile.uri, null, null)
            rows > 0
        } catch (e: Exception) {
            false
        }
    }

    fun openFile(outputFile: OutputMediaFile) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                val mime = when (outputFile.type) {
                    JobType.AUDIO_EXTRACTION -> "audio/*"
                    JobType.THUMBNAIL -> "image/*"
                    else -> "video/*"
                }
                setDataAndType(outputFile.uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback or toast
        }
    }

    fun shareFile(outputFile: OutputMediaFile) {
        try {
            val mime = when (outputFile.type) {
                JobType.AUDIO_EXTRACTION -> "audio/*"
                JobType.THUMBNAIL -> "image/*"
                else -> "video/*"
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, outputFile.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Share via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            // Fallback
        }
    }
}
