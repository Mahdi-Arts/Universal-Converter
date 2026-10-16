package com.example.ui.screens

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.model.JobStatus
import com.example.core.model.ProcessingJob
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.viewmodel.MediaViewModel

@Composable
fun QueueScreen(
    viewModel: MediaViewModel,
    modifier: Modifier = Modifier
) {
    val jobs by viewModel.jobs.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("queue_screen_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(id = R.string.nav_queue),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${jobs.size} operations total",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (jobs.any { it.status == JobStatus.COMPLETED || it.status == JobStatus.FAILED }) {
                    OutlinedButton(
                        onClick = { viewModel.clearCompletedJobs() },
                        modifier = Modifier.testTag("clear_completed_button")
                    ) {
                        Icon(imageVector = Icons.Default.ClearAll, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(id = R.string.action_clear_completed))
                    }
                }
            }
        }

        if (jobs.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(id = R.string.queue_empty),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(jobs.size) { index ->
                val job = jobs[index]
                ProcessingJobCard(
                    job = job,
                    onCancel = { viewModel.cancelJob(job.id) },
                    onOpen = { viewModel.openJobOutput(job) },
                    onShare = { viewModel.shareJobOutput(job) }
                )
            }
        }
    }
}

@Composable
fun ProcessingJobCard(
    job: ProcessingJob,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("queue_job_${job.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = job.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = when (job.status) {
                            JobStatus.QUEUED -> stringResource(id = R.string.status_queued)
                            JobStatus.PROCESSING -> "${stringResource(id = R.string.status_processing)} ${(job.progress * 100).toInt()}%"
                            JobStatus.COMPLETED -> stringResource(id = R.string.status_completed)
                            JobStatus.FAILED -> stringResource(id = R.string.status_failed)
                            JobStatus.CANCELLED -> stringResource(id = R.string.status_cancelled)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (job.status) {
                            JobStatus.COMPLETED -> StatusSuccess
                            JobStatus.FAILED -> StatusError
                            JobStatus.CANCELLED -> StatusWarning
                            JobStatus.PROCESSING -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (job.status == JobStatus.PROCESSING || job.status == JobStatus.QUEUED) {
                    IconButton(onClick = onCancel, modifier = Modifier.testTag("cancel_job_button")) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel", tint = StatusError)
                    }
                } else if (job.status == JobStatus.COMPLETED) {
                    Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = StatusSuccess)
                } else if (job.status == JobStatus.FAILED) {
                    Icon(imageVector = Icons.Default.Error, contentDescription = null, tint = StatusError)
                }
            }

            // Real Progress Bar
            if (job.status == JobStatus.PROCESSING) {
                LinearProgressIndicator(
                    progress = { job.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Technical Details Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (job.originalSizeBytes > 0) {
                    val origMb = job.originalSizeBytes / (1024.0 * 1024.0)
                    Text("Original: ${String.format("%.1f MB", origMb)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (job.outputSizeBytes > 0) {
                    val outMb = job.outputSizeBytes / (1024.0 * 1024.0)
                    Text("Output: ${String.format("%.1f MB", outMb)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    if (job.compressionSavingsPercent != 0) {
                        Text("(${job.compressionSavingsPercent}% saved)", style = MaterialTheme.typography.labelSmall, color = StatusSuccess)
                    }
                }
            }

            // Error message display if failed
            if (job.status == JobStatus.FAILED && !job.errorReason.isNullOrBlank()) {
                Text(
                    text = "Failure Reason: ${job.errorReason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusError
                )
            }

            // Open & Share actions for completed jobs with real output
            if (job.status == JobStatus.COMPLETED && job.outputUri != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = onOpen,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(id = R.string.action_open), style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onShare,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(id = R.string.action_share), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
