package io.github.verybigsad.pimobile.network

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

private const val TLS_HANDSHAKE_TIMEOUT_MILLIS = 10_000L
private const val TLS_QUEUE_TIMEOUT_MILLIS = 10_000L
private const val MAX_TLS_BUFFER_BYTES = 256 * 1_024
private val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocate(0)

internal class TlsHandshakeCarryover(
    val encrypted: ByteArray,
    val decrypted: ByteArray,
)

class TlsByteChannel internal constructor(
    private val underlying: DuplexByteChannel,
    private val engine: SSLEngine,
    private val carryover: TlsHandshakeCarryover,
) : DuplexByteChannel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outbound = Channel<ByteArray>(128)
    private val inbound = Channel<ByteArray>(128)
    private val inboundProgress = Channel<Unit>(Channel.CONFLATED)
    private val readMutex = Mutex()
    private val wrapMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private var pending = ByteArray(0)
    private var pendingOffset = 0

    init {
        scope.launch { wrapLoop() }
        scope.launch { unwrapLoop() }
    }

    override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
        requireRange(destination.size, offset, length)
        if (length == 0) return 0
        return readMutex.withLock {
            while (pendingOffset >= pending.size) {
                val result = inbound.receiveCatching()
                pending = result.getOrNull() ?: return@withLock -1
                pendingOffset = 0
                if (pending.isEmpty()) continue
            }
            val count = minOf(length, pending.size - pendingOffset)
            pending.copyInto(destination, offset, pendingOffset, pendingOffset + count)
            pendingOffset += count
            count
        }
    }

    override suspend fun write(source: ByteArray, offset: Int, length: Int) {
        requireRange(source.size, offset, length)
        if (closed.get()) throw NetworkException(NetworkError.TRANSPORT_CLOSED, "TLS channel is closed")
        var position = offset
        var remaining = length
        while (remaining > 0) {
            val count = minOf(remaining, MAX_CHANNEL_CHUNK_BYTES)
            val chunk = source.copyOfRange(position, position + count)
            try {
                withTimeout(TLS_QUEUE_TIMEOUT_MILLIS) { outbound.send(chunk) }
            } catch (error: TimeoutCancellationException) {
                val exhausted = NetworkException(NetworkError.TRANSPORT_EXHAUSTED, "TLS outbound queue did not make progress", error)
                fail(exhausted)
                throw exhausted
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw NetworkException(NetworkError.TRANSPORT_CLOSED, "TLS outbound channel is closed", error)
            }
            position += count
            remaining -= count
        }
    }

    /**
     * RFC 8446 keying-material exporter of the negotiated session. The provisional pairing
     * exporter uses [TlsExporterLabel] with an empty context and [TlsExporterBytes] bytes.
     */
    fun exportKeyingMaterial(
        label: String = TlsExporterLabel,
        context: ByteArray = ByteArray(0),
        length: Int = TlsExporterBytes,
    ): ByteArray = TlsExporter.export(engine, label, context, length)

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        outbound.close()
        runCatching {
            wrapMutex.withLock {
                engine.closeOutbound()
                emitWrap(EMPTY_BUFFER.duplicate())
            }
        }
        inbound.close()
        inboundProgress.close()
        scope.cancel()
        underlying.close()
    }

    private suspend fun wrapLoop() {
        try {
            for (bytes in outbound) {
                val source = ByteBuffer.wrap(bytes)
                while (source.hasRemaining()) {
                    val status = wrapMutex.withLock { emitWrap(source) }
                    if (status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP && source.hasRemaining()) {
                        awaitInboundProgress()
                    }
                }
            }
        } catch (error: Exception) {
            fail(error)
        }
    }

    /**
     * TLS 1.3 post-handshake records (NewSessionTicket, KeyUpdate) leave some engines
     * (Conscrypt against an OpenSSL/Node peer) in NEED_UNWRAP, where wrap consumes nothing
     * until the pending inbound record is processed. Wait for the unwrap loop to make
     * progress instead of spinning; a peer that never delivers fails the channel instead
     * of deadlocking the write path.
     */
    private suspend fun awaitInboundProgress() {
        try {
            withTimeout(TLS_QUEUE_TIMEOUT_MILLIS) { inboundProgress.receive() }
        } catch (error: TimeoutCancellationException) {
            throw NetworkException(NetworkError.TRANSPORT_EXHAUSTED, "TLS write stalled awaiting post-handshake inbound", error)
        }
    }

    private suspend fun emitWrap(source: ByteBuffer): SSLEngineResult.HandshakeStatus {
        var target = ByteBuffer.allocate(tlsBufferSize(engine.session.packetBufferSize, 1_024))
        while (true) {
            target.clear()
            val result = engine.wrap(source, target)
            when (result.status) {
                SSLEngineResult.Status.OK, SSLEngineResult.Status.CLOSED -> {
                    target.flip()
                    if (target.hasRemaining()) underlying.write(target.toByteArray())
                }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    target = grow(target)
                    continue
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> tlsFailure("TLS wrap underflow")
            }
            runTasks(result.handshakeStatus, engine)
            return result.handshakeStatus
        }
    }

    private suspend fun unwrapLoop() {
        var encrypted = ByteBuffer.allocate(tlsBufferSize(engine.session.packetBufferSize, MAX_CHANNEL_CHUNK_BYTES))
        var clear = ByteBuffer.allocate(tlsBufferSize(engine.session.applicationBufferSize, MAX_CHANNEL_CHUNK_BYTES))
        val chunk = ByteArray(MAX_CHANNEL_CHUNK_BYTES)
        try {
            if (carryover.decrypted.isNotEmpty()) {
                try {
                    withTimeout(TLS_QUEUE_TIMEOUT_MILLIS) { inbound.send(carryover.decrypted) }
                } catch (error: TimeoutCancellationException) {
                    throw NetworkException(NetworkError.TRANSPORT_EXHAUSTED, "TLS inbound queue did not make progress", error)
                }
            }
            if (carryover.encrypted.isNotEmpty()) {
                if (encrypted.remaining() < carryover.encrypted.size) encrypted = growFor(encrypted, carryover.encrypted.size)
                encrypted.put(carryover.encrypted)
                encrypted.flip()
                if (drainEncrypted(encrypted, clear)) return
                encrypted.compact()
                flushClear(clear)
            }
            while (!closed.get()) {
                val count = underlying.read(chunk)
                if (count == -1) {
                    try {
                        engine.closeInbound()
                    } catch (error: SSLException) {
                        throw NetworkException(NetworkError.TLS_HANDSHAKE_FAILED, "TLS stream was truncated", error)
                    }
                    break
                }
                if (count == 0) continue
                if (encrypted.remaining() < count) encrypted = growFor(encrypted, count)
                encrypted.put(chunk, 0, count)
                encrypted.flip()
                if (drainEncrypted(encrypted, clear)) return
                encrypted.compact()
                flushClear(clear)
            }
            inbound.close()
        } catch (error: Exception) {
            fail(error)
        }
    }

    private suspend fun drainEncrypted(encrypted: ByteBuffer, clearInput: ByteBuffer): Boolean {
        var clear = clearInput
        while (encrypted.hasRemaining()) {
            val result = engine.unwrap(encrypted, clear)
            when (result.status) {
                SSLEngineResult.Status.OK -> Unit
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> break
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    clear = grow(clear)
                    continue
                }
                SSLEngineResult.Status.CLOSED -> {
                    flushClear(clear)
                    inbound.close()
                    return true
                }
            }
            runTasks(result.handshakeStatus, engine)
            if (result.bytesConsumed() > 0) inboundProgress.trySend(Unit)
            if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                wrapMutex.withLock { emitWrap(EMPTY_BUFFER.duplicate()) }
            }
            flushClear(clear)
        }
        return false
    }

    private suspend fun flushClear(buffer: ByteBuffer) {
        if (buffer.position() == 0) return
        buffer.flip()
        val bytes = buffer.toByteArray()
        buffer.clear()
        try {
            withTimeout(TLS_QUEUE_TIMEOUT_MILLIS) { inbound.send(bytes) }
        } catch (error: TimeoutCancellationException) {
            throw NetworkException(NetworkError.TRANSPORT_EXHAUSTED, "TLS inbound queue did not make progress", error)
        } catch (error: CancellationException) {
            throw error
        }
    }

    private suspend fun fail(error: Throwable) {
        if (closed.compareAndSet(false, true)) {
            outbound.close(error)
            inbound.close(error)
            inboundProgress.close()
            runCatching { underlying.close() }
            scope.cancel("TLS channel failed", error)
        }
    }

    companion object {
        suspend fun connect(
            underlying: DuplexByteChannel,
            context: TlsClientContext,
            peerHost: String,
            peerPort: Int,
        ): TlsByteChannel {
            val engine = context.newEngine(peerHost, peerPort)
            val carryover: TlsHandshakeCarryover
            val handshakeTimedOut = AtomicBoolean(false)
            try {
                coroutineScope {
                    val watchdog = launch {
                        delay(TLS_HANDSHAKE_TIMEOUT_MILLIS)
                        handshakeTimedOut.set(true)
                        underlying.close()
                    }
                    try {
                        carryover = handshake(underlying, engine)
                    } finally {
                        watchdog.cancelAndJoin()
                    }
                }
                context.authenticate(engine.session)
                return TlsByteChannel(underlying, engine, carryover)
            } catch (error: NetworkException) {
                underlying.close()
                throw handshakeError(error, handshakeTimedOut.get())
            } catch (error: Exception) {
                underlying.close()
                throw handshakeError(error, handshakeTimedOut.get())
            }
        }

        private fun handshakeError(cause: Exception, timedOut: Boolean): NetworkException {
            if (cause is NetworkException && !timedOut) return cause
            val message = if (timedOut) {
                "TLS 1.3 handshake timed out after $TLS_HANDSHAKE_TIMEOUT_MILLIS ms"
            } else {
                "TLS 1.3 handshake failed"
            }
            return NetworkException(NetworkError.TLS_HANDSHAKE_FAILED, message, cause)
        }

        internal suspend fun handshake(channel: DuplexByteChannel, engine: SSLEngine): TlsHandshakeCarryover {
            var encryptedInput = ByteBuffer.allocate(tlsBufferSize(engine.session.packetBufferSize, MAX_CHANNEL_CHUNK_BYTES))
            var encryptedOutput = ByteBuffer.allocate(tlsBufferSize(engine.session.packetBufferSize, MAX_CHANNEL_CHUNK_BYTES))
            var clear = ByteBuffer.allocate(tlsBufferSize(engine.session.applicationBufferSize, MAX_CHANNEL_CHUNK_BYTES))
            var decrypted = ByteArray(0)
            val readBuffer = ByteArray(MAX_CHANNEL_CHUNK_BYTES)
            engine.beginHandshake()
            var status = engine.handshakeStatus
            while (status != SSLEngineResult.HandshakeStatus.FINISHED && status != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                when (status) {
                    SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                        runTasks(status, engine)
                        status = engine.handshakeStatus
                    }
                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                        encryptedOutput.clear()
                        val result = engine.wrap(EMPTY_BUFFER.duplicate(), encryptedOutput)
                        if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                            encryptedOutput = grow(encryptedOutput)
                        } else if (result.status != SSLEngineResult.Status.OK) {
                            tlsFailure("TLS handshake wrap failed")
                        } else {
                            encryptedOutput.flip()
                            if (encryptedOutput.hasRemaining()) channel.write(encryptedOutput.toByteArray())
                            status = result.handshakeStatus
                        }
                    }
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                        encryptedInput.flip()
                        val result = engine.unwrap(encryptedInput, clear)
                        encryptedInput.compact()
                        when (result.status) {
                            SSLEngineResult.Status.OK -> {
                                if (clear.position() > 0) {
                                    clear.flip()
                                    decrypted += clear.toByteArray()
                                    clear.clear()
                                } else {
                                    clear.clear()
                                }
                                status = result.handshakeStatus
                            }
                            SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                                val count = channel.read(readBuffer)
                                if (count <= 0) tlsFailure("TLS peer closed during handshake")
                                if (encryptedInput.remaining() < count) encryptedInput = growFor(encryptedInput, count)
                                encryptedInput.put(readBuffer, 0, count)
                                status = engine.handshakeStatus
                            }
                            SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                                clear = grow(clear)
                                status = engine.handshakeStatus
                            }
                            SSLEngineResult.Status.CLOSED -> tlsFailure("TLS peer closed during handshake")
                        }
                    }
                    SSLEngineResult.HandshakeStatus.FINISHED, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> Unit
                }
            }
            encryptedInput.flip()
            val carryoverEncrypted = encryptedInput.toByteArray()
            return TlsHandshakeCarryover(carryoverEncrypted, decrypted)
        }
    }
}

