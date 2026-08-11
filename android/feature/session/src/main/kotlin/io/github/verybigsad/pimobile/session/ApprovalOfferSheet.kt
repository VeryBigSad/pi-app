package io.github.verybigsad.pimobile.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ApprovalOfferSheet(
    offer: ApprovalOfferUiState,
    onDecision: (ApprovalBinding, ApprovalDecision) -> Unit,
) {
    val denyFocus = remember { FocusRequester() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    val expired = offer.remainingSeconds == 0
    val decisionEnabled = offer.pendingDecision == null && !expired
    val urgency = approvalUrgencyForRemainingSeconds(offer.remainingSeconds)
    val deny = { onDecision(offer.binding, ApprovalDecision.DENY) }

    LaunchedEffect(offer.offerId) {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        view.announceForAccessibility(
            "Approval requested for ${offer.operationName}. Deny is focused by default. " +
                "${offer.remainingSeconds} seconds remain.",
        )
        denyFocus.requestFocus()
    }
    LaunchedEffect(offer.offerId, expired) {
        if (expired) {
            view.announceForAccessibility(
                "Approval offer for ${offer.operationName} expired and was denied by default.",
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (decisionEnabled) deny()
        },
        sheetState = sheetState,
        modifier = Modifier.semantics {
            paneTitle = "Approval decision for ${offer.operationName}"
            contentDescription = "Pre-execution approval offer for ${offer.operationName}"
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = if (expired) "Approval expired" else "Allow this operation once?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (expired) {
                    "The deadline passed. The Mac denies expired offers; this operation will not run."
                } else {
                    "This is a real pre-execution gate. It is not a Pi confirmation dialog."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KeyValueText("Source tool", offer.operationName, monospaced = true)
            KeyValueText("Operation ID", offer.operationId, monospaced = true)
            Text(
                text = "Exact normalized arguments",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalCodeBlock(
                text = offer.normalizedArguments,
                modifier = Modifier.semantics {
                    contentDescription = "Scrollable exact normalized arguments"
                },
            )
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(offer.normalizedArguments)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Copy exact normalized arguments" },
            ) {
                Text("Copy arguments")
            }
            KeyValueText(offer.targetLabel, offer.targetValue, monospaced = true)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Risk classification",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    offer.reasons.forEach { reason ->
                        StateChip(reason, StateTone.WARNING)
                    }
                }
            }
            KeyValueText("Policy", offer.policyVersion)
            KeyValueText("Argument hash", offer.argumentHash, monospaced = true)
            DeadlineCard(offer, urgency)
            Text(
                text = "Guardrail, not sandbox: allowed code runs with Mac permissions. Direct extension Node, file-system, or process side effects are not contained by this gate.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = deny,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .focusRequester(denyFocus)
                        .semantics { contentDescription = "Deny this exact operation" },
                    enabled = decisionEnabled,
                ) {
                    Text(if (offer.pendingDecision == ApprovalDecision.DENY) "Denying…" else "Deny")
                }
                Button(
                    onClick = { onDecision(offer.binding, ApprovalDecision.ALLOW_ONCE) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Allow this exact operation once" },
                    enabled = decisionEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(if (offer.pendingDecision == ApprovalDecision.ALLOW_ONCE) "Allowing once…" else "Allow once")
                }
            }
        }
    }
}

@Composable
private fun DeadlineCard(offer: ApprovalOfferUiState, urgency: ApprovalUrgency) {
    val colors = when (urgency) {
        ApprovalUrgency.CALM -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ApprovalUrgency.WARNING -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        ApprovalUrgency.CRITICAL, ApprovalUrgency.EXPIRED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    val deadlineText = if (urgency == ApprovalUrgency.EXPIRED) {
        "Offer expired. Denied by default."
    } else {
        "${offer.remainingSeconds} seconds remaining. No answer denies."
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = deadlineText
                contentDescription = deadlineText
            },
        colors = CardDefaults.cardColors(containerColor = colors.first),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (urgency == ApprovalUrgency.EXPIRED) "Expired" else "Decision deadline",
                fontWeight = FontWeight.Bold,
                color = colors.second,
            )
            Text(deadlineText, color = colors.second)
            LinearProgressIndicator(
                progress = { urgency.progress },
                modifier = Modifier.fillMaxWidth(),
                color = colors.second,
                trackColor = colors.second.copy(alpha = 0.2f),
            )
            Text(
                text = "Expires ${offer.expiresAtLabel}. Disconnect, expiry, or a classifier error denies the operation.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.second,
            )
        }
    }
}

internal enum class ApprovalUrgency(val progress: Float) {
    CALM(1f),
    WARNING(0.5f),
    CRITICAL(0.2f),
    EXPIRED(0f),
    ;

}

internal fun approvalUrgencyForRemainingSeconds(remainingSeconds: Int): ApprovalUrgency = when {
    remainingSeconds == 0 -> ApprovalUrgency.EXPIRED
    remainingSeconds <= 30 -> ApprovalUrgency.CRITICAL
    remainingSeconds <= 60 -> ApprovalUrgency.WARNING
    else -> ApprovalUrgency.CALM
}

@Composable
internal fun ApprovalNoticeCard(
    notice: ApprovalNoticeUiState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (notice) {
                is ApprovalNoticeUiState.Expired -> {
                    Text(
                        "Approval expired",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "${notice.operationName} was denied when the offer expired.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }

                is ApprovalNoticeUiState.Blocked -> {
                    Text(
                        "Blocked on Mac — approval service unavailable",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "${notice.operationName} did not run through the gated path. Pi resumed with a block result. Code: ${notice.code}.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    if (notice.canRetry) {
                        CompactTextButton(
                            label = "Retry after service recovers",
                            description = "Retry operation after Mac approval service recovers",
                            onClick = onRetry,
                        )
                    }
                }
            }
            CompactTextButton(
                label = "Dismiss notice",
                description = "Dismiss approval outcome notice",
                onClick = onDismiss,
            )
        }
    }
}
