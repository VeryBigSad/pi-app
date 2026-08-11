package io.github.verybigsad.pimobile.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.UUID
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PimbPropertyTest {
    @Test
    fun terminalFramesRoundTripAcrossDeterministicFragmentation() {
        val random = Random(0x50494d42)
        repeat(1_000) {
            val expected = TerminalPayload(
                terminalGeneration = random.nextLong().toULong(),
                sequence = random.nextLong().toULong(),
                data = ByteArray(random.nextInt(2_049)).also(random::nextBytes),
            )
            val encoded = PimbCodec.encode(FrameKind.TerminalBytes, PimbCodec.encodeTerminalPayload(expected))
            val decoder = PimbFrameDecoder()
            val frames = mutableListOf<PimbFrame>()
            var offset = 0
            while (offset < encoded.size) {
                val end = minOf(encoded.size, offset + random.nextInt(97) + 1)
                frames += decoder.push(encoded.copyOfRange(offset, end))
                offset = end
            }
            decoder.finish()
            assertEquals(1, frames.size)
            val actual = PimbCodec.decodeTerminalPayload(frames.single().payload)
            assertEquals(expected.terminalGeneration, actual.terminalGeneration)
            assertEquals(expected.sequence, actual.sequence)
            assertArrayEquals(expected.data, actual.data)
        }
    }

    @Test
    fun streamPrefixesRoundTrip() {
        val random = Random(0x5354524d)
        repeat(1_000) {
            val expected = StreamPayload(
                streamId = UUID(random.nextLong(), random.nextLong()).toString(),
                sequence = random.nextLong() and 0xffff_ffffL,
                offset = random.nextLong().toULong(),
                data = ByteArray(random.nextInt(2_049)).also(random::nextBytes),
            )
            val actual = PimbCodec.decodeStreamPayload(PimbCodec.encodeStreamPayload(expected))
            assertEquals(expected.streamId, actual.streamId)
            assertEquals(expected.sequence, actual.sequence)
            assertEquals(expected.offset, actual.offset)
            assertArrayEquals(expected.data, actual.data)
        }
    }

    @Test
    fun everyNonzeroReservedFlagIsRejected() {
        repeat(0xffff) { index ->
            val encoded = PimbCodec.encode(FrameKind.Json, "{}".encodeToByteArray())
            ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN).putShort(6, (index + 1).toShort())
            try {
                PimbCodec.decode(encoded)
                fail("reserved flags accepted")
            } catch (_: ProtocolException) {
            }
        }
    }

    @Test
    fun hostileByteFuzzNeverBuffersBeyondOneFrame() {
        val random = Random(0x46555a5a)
        repeat(2_000) {
            val decoder = PimbFrameDecoder()
            try {
                repeat(random.nextInt(32) + 1) {
                    decoder.push(ByteArray(random.nextInt(258)).also(random::nextBytes))
                    assertTrue(decoder.bufferedBytes() <= ProtocolConstants.headerBytes + ProtocolConstants.maxFramePayloadBytes)
                }
                decoder.finish()
            } catch (_: ProtocolException) {
                assertTrue(decoder.bufferedBytes() <= ProtocolConstants.headerBytes + ProtocolConstants.maxFramePayloadBytes)
            }
        }
    }

    @Test
    fun arbitraryFrameSequencesCoalesce() {
        val random = Random(0x434f414c)
        repeat(500) {
            val payloads = List(random.nextInt(24) + 1) { ByteArray(random.nextInt(513)).also(random::nextBytes) }
            val coalesced = payloads.fold(ByteArray(0)) { result, payload -> result + PimbCodec.encode(FrameKind.Json, payload) }
            val decoder = PimbFrameDecoder()
            val frames = decoder.push(coalesced)
            decoder.finish()
            assertEquals(payloads.size, frames.size)
            payloads.zip(frames).forEach { (expected, actual) -> assertArrayEquals(expected, actual.payload) }
        }
    }

    @Test
    fun jcsNumbersRoundTripAcrossRandomBitPatterns() {
        val random = Random(0x4a4353)
        val syntax = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?(e[+-][0-9]+)?$")
        repeat(5_000) {
            val value = java.lang.Double.longBitsToDouble(random.nextLong())
            if (value.isNaN() || value.isInfinite()) return@repeat
            val canonical = canonicalizeJson(kotlinx.serialization.json.JsonPrimitive(value))
            assertTrue(canonical, syntax.matches(canonical))
            assertEquals(value, canonical.toDouble(), 0.0)
            assertEquals(canonical, canonicalizeJson(Json.parseToJsonElement(canonical)))
        }
    }

    @Test
    fun randomContiguousStreamPartitionsRemainBounded() {
        val random = Random(0x5354524f)
        repeat(1_000) {
            val data = ByteArray(random.nextInt(4_097)).also(random::nextBytes)
            val streamId = UUID(random.nextLong(), random.nextLong()).toString()
            val stream = ContiguousStream(streamId, data.size.toULong())
            var offset = 0
            var sequence = 0L
            while (offset < data.size) {
                val end = minOf(data.size, offset + random.nextInt(minOf(257, data.size - offset)) + 1)
                stream.accept(streamId, sequence++, offset.toULong(), data.copyOfRange(offset, end))
                offset = end
            }
            assertEquals(data.size.toULong(), stream.length)
        }
    }
}
