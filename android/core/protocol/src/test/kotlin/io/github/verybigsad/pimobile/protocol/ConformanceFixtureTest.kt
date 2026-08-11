package io.github.verybigsad.pimobile.protocol

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ConformanceFixtureTest {
    private val corpus = Json.parseToJsonElement(File(System.getProperty("pimb.fixtures")).readText()).jsonObject

    @Test
    fun streamOrderFaultsConsumeSharedFixtures() {
        corpus.getValue("streamOrderCases").jsonArray.forEach { element ->
            val fixture = element.jsonObject
            assertValidity(fixture.valid()) {
                val stream = ContiguousStream(
                    "550e8400-e29b-41d4-a716-446655440001",
                    fixture.getValue("limit").jsonPrimitive.content.toULong(),
                    fixture["expectedLength"]?.jsonPrimitive?.content?.toULong(),
                    fixture["expectedSha256"]?.jsonPrimitive?.content,
                )
                fixture.getValue("chunks").jsonArray.forEach { stream.accept(it.jsonObject.streamChunk()) }
                fixture["close"]?.jsonObject?.let { stream.close(it.getValue("length").jsonPrimitive.content.toULong(), it.getValue("sha256").jsonPrimitive.content) }
                fixture["afterClose"]?.jsonObject?.let { stream.accept(it.streamChunk()) }
            }
        }
    }

    @Test
    fun pairingCeremonyBindingsConsumeSharedFixtures() {
        corpus.getValue("pairingBindingCases").jsonArray.forEach { element ->
            val fixture = element.jsonObject
            assertValidity(fixture.valid()) {
                assertPairingBinding(fixture.getValue("expected").jsonObject.pairingBinding(), fixture.getValue("actual").jsonObject.pairingBinding())
            }
        }
    }

    @Test
    fun unlockCeremonyBindingsConsumeSharedFixtures() {
        corpus.getValue("unlockBindingCases").jsonArray.forEach { element ->
            val fixture = element.jsonObject
            assertValidity(fixture.valid()) {
                assertUnlockBinding(fixture.getValue("expected").jsonObject.unlockBinding(), fixture.getValue("actual").jsonObject.unlockBinding())
            }
        }
    }

    @Test
    fun pairingTokensConsumeSharedFixtures() {
        corpus.getValue("pairingTokenCases").jsonArray.forEach { element ->
            val fixture = element.jsonObject
            assertValidity(fixture.valid()) {
                assertPairingToken(fixture.getValue("pairingToken").jsonPrimitive.content, fixture.getValue("sessionBinding").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun approvalTuplesConsumeSharedFixtures() {
        corpus.getValue("approvalCases").jsonArray.forEach { element ->
            val fixture = element.jsonObject
            assertValidity(fixture.valid()) {
                assertApprovalBinding(fixture.getValue("expected").jsonObject.approvalBinding(), fixture.getValue("actual").jsonObject.approvalBinding())
            }
        }
    }

    @Test
    fun approvalExpiryAndSingleUseConsumeSharedFixtures() {
        corpus.getValue("approvalLifecycleCases").jsonArray.forEach { element ->
            val fixture = element.jsonObject
            val block = {
                val offer = ApprovalOffer(fixture.getValue("binding").jsonObject.approvalBinding(), fixture.getValue("expiresAtEpochMilliseconds").jsonPrimitive.content.toLong())
                fixture.getValue("decisions").jsonArray.forEach {
                    val decision = it.jsonObject
                    offer.decide(decision.getValue("binding").jsonObject.approvalBinding(), decision.getValue("nowEpochMilliseconds").jsonPrimitive.content.toLong())
                }
            }
            assertValidity(fixture.valid(), block)
            if (!fixture.valid()) {
                try {
                    block()
                    fail("Expected approval failure")
                } catch (error: ProtocolException) {
                    assertEquals(ProtocolErrorCode.valueOf(fixture.string("expectedError")), error.code)
                }
            }
        }
    }

    @Test
    fun terminalHistoryBoundsConsumeSharedFixtures() {
        corpus.getValue("terminalHistoryCases").jsonArray.forEach { element ->
            val fixture = element.jsonObject
            assertValidity(fixture.valid()) {
                assertTerminalHistory(
                    fixture.getValue("text").jsonPrimitive.content,
                    fixture.getValue("maxLines").jsonPrimitive.content.toInt(),
                    fixture.getValue("maxBytes").jsonPrimitive.content.toInt(),
                )
            }
        }
    }

    @Test
    fun promptImageReadyOwnershipHashAndOrphanCasesConsumeSharedFixtures() {
        corpus.getValue("promptImageCases").jsonArray.forEach { element ->
            val fixture = element.jsonObject
            val blob = fixture.getValue("blob").jsonObject
            val ref = fixture.getValue("ref").jsonObject
            assertValidity(fixture.valid()) {
                assertPromptImageRef(
                    ReadyBlob(
                        blob.string("blobId"), blob.string("ownerDeviceId"), blob.string("size"), blob.string("sha256"), blob.string("mimeType"),
                        blob.getValue("expiresAtEpochMilliseconds").jsonPrimitive.content.toLong(), blob.boolean("ready"), blob.boolean("referenced"),
                    ),
                    ImageRef(ref.string("blobId"), ref.string("size"), ref.string("sha256"), ref.string("mimeType")),
                    fixture.string("deviceId"),
                    fixture.getValue("nowEpochMilliseconds").jsonPrimitive.content.toLong(),
                )
            }
        }
    }

    @Test
    fun recoveryCursorsConsumeSharedDuplicateGapEpochAndLeafFixtures() {
        corpus.getValue("recoveryCursorCases").jsonArray.forEach { element ->
            val fixture = element.jsonObject
            val block = {
                val cursor = RecoveryCursor(fixture.string("streamEpoch"), fixture.string("sequence"))
                fixture.getValue("events").jsonArray.forEach { eventElement ->
                    val event = eventElement.jsonObject
                    val result = cursor.accept(event.string("streamEpoch"), event.string("sequence"))
                    event["result"]?.jsonPrimitive?.content?.let { assertEquals(it.uppercase(), result.name) }
                }
                val leaf = fixture["leafId"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                assertEquals(leaf, cursor.snapshot(leaf).leafId)
            }
            assertValidity(fixture.valid(), block)
            if (!fixture.valid()) {
                try {
                    block()
                    fail("Expected cursor failure")
                } catch (error: ProtocolException) {
                    assertEquals(ProtocolErrorCode.valueOf(fixture.string("expectedError")), error.code)
                }
            }
        }
    }

    @Test
    fun snapshotAdjunctValidationAndPostFenceCursorsConsumeSharedFixtures() {
        corpus.getValue("snapshotRecoveryCases").jsonArray.forEach { element ->
            val fixture = element.jsonObject
            val block = {
                val leafId = fixture["leafId"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                val lastAppendId = fixture["lastAppendId"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                val attempt = SnapshotAttempt(fixture.string("sessionId"), fixture.string("streamEpoch"), fixture.string("frozenSequence"), leafId, lastAppendId)
                fixture["adjunct"]?.jsonObject?.let { attempt.acceptAdjunct(it.string("streamEpoch"), it.string("sequence")) }
                fixture["validation"]?.jsonObject?.let {
                    val validationLeaf = it["leafId"]?.takeUnless { value -> value is JsonNull }?.jsonPrimitive?.content
                    attempt.validate(it.getValue("newAppendEntries").jsonPrimitive.content.toInt(), validationLeaf)
                }
                if (fixture.boolean("publish")) attempt.publish()
                fixture.getValue("postFence").jsonArray.forEach {
                    val event = it.jsonObject
                    val result = attempt.acceptPostFence(event.string("streamEpoch"), event.string("sequence"))
                    event["result"]?.jsonPrimitive?.content?.let { expected -> assertEquals(expected.uppercase(), result.name) }
                }
            }
            assertValidity(fixture.valid(), block)
            if (!fixture.valid()) {
                try {
                    block()
                    fail("Expected snapshot failure")
                } catch (error: ProtocolException) {
                    assertEquals(ProtocolErrorCode.valueOf(fixture.string("expectedError")), error.code)
                }
            }
        }
    }

    @Test
    fun wireMessagesConsumeSharedFixtures() {
        corpus.getValue("wireMessageCases").jsonArray.forEach { element ->
            val fixture = element.jsonObject
            val block = { assertWireMessage(fixture.string("type"), fixture.getValue("body").jsonObject) }
            assertValidity(fixture.valid(), block)
            if (!fixture.valid() && fixture["expectedError"] != null) {
                try {
                    block()
                    fail("Expected wire message failure")
                } catch (error: ProtocolException) {
                    assertEquals(ProtocolErrorCode.valueOf(fixture.string("expectedError")), error.code)
                }
            }
        }
    }

    @Test
    fun authoritativeMessageEndAndOrderFaultsConsumeSharedFixtures() {
        corpus.getValue("assistantCases").jsonArray.forEach { element ->
            val fixture = element.jsonObject
            val assembler = AssistantMessageAssembler()
            assertValidity(fixture.valid()) {
                fixture.getValue("records").jsonArray.forEach { recordElement ->
                    val record = recordElement.jsonObject
                    if (record.string("type") == "message_end" && fixture["provisionalBeforeEnd"] != null) {
                        assertEquals(fixture.getValue("provisionalBeforeEnd").jsonArray.toList(), assembler.provisional())
                    }
                    assembler.apply(record)
                }
                assertEquals(fixture["committed"]?.jsonObject, assembler.committed())
            }
            if (!fixture.valid()) assertEquals(fixture.boolean("recovery"), assembler.needsRecovery())
        }
    }

    private fun ContiguousStream.accept(chunk: StreamChunk) = accept(chunk.streamId, chunk.sequence, chunk.offset, chunk.data)

    private fun JsonObject.streamChunk() = StreamChunk(
        string("streamId"),
        getValue("sequence").jsonPrimitive.content.toLong(),
        string("offset").toULong(),
        hexToBytes(string("dataHex")),
    )

    private fun JsonObject.pairingBinding() = PairingBinding(
        string("ceremonyKind"), string("invitationId"), string("sessionBinding"), string("csrSha256"),
        string("rpId"), string("origin"), string("challenge"), string("expiresAt"),
    )

    private fun JsonObject.unlockBinding() = UnlockBinding(
        string("ceremonyKind"), string("deviceId"), string("rpId"), string("origin"), string("challenge"), string("expiresAt"),
    )

    private fun JsonObject.approvalBinding() = ApprovalBinding(string("offerId"), string("operationId"), string("argumentHash"))
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.boolean(key: String) = getValue(key).jsonPrimitive.content.toBoolean()
    private fun JsonObject.valid() = boolean("valid")

    private fun assertValidity(valid: Boolean, block: () -> Unit) {
        if (valid) {
            block()
            return
        }
        try {
            block()
            fail("Expected ProtocolException")
        } catch (_: ProtocolException) {
        }
    }

    private fun hexToBytes(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private data class StreamChunk(val streamId: String, val sequence: Long, val offset: ULong, val data: ByteArray)
}
