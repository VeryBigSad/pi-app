package io.github.verybigsad.pimobile.session.list

import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.session.SessionListEvent
import org.junit.Test

class SessionInboxUiStateTest {
    @Test
    fun previewUsesStructuredSanitizerAndCollapsesLines() {
        assertThat(sanitizePreview("safe\u001B[31m text\u001B[0m\n next\u202E")).isEqualTo("safe text next")
    }

    @Test
    fun relativeTimeUsesHumanScaleAndNeverReportsFutureTime() {
        assertThat(relativeTime(10_000, 10_000)).isEqualTo("Just now")
        assertThat(relativeTime(9_000, 70_000)).isEqualTo("1m ago")
        assertThat(relativeTime(20_000, 10_000)).isEqualTo("Just now")
    }

    @Test
    fun resyncIntentAdaptsToExistingRefreshEvent() {
        assertThat(SessionInboxEvent.RequestResync.toSessionListEvent()).isEqualTo(SessionListEvent.Refresh)
        assertThat(SessionInboxEvent.Open(SessionId("one")).toSessionListEvent())
            .isEqualTo(SessionListEvent.OpenSession(SessionId("one")))
    }

    @Test
    fun catalogMetadataRejectsBlankNamesAndNegativeUnreadCounts() {
        assertThat(runCatching { SessionCatalogMetadata(provider = " ") }.isFailure).isTrue()
        assertThat(runCatching { SessionCatalogMetadata(unreadCount = -1) }.isFailure).isTrue()
    }
}
