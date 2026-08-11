package io.github.verybigsad.pimobile.push

@JvmInline
value class OpaqueWakeId internal constructor(val value: String)

sealed interface WakePayloadParseResult {
    data class Valid(val wakeId: OpaqueWakeId) : WakePayloadParseResult
    data class Invalid(val reason: WakePayloadInvalidReason) : WakePayloadParseResult
}

enum class WakePayloadInvalidReason {
    EMPTY,
    TOO_LARGE,
    MALFORMED_UTF8,
    INVALID_WAKE_ID,
}

object OpaqueWakePayload {
    const val MAX_PAYLOAD_BYTES = 128
    const val MAX_WAKE_ID_BYTES = 128
    const val MIN_WAKE_ID_BYTES = 22

    fun parse(bytes: ByteArray): WakePayloadParseResult {
        if (bytes.isEmpty()) {
            return WakePayloadParseResult.Invalid(WakePayloadInvalidReason.EMPTY)
        }
        if (bytes.size > MAX_PAYLOAD_BYTES) {
            return WakePayloadParseResult.Invalid(WakePayloadInvalidReason.TOO_LARGE)
        }
        if (bytes.any { it.toInt() and 0x80 != 0 }) {
            return WakePayloadParseResult.Invalid(WakePayloadInvalidReason.MALFORMED_UTF8)
        }
        return parseAscii(bytes.decodeToString())
    }

    fun parse(value: String): WakePayloadParseResult {
        if (value.isEmpty()) {
            return WakePayloadParseResult.Invalid(WakePayloadInvalidReason.EMPTY)
        }
        if (value.length > MAX_PAYLOAD_BYTES) {
            return WakePayloadParseResult.Invalid(WakePayloadInvalidReason.TOO_LARGE)
        }
        if (value.any { it.code > 0x7F }) {
            return WakePayloadParseResult.Invalid(WakePayloadInvalidReason.MALFORMED_UTF8)
        }
        return parseAscii(value)
    }

    private fun parseAscii(value: String): WakePayloadParseResult {
        if (value.length !in MIN_WAKE_ID_BYTES..MAX_WAKE_ID_BYTES || !value.all { it.isAsciiBase64Url() }) {
            return WakePayloadParseResult.Invalid(WakePayloadInvalidReason.INVALID_WAKE_ID)
        }
        return WakePayloadParseResult.Valid(OpaqueWakeId(value))
    }

    private fun Char.isAsciiBase64Url(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '-' || this == '_'
}
