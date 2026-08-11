package io.github.verybigsad.pimobile.security

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

internal fun JsonObject.requireExactKeys(required: Set<String>, optional: Set<String> = emptySet()) {
    require(keys.containsAll(required))
    require(keys.all { it in required || it in optional })
}

internal fun JsonObject.requireObject(name: String): JsonObject = this[name] as? JsonObject
    ?: throw IllegalArgumentException("$name must be an object")

internal fun JsonObject.requireArray(name: String): JsonArray = this[name] as? JsonArray
    ?: throw IllegalArgumentException("$name must be an array")

internal fun JsonObject.requireString(name: String, maxBytes: Int): String {
    val primitive = this[name] as? JsonPrimitive
        ?: throw IllegalArgumentException("$name must be a string")
    require(primitive.isString)
    return primitive.content.also {
        require(it.length in 1..maxBytes)
        require(it.encodeToByteArray().size <= maxBytes)
    }
}

internal fun JsonObject.requireInt(name: String): Int {
    val primitive = this[name] as? JsonPrimitive
        ?: throw IllegalArgumentException("$name must be an integer")
    require(!primitive.isString)
    return primitive.intOrNull ?: throw IllegalArgumentException("$name must be an integer")
}

internal fun JsonObject.requireBoolean(name: String): Boolean {
    val primitive = this[name] as? JsonPrimitive
        ?: throw IllegalArgumentException("$name must be a boolean")
    require(!primitive.isString)
    return primitive.booleanOrNull ?: throw IllegalArgumentException("$name must be a boolean")
}

internal fun JsonElement.requireObjectValue(name: String): JsonObject = this as? JsonObject
    ?: throw IllegalArgumentException("$name must be an object")
