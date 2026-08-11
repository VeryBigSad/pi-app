package io.github.verybigsad.pimobile.voice

fun interface VoiceActivityDetector {
    fun isSpeech(frame: ShortArray): Boolean
}

class RmsVoiceActivityDetector(
    private val threshold: Int = 500,
) : VoiceActivityDetector {
    init {
        require(threshold in 1..Short.MAX_VALUE)
    }

    override fun isSpeech(frame: ShortArray): Boolean {
        require(frame.size == VoiceAudioSpec.SAMPLES_PER_FRAME)
        var squaredTotal = 0L
        for (sample in frame) {
            squaredTotal += sample.toLong() * sample.toLong()
        }
        val thresholdTotal = threshold.toLong() * threshold.toLong() * frame.size
        return squaredTotal >= thresholdTotal
    }
}

data class VadChunkingConfig(
    val preRollMilliseconds: Int = VoiceAudioSpec.PRE_ROLL_MS,
    val preferredChunkMilliseconds: Int = VoiceAudioSpec.PREFERRED_CHUNK_MS,
    val forcedChunkMilliseconds: Int = VoiceAudioSpec.FORCED_CHUNK_MS,
    val overlapMilliseconds: Int = VoiceAudioSpec.OVERLAP_MS,
    val silenceBoundaryMilliseconds: Int = VoiceAudioSpec.SILENCE_BOUNDARY_MS,
) {
    init {
        val durations = listOf(
            preRollMilliseconds,
            preferredChunkMilliseconds,
            forcedChunkMilliseconds,
            overlapMilliseconds,
            silenceBoundaryMilliseconds,
        )
        require(durations.all { it > 0 && it % VoiceAudioSpec.FRAME_DURATION_MS == 0 })
        require(preRollMilliseconds < preferredChunkMilliseconds)
        require(overlapMilliseconds < preferredChunkMilliseconds)
        require(preferredChunkMilliseconds < forcedChunkMilliseconds)
        require(preferredChunkMilliseconds + silenceBoundaryMilliseconds <= forcedChunkMilliseconds)
        require(forcedChunkMilliseconds <= VoiceAudioSpec.FORCED_CHUNK_MS)
    }
}

