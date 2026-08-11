package io.github.verybigsad.pimobile.protocol

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val strictJson = Json {
    isLenient = false
    ignoreUnknownKeys = false
}

fun decodeUtf8Strict(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    protocolViolation("Payload is not valid UTF-8")
}

fun parseJsonObject(text: String): JsonObject {
    val result = try {
        strictJson.parseToJsonElement(text)
    } catch (_: Exception) {
        protocolViolation("Payload is not valid JSON")
    }
    assertJsonValue(result)
    return result as? JsonObject ?: protocolViolation("JSON payload must be an object")
}

fun assertJsonValue(value: JsonElement) {
    when (value) {
        is JsonObject -> value.forEach { (key, child) ->
            assertUnicodeScalars(key)
            assertJsonValue(child)
        }
        is JsonArray -> value.forEach(::assertJsonValue)
        is JsonPrimitive -> {
            if (value !is JsonNull && !value.isString && value.content != "true" && value.content != "false") {
                val number = value.content.toDoubleOrNull()
                if (number == null || !number.isFinite()) protocolViolation("JSON number must be finite")
            }
            assertUnicodeScalars(value.content)
        }
    }
}

fun assertUnicodeScalars(value: String) {
    var index = 0
    while (index < value.length) {
        val code = value[index].code
        if (code !in 0xd800..0xdfff) {
            index += 1
            continue
        }
        if (code > 0xdbff || index + 1 >= value.length || value[index + 1].code !in 0xdc00..0xdfff) {
            protocolViolation("JSON contains an unpaired surrogate")
        }
        index += 2
    }
}
