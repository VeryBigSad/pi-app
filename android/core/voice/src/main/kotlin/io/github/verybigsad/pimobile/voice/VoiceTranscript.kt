package io.github.verybigsad.pimobile.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

private const val MAX_TRANSCRIPT_MESSAGE_BYTES = 64 * 1_024
private const val MAX_TRANSCRIPT_TEXT_CHARS = 16 * 1_024
private const val MAX_TRACKED_CHUNKS_PER_SESSION = 1_024

enum class VoiceTranscriptKind(val wireType: String) {
    PARTIAL("voice.partial"),
    FINAL("voice.finish"),
}

enum class VoiceTranscriptRejection {
    OVERSIZED,
    MALFORMED,
    UNKNOWN_SESSION,
    STALE,
    DUPLICATE,
    SESSION_CLOSED,
}

/**
 * One ordered host transcript result for an uploaded chunk.
 */
data class VoiceTranscript(
    val sessionId: String,
    val chunkSequence: Long,
    val revision: Int,
    val kind: VoiceTranscriptKind,
    val text: String,
) {
    init {
        require(isVoiceSessionId(sessionId))
        require(chunkSequence >= 0)
        require(revision >= 0)
        require(kind == VoiceTranscriptKind.PARTIAL || revision == 0)
        require(text.length <= MAX_TRANSCRIPT_TEXT_CHARS)
    }
}

/**
 * Editable-draft callback surface consumed by the composer. Partial drafts are
 * provisional and must never overwrite manually typed text; the final draft is
 * inserted as editable text and is never auto-sent.
 */
interface VoiceTranscriptSink {
    fun onPartialDraft(transcript: VoiceTranscript)

    fun onFinalDraft(transcript: VoiceTranscript)
}

/**
 * Parses inbound `voice.partial` / `voice.finish` message bodies and delivers
 * them to the attached sink with strict per-session ordering and dedup: chunks
 * arrive in ascending order, partial revisions are monotonic per chunk, a final
 * draft closes its session, and late or duplicate results are dropped.
 */
class VoiceTranscriptGate(
    private val sink: VoiceTranscriptSink,
) {
    private val lock = Any()
    private val chunks = HashMap<Long, ChunkProgress>()
    private var highestDeliveredChunk = -1L
    private var sessionClosed = false

    fun accept(sessionId: String, type: String, body: ByteArray): VoiceTranscriptRejection? {
        val kind = when (type) {
            VoiceTranscriptKind.PARTIAL.wireType -> VoiceTranscriptKind.PARTIAL
            VoiceTranscriptKind.FINAL.wireType -> VoiceTranscriptKind.FINAL
            else -> return VoiceTranscriptRejection.MALFORMED
        }
        if (body.isEmpty() || body.size > MAX_TRANSCRIPT_MESSAGE_BYTES) {
            return VoiceTranscriptRejection.OVERSIZED
        }
        val transcript = parse(sessionId, kind, body) ?: return VoiceTranscriptRejection.MALFORMED
        synchronized(lock) {
            if (sessionClosed) return VoiceTranscriptRejection.SESSION_CLOSED
            if (transcript.chunkSequence < highestDeliveredChunk) return VoiceTranscriptRejection.STALE
            if (transcript.chunkSequence >= MAX_TRACKED_CHUNKS_PER_SESSION.toLong()) {
                return VoiceTranscriptRejection.MALFORMED
            }
            val progress = chunks.getOrPut(transcript.chunkSequence) { ChunkProgress() }
            when {
                progress.finalDelivered -> return VoiceTranscriptRejection.DUPLICATE
                transcript.kind == VoiceTranscriptKind.FINAL -> progress.finalDelivered = true
                transcript.revision < progress.highestPartialRevision -> return VoiceTranscriptRejection.STALE
                transcript.revision == progress.highestPartialRevision -> return VoiceTranscriptRejection.DUPLICATE
                else -> progress.highestPartialRevision = transcript.revision
            }
            if (transcript.chunkSequence > highestDeliveredChunk) {
                highestDeliveredChunk = transcript.chunkSequence
            }
            if (transcript.kind == VoiceTranscriptKind.FINAL) {
                sessionClosed = true
            }
        }
        when (transcript.kind) {
            VoiceTranscriptKind.PARTIAL -> sink.onPartialDraft(transcript)
            VoiceTranscriptKind.FINAL -> sink.onFinalDraft(transcript)
        }
        return null
    }

    fun reset() {
        synchronized(lock) {
            chunks.clear()
            highestDeliveredChunk = -1L
            sessionClosed = false
        }
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
        val chunkSequence = (root["chunkSequence"] as? JsonPrimitive)?.longOrNull ?: return null
        val revision = when (kind) {
            VoiceTranscriptKind.PARTIAL -> (root["revision"] as? JsonPrimitive)?.intOrNull ?: return null
            VoiceTranscriptKind.FINAL -> 0
        }
        val text = (root["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (text.isEmpty()) return null
        return try {
            VoiceTranscript(
                sessionId = sessionId,
                chunkSequence = chunkSequence,
                revision = revision,
                kind = kind,
                text = text,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private class ChunkProgress {
        var highestPartialRevision = -1
        var finalDelivered = false
    }
}