class VoiceAudioChunk internal constructor(
    val sequence: Long,
    val final: Boolean,
    pcm16Le: ByteArray,
) {
    private val bytes = pcm16Le.copyOf()

    val sizeBytes: Int
        get() = bytes.size

    val encodedDurationMilliseconds: Int
        get() = bytes.size * 1_000 / (VoiceAudioSpec.SAMPLE_RATE_HZ * VoiceAudioSpec.BYTES_PER_SAMPLE)

    init {
        require(sequence >= 0)
        require(bytes.isNotEmpty())
        require(bytes.size % VoiceAudioSpec.BYTES_PER_FRAME == 0)
        require(bytes.size <= VoiceAudioSpec.MAX_CHUNK_BYTES)
    }

    fun copyPcm16Le(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is VoiceAudioChunk &&
            sequence == other.sequence &&
            final == other.final &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int {
        var result = sequence.hashCode()
        result = 31 * result + final.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

class VadVoiceChunker(
    private val detector: VoiceActivityDetector,
    config: VadChunkingConfig = VadChunkingConfig(),
) {
    private val preRollFrameCount = config.preRollMilliseconds / VoiceAudioSpec.FRAME_DURATION_MS
    private val preferredSpeechFrameCount = config.preferredChunkMilliseconds / VoiceAudioSpec.FRAME_DURATION_MS
    private val forcedFrameCount = config.forcedChunkMilliseconds / VoiceAudioSpec.FRAME_DURATION_MS
    private val overlapFrameCount = config.overlapMilliseconds / VoiceAudioSpec.FRAME_DURATION_MS
    private val silenceBoundaryFrameCount = config.silenceBoundaryMilliseconds / VoiceAudioSpec.FRAME_DURATION_MS
    private val preRoll = ArrayDeque<ClassifiedFrame>(preRollFrameCount)
    private var active: ArrayDeque<ClassifiedFrame>? = null
    private var silenceFrameCount = 0
    private var speechFrameCount = 0
    private var novelFramesAfterForcedBoundary = 0
    private var sequence = 0L

    fun push(frame: ShortArray): List<VoiceAudioChunk> {
        require(frame.size == VoiceAudioSpec.SAMPLES_PER_FRAME)
        val ownedFrame = frame.copyOf()
        val speech = detector.isSpeech(ownedFrame.copyOf())
        val current = active
        if (current == null) {
            if (!speech) {
                rememberPreRoll(ClassifiedFrame(ownedFrame, speech = false))
                return emptyList()
            }
            val started = ArrayDeque<ClassifiedFrame>(forcedFrameCount)
            preRoll.forEach { started.addLast(ClassifiedFrame(it.samples.copyOf(), it.speech)) }
            preRoll.clear()
            started.addLast(ClassifiedFrame(ownedFrame, speech = true))
            active = started
            silenceFrameCount = 0
            speechFrameCount = started.count(ClassifiedFrame::speech)
            novelFramesAfterForcedBoundary = started.size
            return emitIfBoundary(started)
        }

        current.addLast(ClassifiedFrame(ownedFrame, speech))
        novelFramesAfterForcedBoundary += 1
        if (speech) {
            silenceFrameCount = 0
            speechFrameCount += 1
        } else {
            silenceFrameCount += 1
        }
        return emitIfBoundary(current)
    }

    fun finish(): List<VoiceAudioChunk> {
        val current = active
        if (current == null) {
            preRoll.clear()
            return emptyList()
        }
        if (novelFramesAfterForcedBoundary == 0) {
            clearActive()
            return emptyList()
        }
        val chunk = makeChunk(current, final = true)
        clearActive()
        preRoll.clear()
        return listOf(chunk)
    }

    fun cancel() {
        preRoll.clear()
        clearActive()
    }

    private fun emitIfBoundary(current: ArrayDeque<ClassifiedFrame>): List<VoiceAudioChunk> {
        if (current.size >= forcedFrameCount) {
            val chunk = makeChunk(current, final = false)
            val overlap = current.takeLastFrames(overlapFrameCount)
            current.clear()
            overlap.forEach(current::addLast)
            silenceFrameCount = 0
            speechFrameCount = overlap.count(ClassifiedFrame::speech)
            novelFramesAfterForcedBoundary = 0
            return listOf(chunk)
        }
        if (speechFrameCount >= preferredSpeechFrameCount && silenceFrameCount >= silenceBoundaryFrameCount) {
            val chunk = makeChunk(current, final = false)
            val trailingPreRoll = current.takeLastFrames(preRollFrameCount)
            clearActive()
            preRoll.clear()
            trailingPreRoll.forEach(::rememberPreRoll)
            return listOf(chunk)
        }
        return emptyList()
    }

    private fun makeChunk(frames: Collection<ClassifiedFrame>, final: Boolean): VoiceAudioChunk {
        val output = ByteArray(frames.size * VoiceAudioSpec.BYTES_PER_FRAME)
        var offset = 0
        for (frame in frames) {
            for (sample in frame.samples) {
                val value = sample.toInt()
                output[offset] = value.toByte()
                output[offset + 1] = (value ushr 8).toByte()
                offset += VoiceAudioSpec.BYTES_PER_SAMPLE
            }
        }
        return VoiceAudioChunk(
            sequence = sequence++,
            final = final,
            pcm16Le = output,
        )
    }

    private fun rememberPreRoll(frame: ClassifiedFrame) {
        preRoll.addLast(ClassifiedFrame(frame.samples.copyOf(), frame.speech))
        while (preRoll.size > preRollFrameCount) {
            preRoll.removeFirst()
        }
    }

    private fun clearActive() {
        active = null
        silenceFrameCount = 0
        speechFrameCount = 0
        novelFramesAfterForcedBoundary = 0
    }
}

private class ClassifiedFrame(
    val samples: ShortArray,
    val speech: Boolean,
)

private fun ArrayDeque<ClassifiedFrame>.takeLastFrames(count: Int): List<ClassifiedFrame> =
    drop((size - count).coerceAtLeast(0))
