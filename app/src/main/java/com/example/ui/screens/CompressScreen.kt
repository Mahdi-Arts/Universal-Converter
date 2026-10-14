package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.codec.DeviceCodecInspector
import com.example.core.model.CompressionConfig
import com.example.core.model.CompressionPreset
import com.example.ui.components.SelectedMediaCard
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.viewmodel.MediaViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompressScreen(
    viewModel: MediaViewModel,
    onNavigateToQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedMedia by viewModel.selectedMedia.collectAsState()
    val isH265Supported = remember { DeviceCodecInspector.isH265Supported() }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { viewModel.selectMediaUri(it) }
    }

    var selectedPreset by remember { mutableStateOf(CompressionPreset.BALANCED) }
    var targetSizeMb by remember { mutableFloatStateOf(15f) }
    var selectedCodec by remember { mutableStateOf("video/avc") }
    var removeAudio by remember { mutableStateOf(false) }
    var customResolutionWidth by remember { mutableIntStateOf(1280) }
    var customResolutionHeight by remember { mutableIntStateOf(720) }
    var customBitrateMbps by remember { mutableFloatStateOf(2.0f) }
    var customFps by remember { mutableIntStateOf(30) }

    // Calculate live estimates
    val durationSec = (selectedMedia?.durationMs?.div(1000f) ?: 10f).coerceAtLeast(1f)
    val estimatedOutputBytes = when (selectedPreset) {
        CompressionPreset.TARGET_SIZE -> (targetSizeMb * 1024 * 1024).toLong()
        CompressionPreset.HIGH_QUALITY -> ((selectedMedia?.sizeBytes ?: 20_000_000L) * 0.70).toLong()
        CompressionPreset.BALANCED -> ((selectedMedia?.sizeBytes ?: 20_000_000L) * 0.45).toLong()
        CompressionPreset.WEB_OPTIMIZED -> (1_200_000 / 8 * durationSec).toLong()
        CompressionPreset.MESSENGER -> (650_000 / 8 * durationSec).toLong()
        CompressionPreset.SMALL_SIZE -> (400_000 / 8 * durationSec).toLong()
        CompressionPreset.ORIGINAL -> selectedMedia?.sizeBytes ?: 0L
        CompressionPreset.CUSTOM -> ((customBitrateMbps * 1_000_000 / 8) * durationSec).toLong()
    }

    val originalBytes = selectedMedia?.sizeBytes ?: 0L
    val estimatedSavings = if (originalBytes > 0) {
        val diff = originalBytes - estimatedOutputBytes
        ((diff.toDouble() / originalBytes) * 100).toInt().coerceIn(-100, 95)
    } else 0

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("compress_screen"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Text(
                text = stringResource(id = R.string.nav_compress),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Compress without visible quality loss using hardware-tuned profiles.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Selected Media Card or Picker
        item {
            if (selectedMedia != null) {
                SelectedMediaCard(
                    media = selectedMedia!!,
                    onClear = { viewModel.clearSelectedMedia() }
                )
            } else {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        }
                        .testTag("select_video_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(id = R.string.action_select_video),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Presets Chips
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Compression Profile",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CompressionPreset.values().forEach { preset ->
                        val isSelected = selectedPreset == preset
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedPreset = preset },
                            label = {
                                Text(
                                    text = when (preset) {
                                        CompressionPreset.BALANCED -> "Balanced (Recommended)"
                                        CompressionPreset.TARGET_SIZE -> "Target Size"
                                        CompressionPreset.HIGH_QUALITY -> "High Quality"
                                        CompressionPreset.WEB_OPTIMIZED -> "Web Optimized"
                                        CompressionPreset.MESSENGER -> "Messenger"
                                        CompressionPreset.SMALL_SIZE -> "Small Size"
                                        CompressionPreset.ORIGINAL -> "Original"
                                        CompressionPreset.CUSTOM -> "Custom"
                                    }
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }
        }

        // Target Size Mode Specific Controls
        if (selectedPreset == CompressionPreset.TARGET_SIZE) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(id = R.string.label_target_size),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${targetSizeMb.toInt()} MB",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Slider(
                            value = targetSizeMb,
                            onValueChange = { targetSizeMb = it },
                            valueRange = 5f..150f,
                            steps = 28,
                            modifier = Modifier.testTag("target_size_slider")
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(id = R.string.desc_target_size_info),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Custom Mode Specific Controls
        if (selectedPreset == CompressionPreset.CUSTOM) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Custom Parameters",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        // Bitrate Slider
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Video Bitrate", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    String.format("%.1f Mbps", customBitrateMbps),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = customBitrateMbps,
                                onValueChange = { customBitrateMbps = it },
                                valueRange = 0.5f..12.0f,
                                modifier = Modifier.testTag("custom_bitrate_slider")
                            )
                        }

                        // FPS selector
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Frame Rate", style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(24, 30, 60).forEach { fps ->
                                    FilterChip(
                                        selected = customFps == fps,
                                        onClick = { customFps = fps },
                                        label = { Text("${fps}fps") }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Codec & Audio Options
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Encoder & Audio",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    // Codec selection
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Video Codec", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = selectedCodec == "video/avc",
                                onClick = { selectedCodec = "video/avc" },
                                label = { Text("H.264 (Universal)") }
                            )
                            FilterChip(
                                selected = selectedCodec == "video/hevc",
                                onClick = { if (isH265Supported) selectedCodec = "video/hevc" },
                                enabled = isH265Supported,
                                label = {
                                    Text(if (isH265Supported) "H.265 (HEVC)" else "HEVC (Unsupported)")
                                }
                            )
                        }
                    }

                    // Remove audio switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(stringResource(id = R.string.label_remove_audio), style = MaterialTheme.typography.bodyMedium)
                            Text("Produce a muted video", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = removeAudio,
                            onCheckedChange = { removeAudio = it },
                            modifier = Modifier.testTag("remove_audio_switch")
                        )
                    }
                }
            }
        }

        // Comparison & Savings Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Current Size", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = selectedMedia?.formattedSize ?: "--",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(StatusSuccess.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (estimatedSavings > 0) "-$estimatedSavings%" else "--",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusSuccess,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Estimated Output", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(2.dp))
                        val estMb = estimatedOutputBytes / (1024.0 * 1024.0)
                        Text(
                            text = String.format("%.1f MB", estMb),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Start Button
        item {
            Button(
                onClick = {
                    if (selectedMedia != null) {
                        val config = CompressionConfig(
                            preset = selectedPreset,
                            targetSizeBytes = (targetSizeMb * 1024 * 1024).toLong(),
                            videoBitrateBps = (customBitrateMbps * 1_000_000).toInt(),
                            frameRate = customFps,
                            videoMimeType = selectedCodec,
                            removeAudio = removeAudio
                        )
                        viewModel.startCompression(config)
                        onNavigateToQueue()
                    }
                },
                enabled = selectedMedia != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("start_compress_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(imageVector = Icons.Default.Compress, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(id = R.string.action_start_process),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
