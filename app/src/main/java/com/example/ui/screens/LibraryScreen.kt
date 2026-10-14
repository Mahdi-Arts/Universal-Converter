package com.example.ui.screens

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
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.model.JobType
import com.example.core.model.OutputMediaFile
import com.example.ui.theme.StatusError
import com.example.ui.viewmodel.MediaViewModel

@Composable
fun LibraryScreen(
    viewModel: MediaViewModel,
    modifier: Modifier = Modifier
) {
    val outputs by viewModel.libraryOutputs.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) } // 0: All, 1: Videos, 2: Audio, 3: Images
    var fileToDelete by remember { mutableStateOf<OutputMediaFile?>(null) }

    val filteredOutputs = remember(outputs, selectedTab) {
        when (selectedTab) {
            1 -> outputs.filter { it.displayName.endsWith(".mp4") || it.displayName.endsWith(".mov") || it.displayName.endsWith(".webm") }
            2 -> outputs.filter { it.displayName.endsWith(".m4a") || it.displayName.endsWith(".mp3") }
            3 -> outputs.filter { it.displayName.endsWith(".png") || it.displayName.endsWith(".jpg") || it.displayName.endsWith(".webp") }
            else -> outputs
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("library_screen"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(id = R.string.library_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "MediaStore folder: Movies/Universal Converter",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = { viewModel.refreshLibraryOutputs() },
                    modifier = Modifier.testTag("refresh_library_button")
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
        }

        // Tabs
        item {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
            ) {
                listOf(
                    stringResource(id = R.string.library_tab_all),
                    stringResource(id = R.string.library_tab_videos),
                    stringResource(id = R.string.library_tab_audio),
                    stringResource(id = R.string.library_tab_images)
                ).forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, style = MaterialTheme.typography.labelLarge) }
                    )
                }
            }
        }

        if (filteredOutputs.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(id = R.string.library_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(filteredOutputs.size) { index ->
                val file = filteredOutputs[index]
                OutputItemCard(
                    file = file,
                    onOpen = { viewModel.openExportedOutput(file) },
                    onShare = { viewModel.shareExportedOutput(file) },
                    onDelete = { fileToDelete = file }
                )
            }
        }
    }

    // Delete Confirmation Dialog
    if (fileToDelete != null) {
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text(stringResource(id = R.string.delete_confirm_title)) },
            text = { Text(stringResource(id = R.string.delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        fileToDelete?.let { viewModel.deleteExportedOutput(it) }
                        fileToDelete = null
                    }
                ) {
                    Text(stringResource(id = R.string.action_delete), color = StatusError)
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) {
                    Text(stringResource(id = R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
fun OutputItemCard(
    file: OutputMediaFile,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("output_file_${file.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                val icon = when {
                    file.displayName.endsWith(".m4a") || file.displayName.endsWith(".mp3") -> Icons.Default.Audiotrack
                    file.displayName.endsWith(".png") || file.displayName.endsWith(".jpg") || file.displayName.endsWith(".webp") -> Icons.Default.Image
                    else -> Icons.Default.Movie
                }
                Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                val mb = file.sizeBytes / (1024.0 * 1024.0)
                Text(
                    text = if (mb >= 1.0) String.format("%.1f MB", mb) else "${file.sizeBytes / 1024} KB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onOpen, modifier = Modifier.testTag("open_output_button")) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Open", tint = MaterialTheme.colorScheme.primary)
            }

            IconButton(onClick = onShare, modifier = Modifier.testTag("share_output_button")) {
                Icon(imageVector = Icons.Default.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            IconButton(onClick = onDelete, modifier = Modifier.testTag("delete_output_button")) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = StatusError)
            }
        }
    }
}
