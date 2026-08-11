package io.github.verybigsad.pimobile.push

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OpaqueWakePayloadTest {
    @Test
    fun acceptsOnlyBoundedBase64UrlWakeIds() {
        val minimum = "A".repeat(OpaqueWakePayload.MIN_WAKE_ID_BYTES)
        val maximum = ("aZ09-_".repeat(22)).take(OpaqueWakePayload.MAX_WAKE_ID_BYTES)

        assertValid(minimum)
        assertValid(maximum)
        assertValid("0123456789abcdefghijkl")
    }

    @Test
    fun byteAndStringParsingAreEquivalent() {
        val value = "wake_0123456789-ABCDEFG"

        assertThat(OpaqueWakePayload.parse(value.toByteArray())).isEqualTo(OpaqueWakePayload.parse(value))
    }

    @Test
    fun rejectsEmptyAndShortPayloads() {
        assertInvalid(ByteArray(0), WakePayloadInvalidReason.EMPTY)
        assertInvalid("", WakePayloadInvalidReason.EMPTY)
        assertInvalid("a".repeat(OpaqueWakePayload.MIN_WAKE_ID_BYTES - 1), WakePayloadInvalidReason.INVALID_WAKE_ID)
    }

    @Test
    fun checksByteBoundBeforeContent() {
        assertInvalid(
            ByteArray(OpaqueWakePayload.MAX_PAYLOAD_BYTES + 1) { 0xFF.toByte() },
            WakePayloadInvalidReason.TOO_LARGE,
        )
        assertInvalid(
            "é".repeat(OpaqueWakePayload.MAX_PAYLOAD_BYTES + 1),
            WakePayloadInvalidReason.TOO_LARGE,
        )
    }

    @Test
    fun rejectsMalformedOrNonAsciiUtf8() {
        assertInvalid(byteArrayOf(0xC3.toByte(), 0x28), WakePayloadInvalidReason.MALFORMED_UTF8)
        assertInvalid("é".repeat(22), WakePayloadInvalidReason.MALFORMED_UTF8)
        assertInvalid("😀".repeat(22), WakePayloadInvalidReason.MALFORMED_UTF8)
    }

    @Test
    fun rejectsStructuredOrHumanReadableContent() {
        listOf(
            "{\"wakeId\":\"abcdefghijklmnopqrstuv\"}",
            "session:abcdefghijklmnopqrstuv",
            "Pi finished successfully",
            "prompt file name result 123",
        ).forEach { payload ->
            assertInvalid(payload, WakePayloadInvalidReason.INVALID_WAKE_ID)
        }
    }

    @Test
    fun rejectsPaddingWhitespaceAndPunctuation() {
        listOf(
            "abcdefghijklmnopqrstu=",
            "abcdefghijklmnopqrstu+",
            "abcdefghijklmnopqrstu/",
            "abcdefghijklmnopqrstu ",
            "abcdefghijklmnopqrstu\n",
            "abcdefghijklmnopqrstu.",
            "abcdefghijklmnopqrstu:",
        ).forEach { payload ->
            assertInvalid(payload, WakePayloadInvalidReason.INVALID_WAKE_ID)
        }
    }

    private fun assertValid(value: String) {
        val result = OpaqueWakePayload.parse(value)
        assertThat(result).isInstanceOf(WakePayloadParseResult.Valid::class.java)
        assertThat((result as WakePayloadParseResult.Valid).wakeId.value).isEqualTo(value)
    }

    private fun assertInvalid(value: String, reason: WakePayloadInvalidReason) {
        val result = OpaqueWakePayload.parse(value)
        assertThat(result).isEqualTo(WakePayloadParseResult.Invalid(reason))
    }

    private fun assertInvalid(value: ByteArray, reason: WakePayloadInvalidReason) {
        val result = OpaqueWakePayload.parse(value)
        assertThat(result).isEqualTo(WakePayloadParseResult.Invalid(reason))
    }
}
