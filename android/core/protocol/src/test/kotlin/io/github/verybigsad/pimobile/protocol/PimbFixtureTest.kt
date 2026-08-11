package io.github.verybigsad.pimobile.protocol

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PimbFixtureTest {
    private val corpus = Json.parseToJsonElement(File(System.getProperty("pimb.fixtures")).readText()).jsonObject

    @Test
    fun framesConsumeSharedFragmentationCoalescingAndMalformedFixtures() {
        corpus.getValue("frames").jsonArray.forEach { element ->
            val fixture = element.jsonObject
            val chunks = fixture.getValue("chunksHex").jsonArray.map { hexToBytes(it.jsonPrimitive.content) }
            val expectedError = fixture["expectedError"]?.jsonPrimitive?.content
            if (expectedError != null) {
                val errorStage = fixture.getValue("errorStage").jsonPrimitive.content
                assertProtocolException(ProtocolErrorCode.valueOf(expectedError)) {
                    when (errorStage) {
                        "json" -> PimbCodec.decodeJsonPayload(decodeChunks(chunks).single().payload)
                        "finish" -> decodeChunks(chunks, finish = true)
                        else -> decodeChunks(chunks)
                    }
                }
                return@forEach
            }
            val frames = decodeChunks(chunks)
            val expectedFrames = fixture.getValue("expected").jsonArray
            assertEquals(expectedFrames.size, frames.size)
            expectedFrames.forEachIndexed { index, expectedElement ->
                val expected = expectedElement.jsonObject
                val frame = frames[index]
                assertEquals(expected.getValue("kind").jsonPrimitive.content, frame.kind.fixtureName())
                when (frame.kind) {
                    FrameKind.Json -> assertEquals(expected.getValue("envelope").jsonObject, PimbCodec.decodeJsonPayload(frame.payload))
                    FrameKind.BlobChunk, FrameKind.AudioPcm -> {
                        val stream = PimbCodec.decodeStreamPayload(frame.payload)
                        assertEquals(expected.getValue("streamId").jsonPrimitive.content, stream.streamId)
                        assertEquals(expected.getValue("sequence").jsonPrimitive.content.toLong(), stream.sequence)
                        assertEquals(expected.getValue("offset").jsonPrimitive.content.toULong(), stream.offset)
                        assertEquals(expected.getValue("dataHex").jsonPrimitive.content, stream.data.hex())
                    }
                    FrameKind.TerminalBytes -> {
                        val terminal = PimbCodec.decodeTerminalPayload(frame.payload)
                        assertEquals(expected.getValue("terminalGeneration").jsonPrimitive.content.toULong(), terminal.terminalGeneration)
                        assertEquals(expected.getValue("sequence").jsonPrimitive.content.toULong(), terminal.sequence)
                        assertEquals(expected.getValue("dataHex").jsonPrimitive.content, terminal.data.hex())
                    }
                }
            }
        }
    }

    @Test
    fun hardBoundsAreIdenticalAndEnforcedAtBoundary() {
        val bounds = corpus.getValue("hardBounds").jsonObject.mapValues { it.value.jsonPrimitive.content.toLong() }
        assertEquals(ProtocolConstants.hardBounds, bounds)
        assertEquals(
            ProtocolConstants.headerBytes + ProtocolConstants.maxJsonPayloadBytes,
            PimbCodec.encode(FrameKind.Json, ByteArray(ProtocolConstants.maxJsonPayloadBytes)).size,
        )
        assertProtocolException(ProtocolErrorCode.FRAME_TOO_LARGE) {
            PimbCodec.encode(FrameKind.Json, ByteArray(ProtocolConstants.maxJsonPayloadBytes + 1))
        }
        val stream = PimbCodec.encodeStreamPayload(StreamPayload("550e8400-e29b-41d4-a716-446655440001", 0, 0uL, ByteArray(ProtocolConstants.maxBinaryDataBytes)))
        assertEquals(ProtocolConstants.streamPrefixBytes + ProtocolConstants.maxBinaryDataBytes, stream.size)
        assertProtocolException(ProtocolErrorCode.PROTOCOL_VIOLATION) {
            PimbCodec.encodeStreamPayload(StreamPayload("550e8400-e29b-41d4-a716-446655440001", 0, 0uL, ByteArray(ProtocolConstants.maxBinaryDataBytes + 1)))
        }
    }

    @Test
    fun jcsNumberCasesConsumeSharedFixtures() {
        corpus.getValue("jcsNumberCases").jsonArray.forEach { element ->
            val fixture = element.jsonObject
            assertEquals(
                fixture.getValue("name").jsonPrimitive.content,
                fixture.getValue("canonical").jsonPrimitive.content,
                canonicalizeJson(Json.parseToJsonElement(fixture.getValue("lexeme").jsonPrimitive.content)),
            )
        }
    }

    @Test
    fun envelopeCasesKeepEnvelopeValidationAboveFrameParsing() {
        corpus.getValue("envelopeCases").jsonArray.forEach { element ->
            val fixture = element.jsonObject
            val json = fixture.getValue("json").jsonPrimitive.content
            assertEquals(Json.parseToJsonElement(json).jsonObject, PimbCodec.decodeJsonPayload(json.encodeToByteArray()))
            if (fixture.getValue("valid").jsonPrimitive.content.toBoolean()) {
                parseEnvelope(json)
            } else {
                assertProtocolException(ProtocolErrorCode.valueOf(fixture.getValue("expectedError").jsonPrimitive.content)) { parseEnvelope(json) }
            }
        }
    }

    @Test
    fun uint64AndLeafVariantsConsumeSharedFixtures() {
        corpus.getValue("uint64Cases").jsonArray.forEach { element ->
            val fixture = element.jsonObject
            val value = fixture.getValue("value").jsonPrimitive.content
            if (fixture.getValue("valid").jsonPrimitive.content.toBoolean()) assertEquals(value, parseUint64(value).toString())
            else assertProtocolException { parseUint64(value) }
        }
        corpus.getValue("leafCases").jsonArray.forEach { element ->
            val fixture = element.jsonObject
            val value = fixture["value"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
            if (fixture.getValue("valid").jsonPrimitive.content.toBoolean()) assertLeafId(value)
            else assertProtocolException { assertLeafId(value) }
        }
    }

    @Test
    fun hashesConsumeSharedAbsentNullLeafUnicodeAndImageFixtures() {
        corpus.getValue("hashes").jsonArray.forEach { element ->
            val fixture = element.jsonObject
            val includeLeaf = fixture.getValue("includeExpectedLeafId").jsonPrimitive.content.toBoolean()
            val expectedLeaf = fixture["expectedLeafId"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
            assertEquals(
                fixture.getValue("sha256").jsonPrimitive.content,
                commandPayloadHash(
                    fixture.getValue("sessionId").jsonPrimitive.content,
                    fixture.getValue("operation").jsonPrimitive.content,
                    fixture.getValue("payload").jsonObject,
                    expectedLeaf,
                    includeLeaf,
                ),
            )
        }
    }

    @Test
    fun rawRecordsConsumeSharedExactBytesProjectionAndDigest() {
        corpus.getValue("rawRecords").jsonArray.forEach { element ->
            val fixture = element.jsonObject
            val record = projectRawPiJson(fixture.getValue("rawJson").jsonPrimitive.content.encodeToByteArray())
            assertEquals(fixture.getValue("rawSize").jsonPrimitive.content, record.rawSize)
            assertEquals(fixture.getValue("rawSha256").jsonPrimitive.content, record.rawSha256)
            assertEquals(fixture.getValue("projection").jsonObject, record.projection)
            verifyInlineRawRecord(record)
            assertProtocolException { verifyInlineRawRecord(record.copy(rawSha256 = "0".repeat(64))) }
        }
    }

    private fun decodeChunks(chunks: List<ByteArray>, finish: Boolean = true): List<PimbFrame> {
        val decoder = PimbFrameDecoder()
        val frames = chunks.flatMap(decoder::push)
        if (finish) decoder.finish()
        assertTrue(decoder.bufferedBytes() <= ProtocolConstants.headerBytes + ProtocolConstants.maxFramePayloadBytes)
        return frames
    }

    private fun assertProtocolException(code: ProtocolErrorCode? = null, block: () -> Unit) {
        try {
            block()
            fail("Expected ProtocolException")
        } catch (error: ProtocolException) {
            if (code != null) assertEquals(code, error.code)
        }
    }

    private fun FrameKind.fixtureName(): String = when (this) {
        FrameKind.Json -> "JSON"
        FrameKind.BlobChunk -> "BLOB_CHUNK"
        FrameKind.AudioPcm -> "AUDIO_PCM"
        FrameKind.TerminalBytes -> "TERMINAL_BYTES"
    }

    private fun hexToBytes(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
