package io.github.verybigsad.pimobile.wire

import io.github.verybigsad.pimobile.model.EventCursor
import io.github.verybigsad.pimobile.model.LeafId
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.StreamEpoch
import io.github.verybigsad.pimobile.model.Uint64Decimal
import io.github.verybigsad.pimobile.protocol.canonicalizeJson
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Minimal typed codec for the protocol v1 JSON envelope and the message bodies the app
 * consumes. Adapter gap: core/protocol does not yet expose typed body encoders/decoders
 * (integration plan §2.4); shapes here follow protocol/schema/messages.schema.json and are
 * validated against protocol fixtures in unit tests. Unknown fields are retained in the
 * parsed envelope body and never executed.
 */
object WireMessages {
    private val json = Json

    data class Envelope(
        val type: String,
        val messageId: String,
        val replyTo: String?,
        val body: JsonObject,
        val raw: ByteArray,
    )

    fun encode(type: String, body: JsonObject, replyTo: String? = null): ByteArray {
        val envelope = buildJsonObject {
            put("v", buildJsonObject {
                put("major", 1)
                put("minor", 0)
            })
            put("type", type)
            put("messageId", UUID.randomUUID().toString())
            if (replyTo == null) {
                put("replyTo", JsonPrimitive(null))
            } else {
                put("replyTo", replyTo)
            }
            put("body", body)
        }
        return canonicalizeJson(envelope).encodeToByteArray()
    }

    fun parseEnvelope(payload: ByteArray): Envelope? {
        val root = runCatching { json.parseToJsonElement(payload.decodeToString()) }.getOrNull() as? JsonObject
            ?: return null
        val type = root.string("type") ?: return null
        val messageId = root.string("messageId") ?: return null
        val replyTo = (root["replyTo"] as? JsonPrimitive)?.contentOrNull
        val body = root["body"] as? JsonObject ?: return null
        return Envelope(type, messageId, replyTo, body, payload)
    }

