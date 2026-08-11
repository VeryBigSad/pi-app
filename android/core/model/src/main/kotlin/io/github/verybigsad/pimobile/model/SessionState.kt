package io.github.verybigsad.pimobile.model

data class SessionMetadata(
    val id: SessionId,
    val macId: MacId,
    val displayName: String,
    val repositoryPath: String,
    val worktreePath: String,
    val parentSessionId: SessionId?,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(displayName.isNotBlank())
        require(repositoryPath.isNotBlank())
        require(worktreePath.isNotBlank())
        require(updatedAtEpochMillis >= 0)
        require(parentSessionId != id)
    }
}

data class SessionState(
    val metadata: SessionMetadata,
    val conversation: ConversationState,
    val draft: DraftState,
    val trust: TrustState,
    val connection: ConnectionState,
) {
    init {
        require(conversation.sessionId == metadata.id)
        require(draft.sessionId == metadata.id)
    }

    companion object {
        fun initial(metadata: SessionMetadata): SessionState = SessionState(
            metadata = metadata,
            conversation = ConversationState.awaitingCanonical(metadata.id),
            draft = DraftState.empty(metadata.id),
            trust = TrustState.Unpaired,
            connection = ConnectionState.Disconnected(DisconnectReason.NEVER_CONNECTED),
        )
    }
}

sealed interface SessionAction {
    data class Conversation(val action: ConversationAction) : SessionAction
    data class Draft(val action: DraftAction) : SessionAction
    data class TrustChanged(val trust: TrustState) : SessionAction
    data class ConnectionChanged(val connection: ConnectionState) : SessionAction
    data class MetadataChanged(val metadata: SessionMetadata) : SessionAction

    /**
     * Host-communicated user-authentication expiry (auth.result `expiresAt`). When the
     * connection is READY the host value replaces the local expiry verbatim; an expiry at
     * or before verification time downgrades to device-authenticated immediately.
     */
    data class AuthExpiryReceived(val expiresAtEpochMillis: Long) : SessionAction
}

object SessionReducer {
    fun reduce(state: SessionState, action: SessionAction, nowEpochMillis: Long): SessionState {
        require(nowEpochMillis >= 0)
        return when (action) {
            is SessionAction.Conversation -> state.copy(
                conversation = ConversationReducer.reduce(state.conversation, action.action),
            )

            is SessionAction.Draft -> state.copy(draft = DraftReducer.reduce(state.draft, action.action))
            is SessionAction.TrustChanged -> applyTrust(state, action.trust, nowEpochMillis)
            is SessionAction.ConnectionChanged -> applyConnection(state, action.connection, nowEpochMillis)
            is SessionAction.MetadataChanged -> {
                require(action.metadata.id == state.metadata.id)
                state.copy(metadata = action.metadata)
            }

            is SessionAction.AuthExpiryReceived -> applyHostAuthExpiry(state, action.expiresAtEpochMillis, nowEpochMillis)
        }
    }

    private fun applyHostAuthExpiry(state: SessionState, expiresAt: Long, now: Long): SessionState {
        require(expiresAt >= 0)
        val ready = state.connection as? ConnectionState.Ready ?: return state
        val trust = state.trust as? TrustState.Trusted
            ?: return state.copy(connection = ConnectionState.Disconnected(DisconnectReason.TRUST_REQUIRED))
        if (expiresAt <= ready.userAuthentication.verifiedAtEpochMillis) {
            val device = validDeviceAuthentication(ready, trust, now)
            return state.copy(connection = device ?: ConnectionState.Disconnected(DisconnectReason.TRUST_REQUIRED))
        }
        return applyConnection(
            state,
            ready.copy(userAuthentication = ready.userAuthentication.copy(expiresAtEpochMillis = expiresAt)),
            now,
        )
    }

    private fun applyTrust(state: SessionState, trust: TrustState, nowEpochMillis: Long): SessionState {
        val connection = when (trust) {
            TrustState.Unpaired -> ConnectionState.Disconnected(DisconnectReason.TRUST_REQUIRED)
            is TrustState.Revoked -> ConnectionState.Revoked(trust.macId, trust.revokedAtEpochMillis)
            is TrustState.Trusted -> revalidate(state.connection, trust, nowEpochMillis)
        }
        return state.copy(trust = trust, connection = connection)
    }

    private fun applyConnection(
        state: SessionState,
        connection: ConnectionState,
        nowEpochMillis: Long,
    ): SessionState {
        val trust = state.trust as? TrustState.Trusted
        val accepted = when (connection) {
            is ConnectionState.Ready -> {
                val device = trust?.let { validDeviceAuthentication(connection, it, nowEpochMillis) }
                when {
                    device == null -> null
                    connection.userAuthentication.expiresAtEpochMillis <= nowEpochMillis -> device
                    else -> connection
                }
            }

            is ConnectionState.DeviceAuthenticated ->
                trust?.let { validDeviceAuthentication(connection, it, nowEpochMillis) }

            else -> connection
        } ?: ConnectionState.Disconnected(DisconnectReason.TRUST_REQUIRED)
        return state.copy(connection = accepted)
    }

    private fun revalidate(
        connection: ConnectionState,
        trust: TrustState.Trusted,
        nowEpochMillis: Long,
    ): ConnectionState = when (connection) {
        is ConnectionState.Ready -> {
            val device = validDeviceAuthentication(connection, trust, nowEpochMillis)
            when {
                device == null -> ConnectionState.Disconnected(DisconnectReason.TRUST_REQUIRED)
                connection.userAuthentication.expiresAtEpochMillis <= nowEpochMillis -> device
                else -> connection
            }
        }

        is ConnectionState.DeviceAuthenticated ->
            validDeviceAuthentication(connection, trust, nowEpochMillis)
                ?: ConnectionState.Disconnected(DisconnectReason.TRUST_REQUIRED)

        else -> connection
    }

    /**
     * Returns the still-valid device authentication for an authenticated connection, or null when
     * the connection is stale: wrong Mac, certificate serial no longer matching the trusted
     * certificate (rotation or replacement), or trusted certificate expired at [nowEpochMillis].
     */
    private fun validDeviceAuthentication(
        connection: ConnectionState,
        trust: TrustState.Trusted,
        nowEpochMillis: Long,
    ): ConnectionState.DeviceAuthenticated? {
        val (path, macId, authentication) = when (connection) {
            is ConnectionState.Ready ->
                Triple(connection.path, connection.macId, connection.deviceAuthentication)

            is ConnectionState.DeviceAuthenticated ->
                Triple(connection.path, connection.macId, connection.deviceAuthentication)

            else -> return null
        }
        val valid = macId == trust.macId &&
            authentication.certificateSerial == trust.certificateSerial &&
            nowEpochMillis < trust.certificateNotAfterEpochMillis
        return if (valid) ConnectionState.DeviceAuthenticated(path, macId, authentication) else null
    }
}
