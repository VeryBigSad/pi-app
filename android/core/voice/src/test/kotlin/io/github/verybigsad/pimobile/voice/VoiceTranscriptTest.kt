package io.github.verybigsad.pimobile.voice

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceTranscriptTest {
    private val sink = RecordingSink()
    private val gate = VoiceTranscriptGate(sink).also { it.reset(GENERATION_A) }

    @Test
    fun parsesCanonicalPartialIntoBoundDraftCallback() {
        assertThat(gate.begin(STREAM_A, TARGET_A, GENERATION_A)).isNull()

        val rejection = acceptA("voice.partial", partialBody(STREAM_A, 3u, 2u, "hello wor"))

        assertThat(rejection).isNull()
        assertThat(sink.partials).containsExactly(
            TARGET_A to VoiceTranscript(STREAM_A, 3u, 2u, VoiceTranscriptKind.PARTIAL, "hello wor"),
        )
        assertThat(sink.finals).isEmpty()
    }

    @Test
    fun finalDraftClosesAndTombstonesStream() {
        assertThat(gate.begin(STREAM_A, TARGET_A, GENERATION_A)).isNull()
        acceptA("voice.partial", partialBody(STREAM_A, 0u, 1u, "one"))
        val rejection = acceptA("voice.finish", finishBody(STREAM_A, 1u, "one two."))

        assertThat(rejection).isNull()
        assertThat(sink.finals).containsExactly(
            TARGET_A to VoiceTranscript(STREAM_A, 1u, 0u, VoiceTranscriptKind.FINAL, "one two."),
        )
        assertThat(acceptA("voice.partial", partialBody(STREAM_A, 2u, 3u, "late")))
            .isEqualTo(VoiceTranscriptRejection.SESSION_CLOSED)
        assertThat(gate.begin(STREAM_A, TARGET_B, GENERATION_A))
            .isEqualTo(VoiceTranscriptRejection.SESSION_CLOSED)
    }

    @Test
    fun dropsDuplicateStaleRevisionsAndOutOfOrderChunks() {
        assertThat(gate.begin(STREAM_A, TARGET_A, GENERATION_A)).isNull()
        assertThat(acceptA("voice.partial", partialBody(STREAM_A, 0u, 1u, "a"))).isNull()
        assertThat(acceptA("voice.partial", partialBody(STREAM_A, 0u, 2u, "ab"))).isNull()
        assertThat(acceptA("voice.partial", partialBody(STREAM_A, 0u, 2u, "ab")))
            .isEqualTo(VoiceTranscriptRejection.DUPLICATE)
        assertThat(acceptA("voice.partial", partialBody(STREAM_A, 0u, 1u, "a")))
            .isEqualTo(VoiceTranscriptRejection.STALE)
        assertThat(acceptA("voice.partial", partialBody(STREAM_A, 4u, 3u, "newer"))).isNull()
        assertThat(acceptA("voice.partial", partialBody(STREAM_A, 3u, 4u, "older")))
            .isEqualTo(VoiceTranscriptRejection.STALE)
        assertThat(sink.partials.map { it.second.text }).containsExactly("a", "ab", "newer").inOrder()
    }

    @Test
    fun cancelRejectsLateResultsAndNextStreamReordering() {
        assertThat(gate.begin(STREAM_A, TARGET_A, GENERATION_A)).isNull()
        assertThat(gate.cancel(STREAM_A, GENERATION_A)).isNull()
        assertThat(gate.begin(STREAM_B, TARGET_B, GENERATION_A)).isNull()

        assertThat(acceptA("voice.finish", finishBody(STREAM_A, 0u, "late A")))
            .isEqualTo(VoiceTranscriptRejection.SESSION_CLOSED)
        assertThat(gate.accept(GENERATION_A, STREAM_B, "voice.partial", partialBody(STREAM_B, 0u, 1u, "B")))
            .isNull()
        assertThat(acceptA("voice.partial", partialBody(STREAM_A, 1u, 2u, "later A")))
            .isEqualTo(VoiceTranscriptRejection.SESSION_CLOSED)
        assertThat(sink.partials).containsExactly(
            TARGET_B to VoiceTranscript(STREAM_B, 0u, 1u, VoiceTranscriptKind.PARTIAL, "B"),
        )
    }

    @Test
    fun reconnectTombstonesOldGenerationAndRejectsLateAAfterBStarts() {
        assertThat(gate.begin(STREAM_A, TARGET_A, GENERATION_A)).isNull()
        gate.reset(GENERATION_B)
        assertThat(gate.begin(STREAM_B, TARGET_B, GENERATION_B)).isNull()

        assertThat(gate.accept(GENERATION_A, STREAM_A, "voice.partial", partialBody(STREAM_A, 0u, 1u, "late")))
            .isEqualTo(VoiceTranscriptRejection.STALE_GENERATION)
        assertThat(gate.accept(GENERATION_B, STREAM_A, "voice.partial", partialBody(STREAM_A, 0u, 1u, "late")))
            .isEqualTo(VoiceTranscriptRejection.SESSION_CLOSED)
        assertThat(gate.accept(GENERATION_B, STREAM_B, "voice.finish", finishBody(STREAM_B, 0u, "current")))
            .isNull()
        assertThat(sink.finals.single().first).isEqualTo(TARGET_B)
    }

    @Test
    fun beginBWithoutCancelTombstonesA() {
        assertThat(gate.begin(STREAM_A, TARGET_A, GENERATION_A)).isNull()
        assertThat(gate.begin(STREAM_B, TARGET_B, GENERATION_A)).isNull()

        assertThat(acceptA("voice.partial", partialBody(STREAM_A, 0u, 1u, "A")))
            .isEqualTo(VoiceTranscriptRejection.SESSION_CLOSED)
        assertThat(gate.accept(GENERATION_A, STREAM_B, "voice.partial", partialBody(STREAM_B, 0u, 1u, "B")))
            .isNull()
        assertThat(sink.partials.single().first).isEqualTo(TARGET_B)
    }

    @Test
    fun emptyFinalClosesSilentStreamWithoutDraft() {
        assertThat(gate.begin(STREAM_A, TARGET_A, GENERATION_A)).isNull()
        assertThat(acceptA("voice.finish", finishBody(STREAM_A, 0u, ""))).isNull()
        assertThat(sink.finals).isEmpty()
        assertThat(gate.begin(STREAM_B, TARGET_B, GENERATION_A)).isNull()
    }

    @Test
    fun rejectsNoncanonicalDecimalAndMalformedBodies() {
        assertThat(gate.begin(STREAM_A, TARGET_A, GENERATION_A)).isNull()
        for (invalid in listOf(
            """{"sessionId":"$STREAM_A","chunkSequence":0,"revision":"1","text":"a"}""",
            """{"sessionId":"$STREAM_A","chunkSequence":"00","revision":"1","text":"a"}""",
            """{"sessionId":"$STREAM_A","chunkSequence":"0","revision":1,"text":"a"}""",
            """{"sessionId":"$STREAM_A","chunkSequence":"0","revision":"01","text":"a"}""",
            """{"sessionId":"$STREAM_A","chunkSequence":"18446744073709551616","revision":"1","text":"a"}""",
            """{"sessionId":"$STREAM_A","chunkSequence":"0","revision":"1","text":""}""",
            "not json",
        )) {
            assertThat(acceptA("voice.partial", body(invalid))).isEqualTo(VoiceTranscriptRejection.MALFORMED)
        }
    }

    @Test
    fun acceptsUint64MaxAndRejectsOversizedTextOrBody() {
        assertThat(gate.begin(STREAM_A, TARGET_A, GENERATION_A)).isNull()
        assertThat(acceptA("voice.partial", partialBody(STREAM_A, ULong.MAX_VALUE, ULong.MAX_VALUE, "max"))).isNull()
        val huge = """{"sessionId":"$STREAM_A","chunkSequence":"0","revision":"1","text":"${"a".repeat(70_000)}"}"""
        assertThat(acceptA("voice.partial", body(huge))).isEqualTo(VoiceTranscriptRejection.OVERSIZED)
        val longText = "a".repeat(17_000)
        assertThat(acceptA("voice.partial", partialBody(STREAM_A, 0u, 1u, longText)))
            .isEqualTo(VoiceTranscriptRejection.MALFORMED)
    }

    @Test
    fun transcriptModelValidatesIdentityAndFinalRevision() {
        assertThrows(IllegalArgumentException::class.java) {
            VoiceTranscript("not-a-uuid", 0u, 0u, VoiceTranscriptKind.PARTIAL, "x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceTranscript(STREAM_A, 0u, 1u, VoiceTranscriptKind.FINAL, "x")
        }
    }

    private fun acceptA(type: String, body: ByteArray): VoiceTranscriptRejection? =
        gate.accept(GENERATION_A, STREAM_A, type, body)

    private class RecordingSink : VoiceTranscriptSink {
        val partials = CopyOnWriteArrayList<Pair<String, VoiceTranscript>>()
        val finals = CopyOnWriteArrayList<Pair<String, VoiceTranscript>>()

        override fun onPartialDraft(targetSessionId: String, transcript: VoiceTranscript) {
            partials += targetSessionId to transcript
        }

        override fun onFinalDraft(targetSessionId: String, transcript: VoiceTranscript) {
            finals += targetSessionId to transcript
        }
    }

    private companion object {
        const val STREAM_A = "123e4567-e89b-42d3-a456-426614174000"
        const val STREAM_B = "123e4567-e89b-42d3-a456-426614174999"
        const val TARGET_A = "223e4567-e89b-42d3-a456-426614174000"
        const val TARGET_B = "223e4567-e89b-42d3-a456-426614174999"
        const val GENERATION_A = 7L
        const val GENERATION_B = 8L

        fun body(json: String): ByteArray = json.toByteArray(Charsets.UTF_8)

        fun partialBody(sessionId: String, chunk: ULong, revision: ULong, text: String): ByteArray =
            body("""{"sessionId":"$sessionId","chunkSequence":"$chunk","revision":"$revision","text":"$text"}""")

        fun finishBody(sessionId: String, chunk: ULong, text: String): ByteArray =
            body("""{"sessionId":"$sessionId","chunkSequence":"$chunk","text":"$text"}""")
    }
}
