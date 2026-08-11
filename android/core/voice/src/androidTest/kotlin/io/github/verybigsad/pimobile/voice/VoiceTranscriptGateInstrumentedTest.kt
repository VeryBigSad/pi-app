package io.github.verybigsad.pimobile.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceTranscriptGateInstrumentedTest {
    @Test
    fun partialsAndFinalDeliveredInOrderIntoDraftSurface() {
        val partials = CopyOnWriteArrayList<VoiceTranscript>()
        val finals = CopyOnWriteArrayList<VoiceTranscript>()
        val gate = VoiceTranscriptGate(
            object : VoiceTranscriptSink {
                override fun onPartialDraft(transcript: VoiceTranscript) {
                    partials += transcript
                }

                override fun onFinalDraft(transcript: VoiceTranscript) {
                    finals += transcript
                }
            },
        )

        fun partial(chunk: Long, revision: Int, text: String) = gate.accept(
            SESSION_ID,
            "voice.partial",
            """{"sessionId":"$SESSION_ID","chunkSequence":$chunk,"revision":$revision,"text":"$text"}"""
                .toByteArray(Charsets.UTF_8),
        )

        assertThat(partial(0, 0, "draft")).isNull()
        assertThat(partial(0, 1, "draft one")).isNull()
        assertThat(partial(0, 1, "draft one")).isEqualTo(VoiceTranscriptRejection.DUPLICATE)
        assertThat(partial(1, 0, "two")).isNull()
        assertThat(
            gate.accept(
                SESSION_ID,
                "voice.finish",
                """{"sessionId":"$SESSION_ID","chunkSequence":2,"text":"draft one two."}"""
                    .toByteArray(Charsets.UTF_8),
            ),
        ).isNull()
        assertThat(partial(3, 0, "late")).isEqualTo(VoiceTranscriptRejection.SESSION_CLOSED)

        assertThat(partials.map(VoiceTranscript::text)).containsExactly("draft", "draft one", "two").inOrder()
        assertThat(finals.map(VoiceTranscript::text)).containsExactly("draft one two.")
    }

    private companion object {
        const val SESSION_ID = "123e4567-e89b-42d3-a456-426614174000"
    }
}
