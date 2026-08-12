package io.github.verybigsad.pimobile.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SessionDetailScreen(
    state: SessionDetailUiState,
    onEvent: (SessionDetailEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SessionDetailTopBar(state, onEvent)
        when (val access = state.access) {
            is SessionContentAccess.Locked -> LockedDetail(state, access, onEvent)
            is SessionContentAccess.Offline -> AvailableDetail(state, access, onEvent)
            is SessionContentAccess.Online -> AvailableDetail(state, null, onEvent)
        }
    }
    if (state.access is SessionContentAccess.Online) {
        state.approvalOffer?.let { offer ->
            ApprovalOfferSheet(
                offer = offer,
                onDecision = { binding, decision ->
                    onEvent(SessionDetailEvent.DecideApproval(binding, decision))
                },
            )
        }
    }
}

@Composable
private fun SessionDetailTopBar(
    state: SessionDetailUiState,
    onEvent: (SessionDetailEvent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactTextButton(
                label = "Back",
                description = "Return to session list",
                onClick = { onEvent(SessionDetailEvent.NavigateBack) },
            )
            Text(
                text = state.session.metadata.displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
        }
        StateChip(
            label = state.session.conversation.runState.displayLabel(),
            tone = state.session.conversation.runState.tone(),
        )
        MetadataFlow(
            modelName = state.modelName,
            thinkingLevel = state.thinkingLevel,
            elapsedLabel = state.elapsedLabel,
        )
    }
}

@Composable
private fun LockedDetail(
    state: SessionDetailUiState,
    access: SessionContentAccess.Locked,
    onEvent: (SessionDetailEvent) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "target", contentType = "target") {
            ExecutionTarget(
                metadata = state.session.metadata,
                macDisplayName = state.macDisplayName,
                connection = state.session.connection,
            )
        }
        item(key = "lock", contentType = "lock") {
            AccessLockedCard(
                state = access,
                onAction = { onEvent(it.toDetailEvent()) },
            )
        }
    }
}

@Composable
private fun AvailableDetail(
    state: SessionDetailUiState,
    offline: SessionContentAccess.Offline?,
    onEvent: (SessionDetailEvent) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 840.dp
        if (expanded) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SessionTimeline(
                    state = state,
                    onEvent = onEvent,
                    modifier = Modifier.weight(1f),
                )
                LazyColumn(
                    modifier = Modifier
                        .widthIn(min = 320.dp, max = 420.dp)
                        .fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    offline?.let { offlineState ->
                        item(key = "offline", contentType = "offline") { OfflineBanner(offlineState) }
                    }
                    item(key = "run_state", contentType = "run_state") {
                        RunStateCard(state.session.conversation.runState)
                    }
                    item(key = "target", contentType = "target") {
                        ExecutionTarget(
                            metadata = state.session.metadata,
                            macDisplayName = state.macDisplayName,
                            connection = state.session.connection,
                        )
                    }
                    state.approvalNotice?.let { notice ->
                        item(key = "approval_notice", contentType = "approval_notice") {
                            ApprovalNoticeCard(
                                notice = notice,
                                onRetry = { onEvent(SessionDetailEvent.RetryApprovalService) },
                                onDismiss = { onEvent(SessionDetailEvent.DismissApprovalNotice) },
                            )
                        }
                    }
                    item(key = "composer", contentType = "composer") {
                        SessionComposer(
                            draft = state.session.draft,
                            runState = state.session.conversation.runState,
                            commandNotice = state.commandNotice,
                            voicePermission = state.voicePermission,
                            enabled = state.canMutate,
                            onEvent = onEvent,
                        )
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                SessionTimeline(
                    state = state,
                    onEvent = onEvent,
                    modifier = Modifier.weight(1f),
                    contextContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            offline?.let { OfflineBanner(it) }
                            RunStateCard(state.session.conversation.runState)
                            ExecutionTarget(
                                metadata = state.session.metadata,
                                macDisplayName = state.macDisplayName,
                                connection = state.session.connection,
                            )
                            state.approvalNotice?.let { notice ->
                                ApprovalNoticeCard(
                                    notice = notice,
                                    onRetry = { onEvent(SessionDetailEvent.RetryApprovalService) },
                                    onDismiss = { onEvent(SessionDetailEvent.DismissApprovalNotice) },
                                )
                            }
                        }
                    },
                )
                SessionComposer(
                    draft = state.session.draft,
                    runState = state.session.conversation.runState,
                    commandNotice = state.commandNotice,
                    voicePermission = state.voicePermission,
                    enabled = state.canMutate,
                    onEvent = onEvent,
                )
            }
        }
    }
}

private fun LockAction.toDetailEvent(): SessionDetailEvent = when (this) {
    LockAction.PAIR_MAC -> SessionDetailEvent.PairMac
    LockAction.AUTHENTICATE -> SessionDetailEvent.Authenticate
    LockAction.RETRY_CONNECTION -> SessionDetailEvent.RetryConnection
}

private fun io.github.verybigsad.pimobile.model.SessionRunState.tone(): StateTone = when (this) {
    io.github.verybigsad.pimobile.model.SessionRunState.STREAMING,
    io.github.verybigsad.pimobile.model.SessionRunState.RETRYING,
    io.github.verybigsad.pimobile.model.SessionRunState.COMPACTING,
    -> StateTone.PRIMARY

    io.github.verybigsad.pimobile.model.SessionRunState.WAITING_FOR_INPUT -> StateTone.WARNING
    io.github.verybigsad.pimobile.model.SessionRunState.SETTLED -> StateTone.POSITIVE
    io.github.verybigsad.pimobile.model.SessionRunState.FAULTED -> StateTone.WARNING
    else -> StateTone.NEUTRAL
}
