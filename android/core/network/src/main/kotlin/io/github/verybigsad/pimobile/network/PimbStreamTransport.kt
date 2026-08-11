package io.github.verybigsad.pimobile.network

import io.github.verybigsad.pimobile.protocol.FrameKind
import io.github.verybigsad.pimobile.protocol.PimbCodec
import io.github.verybigsad.pimobile.protocol.PimbFrame
import io.github.verybigsad.pimobile.protocol.PimbFrameDecoder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val MAX_QUEUED_PIMB_FRAMES = 512

class PimbStreamTransport(
    private val channel: DuplexByteChannel,
) {
    private val decoder = PimbFrameDecoder()
    private val received = ArrayDeque<PimbFrame>()
    private val readMutex = Mutex()
    private val writeMutex = Mutex()
    private var receivedBytes = 0

    suspend fun send(kind: FrameKind, payload: ByteArray) {
        val encoded = PimbCodec.encode(kind, payload)
        writeMutex.withLock { channel.write(encoded) }
    }

    suspend fun receive(): PimbFrame = readMutex.withLock {
        while (received.isEmpty()) {
            val chunk = ByteArray(MAX_CHANNEL_CHUNK_BYTES)
            val count = channel.read(chunk)
            if (count == -1) {
                decoder.finish()
                throw NetworkException(NetworkError.TRANSPORT_CLOSED, "PIMB transport closed")
            }
            try {
                decoder.push(chunk.copyOf(count)).forEach { frame ->
                    val size = frame.payload.size + 12
                    if (received.size >= MAX_QUEUED_PIMB_FRAMES || receivedBytes > MAX_CHANNEL_QUEUE_BYTES - size) {
                        throw NetworkException(NetworkError.TRANSPORT_EXHAUSTED, "PIMB receive queue exceeds its bound")
                    }
                    received += frame
                    receivedBytes += size
                }
            } catch (error: Exception) {
                channel.close()
                throw error
            }
        }
        received.removeFirst().also { receivedBytes -= it.payload.size + 12 }
    }

    suspend fun close() = channel.close()
}
