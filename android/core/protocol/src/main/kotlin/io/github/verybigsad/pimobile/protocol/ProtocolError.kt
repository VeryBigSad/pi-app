package io.github.verybigsad.pimobile.protocol

enum class ProtocolErrorCode {
    UNSUPPORTED_VERSION,
    PROTOCOL_VIOLATION,
    FRAME_TOO_LARGE,
    RESOURCE_EXHAUSTED,
    AUTH_REQUIRED,
    AUTH_FAILED,
    PAIRING_PHASE_REQUIRED,
    REVOKED,
    SEQUENCE_GAP,
    SYNC_REQUIRED,
    SNAPSHOT_WAITING_FOR_IDLE,
    SNAPSHOT_LEAF_CHANGED,
    COMMAND_ID_REUSE,
    COMMAND_DORMANT,
    COMMAND_INDETERMINATE,
    JOURNAL_UNAVAILABLE,
    APPROVAL_DENIED,
    APPROVAL_EXPIRED,
    BROKER_UNAVAILABLE,
    SESSION_LEASE_CONFLICT,
    STREAM_INVALID,
    BLOB_NOT_READY,
    BLOB_INVALID,
    TERMINAL_RESET_REQUIRED,
}

class ProtocolException(
    val code: ProtocolErrorCode,
    message: String,
) : IllegalArgumentException(message)

fun protocolViolation(message: String): Nothing = throw ProtocolException(ProtocolErrorCode.PROTOCOL_VIOLATION, message)
fun frameTooLarge(message: String): Nothing = throw ProtocolException(ProtocolErrorCode.FRAME_TOO_LARGE, message)
