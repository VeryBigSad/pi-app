package io.github.verybigsad.pimobile.state

import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.model.MacId
import io.github.verybigsad.pimobile.storage.SessionEntity
import org.junit.Test

class StorageMappersTest {
    @Test
    fun sessionMetadataRetainsStoredRepositoryWorktreeAndParent() {
        val metadata = StorageMappers.sessionMetadata(
            SessionEntity(
                sessionId = "child-session",
                cwd = "/repo/.worktrees/child",
                displayName = "Child",
                provider = "anthropic",
                modelId = "claude",
                thinkingLevel = "high",
                canonicalCursor = null,
                updatedAtEpochMs = 12,
                repositoryPath = "/repo",
                worktreePath = "/repo/.worktrees/child",
                parentSessionId = "parent-session",
            ),
            MacId("mac-1"),
        )

        assertThat(metadata.repositoryPath).isEqualTo("/repo")
        assertThat(metadata.worktreePath).isEqualTo("/repo/.worktrees/child")
        assertThat(metadata.parentSessionId?.value).isEqualTo("parent-session")
    }
}
