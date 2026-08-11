package io.github.verybigsad.pimobile.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test

class Uint64DecimalTest {
    @Test
    fun acceptsCanonicalBoundaryValues() {
        assertThat(Uint64Decimal("0").text).isEqualTo("0")
        assertThat(Uint64Decimal("42").text).isEqualTo("42")
        assertThat(Uint64Decimal("9223372036854775807").toULong()).isEqualTo(Long.MAX_VALUE.toULong())
        assertThat(Uint64Decimal("9223372036854775808").toULong()).isEqualTo(ULong.MIN_VALUE + (1uL shl 63))
        assertThat(Uint64Decimal("18446744073709551615").toULong()).isEqualTo(ULong.MAX_VALUE)
    }

    @Test
    fun rejectsNonCanonicalText() {
        val invalid = listOf(
            "",
            "00",
            "01",
            "007",
            "+1",
            "-1",
            " 1",
            "1 ",
            "1.0",
            "0x10",
            "1e3",
            "18446744073709551616", // 2^64
            "99999999999999999999",
            "184467440737095516150",
        )
        invalid.forEach { text ->
            assertWithMessage(text).that(Uint64Decimal.isCanonical(text)).isFalse()
            assertThrows(IllegalArgumentException::class.java) { Uint64Decimal(text) }
        }
    }

    @Test
    fun orderingIsNumericAcrossLengthBoundary() {
        assertThat(Uint64Decimal("9") < Uint64Decimal("10")).isTrue()
        assertThat(Uint64Decimal("9223372036854775807") < Uint64Decimal("9223372036854775808")).isTrue()
        assertThat(Uint64Decimal("18446744073709551615") > Uint64Decimal("18446744073709551614")).isTrue()
        assertThat(Uint64Decimal.ZERO).isEqualTo(Uint64Decimal("0"))
        assertThat(Uint64Decimal.MAX.compareTo(Uint64Decimal.ZERO)).isGreaterThan(0)
    }

    @Test
    fun incrementedAdvancesAndOverflowsAtMax() {
        assertThat(Uint64Decimal.ZERO.incremented()).isEqualTo(Uint64Decimal("1"))
        assertThat(Uint64Decimal("9223372036854775807").incremented())
            .isEqualTo(Uint64Decimal("9223372036854775808"))
        assertThat(Uint64Decimal.MAX.incremented()).isNull()
    }

    @Test
    fun longFactoryRejectsNegativeValues() {
        assertThat(Uint64Decimal.of(0L)).isEqualTo(Uint64Decimal.ZERO)
        assertThat(Uint64Decimal.of(Long.MAX_VALUE).text).isEqualTo("9223372036854775807")
        assertThrows(IllegalArgumentException::class.java) { Uint64Decimal.of(-1L) }
    }

    @Test
    fun eventCursorExposesCanonicalDecimalSequence() {
        val cursor = EventCursor(StreamEpoch("epoch"), Uint64Decimal("18446744073709551615"), LeafId("deadbeef"))
        assertThat(cursor.sequence.text).isEqualTo("18446744073709551615")
        val legacy = EventCursor(StreamEpoch("epoch"), 42L, null)
        assertThat(legacy.sequence).isEqualTo(Uint64Decimal("42"))
        assertThrows(IllegalArgumentException::class.java) {
            EventCursor(StreamEpoch("epoch"), -1L, null)
        }
    }
}
