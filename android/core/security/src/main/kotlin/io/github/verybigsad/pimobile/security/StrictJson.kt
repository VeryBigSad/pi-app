package io.github.verybigsad.pimobile.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal object StrictJson {
    private val parser = Json {
        allowComments = false
        allowSpecialFloatingPointValues = false
        allowStructuredMapKeys = false
        allowTrailingComma = false
        coerceInputValues = false
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        useAlternativeNames = false
    }

    fun objectValue(
        value: String,
        maxBytes: Int,
        maxDepth: Int = 16,
        maxObjectMembers: Int = 128,
        maxArrayItems: Int = 128,
    ): JsonObject {
        require(value.length <= maxBytes)
        require(value.encodeToByteArray().size <= maxBytes)
        JsonGuard(value, maxDepth, maxObjectMembers, maxArrayItems).validate()
        return parser.parseToJsonElement(value) as? JsonObject
            ?: throw IllegalArgumentException("JSON root must be an object")
    }
}

private class JsonGuard(
    private val input: String,
    private val maxDepth: Int,
    private val maxObjectMembers: Int,
    private val maxArrayItems: Int,
) {
    private var offset = 0

    fun validate() {
        whitespace()
        value(0)
        whitespace()
        require(offset == input.length)
    }

    private fun value(depth: Int) {
        require(depth <= maxDepth)
        whitespace()
        require(offset < input.length)
        when (input[offset]) {
            '{' -> objectValue(depth + 1)
            '[' -> arrayValue(depth + 1)
            '"' -> stringValue()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            '-', in '0'..'9' -> numberValue()
            else -> throw IllegalArgumentException("invalid JSON")
        }
    }

    private fun objectValue(depth: Int) {
        offset++
        whitespace()
        if (consume('}')) return
        val keys = HashSet<String>()
        var count = 0
        while (true) {
            whitespace()
            require(offset < input.length && input[offset] == '"')
            val key = stringValue()
            require(keys.add(key)) { "duplicate JSON object key" }
            require(++count <= maxObjectMembers)
            whitespace()
            require(consume(':'))
            value(depth)
            whitespace()
            if (consume('}')) return
            require(consume(','))
        }
    }

    private fun arrayValue(depth: Int) {
        offset++
        whitespace()
        if (consume(']')) return
        var count = 0
        while (true) {
            require(++count <= maxArrayItems)
            value(depth)
            whitespace()
            if (consume(']')) return
            require(consume(','))
        }
    }

    private fun stringValue(): String {
        require(consume('"'))
        val result = StringBuilder()
        while (offset < input.length) {
            val character = input[offset++]
            when {
                character == '"' -> return result.toString()
                character == '\\' -> escapedCharacter(result)
                character.code < 0x20 -> throw IllegalArgumentException("invalid JSON string")
                character.isHighSurrogate() -> {
                    require(offset < input.length && input[offset].isLowSurrogate())
                    result.append(character)
                    result.append(input[offset++])
                }
                character.isLowSurrogate() -> throw IllegalArgumentException("invalid JSON surrogate")
                else -> result.append(character)
            }
        }
        throw IllegalArgumentException("unterminated JSON string")
    }

    private fun escapedCharacter(result: StringBuilder) {
        require(offset < input.length)
        when (val escaped = input[offset++]) {
            '"', '\\', '/' -> result.append(escaped)
            'b' -> result.append('\b')
            'f' -> result.append('\u000c')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> {
                val first = unicodeEscape()
                when {
                    first.isHighSurrogate() -> {
                        require(offset + 1 < input.length && input[offset] == '\\' && input[offset + 1] == 'u')
                        offset += 2
                        val second = unicodeEscape()
                        require(second.isLowSurrogate())
                        result.append(first)
                        result.append(second)
                    }
                    first.isLowSurrogate() -> throw IllegalArgumentException("invalid JSON surrogate")
                    else -> result.append(first)
                }
            }
            else -> throw IllegalArgumentException("invalid JSON escape")
        }
    }

    private fun unicodeEscape(): Char {
        require(offset + 4 <= input.length)
        val value = input.substring(offset, offset + 4).toIntOrNull(16)
            ?: throw IllegalArgumentException("invalid JSON unicode escape")
        offset += 4
        return value.toChar()
    }

    private fun numberValue() {
        consume('-')
        require(offset < input.length)
        if (consume('0')) {
            require(offset >= input.length || input[offset] !in '0'..'9')
        } else {
            require(input[offset] in '1'..'9')
            while (offset < input.length && input[offset] in '0'..'9') offset++
        }
        if (consume('.')) {
            require(offset < input.length && input[offset] in '0'..'9')
            while (offset < input.length && input[offset] in '0'..'9') offset++
        }
        if (offset < input.length && (input[offset] == 'e' || input[offset] == 'E')) {
            offset++
            if (offset < input.length && (input[offset] == '+' || input[offset] == '-')) offset++
            require(offset < input.length && input[offset] in '0'..'9')
            while (offset < input.length && input[offset] in '0'..'9') offset++
        }
    }

    private fun literal(expected: String) {
        require(input.regionMatches(offset, expected, 0, expected.length))
        offset += expected.length
    }

    private fun whitespace() {
        while (offset < input.length && input[offset] in charArrayOf(' ', '\t', '\n', '\r')) offset++
    }

    private fun consume(expected: Char): Boolean {
        if (offset >= input.length || input[offset] != expected) return false
        offset++
        return true
    }
}
