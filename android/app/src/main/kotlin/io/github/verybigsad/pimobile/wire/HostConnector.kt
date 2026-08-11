package io.github.verybigsad.pimobile.wire

import io.github.verybigsad.pimobile.model.EventCursor
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.TransportPath
import io.github.verybigsad.pimobile.storage.MessageEntity
import io.github.verybigsad.pimobile.storage.SessionEntity
import io.github.verybigsad.pimobile.voice.MacVoiceError
import kotlinx.serialization.json.JsonObject

/** Inbound events from one live host connection generation. */
sealed interface HostConnectionEvent {
    data class Connecting(val path: TransportPath, val attempt: Int) : HostConnectionEvent

    /** mTLS finished and client.hello negotiated; user authentication is still required. */
    data class DeviceAuthenticated(val path: TransportPath, val certificateSerial: String) : HostConnectionEvent

    data class AssertionOptions(val ceremonyId: String, val binding: JsonObject, val optionsJson: String) : HostConnectionEvent

    data class AuthResult(val ceremonyId: String?, val success: Boolean) : HostConnectionEvent

    data object HostLocked : HostConnectionEvent

    data class SyncReset(val sessionId: SessionId, val reason: String) : HostConnectionEvent

    /**
     * One complete validated snapshot (all pages received and snapshot.end validated).
     * Entities are built from the wire pages; the coordinator reduces and commits them
     * transactionally before acknowledging.
     */
    data class SnapshotReady(
        val sessionId: SessionId,
        val cursor: EventCursor,
        val session: SessionEntity,
        val messages: List<MessageEntity>,
        val runState: String?,
    ) : HostConnectionEvent

    /** One canonical event batch entry, already mapped to reducer and storage forms. */
    data class CanonicalEvent(
        val sessionId: SessionId,
        val cursor: EventCursor,
        val conversationEvent: io.github.verybigsad.pimobile.model.ConversationEvent?,
        val finalized: MessageEntity?,
    ) : HostConnectionEvent

    data class ApprovalOffer(
        val offerId: String,
        val operationId: String,
        val operationName: String,
        val normalizedArguments: String,
        val targetLabel: String,
        val targetValue: String,
        val reasons: List<String>,
        val policyVersion: String,
        val argumentHash: String,
        val expiresAtEpochMillis: Long,
    ) : HostConnectionEvent

    data class ApprovalExpired(val offerId: String, val reason: String) : HostConnectionEvent

    data class AgentsCatalogReceived(val catalog: io.github.verybigsad.pimobile.network.WireBodies.AgentsCatalog) :
        HostConnectionEvent

    data class AgentsUpdateReceived(val update: io.github.verybigsad.pimobile.network.WireBodies.AgentsUpdate) :
        HostConnectionEvent

    data class VoiceTranscript(val sessionId: String, val type: String, val body: ByteArray) : HostConnectionEvent

    data class VoiceError(val streamId: String, val error: MacVoiceError) : HostConnectionEvent

    data class TerminalReady(val terminalGeneration: ULong, val columns: Int, val rows: Int) : HostConnectionEvent

    data class TerminalOutput(val terminalGeneration: ULong, val sequence: ULong, val bytes: ByteArray) : HostConnectionEvent

    data object TerminalReset : HostConnectionEvent

    /**
     * terminal.history.result: a read-only bounded capture. [text] is null when the host
     * returned a rawRef-only body, which this client does not dereference (honest gap).
     */
    data class TerminalHistoryResult(
        val terminalGeneration: ULong,
        val capturedAt: String,
        val text: String?,
        val truncatedLines: Boolean,
        val truncatedBytes: Boolean,
    ) : HostConnectionEvent

    /** The sync round committed on the host; the connection is READY for commands. */
    data object SyncComplete : HostConnectionEvent

    data class HostError(val code: String, val retryable: Boolean) : HostConnectionEvent

    data class Disconnected(val reason: String?) : HostConnectionEvent
}

/** One live host connection. Implementations are single-generation; closing is idempotent. */
interface HostConnector {
    val path: TransportPath

    suspend fun send(type: String, body: JsonObject, replyTo: String? = null)

    suspend fun sendTerminalInput(terminalGeneration: ULong, sequence: ULong, bytes: ByteArray)

    suspend fun sendVoicePcm(streamId: String, sequence: Long, offset: ULong, bytes: ByteArray)

    suspend fun close()
}

fun interface HostConnectorFactory {
    fun create(onEvent: (HostConnectionEvent) -> Unit): HostConnectionRunner
}

/** Drives connect + hello negotiation and then streams inbound events until closed. */
fun interface HostConnectionRunner {
    suspend fun run(): HostConnector
}
