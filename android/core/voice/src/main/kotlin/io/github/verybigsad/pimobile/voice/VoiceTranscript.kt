package io.github.verybigsad.pimobile.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MAX_TRANSCRIPT_MESSAGE_BYTES = 64 * 1_024
private const val MAX_TRANSCRIPT_TEXT_CHARS = 16 * 1_024
private val CANONICAL_UINT64 = Regex("^(0|[1-9][0-9]{0,19})$")

enum class VoiceTranscriptKind(val wireType: String) {
    PARTIAL("voice.partial"),
    FINAL("voice.finish"),
}

enum class VoiceTranscriptRejection {
    OVERSIZED,
    MALFORMED,
    UNKNOWN_SESSION,
    STALE_GENERATION,
    STALE,
    DUPLICATE,
    SESSION_CLOSED,
}

data class VoiceTranscript(
    val sessionId: String,
    val chunkSequence: ULong,
    val revision: ULong,
    val kind: VoiceTranscriptKind,
    val text: String,
) {
    init {
        require(isVoiceSessionId(sessionId))
        require(kind == VoiceTranscriptKind.PARTIAL || revision == 0uL)
        require(text.length <= MAX_TRANSCRIPT_TEXT_CHARS)
    }
}

interface VoiceTranscriptSink {
    fun onPartialDraft(targetSessionId: String, transcript: VoiceTranscript)

    fun onFinalDraft(targetSessionId: String, transcript: VoiceTranscript)
}

