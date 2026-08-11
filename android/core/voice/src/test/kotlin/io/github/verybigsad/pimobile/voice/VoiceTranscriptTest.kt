package io.github.verybigsad.pimobile.voice

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceTranscriptTest {
    private val sink = RecordingSink()
    private val gate = VoiceTranscriptGate(sink)

    @Test
    fun parsesPartialIntoDraftCallback() {
        val rejection = gate.accept(
            SESSION_ID,
            "voice.partial",
            body("""{"sessionId":"$SESSION_ID","chunkSequence":3,"revision":2,"text":"hello wor"}"""),
        )

        assertThat(rejection).isNull()
        assertThat(sink.partials).containsExactly(
            VoiceTranscript(SESSION_ID, 3, 2, VoiceTranscriptKind.PARTIAL, "hello wor"),
        )
        assertThat(sink.finals).isEmpty()
    }

    @Test
    fun parsesFinishIntoFinalDraftCallbackAndClosesSession() {
        gate.accept(SESSION_ID, "voice.partial", partial(chunk = 0, revision = 0, text = "one"))

        val rejection = gate.accept(
            SESSION_ID,
            "voice.finish",
            body("""{"sessionId":"$SESSION_ID","chunkSequence":1,"text":"one two."}"""),
        )

        assertThat(rejection).isNull()
        assertThat(sink.finals).containsExactly(
            VoiceTranscript(SESSION_ID, 1, 0, VoiceTranscriptKind.FINAL, "one two."),
        )
        assertThat(
            gate.accept(SESSION_ID, "voice.partial", partial(chunk = 2, revision = 0, text = "late")),
        ).isEqualTo(VoiceTranscriptRejection.SESSION_CLOSED)
        assertThat(sink.partials).hasSize(1)
    }

    @Test
    fun dropsDuplicateAndStaleRevisionsPerChunk() {
        assertThat(gate.accept(SESSION_ID, "voice.partial", partial(0, 0, "a"))).isNull()
        assertThat(gate.accept(SESSION_ID, "voice.partial", partial(0, 1, "ab"))).isNull()

        assertThat(gate.accept(SESSION_ID, "voice.partial", partial(0, 1, "ab")))
            .isEqualTo(VoiceTranscriptRejection.DUPLICATE)
        assertThat(gate.accept(SESSION_ID, "voice.partial", partial(0, 0, "a")))
            .isEqualTo(VoiceTranscriptRejection.STALE)

        assertThat(sink.partials.map(VoiceTranscript::text)).containsExactly("a", "ab").inOrder()
    }

    @Test
    fun dropsOutOfOrderChunksAndLatePartialsAfterFinal() {
        assertThat(gate.accept(SESSION_ID, "voice.partial", partial(4, 0, "newer"))).isNull()

        assertThat(gate.accept(SESSION_ID, "voice.partial", partial(3, 0, "older")))
            .isEqualTo(VoiceTranscriptRejection.STALE)
        assertThat(gate.accept(SESSION_ID, "voice.partial", partial(4, 1, "newer!"))).isNull()

        assertThat(
            gate.accept(SESSION_ID, "voice.finish", body("""{"sessionId":"$SESSION_ID","chunkSequence":5,"text":"done"}""")),
        ).isNull()
        assertThat(gate.accept(SESSION_ID, "voice.partial", partial(5, 7, "late")))
            .isEqualTo(VoiceTranscriptRejection.SESSION_CLOSED)

        assertThat(sink.partials.map(VoiceTranscript::text)).containsExactly("newer", "newer!").inOrder()
    }

    @Test
    fun rejectsMismatchedSessionIds() {
        assertThat(gate.accept(OTHER_SESSION_ID, "voice.partial", partial(0, 0, "a")))
            .isEqualTo(VoiceTranscriptRejection.MALFORMED)
        assertThat(gate.accept(SESSION_ID, "voice.partial", body("""{"sessionId":"$OTHER_SESSION_ID","chunkSequence":0,"revision":0,"text":"a"}""")))
            .isEqualTo(VoiceTranscriptRejection.MALFORMED)
        assertThat(sink.partials).isEmpty()
    }

    @Test
    fun rejectsMalformedBodies() {
        assertThat(gate.accept(SESSION_ID, "voice.partial", body("""{"sessionId":"$SESSION_ID","chunkSequence":0,"revision":0}""")))
            .isEqualTo(VoiceTranscriptRejection.MALFORMED)
        assertThat(gate.accept(SESSION_ID, "voice.partial", body("""{"sessionId":"$SESSION_ID","chunkSequence":0,"text":"a"}""")))
            .isEqualTo(VoiceTranscriptRejection.MALFORMED)
        assertThat(gate.accept(SESSION_ID, "voice.partial", body("""{"sessionId":"$SESSION_ID","chunkSequence":-1,"revision":0,"text":"a"}""")))
            .isEqualTo(VoiceTranscriptRejection.MALFORMED)
        assertThat(gate.accept(SESSION_ID, "voice.partial", body("""{"sessionId":"$SESSION_ID","chunkSequence":0,"revision":0,"text":""}""")))
            .isEqualTo(VoiceTranscriptRejection.MALFORMED)
        assertThat(gate.accept(SESSION_ID, "voice.partial", body("""not json""")))
            .isEqualTo(VoiceTranscriptRejection.MALFORMED)
        assertThat(gate.accept(SESSION_ID, "voice.partial", body("""{"sessionId":"$SESSION_ID","chunkSequence":0,"revision":0,"text":"a"}""".dropLast(1))))
            .isEqualTo(VoiceTranscriptRejection.MALFORMED)
        assertThat(gate.accept(SESSION_ID, "voice.unknown", partial(0, 0, "a")))
            .isEqualTo(VoiceTranscriptRejection.MALFORMED)
        assertThat(gate.accept(SESSION_ID, "voice.partial", body("""{"sessionId":"$SESSION_ID","chunkSequence":0,"revision":0,"text":"a","extra":"tolerated"}""")))
            .isNull()
    }

    @Test
    fun rejectsOversizedBodiesAndText() {
        val huge = """{"sessionId":"$SESSION_ID","chunkSequence":0,"revision":0,"text":"${"a".repeat(70_000)}"}"""
        assertThat(gate.accept(SESSION_ID, "voice.partial", body(huge)))
            .isEqualTo(VoiceTranscriptRejection.OVERSIZED)

        val longText = "a".repeat(17_000)
        val padded = """{"sessionId":"$SESSION_ID","chunkSequence":0,"revision":0,"text":"$longText"}"""
        assertThat(gate.accept(SESSION_ID, "voice.partial", body(padded)))
            .isEqualTo(VoiceTranscriptRejection.MALFORMED)
    }

    @Test
    fun finishSuppressesLaterDuplicateFinals() {
        val finish = body("""{"sessionId":"$SESSION_ID","chunkSequence":0,"text":"final."}""")

        assertThat(gate.accept(SESSION_ID, "voice.finish", finish)).isNull()
        assertThat(gate.accept(SESSION_ID, "voice.finish", finish))
            .isEqualTo(VoiceTranscriptRejection.SESSION_CLOSED)

        assertThat(sink.finals).hasSize(1)
    }

    @Test
    fun resetAllowsANewSessionSequence() {
        gate.accept(SESSION_ID, "voice.partial", partial(9, 0, "old"))
        gate.accept(SESSION_ID, "voice.finish", body("""{"sessionId":"$SESSION_ID","chunkSequence":10,"text":"old final"}"""))

        gate.reset()

        assertThat(gate.accept(OTHER_SESSION_ID, "voice.partial", partialBody(OTHER_SESSION_ID, 0, 0, "new"))).isNull()
        assertThat(sink.partials.map(VoiceTranscript::text)).containsExactly("old", "new").inOrder()
    }

    @Test
    fun transcriptModelValidatesInvariants() {
        assertThrows(IllegalArgumentException::class.java) {
            VoiceTranscript("not-a-uuid", 0, 0, VoiceTranscriptKind.PARTIAL, "x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceTranscript(SESSION_ID, -1, 0, VoiceTranscriptKind.PARTIAL, "x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceTranscript(SESSION_ID, 0, 1, VoiceTranscriptKind.FINAL, "x")
        }
    }

    private class RecordingSink : VoiceTranscriptSink {
        val partials = CopyOnWriteArrayList<VoiceTranscript>()
        val finals = CopyOnWriteArrayList<VoiceTranscript>()

        override fun onPartialDraft(transcript: VoiceTranscript) {
            partials += transcript
        }

        override fun onFinalDraft(transcript: VoiceTranscript) {
            finals += transcript
        }
    }

    private companion object {
        const val SESSION_ID = "123e4567-e89b-42d3-a456-426614174000"
        const val OTHER_SESSION_ID = "123e4567-e89b-42d3-a456-426614174999"

        fun body(json: String): ByteArray = json.toByteArray(Charsets.UTF_8)

        fun partial(chunk: Long, revision: Int, text: String): ByteArray =
            partialBody(SESSION_ID, chunk, revision, text)

        fun partialBody(sessionId: String, chunk: Long, revision: Int, text: String): ByteArray =
            body("""{"sessionId":"$sessionId","chunkSequence":$chunk,"revision":$revision,"text":"$text"}""")
    }
}
