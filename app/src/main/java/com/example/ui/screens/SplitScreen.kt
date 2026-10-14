package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.model.SplitConfig
import com.example.core.model.SplitEngine
import com.example.core.model.SplitMode
import com.example.ui.components.SelectedMediaCard
import com.example.ui.theme.StatusSuccess
import com.example.ui.viewmodel.MediaViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SplitScreen(
    viewModel: MediaViewModel,
    onNavigateToQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedMedia by viewModel.selectedMedia.collectAsState()
    val totalDurationSec = ((selectedMedia?.durationMs ?: 60_000L) / 1000f).toInt().coerceAtLeast(1)

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { viewModel.selectMediaUri(it) }
    }

    var selectedMode by remember { mutableStateOf(SplitMode.FIXED_DURATION) }
    var fixedSeconds by remember { mutableIntStateOf(30) }
    var equalPartsCount by remember { mutableIntStateOf(3) }
    var maxSizeMb by remember { mutableIntStateOf(15) }
    var selectedEngine by remember { mutableStateOf(SplitEngine.FAST_STREAM_COPY) }

    // Calculate projected parts
    val projectedParts = when (selectedMode) {
        SplitMode.FIXED_DURATION -> (totalDurationSec / fixedSeconds.coerceAtLeast(1)) + (if (totalDurationSec % fixedSeconds != 0) 1 else 0)
        SplitMode.EQUAL_PARTS -> equalPartsCount
        SplitMode.MAX_SIZE -> {
            val totalMb = ((selectedMedia?.sizeBytes ?: 30_000_000L) / (1024.0 * 1024.0)).coerceAtLeast(1.0)
            (totalMb / maxSizeMb).toInt() + 1
        }
        SplitMode.CUSTOM_TIMESTAMPS -> 2
    }.coerceAtLeast(1)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("split_screen"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Text(
                text = stringResource(id = R.string.split_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Seamlessly divide long videos for Instagram Stories, WhatsApp Status, and Shorts.",
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

        // Split Mode Selector
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Split Mode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedMode == SplitMode.FIXED_DURATION,
                            onClick = { selectedMode = SplitMode.FIXED_DURATION },
                            label = { Text("Fixed Duration") }
                        )
                        FilterChip(
                            selected = selectedMode == SplitMode.EQUAL_PARTS,
                            onClick = { selectedMode = SplitMode.EQUAL_PARTS },
                            label = { Text("Equal Parts") }
                        )
                        FilterChip(
                            selected = selectedMode == SplitMode.MAX_SIZE,
                            onClick = { selectedMode = SplitMode.MAX_SIZE },
                            label = { Text("Max File Size") }
                        )
                    }

                    // Mode options
                    if (selectedMode == SplitMode.FIXED_DURATION) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(
                                15 to "15s (Stories)",
                                30 to "30s (WhatsApp)",
                                60 to "60s (Reels)"
                            ).forEach { (sec, label) ->
                                FilterChip(
                                    selected = fixedSeconds == sec,
                                    onClick = { fixedSeconds = sec },
                                    label = { Text(label) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    } else if (selectedMode == SplitMode.EQUAL_PARTS) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(2, 3, 4, 5, 6).forEach { count ->
                                FilterChip(
                                    selected = equalPartsCount == count,
                                    onClick = { equalPartsCount = count },
                                    label = { Text("$count Parts") },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    } else if (selectedMode == SplitMode.MAX_SIZE) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(10, 16, 25, 50).forEach { mb ->
                                FilterChip(
                                    selected = maxSizeMb == mb,
                                    onClick = { maxSizeMb = mb },
                                    label = { Text("${mb}MB") },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Engine Selector: Fast vs Precise
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Split Engine",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedEngine == SplitEngine.FAST_STREAM_COPY,
                            onClick = { selectedEngine = SplitEngine.FAST_STREAM_COPY },
                            label = { Text("⚡ Fast (Stream Copy)") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedEngine == SplitEngine.PRECISE_RE_ENCODE,
                            onClick = { selectedEngine = SplitEngine.PRECISE_RE_ENCODE },
                            label = { Text("🎯 Precise (Re-encode)") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = if (selectedEngine == SplitEngine.FAST_STREAM_COPY) {
                            stringResource(id = R.string.split_engine_fast_desc)
                        } else {
                            stringResource(id = R.string.split_engine_precise_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Output Preview & Fault Tolerance Notice
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Security, contentDescription = null, tint = StatusSuccess, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Preserves All Successful Segments",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = StatusSuccess
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Generates approx $projectedParts segments: ${selectedMedia?.name?.substringBeforeLast(".") ?: "video"}_part_001.mp4, part_002.mp4…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Start Action
        item {
            Button(
                onClick = {
                    if (selectedMedia != null) {
                        val config = SplitConfig(
                            mode = selectedMode,
                            durationSeconds = fixedSeconds,
                            equalPartsCount = equalPartsCount,
                            maxSizeMb = maxSizeMb,
                            engine = selectedEngine
                        )
                        viewModel.startSocialSplit(config)
                        onNavigateToQueue()
                    }
                },
                enabled = selectedMedia != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("start_split_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.CallSplit, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Split Video into $projectedParts Parts",
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
