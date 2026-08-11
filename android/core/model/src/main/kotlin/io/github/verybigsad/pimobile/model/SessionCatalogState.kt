package io.github.verybigsad.pimobile.model

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap

/**
 * One `session.catalog` entry (frozen shape: sessionId/provider/model/thinkingLevel/repo/
 * worktree/cwd/parentId/createdAt/updatedAt). This is the host's session listing; it is
 * distinct from [SessionMetadata], which the local session state owns.
 */
data class SessionCatalogEntry(
    val id: SessionId,
    val provider: String,
    val model: String,
    val thinkingLevel: String,
    val repositoryPath: String,
    val worktreePath: String?,
    val workingDirectory: String,
    val parentSessionId: SessionId?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(provider.isNotBlank())
        require(model.isNotBlank())
        require(thinkingLevel.isNotBlank())
        require(repositoryPath.isNotBlank())
        require(worktreePath == null || worktreePath.isNotBlank())
        require(workingDirectory.isNotBlank())
        require(createdAtEpochMillis >= 0)
        require(updatedAtEpochMillis >= 0)
        require(parentSessionId != id)
    }
}

/** Host-published session catalog; replaced atomically on every `session.catalog`. */
data class SessionCatalogState(
    val sessions: PersistentMap<SessionId, SessionCatalogEntry>,
) {
    companion object {
        val EMPTY = SessionCatalogState(persistentMapOf())
    }
}

sealed interface SessionCatalogAction {
    data class CatalogReceived(val sessions: List<SessionCatalogEntry>) : SessionCatalogAction
}

object SessionCatalogReducer {
    const val MAX_CATALOG_SESSIONS = 512

    fun reduce(state: SessionCatalogState, action: SessionCatalogAction): SessionCatalogState = when (action) {
        is SessionCatalogAction.CatalogReceived -> {
            require(action.sessions.size <= MAX_CATALOG_SESSIONS)
            val keyed = action.sessions.associateBy(SessionCatalogEntry::id)
            require(keyed.size == action.sessions.size)
            state.copy(sessions = keyed.toPersistentMap())
        }
    }
}
