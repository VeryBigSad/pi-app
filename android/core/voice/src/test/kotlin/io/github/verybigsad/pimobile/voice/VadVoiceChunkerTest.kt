package io.github.verybigsad.pimobile.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class VadVoiceChunkerTest {
    private val detector = VoiceActivityDetector { frame -> frame.any { sample -> kotlin.math.abs(sample.toInt()) >= 500 } }

    @Test
    fun audioSpecIsApi29PcmContract() {
        assertThat(VoiceAudioSpec.SAMPLE_RATE_HZ).isEqualTo(16_000)
        assertThat(VoiceAudioSpec.CHANNEL_COUNT).isEqualTo(1)
        assertThat(VoiceAudioSpec.BITS_PER_SAMPLE).isEqualTo(16)
        assertThat(VoiceAudioSpec.FRAME_DURATION_MS).isEqualTo(20)
        assertThat(VoiceAudioSpec.SAMPLES_PER_FRAME).isEqualTo(320)
        assertThat(VoiceAudioSpec.BYTES_PER_FRAME).isEqualTo(640)
        assertThat(VoiceAudioSpec.MAX_CHUNK_BYTES).isEqualTo(384_000)
    }

    @Test
    fun rmsDetectorHasDeterministicInclusiveThreshold() {
        val vad = RmsVoiceActivityDetector(threshold = 500)

        assertThat(vad.isSpeech(frame(499))).isFalse()
        assertThat(vad.isSpeech(frame(500))).isTrue()
        assertThat(vad.isSpeech(frame(-500))).isTrue()
    }

    @Test
    fun silenceNeverProducesAChunkOrFinalFlush() {
        val chunker = VadVoiceChunker(detector)

        repeat(2_000) { assertThat(chunker.push(frame(0))).isEmpty() }

        assertThat(chunker.finish()).isEmpty()
    }

    @Test
    fun preferredBoundaryRequiresEightSecondsOfSpeechExcludingPreRoll() {
        val chunker = VadVoiceChunker(detector)
        repeat(15) { index -> chunker.push(frame(index + 1)) }
        repeat(385) { chunker.push(frame(1_000)) }
        repeat(10) { assertThat(chunker.push(frame(0))).isEmpty() }
        repeat(15) { chunker.push(frame(2_000)) }
        repeat(9) { assertThat(chunker.push(frame(0))).isEmpty() }

        val chunks = chunker.push(frame(0))

        assertThat(chunks).hasSize(1)
        val chunk = chunks.single()
        assertThat(chunk.sequence).isEqualTo(0)
        assertThat(chunk.final).isFalse()
        assertThat(chunk.encodedDurationMilliseconds).isEqualTo(8_700)
        assertThat(firstSample(chunk.copyPcm16Le(), frameIndex = 0)).isEqualTo(1)
        assertThat(firstSample(chunk.copyPcm16Le(), frameIndex = 14)).isEqualTo(15)
        assertThat(firstSample(chunk.copyPcm16Le(), frameIndex = 15)).isEqualTo(1_000)
        assertThat(firstSample(chunk.copyPcm16Le(), frameIndex = 410)).isEqualTo(2_000)
    }

    @Test
    fun preferredBoundaryCutsAtExactlyEightSecondsOfSpeechOnSilence() {
        val chunker = VadVoiceChunker(detector)
        repeat(15) { chunker.push(frame(0)) }
        repeat(400) { chunker.push(frame(1_000)) }
        repeat(9) { assertThat(chunker.push(frame(0))).isEmpty() }

        val chunks = chunker.push(frame(0))

        val chunk = chunks.single()
        assertThat(chunk.encodedDurationMilliseconds).isEqualTo(8_500)
        assertThat(chunk.final).isFalse()
    }

    @Test
    fun preferredBoundaryIgnoresSilenceWhenSpeechIsOneFrameShort() {
        val chunker = VadVoiceChunker(detector)
        repeat(399) { chunker.push(frame(1_000)) }

        repeat(25) { assertThat(chunker.push(frame(0))).isEmpty() }

        chunker.push(frame(1_000))
        repeat(9) { assertThat(chunker.push(frame(0))).isEmpty() }
        val chunks = chunker.push(frame(0))

        assertThat(chunks.single().encodedDurationMilliseconds).isEqualTo(8_700)
    }

    @Test
    fun forcedCutKeepsSpeechCountingAcrossTheOverlap() {
        val chunker = VadVoiceChunker(detector)
        repeat(600) { chunker.push(frame(1_000)) }

        repeat(375) { assertThat(chunker.push(frame(1_000))).isEmpty() }
        repeat(9) { assertThat(chunker.push(frame(0))).isEmpty() }

        val chunk = chunker.push(frame(0)).single()

        assertThat(chunk.sequence).isEqualTo(1)
        assertThat(chunk.encodedDurationMilliseconds).isEqualTo(8_200)
        assertThat(chunker.finish()).isEmpty()
    }

    @Test
    fun preRollIsCappedAtThreeHundredMilliseconds() {
        val chunker = VadVoiceChunker(detector)
        repeat(20) { index -> chunker.push(frame(index + 1)) }
        val chunks = buildList {
            repeat(585) { addAll(chunker.push(frame(1_000))) }
        }

        val chunk = chunks.single()

        assertThat(chunk.encodedDurationMilliseconds).isEqualTo(12_000)
        assertThat(firstSample(chunk.copyPcm16Le(), 0)).isEqualTo(6)
        assertThat(firstSample(chunk.copyPcm16Le(), 14)).isEqualTo(20)
        assertThat(firstSample(chunk.copyPcm16Le(), 15)).isEqualTo(1_000)
    }

    @Test
    fun forcedBoundaryNeverExceedsTwelveSecondsAndCarriesFiveHundredMillisecondOverlap() {
        val chunker = VadVoiceChunker(detector)
        val chunks = buildList {
            repeat(1_200) { addAll(chunker.push(frame((it % 30_000) + 1_000))) }
        }

        assertThat(chunks).hasSize(2)
        assertThat(chunks.map(VoiceAudioChunk::encodedDurationMilliseconds)).containsExactly(12_000, 12_000).inOrder()
        assertThat(chunks.map(VoiceAudioChunk::sequence)).containsExactly(0L, 1L).inOrder()
        val overlapBytes = VoiceAudioSpec.OVERLAP_MS * VoiceAudioSpec.SAMPLE_RATE_HZ * VoiceAudioSpec.BYTES_PER_SAMPLE / 1_000
        val first = chunks[0].copyPcm16Le()
        val second = chunks[1].copyPcm16Le()
        assertThat(second.copyOfRange(0, overlapBytes))
            .isEqualTo(first.copyOfRange(first.size - overlapBytes, first.size))
    }

    @Test
    fun exactForcedBoundaryDoesNotCreateAnOverlapOnlyFinalChunk() {
        val chunker = VadVoiceChunker(detector)
        repeat(600) { chunker.push(frame(1_000)) }

        assertThat(chunker.finish()).isEmpty()
    }

    @Test
    fun finalFlushIncludesOverlapOnlyWhenNewAudioExists() {
        val chunker = VadVoiceChunker(detector)
        repeat(600) { chunker.push(frame(1_000)) }
        repeat(5) { chunker.push(frame(2_000)) }

        val final = chunker.finish().single()

        assertThat(final.final).isTrue()
        assertThat(final.sequence).isEqualTo(1)
        assertThat(final.encodedDurationMilliseconds).isEqualTo(600)
        assertThat(firstSample(final.copyPcm16Le(), 0)).isEqualTo(1_000)
        assertThat(firstSample(final.copyPcm16Le(), 25)).isEqualTo(2_000)
    }

    @Test
    fun finishClearsSilencePreRollAcrossReuse() {
        val chunker = VadVoiceChunker(detector)
        repeat(15) { chunker.push(frame(it + 1)) }
        assertThat(chunker.finish()).isEmpty()
        repeat(10) { chunker.push(frame(1_000)) }

        val chunk = chunker.finish().single()

        assertThat(chunk.encodedDurationMilliseconds).isEqualTo(200)
        assertThat(firstSample(chunk.copyPcm16Le(), 0)).isEqualTo(1_000)
    }

    @Test
    fun detectorCannotMutateCapturedAudio() {
        val mutatingDetector = VoiceActivityDetector { candidate ->
            candidate.fill(0)
            true
        }
        val chunker = VadVoiceChunker(mutatingDetector)
        chunker.push(frame(1_000))

        val chunk = chunker.finish().single()

        assertThat(firstSample(chunk.copyPcm16Le(), 0)).isEqualTo(1_000)
    }

    @Test
    fun cancelDropsPreRollAndActiveAudio() {
        val chunker = VadVoiceChunker(detector)
        repeat(15) { chunker.push(frame(0)) }
        repeat(100) { chunker.push(frame(1_000)) }

        chunker.cancel()

        assertThat(chunker.finish()).isEmpty()
        val chunks = buildList {
            repeat(600) { addAll(chunker.push(frame(2_000))) }
        }
        assertThat(chunks).hasSize(1)
        assertThat(firstSample(chunks.single().copyPcm16Le(), 0)).isEqualTo(2_000)
        assertThat(chunker.finish()).isEmpty()
    }

    @Test
    fun ownsInputAndOutputBuffers() {
        val chunker = VadVoiceChunker(detector)
        val input = frame(1_000)
        chunker.push(input)
        input.fill(2_000)
        repeat(9) { chunker.push(frame(1_000)) }

        val chunk = chunker.finish().single()
        val firstCopy = chunk.copyPcm16Le()
        firstCopy.fill(0)

        assertThat(firstSample(chunk.copyPcm16Le(), 0)).isEqualTo(1_000)
    }

    @Test
    fun rejectsWrongFramesAndInvalidTiming() {
        val chunker = VadVoiceChunker(detector)

        assertThrows(IllegalArgumentException::class.java) { chunker.push(ShortArray(319)) }
        assertThrows(IllegalArgumentException::class.java) {
            VadChunkingConfig(preRollMilliseconds = 310)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VadChunkingConfig(preferredChunkMilliseconds = 12_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VadChunkingConfig(forcedChunkMilliseconds = 12_020)
        }
    }

    private fun frame(sample: Int): ShortArray = ShortArray(VoiceAudioSpec.SAMPLES_PER_FRAME) { sample.toShort() }

    private fun firstSample(bytes: ByteArray, frameIndex: Int): Int {
        val offset = frameIndex * VoiceAudioSpec.BYTES_PER_FRAME
        return (bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)
    }
}
