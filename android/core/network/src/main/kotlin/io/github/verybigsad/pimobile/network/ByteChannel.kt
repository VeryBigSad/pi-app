package io.github.verybigsad.pimobile.network

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

const val MAX_CHANNEL_CHUNK_BYTES = 64 * 1_024
const val MAX_CHANNEL_QUEUE_BYTES = 8 * 1_024 * 1_024

interface DuplexByteChannel {
    suspend fun read(destination: ByteArray, offset: Int = 0, length: Int = destination.size - offset): Int
    suspend fun write(source: ByteArray, offset: Int = 0, length: Int = source.size - offset)
    suspend fun close()
}

class StreamByteChannel private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
) : DuplexByteChannel {
    private val readMutex = Mutex()
    private val writeMutex = Mutex()
    private val closed = AtomicBoolean(false)

    override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
        requireRange(destination.size, offset, length)
        if (length == 0) return 0
        return readMutex.withLock {
            withContext(Dispatchers.IO) {
                if (closed.get()) return@withContext -1
                input.read(destination, offset, length)
            }
        }
    }

    override suspend fun write(source: ByteArray, offset: Int, length: Int) {
        requireRange(source.size, offset, length)
        if (length == 0) return
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                if (closed.get()) throw NetworkException(NetworkError.TRANSPORT_CLOSED, "Byte channel is closed")
                var position = offset
                var remaining = length
                while (remaining > 0) {
                    val count = minOf(remaining, MAX_CHANNEL_CHUNK_BYTES)
                    output.write(source, position, count)
                    output.flush()
                    position += count
                    remaining -= count
                }
            }
        }
    }

    override suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            withContext(Dispatchers.IO) { socket.close() }
        }
    }

    companion object {
        suspend fun connect(host: String, port: Int, connectTimeoutMillis: Int = 10_000): StreamByteChannel {
            if (host.isBlank() || port !in 1..65_535 || connectTimeoutMillis !in 1..60_000) {
                throw NetworkException(NetworkError.TRANSPORT_CLOSED, "TCP endpoint is invalid")
            }
            return withContext(Dispatchers.IO) {
                val socket = Socket()
                try {
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
                    StreamByteChannel(socket, socket.getInputStream(), socket.getOutputStream())
                } catch (error: Exception) {
                    socket.close()
                    throw NetworkException(NetworkError.TRANSPORT_CLOSED, "TCP connection failed", error)
                }
            }
        }

        fun fromConnectedSocket(socket: Socket): StreamByteChannel {
            if (!socket.isConnected || socket.isClosed) throw NetworkException(NetworkError.TRANSPORT_CLOSED, "Socket is not connected")
            socket.tcpNoDelay = true
            return StreamByteChannel(socket, socket.getInputStream(), socket.getOutputStream())
        }
    }
}

internal fun requireRange(size: Int, offset: Int, length: Int) {
    if (offset < 0 || length < 0 || offset > size - length) throw IndexOutOfBoundsException()
}
