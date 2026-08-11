package io.github.verybigsad.pimobile.network

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal data class JsonBounds(
    val maxBytes: Int,
    val maxDepth: Int = 12,
    val maxNodes: Int = 256,
    val maxStringChars: Int = 2_048,
)

internal fun requireKeys(value: JsonObject, expected: Set<String>, error: NetworkError) {
    if (value.keys != expected) throw NetworkException(error, "JSON fields do not match the required profile")
}

internal fun JsonObject.stringValue(name: String, error: NetworkError): String {
    val primitive = this[name] as? JsonPrimitive
    if (primitive == null || !primitive.isString) throw NetworkException(error, "$name is invalid")
    return primitive.content
}

internal fun JsonObject.intValue(name: String, error: NetworkError): Int {
    val primitive = this[name] as? JsonPrimitive
    return primitive?.intOrNull ?: throw NetworkException(error, "$name is invalid")
}

internal fun JsonObject.objectValue(name: String, error: NetworkError): JsonObject = this[name] as? JsonObject
    ?: throw NetworkException(error, "$name is invalid")

internal fun JsonObject.arrayValue(name: String, error: NetworkError): JsonArray = this[name] as? JsonArray
    ?: throw NetworkException(error, "$name is invalid")

internal fun JsonObject.opaqueId(name: String, error: NetworkError): String = stringValue(name, error).also {
    if (!opaqueIdPattern.matches(it)) throw NetworkException(error, "$name is invalid")
}

internal object StrictJson {
    fun parseObject(bytes: ByteArray, bounds: JsonBounds): JsonObject {
        if (bytes.isEmpty() || bytes.size > bounds.maxBytes) {
            throw NetworkException(NetworkError.MALFORMED_JSON, "JSON size is outside its bound")
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            throw NetworkException(NetworkError.MALFORMED_JSON, "JSON is not valid UTF-8")
        }
        return Parser(text, bounds).parseObjectDocument()
    }

    fun canonicalize(value: JsonElement): ByteArray = canonical(value).encodeToByteArray()

    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.keys.sorted().joinToString(",", "{", "}") { key ->
            "${quote(key)}:${canonical(value.getValue(key))}"
        }
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        is JsonPrimitive -> when {
            value is JsonNull -> "null"
            value.isString -> quote(value.content)
            else -> value.content
        }
    }

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    private class Parser(
        private val text: String,
        private val bounds: JsonBounds,
    ) {
        private var index = 0
        private var nodes = 0

        fun parseObjectDocument(): JsonObject {
            skipWhitespace()
            val value = parseValue(0)
            skipWhitespace()
            if (index != text.length || value !is JsonObject) malformed()
            return value
        }

        private fun parseValue(depth: Int): JsonElement {
            if (depth > bounds.maxDepth || ++nodes > bounds.maxNodes || index >= text.length) malformed()
            return when (text[index]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> JsonPrimitive(parseString())
                't' -> parseLiteral("true", JsonPrimitive(true))
                'f' -> parseLiteral("false", JsonPrimitive(false))
                'n' -> parseLiteral("null", JsonNull)
                '-', in '0'..'9' -> parseInteger()
                else -> malformed()
            }
        }

        private fun parseObject(depth: Int): JsonObject {
            index += 1
            skipWhitespace()
            val values = linkedMapOf<String, JsonElement>()
            if (take('}')) return JsonObject(values)
            while (true) {
                if (index >= text.length || text[index] != '"') malformed()
                val key = parseString()
                if (values.containsKey(key)) malformed()
                skipWhitespace()
                requireChar(':')
                skipWhitespace()
                values[key] = parseValue(depth + 1)
                skipWhitespace()
                if (take('}')) return JsonObject(values)
                requireChar(',')
                skipWhitespace()
            }
        }

        private fun parseArray(depth: Int): JsonArray {
            index += 1
            skipWhitespace()
            val values = mutableListOf<JsonElement>()
            if (take(']')) return JsonArray(values)
            while (true) {
                values += parseValue(depth + 1)
                skipWhitespace()
                if (take(']')) return JsonArray(values)
                requireChar(',')
                skipWhitespace()
            }
        }

        private fun parseString(): String {
            requireChar('"')
            val result = StringBuilder()
            while (index < text.length) {
                val character = text[index++]
                when {
                    character == '"' -> {
                        if (result.length > bounds.maxStringChars) malformed()
                        return result.toString()
                    }
                    character == '\\' -> parseEscape(result)
                    character.code < 0x20 || character.code in 0xdc00..0xdfff -> malformed()
                    character.code in 0xd800..0xdbff -> {
                        if (index >= text.length || text[index].code !in 0xdc00..0xdfff) malformed()
                        result.append(character).append(text[index++])
                    }
                    else -> result.append(character)
                }
                if (result.length > bounds.maxStringChars) malformed()
            }
            return malformed()
        }

        private fun parseEscape(result: StringBuilder) {
            if (index >= text.length) malformed()
            when (text[index++]) {
                '"' -> result.append('"')
                '\\' -> result.append('\\')
                '/' -> result.append('/')
                'b' -> result.append('\b')
                'f' -> result.append('\u000c')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    val first = parseHexCodeUnit()
                    if (first in 0xdc00..0xdfff) malformed()
                    if (first in 0xd800..0xdbff) {
                        if (index + 2 > text.length || text[index] != '\\' || text[index + 1] != 'u') malformed()
                        index += 2
                        val second = parseHexCodeUnit()
                        if (second !in 0xdc00..0xdfff) malformed()
                        result.append(first.toChar()).append(second.toChar())
                    } else {
                        result.append(first.toChar())
                    }
                }
                else -> malformed()
            }
        }

        private fun parseHexCodeUnit(): Int {
            if (index + 4 > text.length) malformed()
            var value = 0
            repeat(4) {
                val digit = text[index++].digitToIntOrNull(16) ?: malformed()
                value = value * 16 + digit
            }
            return value
        }

        private fun parseInteger(): JsonPrimitive {
            val start = index
            if (take('-') && index >= text.length) malformed()
            if (take('0')) {
                if (index < text.length && text[index].isDigit()) malformed()
            } else {
                if (index >= text.length || text[index] !in '1'..'9') malformed()
                while (index < text.length && text[index].isDigit()) index += 1
            }
            if (index < text.length && (text[index] == '.' || text[index] == 'e' || text[index] == 'E')) malformed()
            val value = text.substring(start, index).toLongOrNull() ?: malformed()
            if (value !in -9_007_199_254_740_991L..9_007_199_254_740_991L) malformed()
            return JsonPrimitive(value)
        }

        private fun parseLiteral(literal: String, value: JsonElement): JsonElement {
            if (!text.regionMatches(index, literal, 0, literal.length)) malformed()
            index += literal.length
            return value
        }

        private fun requireChar(expected: Char) {
            if (!take(expected)) malformed()
        }

        private fun take(expected: Char): Boolean {
            if (index >= text.length || text[index] != expected) return false
            index += 1
            return true
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index] in charArrayOf(' ', '\t', '\n', '\r')) index += 1
        }

        private fun malformed(): Nothing = throw NetworkException(NetworkError.MALFORMED_JSON, "JSON does not match the strict bounded profile")
    }
}
