package io.github.verybigsad.pimobile.model

@JvmInline
value class SessionId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class MessageId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class MacId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class StreamEpoch(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class SettlementId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class AppendId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class LeafId(val value: String) {
    init {
        require(LEAF_PATTERN.matches(value))
    }

    private companion object {
        val LEAF_PATTERN = Regex("^[0-9a-f]{8}$")
    }
}

/**
 * Protocol unsigned 64-bit integer in canonical decimal text form (docs/protocol-v1.md:
 * uint64 values are encoded and stored as decimal text, never Kotlin Long or SQLite INTEGER).
 * Canonical means digits only, no leading zeros, and a value in [0, 18446744073709551615].
 */
@JvmInline
value class Uint64Decimal(val text: String) : Comparable<Uint64Decimal> {
    init {
        require(isCanonical(text)) { "Not a canonical uint64 decimal: $text" }
    }

    /** Successor value, or null when this value is [MAX] (uint64 overflow). */
    fun incremented(): Uint64Decimal? =
        if (this == MAX) null else Uint64Decimal((toULong() + 1u).toString())

    fun toULong(): ULong = text.toULong()

    override fun compareTo(other: Uint64Decimal): Int =
        if (text.length != other.text.length) {
            text.length.compareTo(other.text.length)
        } else {
            text.compareTo(other.text)
        }

    override fun toString(): String = text

    companion object {
        const val MAX_TEXT = "18446744073709551615"

        private val DIGITS = Regex("^[0-9]+$")

        val ZERO = Uint64Decimal("0")
        val MAX = Uint64Decimal(MAX_TEXT)

        fun isCanonical(text: String): Boolean =
            DIGITS.matches(text) &&
                (text == "0" || !text.startsWith("0")) &&
                (text.length < MAX_TEXT.length ||
                    (text.length == MAX_TEXT.length && text <= MAX_TEXT))

        fun of(value: ULong): Uint64Decimal = Uint64Decimal(value.toString())

        fun of(value: Long): Uint64Decimal {
            require(value >= 0) { "Negative value is not uint64: $value" }
            return of(value.toULong())
        }
    }
}

data class EventCursor(
    val streamEpoch: StreamEpoch,
    val sequence: Uint64Decimal,
    val leafId: LeafId?,
) {
    constructor(streamEpoch: StreamEpoch, sequence: Long, leafId: LeafId?) :
        this(streamEpoch, Uint64Decimal.of(sequence), leafId)
}
