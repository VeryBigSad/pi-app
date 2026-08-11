package io.github.verybigsad.pimobile.storage

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test

class CanonicalUint64Test {
    @Test
    fun canonicalBoundariesAreAccepted() {
        assertThat(isCanonicalUint64Decimal("0")).isTrue()
        assertThat(isCanonicalUint64Decimal("1")).isTrue()
        assertThat(isCanonicalUint64Decimal("9223372036854775807")).isTrue() // 2^63 - 1
        assertThat(isCanonicalUint64Decimal("9223372036854775808")).isTrue() // 2^63
        assertThat(isCanonicalUint64Decimal("18446744073709551615")).isTrue() // 2^64 - 1
    }

    @Test
    fun nonCanonicalValuesAreRejected() {
        val invalid = listOf(
            "",
            "00",
            "01",
            " 1",
            "1 ",
            "+1",
            "-1",
            "1.0",
            "0xFF",
            "1e6",
            "١٢٣",
            "18446744073709551616", // 2^64
            "99999999999999999999",
            "184467440737095516159",
        )
        invalid.forEach { value ->
            assertWithMessage(value).that(isCanonicalUint64Decimal(value)).isFalse()
        }
    }

    @Test
    fun comparisonIsNumeric() {
        val ordered = listOf("0", "2", "10", "9223372036854775807", "9223372036854775808", "18446744073709551615")
        ordered.zipWithNext().forEach { (left, right) ->
            assertWithMessage("$left < $right").that(compareCanonicalUint64(left, right)).isLessThan(0)
            assertThat(compareCanonicalUint64(right, left)).isGreaterThan(0)
            assertThat(compareCanonicalUint64(left, left)).isEqualTo(0)
        }
    }

    @Test
    fun cursorRejectsSignedOverflowButAcceptsUint64Max() {
        CanonicalAppendCursor("epoch", "18446744073709551615", "deadbeef", null)
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalAppendCursor("epoch", "18446744073709551616", "deadbeef", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalAppendCursor("epoch", "-1", null, null)
        }
    }

    @Test
    fun authoritativeFinalMetadataRecomputesAndVerifiesRawSha256() {
        val raw = """{"type":"assistant","final":true}"""
        val valid = AuthoritativeFinalMetadata(
            source = FinalMetadataSource.AUTHORITATIVE,
            rawJson = raw,
            rawRef = null,
            rawSizeBytes = raw.encodeToByteArray().size.toLong(),
            rawSha256 = sha256Hex(raw),
            projectionJson = """{"type":"assistant"}""",
            signature = null,
            redacted = false,
            createdAtEpochMs = 1,
            finalizedAtEpochMs = 1,
        )
        assertThat(valid.rawSha256).isEqualTo(sha256Hex(raw))

        // A syntactically valid but wrong digest must not pass.
        val forged = "0".repeat(64)
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(rawSha256 = forged)
        }
        // Tampered payload with the original digest must not pass either.
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(
                rawJson = """{"type":"user"}""",
                rawSizeBytes = 16,
            )
        }
        // Referenced raw payloads cannot be recomputed; only the format is enforced.
        valid.copy(rawJson = null, rawRef = "blob/ref", rawSizeBytes = 0)
    }
}
