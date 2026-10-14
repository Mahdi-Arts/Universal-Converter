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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.example.ui.components.SelectedMediaCard
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.viewmodel.MediaViewModel

data class FormatOption(
    val id: String,
    val title: String,
    val subtitle: String,
    val mimeType: String,
    val codecMime: String,
    val isSupported: Boolean,
    val reasonIfNotSupported: String? = null
)

@Composable
fun ConvertScreen(
    viewModel: MediaViewModel,
    onNavigateToQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedMedia by viewModel.selectedMedia.collectAsState()
    val isH265Supported = remember { DeviceCodecInspector.isH265Supported() }
    val isVp9Supported = remember { DeviceCodecInspector.isVp9Supported() }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { viewModel.selectMediaUri(it) }
    }

    val formatOptions = remember(isH265Supported, isVp9Supported) {
        listOf(
            FormatOption(
                id = "mp4_h264",
                title = "MP4 (H.264 / AAC)",
                subtitle = "Maximum universal compatibility with all Android, iOS, Windows, Mac devices & web players.",
                mimeType = "video/mp4",
                codecMime = "video/avc",
                isSupported = true
            ),
            FormatOption(
                id = "mp4_hevc",
                title = "MP4 (H.265 / HEVC)",
                subtitle = "Modern high-efficiency codec with 50% smaller size for 4K / 1080p.",
                mimeType = "video/mp4",
                codecMime = "video/hevc",
                isSupported = isH265Supported,
                reasonIfNotSupported = if (!isH265Supported) "No hardware HEVC encoder detected on this device" else null
            ),
            FormatOption(
                id = "webm_vp9",
                title = "WebM (VP9 / Opus)",
                subtitle = "Optimized open container for web embedding and Chromium browsers.",
                mimeType = "video/webm",
                codecMime = "video/x-vnd.on2.vp9",
                isSupported = isVp9Supported,
                reasonIfNotSupported = if (!isVp9Supported) "VP9 encoder unavailable on this hardware" else null
            ),
            FormatOption(
                id = "mov",
                title = "MOV (QuickTime)",
                subtitle = "Standard Apple QuickTime container format.",
                mimeType = "video/quicktime",
                codecMime = "video/avc",
                isSupported = true
            )
        )
    }

    var selectedFormatId by remember { mutableStateOf("mp4_h264") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("convert_screen"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Text(
                text = stringResource(id = R.string.nav_convert),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Capability-aware native container & codec conversion with verified device support.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Media Selector
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

        // Target Formats List
        item {
            Text(
                text = "Target Output Format",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        items(formatOptions.size) { index ->
            val option = formatOptions[index]
            val isSelected = selectedFormatId == option.id

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = option.isSupported) {
                        selectedFormatId = option.id
                    }
                    .testTag("format_option_${option.id}"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = option.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (option.isSupported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )

                        if (option.isSupported) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = StatusSuccess.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "Supported",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusSuccess,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = StatusWarning.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "Unsupported",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusWarning,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = option.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (!option.isSupported && option.reasonIfNotSupported != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = StatusWarning,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = option.reasonIfNotSupported,
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusWarning
                            )
                        }
                    }
                }
            }
        }

        // Action Button
        item {
            val selectedOption = formatOptions.firstOrNull { it.id == selectedFormatId }
            Button(
                onClick = {
                    if (selectedMedia != null && selectedOption != null) {
                        viewModel.startFormatConversion(
                            outputMime = selectedOption.mimeType,
                            targetCodec = selectedOption.codecMime
                        )
                        onNavigateToQueue()
                    }
                },
                enabled = selectedMedia != null && selectedOption?.isSupported == true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("start_convert_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(imageVector = Icons.Default.SwapHoriz, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Convert to ${selectedOption?.title?.substringBefore(" ") ?: "Format"}",
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
