package io.github.verybigsad.pimobile.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.verybigsad.pimobile.update.UpdateCandidate
import io.github.verybigsad.pimobile.update.UpdateState
import java.util.Locale

/**
 * Actions the host app performs for the update sheet. Defaults are no-ops so previews and
 * tests can omit them. All download/install decisions stay explicit user actions.
 */
@Immutable
data class UpdateSheetActions(
    val onConfirmDownload: (versionCode: Long) -> Unit = {},
    val onConfirmMeteredDownload: (versionCode: Long) -> Unit = {},
    val onPauseDownload: () -> Unit = {},
    val onResumeDownload: () -> Unit = {},
    val onCancelDownload: () -> Unit = {},
    val onInstall: (versionCode: Long) -> Unit = {},
    val onOpenInstallPermissionSettings: () -> Unit = {},
)

/** Short hash label, e.g. "sha256:a1b2c3d4e5f6"; null when absent. */
fun shortHashLabel(sha256Hex: String): String =
    "sha256:${sha256Hex.take(12)}"

fun formatSizeLabel(sizeBytes: Long): String {
    val megabytes = sizeBytes / 1_000_000.0
    return when {
        megabytes >= 1.0 -> String.format(Locale.US, "%.1f MB", megabytes)
        else -> String.format(Locale.US, "%.0f KB", sizeBytes / 1_000.0)
    }
}

/**
 * Assisted-update sheet content: review the candidate, confirm the download, watch progress,
 * pause/resume/cancel, install once verified. Pure projection of core/update [UpdateState];
 * the host wraps this in a modal sheet and supplies [actions].
 */
@Composable
fun UpdateSheet(
    state: UpdateState,
    meteredConfirmationRequired: Boolean = false,
    notificationsDenied: Boolean = false,
    actions: UpdateSheetActions = UpdateSheetActions(),
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "App update",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        if (notificationsDenied) {
            Text(
                "Notifications are denied; you will not be alerted when an update is ready.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (state) {
            UpdateState.Disabled -> Text(
                "In-app updates are disabled in debug builds.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "Updates disabled in debug builds" },
            )

            UpdateState.Idle -> Text(
                "No update is currently available.",
                style = MaterialTheme.typography.bodyMedium,
            )

            UpdateState.Checking -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LinearProgressIndicator(modifier = Modifier.weight(1f))
                Text("Checking…", style = MaterialTheme.typography.bodyMedium)
            }

            is UpdateState.Available -> {
                CandidateCard(state.candidate)
                if (meteredConfirmationRequired) {
                    Text(
                        "You are on a metered network. Downloading uses " +
                            formatSizeLabel(state.candidate.apkSizeBytes) + " of mobile data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = { actions.onConfirmMeteredDownload(state.candidate.versionCode) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Download anyway")
                    }
                } else {
                    Button(
                        onClick = { actions.onConfirmDownload(state.candidate.versionCode) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Confirm update download" },
                    ) {
                        Text("Download update")
                    }
                }
            }

            is UpdateState.InstallPermissionRequired -> {
                CandidateCard(state.candidate)
                Text(
                    "Android requires your permission before this app can install updates. " +
                        "Open system settings and allow installs from this source, then return here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = actions.onOpenInstallPermissionSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Open install-permission settings" },
                ) {
                    Text("Open install-permission settings")
                }
            }

            is UpdateState.Downloading -> {
                CandidateCard(state.candidate)
                DownloadProgress(state.candidate)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        onClick = actions.onPauseDownload,
                        modifier = Modifier.semantics { contentDescription = "Pause download" },
                    ) {
                        Text("Pause")
                    }
                    TextButton(
                        onClick = actions.onCancelDownload,
                        modifier = Modifier.semantics { contentDescription = "Cancel download" },
                    ) {
                        Text("Cancel")
                    }
                }
            }

            is UpdateState.Paused -> {
                CandidateCard(state.candidate)
                DownloadProgress(state.candidate)
                Text(
                    "Download paused. Partial data is kept for resume.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = actions.onResumeDownload,
                        modifier = Modifier.semantics { contentDescription = "Resume download" },
                    ) {
                        Text("Resume")
                    }
                    TextButton(
                        onClick = actions.onCancelDownload,
                        modifier = Modifier.semantics { contentDescription = "Cancel download" },
                    ) {
                        Text("Cancel")
                    }
                }
            }

            is UpdateState.Verifying -> {
                CandidateCard(state.candidate)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LinearProgressIndicator(modifier = Modifier.weight(1f))
                    Text("Verifying signature…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            is UpdateState.ReadyToInstall -> {
                CandidateCard(state.candidate)
                Text(
                    "Download verified against the release signing certificate.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { contentDescription = "Update verified and ready to install" },
                )
                Button(
                    onClick = { actions.onInstall(state.candidate.versionCode) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Install update" },
                ) {
                    Text("Install update")
                }
            }

            is UpdateState.Staging -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Preparing installer…", style = MaterialTheme.typography.bodyMedium)
            }

            is UpdateState.AwaitingSystemConfirmation -> Text(
                "Confirm the installation in the Android system dialog.",
                style = MaterialTheme.typography.bodyMedium,
            )

            is UpdateState.Installing -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Installing…", style = MaterialTheme.typography.bodyMedium)
            }

            is UpdateState.Installed -> Text(
                "Update installed.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { contentDescription = "Update installed" },
            )

            is UpdateState.Failed -> {
                Text(
                    "Update failed: ${state.code}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { contentDescription = "Update failed: ${state.code}" },
                )
                if (state.message.isNotBlank()) {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.candidate?.let { CandidateCard(it) }
            }
        }
    }
}

@Composable
private fun CandidateCard(candidate: UpdateCandidate) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Version ${candidate.versionName} (${candidate.versionCode})",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics {
                    contentDescription = "Candidate version ${candidate.versionName}"
                },
            )
            Text(
                "Size ${formatSizeLabel(candidate.apkSizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                shortHashLabel(candidate.apkSha256),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadProgress(candidate: UpdateCandidate) {
    val progress = if (candidate.apkSizeBytes > 0) {
        (candidate.downloadedBytes.toFloat() / candidate.apkSizeBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${formatSizeLabel(candidate.downloadedBytes)} of ${formatSizeLabel(candidate.apkSizeBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
