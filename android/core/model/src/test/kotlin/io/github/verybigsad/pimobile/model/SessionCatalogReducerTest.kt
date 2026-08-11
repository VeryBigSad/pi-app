package io.github.verybigsad.pimobile.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionCatalogReducerTest {
    @Test
    fun catalogReceivedReplacesSessionsAtomically() {
        val first = entry("550e8400-e29b-41d4-a716-446655440001")
        val second = entry("550e8400-e29b-41d4-a716-446655440002")

        val loaded = SessionCatalogReducer.reduce(
            SessionCatalogState.EMPTY,
            SessionCatalogAction.CatalogReceived(listOf(first, second)),
        )
        assertThat(loaded.sessions.values).containsExactly(first, second)

        val replaced = SessionCatalogReducer.reduce(
            loaded,
            SessionCatalogAction.CatalogReceived(listOf(second)),
        )
        assertThat(replaced.sessions.values).containsExactly(second)
        assertThat(loaded.sessions.values).containsExactly(first, second)
    }

    @Test
    fun catalogReceivedRejectsDuplicatesAndOverflow() {
        val first = entry("550e8400-e29b-41d4-a716-446655440001")
        assertThat(
            runCatching {
                SessionCatalogReducer.reduce(
                    SessionCatalogState.EMPTY,
                    SessionCatalogAction.CatalogReceived(listOf(first, first)),
                )
            }.isFailure,
        ).isTrue()
        val overflow = (1..SessionCatalogReducer.MAX_CATALOG_SESSIONS + 1).map { index ->
            entry("550e8400-e29b-41d4-a716-%012x".format(index))
        }
        assertThat(
            runCatching {
                SessionCatalogReducer.reduce(SessionCatalogState.EMPTY, SessionCatalogAction.CatalogReceived(overflow))
            }.isFailure,
        ).isTrue()
    }

    private fun entry(id: String) = SessionCatalogEntry(
        id = SessionId(id),
        provider = "openai",
        model = "gpt-5",
        thinkingLevel = "high",
        repositoryPath = "/work/pi-app",
        worktreePath = null,
        workingDirectory = "/work/pi-app",
        parentSessionId = null,
        createdAtEpochMillis = 1_000,
        updatedAtEpochMillis = 2_000,
    )
}