class VoiceTranscriptGate(
    private val sink: VoiceTranscriptSink,
) {
    private val lock = Any()
    private val chunks = HashMap<ULong, ChunkProgress>()
    private val tombstones = LinkedHashSet<String>()
    private var highestDeliveredChunk: ULong? = null
    private var connectionGeneration: Long? = null
    private var active: ActiveStream? = null

    fun reset(connectionGeneration: Long) {
        synchronized(lock) {
            if (this.connectionGeneration == connectionGeneration) return
            active?.let { tombstoneLocked(it.streamId) }
            clearProgressLocked()
            active = null
            this.connectionGeneration = connectionGeneration
        }
    }

    fun begin(streamId: String, targetSessionId: String, connectionGeneration: Long): VoiceTranscriptRejection? =
        synchronized(lock) {
            if (this.connectionGeneration != connectionGeneration) return@synchronized VoiceTranscriptRejection.STALE_GENERATION
            if (!isVoiceSessionId(streamId) || !isVoiceSessionId(targetSessionId)) {
                return@synchronized VoiceTranscriptRejection.MALFORMED
            }
            if (streamId in tombstones) return@synchronized VoiceTranscriptRejection.SESSION_CLOSED
            active?.let { tombstoneLocked(it.streamId) }
            clearProgressLocked()
            active = ActiveStream(streamId, targetSessionId)
            null
        }

    fun cancel(streamId: String, connectionGeneration: Long): VoiceTranscriptRejection? =
        close(streamId, connectionGeneration)

    fun finish(streamId: String, connectionGeneration: Long): VoiceTranscriptRejection? =
        close(streamId, connectionGeneration)

    fun accept(
        connectionGeneration: Long,
        streamId: String,
        type: String,
        body: ByteArray,
    ): VoiceTranscriptRejection? {
        val kind = when (type) {
            VoiceTranscriptKind.PARTIAL.wireType -> VoiceTranscriptKind.PARTIAL
            VoiceTranscriptKind.FINAL.wireType -> VoiceTranscriptKind.FINAL
            else -> return VoiceTranscriptRejection.MALFORMED
        }
        if (body.isEmpty() || body.size > MAX_TRANSCRIPT_MESSAGE_BYTES) return VoiceTranscriptRejection.OVERSIZED
        val transcript = parse(streamId, kind, body) ?: return VoiceTranscriptRejection.MALFORMED
        synchronized(lock) {
            if (this.connectionGeneration != connectionGeneration) return VoiceTranscriptRejection.STALE_GENERATION
            val binding = active
            if (binding == null || binding.streamId != streamId) {
                return if (streamId in tombstones) {
                    VoiceTranscriptRejection.SESSION_CLOSED
                } else {
                    VoiceTranscriptRejection.UNKNOWN_SESSION
                }
            }
            val highest = highestDeliveredChunk
            if (highest != null && transcript.chunkSequence < highest) return VoiceTranscriptRejection.STALE
            if (highest == null || transcript.chunkSequence > highest) chunks.clear()
            val progress = chunks.getOrPut(transcript.chunkSequence) { ChunkProgress() }
            when {
                progress.finalDelivered -> return VoiceTranscriptRejection.DUPLICATE
                transcript.kind == VoiceTranscriptKind.FINAL -> progress.finalDelivered = true
                progress.highestPartialRevision != null && transcript.revision < progress.highestPartialRevision!! -> return VoiceTranscriptRejection.STALE
                transcript.revision == progress.highestPartialRevision -> return VoiceTranscriptRejection.DUPLICATE
                else -> progress.highestPartialRevision = transcript.revision
            }
            if (highest == null || transcript.chunkSequence > highest) highestDeliveredChunk = transcript.chunkSequence
            if (transcript.text.isNotEmpty()) {
                when (transcript.kind) {
                    VoiceTranscriptKind.PARTIAL -> sink.onPartialDraft(binding.targetSessionId, transcript)
                    VoiceTranscriptKind.FINAL -> sink.onFinalDraft(binding.targetSessionId, transcript)
                }
            }
            if (transcript.kind == VoiceTranscriptKind.FINAL) closeLocked(binding)
        }
        return null
    }

    private fun close(streamId: String, connectionGeneration: Long): VoiceTranscriptRejection? = synchronized(lock) {
        if (this.connectionGeneration != connectionGeneration) return@synchronized VoiceTranscriptRejection.STALE_GENERATION
        val binding = active
        if (binding == null || binding.streamId != streamId) {
            return@synchronized if (streamId in tombstones) {
                VoiceTranscriptRejection.SESSION_CLOSED
            } else {
                VoiceTranscriptRejection.UNKNOWN_SESSION
            }
        }
        closeLocked(binding)
        null
    }

    private fun closeLocked(binding: ActiveStream) {
        tombstoneLocked(binding.streamId)
        active = null
        clearProgressLocked()
    }

    private fun tombstoneLocked(streamId: String) {
        tombstones += streamId
        while (tombstones.size > MAX_TOMBSTONES) {
            tombstones.remove(tombstones.first())
        }
    }

    private fun clearProgressLocked() {
        chunks.clear()
        highestDeliveredChunk = null
    }

    private fun parse(sessionId: String, kind: VoiceTranscriptKind, body: ByteArray): VoiceTranscript? {
        val root = try {
            Json.parseToJsonElement(String(body, Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        }
        if (root !is JsonObject) return null
        val bodySessionId = (root["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (bodySessionId != sessionId || !isVoiceSessionId(sessionId)) return null
        val chunkSequence = root.canonicalUint64("chunkSequence") ?: return null
        val revision = when (kind) {
            VoiceTranscriptKind.PARTIAL -> root.canonicalUint64("revision") ?: return null
            VoiceTranscriptKind.FINAL -> 0uL
        }
        val text = (root["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (kind == VoiceTranscriptKind.PARTIAL && text.isEmpty()) return null
        return runCatching { VoiceTranscript(sessionId, chunkSequence, revision, kind, text) }.getOrNull()
    }

    private data class ActiveStream(
        val streamId: String,
        val targetSessionId: String,
    )

    private class ChunkProgress {
        var highestPartialRevision: ULong? = null
        var finalDelivered = false
    }

    private companion object {
        const val MAX_TOMBSTONES = 256
    }
}

private fun JsonObject.canonicalUint64(name: String): ULong? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    if (!primitive.isString || !CANONICAL_UINT64.matches(primitive.content)) return null
    return primitive.content.toULongOrNull()
}
