package io.github.verybigsad.pimobile.session

import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.model.ConnectionState
import io.github.verybigsad.pimobile.model.ConversationState
import io.github.verybigsad.pimobile.model.DraftState
import io.github.verybigsad.pimobile.model.MacId
import io.github.verybigsad.pimobile.model.MutualTlsAuthentication
import io.github.verybigsad.pimobile.model.PasskeyAuthentication
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.SessionMetadata
import io.github.verybigsad.pimobile.model.SessionState
import io.github.verybigsad.pimobile.model.TransportPath
import io.github.verybigsad.pimobile.model.TrustState
import org.junit.Test

class TimelinePresentationTest {
    @Test
    fun boundedPreviewSanitizesAndAddsEllipsisOnlyWhenTruncated() {
        assertThat(boundedPreview("safe\u001B[31m text", 20)).isEqualTo("safe text")
        assertThat(boundedPreview("abcdefgh", 5)).isEqualTo("abcde…")
    }

    @Test
    fun unavailableCanonicalConversationProducesOnlyGapBanner() {
        val rows = buildTimelineRows(detailState(ConversationState.awaitingCanonical(SessionId("session"))))

        assertThat(rows).hasSize(1)
        assertThat(rows.single()).isInstanceOf(TimelineRow.CanonicalGap::class.java)
    }

    @Test
    fun boundedPreviewRejectsInvalidBounds() {
        val failure = runCatching { boundedPreview("text", 0) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun detailState(conversation: ConversationState): SessionDetailUiState {
        val macId = MacId("mac")
        val now = 1_000L
        val trust = TrustState.Trusted(macId, "Mac", "cert", now + 10_000)
        return SessionDetailUiState(
            session = SessionState(
                metadata = SessionMetadata(SessionId("session"), macId, "Session", "/repo", "/work", null, now),
                conversation = conversation,
                draft = DraftState.empty(SessionId("session")),
                trust = trust,
                connection = ConnectionState.Ready(
                    TransportPath.DIRECT,
                    macId,
                    PasskeyAuthentication("assertion", now - 1, now + 1),
                    MutualTlsAuthentication("cert", now - 1),
                ),
            ),
            passkeyProvider = PasskeyProviderAvailability.Available("Provider"),
            retainedAuthentication = null,
            nowEpochMillis = now,
            macDisplayName = "Mac",
            modelName = null,
            thinkingLevel = null,
            elapsedLabel = null,
            lastSyncedLabel = null,
        )
    }
}
