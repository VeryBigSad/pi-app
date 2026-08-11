package io.github.verybigsad.pimobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.verybigsad.pimobile.MainViewModel
import io.github.verybigsad.pimobile.agents.AgentsScreen
import io.github.verybigsad.pimobile.notifications.NotificationPermissionStatus
import io.github.verybigsad.pimobile.settings.SettingsActions
import io.github.verybigsad.pimobile.settings.SettingsScreen
import io.github.verybigsad.pimobile.settings.UpdateSheet
import io.github.verybigsad.pimobile.settings.UpdateSheetActions
import io.github.verybigsad.pimobile.state.AppIntent

/** Actions only an activity can perform (runtime permission, system settings intents). */
@Immutable
data class AppActivityActions(
    val onRequestNotificationPermission: () -> Unit = {},
    val onOpenChannelSettings: (channelId: String) -> Unit = {},
    val onOpenAppNotificationSettings: () -> Unit = {},
    val onOpenInstallPermissionSettings: () -> Unit = {},
)

/** Entry-point affordances overlaid top-end on the session list. */
@Composable
fun SessionListEntryPoints(
    onOpenSettings: () -> Unit,
    onOpenAgents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.padding(4.dp)) {
        TextButton(
            onClick = onOpenAgents,
            modifier = Modifier.semantics { contentDescription = "Open agents insight" },
        ) {
            Text("Agents")
        }
        TextButton(
            onClick = onOpenSettings,
            modifier = Modifier.semantics { contentDescription = "Open settings" },
        ) {
            Text("Settings")
        }
    }
}

/** Agents entry affordance overlaid top-end on session detail. */
@Composable
fun SessionDetailEntryPoints(
    onOpenAgents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onOpenAgents,
        modifier = modifier
            .padding(4.dp)
            .semantics { contentDescription = "Open agents insight" },
    ) {
        Text("Agents")
    }
}

@Composable
fun SettingsDestination(viewModel: MainViewModel, activityActions: AppActivityActions) {
    val settings by viewModel.settingsState.collectAsState()
    SettingsScreen(
        state = settings,
        actions = SettingsActions(
            onRevokeThisDevice = {
                viewModel.submit(AppIntent.CloseSettings)
                viewModel.submit(AppIntent.UnpairRequested)
            },
            onOpenChannelSettings = activityActions.onOpenChannelSettings,
            onOpenAppNotificationSettings = activityActions.onOpenAppNotificationSettings,
            onRequestNotificationPermission = activityActions.onRequestNotificationPermission,
            onCheckForUpdates = { viewModel.updateIntegration.checkNow() },
            onOpenUpdateSheet = { viewModel.submit(AppIntent.OpenUpdateSheet) },
        ),
    )
}

@Composable
fun AgentsDestination(viewModel: MainViewModel) {
    AgentsScreen(store = viewModel.agentsStore)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheetDestination(viewModel: MainViewModel, activityActions: AppActivityActions) {
    val updateState by viewModel.updateState.collectAsState()
    val metered by viewModel.updateMeteredConfirmation.collectAsState()
    val permission by viewModel.notificationPermissionStatus.collectAsState()
    ModalBottomSheet(
        onDismissRequest = { viewModel.submit(AppIntent.CloseUpdateSheet) },
    ) {
        UpdateSheet(
            state = updateState,
            meteredConfirmationRequired = metered != null,
            notificationsDenied = permission == NotificationPermissionStatus.DENIED,
            actions = UpdateSheetActions(
                onConfirmDownload = { viewModel.updateIntegration.confirmDownload(it) },
                onConfirmMeteredDownload = { viewModel.updateIntegration.confirmMeteredDownload(it) },
                onPauseDownload = { viewModel.updateIntegration.pauseDownload() },
                onResumeDownload = { viewModel.updateIntegration.resumeDownload() },
                onCancelDownload = { viewModel.updateIntegration.cancelDownload() },
                onInstall = { viewModel.updateIntegration.authorizeInstall(it) },
                onOpenInstallPermissionSettings = activityActions.onOpenInstallPermissionSettings,
            ),
        )
    }
}

@Composable
fun SessionListWithEntryPoints(viewModel: MainViewModel) {
    Box(modifier = Modifier.fillMaxSize()) {
        SessionListDestination(viewModel)
        SessionListEntryPoints(
            onOpenSettings = { viewModel.submit(AppIntent.OpenSettings) },
            onOpenAgents = { viewModel.submit(AppIntent.OpenAgents) },
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}
