package com.example.core.engine

import android.content.Context
import com.example.core.model.CompressionConfig
import com.example.core.model.EditConfig
import com.example.core.model.MediaItemInfo
import com.example.core.model.OutputMediaFile
import com.example.core.model.SplitConfig
import com.example.core.model.ThumbnailConfig
import java.io.File

interface MediaEngine {

    suspend fun compressOrConvert(
        context: Context,
        input: MediaItemInfo,
        config: CompressionConfig,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Result<OutputMediaFile>

    suspend fun editVideo(
        context: Context,
        input: MediaItemInfo,
        config: EditConfig,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Result<OutputMediaFile>

    suspend fun extractAudio(
        context: Context,
        input: MediaItemInfo,
        asMp3: Boolean,
        bitrateBps: Int,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Result<OutputMediaFile>

    suspend fun splitVideo(
        context: Context,
        input: MediaItemInfo,
        config: SplitConfig,
        onPartProgress: (currentPart: Int, totalParts: Int, progress: Float) -> Unit,
        isCancelled: () -> Boolean
    ): Result<List<OutputMediaFile>>

    suspend fun generateThumbnail(
        context: Context,
        input: MediaItemInfo,
        config: ThumbnailConfig,
        isCancelled: () -> Boolean = { false }
    ): Result<OutputMediaFile>
}