    fun JsonObject.string(field: String): String? =
        (this[field] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun JsonObject.boolean(field: String): Boolean? = (this[field] as? JsonPrimitive)?.booleanOrNull

    fun JsonObject.integer(field: String): Int? = (this[field] as? JsonPrimitive)?.intOrNull

    fun cursorFrom(body: JsonObject, prefix: String = ""): EventCursor? {
        val sessionId = body.string("${prefix}sessionId") ?: return null
        val epoch = body.string("${prefix}streamEpoch") ?: return null
        val sequence = body.string("${prefix}sequence") ?: return null
        if (!Uint64Decimal.isCanonical(sequence)) return null
        val leaf = body.string("${prefix}leafId")
        return EventCursor(
            streamEpoch = StreamEpoch(epoch),
            sequence = Uint64Decimal(sequence),
            leafId = leaf?.let(::LeafId),
        )
    }

    fun cursorJson(sessionId: SessionId, cursor: EventCursor?): JsonObject = buildJsonObject {
        put("sessionId", sessionId.value)
        put("streamEpoch", cursor?.streamEpoch?.value ?: "00000000-0000-4000-8000-000000000000")
        put("sequence", cursor?.sequence?.text ?: "0")
        put("leafId", cursor?.leafId?.value?.let(::JsonPrimitive) ?: JsonPrimitive(null))
    }

    fun clientHello(deviceId: String, appVersion: String, features: List<String>): JsonObject = buildJsonObject {
        put("minMinor", 0)
        put("maxMinor", 0)
        put("appVersion", appVersion)
        put("deviceId", deviceId)
        put("features", kotlinx.serialization.json.JsonArray(features.map(::JsonPrimitive)))
    }

    fun syncResume(cursors: List<Pair<SessionId, EventCursor?>>): JsonObject = buildJsonObject {
        put("cursors", kotlinx.serialization.json.JsonArray(cursors.map { (session, cursor) -> cursorJson(session, cursor) }))
    }

    fun eventAck(sessionId: SessionId, cursor: EventCursor): JsonObject = buildJsonObject {
        put("cursor", cursorJson(sessionId, cursor))
        put("sessionId", sessionId.value)
        put("streamEpoch", cursor.streamEpoch.value)
        put("sequence", cursor.sequence.text)
        put("leafId", cursor.leafId?.value?.let(::JsonPrimitive) ?: JsonPrimitive(null))
    }

    fun assertionResponse(ceremonyId: String, binding: JsonObject, credentialJson: String): JsonObject {
        val credential = runCatching { json.parseToJsonElement(credentialJson) }.getOrNull() as? JsonObject
            ?: throw IllegalArgumentException("passkey credential response is not a JSON object")
        return buildJsonObject {
            put("ceremonyId", ceremonyId)
            put("binding", binding)
            put("credential", credential)
        }
    }

    fun registrationResponse(ceremonyId: String, binding: JsonObject, credentialJson: String): JsonObject =
        assertionResponse(ceremonyId, binding, credentialJson)

    fun pairBegin(invitationId: String, deviceRouteKeyId: String, deviceRoutePublicKey: String, csrSha256: String): JsonObject =
        buildJsonObject {
            put("invitationId", invitationId)
            put("deviceRouteKeyId", deviceRouteKeyId)
            put("deviceRoutePublicKey", deviceRoutePublicKey)
            put("csrSha256", csrSha256)
        }

    fun pairCsr(invitationId: String, csrSha256: String, csrDerBase64Url: String): JsonObject = buildJsonObject {
        put("invitationId", invitationId)
        put("csrSha256", csrSha256)
        put("csrDer", csrDerBase64Url)
    }

    fun pairConfirm(invitationId: String): JsonObject = buildJsonObject {
        put("invitationId", invitationId)
        put("status", "waiting")
        put("transcriptHash", "0".repeat(64))
    }

    fun commandSubmit(commandId: String, sessionId: SessionId, operation: String, payload: JsonObject, payloadHash: String): JsonObject =
        buildJsonObject {
            put("commandId", commandId)
            put("sessionId", sessionId.value)
            put("operation", operation)
            put("payload", payload)
            put("payloadHash", payloadHash)
        }

    fun approvalDecision(offerId: String, operationId: String, argumentHash: String, allow: Boolean): JsonObject =
        buildJsonObject {
            put("offerId", offerId)
            put("operationId", operationId)
            put("argumentHash", argumentHash)
            put("decision", if (allow) "allow_once" else "deny")
        }

    fun pushEndpoint(endpointId: String, distributor: String, endpoint: String, wakePublicKey: String): JsonObject =
        buildJsonObject {
            put("endpointId", endpointId)
            put("distributor", distributor)
            put("endpoint", endpoint)
            put("wakePublicKey", wakePublicKey)
        }

    fun pushEndpointRevoke(endpointId: String): JsonObject = buildJsonObject {
        put("endpointId", endpointId)
    }

    fun voiceStart(streamId: String): JsonObject = buildJsonObject {
        put("streamId", streamId)
        put("sampleRate", 16_000)
        put("channels", 1)
        put("sampleFormat", "s16le")
    }

    fun streamControl(type: String, streamId: String): JsonObject = buildJsonObject {
        put("streamId", streamId)
    }

    fun terminalOpen(sessionId: SessionId, columns: Int, rows: Int): JsonObject = buildJsonObject {
        put("sessionId", sessionId.value)
        put("columns", columns)
        put("rows", rows)
    }

    fun sessionRef(sessionId: SessionId): JsonObject = buildJsonObject {
        put("sessionId", sessionId.value)
    }

    fun terminalResize(sessionId: SessionId, columns: Int, rows: Int): JsonObject = buildJsonObject {
        put("sessionId", sessionId.value)
        put("columns", columns)
        put("rows", rows)
    }

    /**
     * terminal.history.request body (protocol v1): read-only tmux capture bounded to
     * 5,000 lines / 1 MiB. Adapter note: the request carries no sessionId; it is scoped by
     * the active terminal generation of this connection.
     */
    fun terminalHistoryRequest(terminalGeneration: ULong, maxLines: Int, maxBytes: Int): JsonObject {
        require(maxLines in 1..5_000)
        require(maxBytes in 1..1_048_576)
        return buildJsonObject {
            put("terminalGeneration", terminalGeneration.toString())
            put("maxLines", maxLines)
            put("maxBytes", maxBytes)
        }
    }

    fun foregroundPing(foregroundLease: Boolean): JsonObject = buildJsonObject {
        put("foregroundLease", foregroundLease)
    }
}