private fun runTasks(status: SSLEngineResult.HandshakeStatus, engine: SSLEngine? = null) {
    if (status != SSLEngineResult.HandshakeStatus.NEED_TASK) return
    val target = engine ?: return
    while (true) (target.delegatedTask ?: break).run()
}

private fun ByteBuffer.toByteArray(): ByteArray = ByteArray(remaining()).also(::get)

private fun tlsBufferSize(requested: Int, minimum: Int): Int {
    if (requested <= 0 || requested > MAX_TLS_BUFFER_BYTES || minimum > MAX_TLS_BUFFER_BYTES) {
        throw NetworkException(NetworkError.TRANSPORT_EXHAUSTED, "TLS buffer request exceeds its bound")
    }
    return maxOf(requested, minimum)
}

private fun grow(buffer: ByteBuffer): ByteBuffer {
    if (buffer.capacity() >= MAX_TLS_BUFFER_BYTES) throw NetworkException(NetworkError.TRANSPORT_EXHAUSTED, "TLS buffer exceeds its bound")
    val enlarged = ByteBuffer.allocate(minOf(MAX_TLS_BUFFER_BYTES, buffer.capacity() * 2))
    buffer.flip()
    enlarged.put(buffer)
    return enlarged
}

private fun growFor(buffer: ByteBuffer, additional: Int): ByteBuffer {
    var target = buffer
    while (target.remaining() < additional) target = grow(target)
    return target
}

private fun tlsFailure(message: String): Nothing = throw NetworkException(NetworkError.TLS_HANDSHAKE_FAILED, message)
