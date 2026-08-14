package io.github.verybigsad.pimobile.network

import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.protocol.FrameKind
import io.github.verybigsad.pimobile.protocol.PimbCodec
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/**
 * Deterministic reproduction of the live relay-splice defect: a TLS 1.3 peer that emits
 * post-handshake NewSessionTicket (Node/OpenSSL) leaves a Conscrypt-style client engine in
 * NEED_UNWRAP, where wrap consumes nothing until the pending inbound record is processed.
 * The channel write path must pump that inbound instead of stalling the first application
 * write (the PIMB pair.begin frame) forever.
 *
 * The scripted engine mimics the Conscrypt state machine faithfully: wrap reports
 * OK + NEED_UNWRAP with zero progress while a session ticket is pending, and only a
 * matching unwrap clears it. JSSE never stalls this way, so a real-engine pair cannot
 * reproduce the mechanism on the JVM.
 */
class TlsPostHandshakeWriteTest {

    @Test
    fun writePumpsPendingSessionTicketInsteadOfStalling() = runBlocking {
        withTimeout(TEST_TIMEOUT_MILLIS) {
            val engine = PostHandshakeStallingEngine()
            val endpoint = ScriptedEndpoint()
            val tls = TlsByteChannel(endpoint, engine, TlsHandshakeCarryover(ByteArray(0), ByteArray(0)))

            // The Node server flight: NewSessionTicket arrives shortly after the handshake,
            // while the client's first application write is already queued. Until it lands,
            // the engine reports NEED_UNWRAP and consumes nothing.
            val ticketFlight = launch { delay(TICKET_FLIGHT_DELAY_MILLIS); endpoint.offer(PostHandshakeStallingEngine.SESSION_TICKET) }
            val frame = PimbCodec.encode(FrameKind.Json, """{"type":"pair.begin"}""".encodeToByteArray())
            tls.write(frame)

            assertThat(endpoint.takeClientBytes()).isEqualTo(frame)
            ticketFlight.join()
            // The write path must wait for inbound progress, not busy-spin on wrap: a
            // spinning wrap loop issues unbounded wrap calls for the whole ticket flight.
            assertThat(engine.wrapCalls.get()).isLessThan(MAX_EXPECTED_WRAP_CALLS)
            tls.close()
        }
    }

    @Test
    fun writePumpsSessionTicketCarriedOverFromHandshake() = runBlocking {
        withTimeout(TEST_TIMEOUT_MILLIS) {
            val engine = PostHandshakeStallingEngine()
            val endpoint = ScriptedEndpoint()
            // The ticket was coalesced with the server Finished flight: it surfaces as
            // handshake carryover rather than a fresh transport read.
            val tls = TlsByteChannel(
                endpoint,
                engine,
                TlsHandshakeCarryover(PostHandshakeStallingEngine.SESSION_TICKET, ByteArray(0)),
            )

            val frame = PimbCodec.encode(FrameKind.Json, """{"type":"pair.begin"}""".encodeToByteArray())
            tls.write(frame)

            assertThat(endpoint.takeClientBytes()).isEqualTo(frame)
            tls.close()
        }
    }

    @Test
    fun concurrentInboundAndOutboundRecordsNeverEnterEngineTogether() = runBlocking {
        val dispatcher = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
        try {
            withTimeout(TEST_TIMEOUT_MILLIS) {
                val probe = EngineOverlapProbe()
                val unwrapAttempted = CompletableDeferred<Unit>()
                val engine = PostHandshakeStallingEngine(ticketPendingAtStart = false, overlapProbe = probe)
                val endpoint = ScriptedEndpoint()
                val tls = TlsByteChannel(
                    endpoint,
                    engine,
                    TlsHandshakeCarryover(ByteArray(0), ByteArray(0)),
                    dispatcher,
                ) { unwrapAttempted.complete(Unit) }
                endpoint.readWaiting.await()

                val outbound = PimbCodec.encode(FrameKind.Json, """{"type":"sync.resume"}""".encodeToByteArray())
                tls.write(outbound)
                probe.wrapStarted.await()

                val inbound = PimbCodec.encode(FrameKind.Json, """{"type":"auth.result"}""".encodeToByteArray())
                endpoint.offer(inbound)
                endpoint.readStarted.await()
                unwrapAttempted.await()
                assertThat(probe.concurrentEntry.get()).isFalse()
                probe.releaseWrap()
                assertThat(tls.readExact(inbound.size)).isEqualTo(inbound)
                assertThat(endpoint.takeClientBytes()).isEqualTo(outbound)
                assertThat(probe.concurrentEntry.get()).isFalse()
                tls.close()
            }
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun engineLockIsReleasedBeforeTransportWriteCanBlock() = runBlocking {
        withTimeout(TEST_TIMEOUT_MILLIS) {
            val engine = PostHandshakeStallingEngine(ticketPendingAtStart = false)
            val endpoint = ScriptedEndpoint(blockWrites = true)
            val tls = TlsByteChannel(endpoint, engine, TlsHandshakeCarryover(ByteArray(0), ByteArray(0)))

            val outbound = PimbCodec.encode(FrameKind.Json, """{"type":"sync.resume"}""".encodeToByteArray())
            tls.write(outbound)
            endpoint.writeStarted.await()

            val inbound = PimbCodec.encode(FrameKind.Json, """{"type":"auth.result"}""".encodeToByteArray())
            endpoint.offer(inbound)
            assertThat(tls.readExact(inbound.size)).isEqualTo(inbound)
            endpoint.releaseWrite.complete(Unit)
            assertThat(endpoint.takeClientBytes()).isEqualTo(outbound)
            tls.close()
        }
    }

    @Test
    fun unexpectedPostHandshakeDelegatedTaskFailsClosedWithoutRunning() = runBlocking {
        withTimeout(TEST_TIMEOUT_MILLIS) {
            val engine = PostHandshakeStallingEngine(
                ticketPendingAtStart = false,
                requestTaskOnFirstWrap = true,
            )
            val endpoint = ScriptedEndpoint()
            val tls = TlsByteChannel(endpoint, engine, TlsHandshakeCarryover(ByteArray(0), ByteArray(0)))

            val outbound = PimbCodec.encode(FrameKind.Json, """{"type":"sync.resume"}""".encodeToByteArray())
            tls.write(outbound)

            endpoint.closed.await()
            assertThat(engine.delegatedTaskRan.get()).isFalse()
            tls.close()
        }
    }

    @Test
    fun unwrapNeedWrapDoesNotBlockInboundPumpOnTransportWrite() = runBlocking {
        withTimeout(TEST_TIMEOUT_MILLIS) {
            val engine = PostHandshakeStallingEngine(
                ticketPendingAtStart = false,
                requestWrapAfterFirstApplicationUnwrap = true,
            )
            val endpoint = ScriptedEndpoint(blockWrites = true)
            val tls = TlsByteChannel(endpoint, engine, TlsHandshakeCarryover(ByteArray(0), ByteArray(0)))
            endpoint.readWaiting.await()

            val first = PimbCodec.encode(FrameKind.Json, """{"type":"auth.result"}""".encodeToByteArray())
            endpoint.offer(first)
            assertThat(tls.readExact(first.size)).isEqualTo(first)
            endpoint.writeStarted.await()

            val second = PimbCodec.encode(FrameKind.Json, """{"type":"sync.event"}""".encodeToByteArray())
            endpoint.offer(second)
            assertThat(tls.readExact(second.size)).isEqualTo(second)

            endpoint.releaseWrite.complete(Unit)
            assertThat(endpoint.takeClientBytes()).isEqualTo(PostHandshakeStallingEngine.KEY_UPDATE_RESPONSE)
            tls.close()
        }
    }

    @Test
    fun closeDoesNotWaitForBlockedTransportWrite() = runBlocking {
        withTimeout(TEST_TIMEOUT_MILLIS) {
            val engine = PostHandshakeStallingEngine(ticketPendingAtStart = false)
            val endpoint = ScriptedEndpoint(blockWrites = true)
            val tls = TlsByteChannel(endpoint, engine, TlsHandshakeCarryover(ByteArray(0), ByteArray(0)))

            val outbound = PimbCodec.encode(FrameKind.Json, """{"type":"sync.resume"}""".encodeToByteArray())
            tls.write(outbound)
            endpoint.writeStarted.await()

            tls.close()
            endpoint.closed.await()
        }
    }

    @Test
    fun readDeliversApplicationDataArrivingRightAfterHandshake() = runBlocking {
        withTimeout(TEST_TIMEOUT_MILLIS) {
            val engine = PostHandshakeStallingEngine()
            val endpoint = ScriptedEndpoint()
            val tls = TlsByteChannel(endpoint, engine, TlsHandshakeCarryover(ByteArray(0), ByteArray(0)))

            val challenge = PimbCodec.encode(FrameKind.Json, """{"type":"pairing.challenge"}""".encodeToByteArray())
            endpoint.offer(PostHandshakeStallingEngine.SESSION_TICKET + challenge)
            assertThat(tls.readExact(challenge.size)).isEqualTo(challenge)

            val frame = PimbCodec.encode(FrameKind.Json, """{"type":"pair.begin"}""".encodeToByteArray())
            tls.write(frame)
            assertThat(endpoint.takeClientBytes()).isEqualTo(frame)
            tls.close()
        }
    }

    private suspend fun TlsByteChannel.readExact(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(result, offset, size - offset)
            if (count < 0) break
            offset += count
        }
        check(offset == size) { "Channel closed after $offset of $size bytes" }
        return result
    }

    /**
     * Conscrypt-style post-handshake state machine: while [SESSION_TICKET] is pending,
     * wrap makes zero progress and reports NEED_UNWRAP; unwrapping the ticket clears it.
     * Application bytes pass through untransformed so the peer can assert exact content.
     */
    private class PostHandshakeStallingEngine(
        ticketPendingAtStart: Boolean = true,
        private val overlapProbe: EngineOverlapProbe? = null,
        private val requestWrapAfterFirstApplicationUnwrap: Boolean = false,
        private val requestTaskOnFirstWrap: Boolean = false,
    ) : SSLEngine() {
        val wrapCalls = AtomicInteger()
        val delegatedTaskRan = AtomicBoolean(false)
        private var ticketPending = ticketPendingAtStart
        private var wrapResponsePending = false
        private var wrapRequested = false
        private var taskRequested = false
        private var taskReturned = false
        private var outboundClosed = false

        override fun wrap(source: ByteBuffer, target: ByteBuffer): SSLEngineResult {
            overlapProbe?.enterWrap()
            try {
                wrapCalls.incrementAndGet()
                if (ticketPending) {
                    return SSLEngineResult(SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NEED_UNWRAP, 0, 0)
                }
                if (wrapResponsePending) {
                    target.put(KEY_UPDATE_RESPONSE)
                    wrapResponsePending = false
                    return SSLEngineResult(
                        SSLEngineResult.Status.OK,
                        SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING,
                        0,
                        KEY_UPDATE_RESPONSE.size,
                    )
                }
                if (requestTaskOnFirstWrap && !taskRequested) {
                    taskRequested = true
                    return SSLEngineResult(
                        SSLEngineResult.Status.OK,
                        SSLEngineResult.HandshakeStatus.NEED_TASK,
                        0,
                        0,
                    )
                }
                val count = minOf(source.remaining(), target.remaining())
                val chunk = ByteArray(count)
                source.get(chunk)
                target.put(chunk)
                val status = if (outboundClosed) SSLEngineResult.Status.CLOSED else SSLEngineResult.Status.OK
                return SSLEngineResult(status, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, count, count)
            } finally {
                overlapProbe?.leave()
            }
        }

        override fun wrap(sources: Array<ByteBuffer>, offset: Int, length: Int, target: ByteBuffer): SSLEngineResult =
            wrap(sources[offset], target)

        override fun unwrap(source: ByteBuffer, target: ByteBuffer): SSLEngineResult {
            overlapProbe?.enterUnwrap()
            try {
                if (ticketPending) {
                    if (source.remaining() < SESSION_TICKET.size) {
                        return SSLEngineResult(SSLEngineResult.Status.BUFFER_UNDERFLOW, SSLEngineResult.HandshakeStatus.NEED_UNWRAP, 0, 0)
                    }
                    val head = ByteArray(SESSION_TICKET.size)
                    source.get(head)
                    check(head.contentEquals(SESSION_TICKET)) { "Unexpected post-handshake record" }
                    ticketPending = false
                    return SSLEngineResult(
                        SSLEngineResult.Status.OK,
                        SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING,
                        SESSION_TICKET.size,
                        0,
                    )
                }
                val count = minOf(source.remaining(), target.remaining())
                val chunk = ByteArray(count)
                source.get(chunk)
                target.put(chunk)
                val handshakeStatus = if (requestWrapAfterFirstApplicationUnwrap && !wrapRequested) {
                    wrapRequested = true
                    wrapResponsePending = true
                    SSLEngineResult.HandshakeStatus.NEED_WRAP
                } else {
                    SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                }
                return SSLEngineResult(SSLEngineResult.Status.OK, handshakeStatus, count, count)
            } finally {
                overlapProbe?.leave()
            }
        }

        override fun unwrap(source: ByteBuffer, targets: Array<ByteBuffer>, offset: Int, length: Int): SSLEngineResult =
            unwrap(source, targets[offset])

        override fun getSession(): SSLSession = FakeSession
        override fun beginHandshake() = Unit
        override fun getHandshakeStatus(): SSLEngineResult.HandshakeStatus = when {
            ticketPending -> SSLEngineResult.HandshakeStatus.NEED_UNWRAP
            wrapResponsePending -> SSLEngineResult.HandshakeStatus.NEED_WRAP
            else -> SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        }
        override fun getDelegatedTask(): Runnable? = if (taskRequested && !taskReturned) {
            taskReturned = true
            Runnable { delegatedTaskRan.set(true) }
        } else {
            null
        }
        override fun closeInbound() = Unit
        override fun closeOutbound() {
            outboundClosed = true
        }
        override fun isInboundDone(): Boolean = false
        override fun isOutboundDone(): Boolean = outboundClosed
        override fun getUseClientMode(): Boolean = true
        override fun setUseClientMode(mode: Boolean) = Unit
        override fun getNeedClientAuth(): Boolean = false
        override fun setNeedClientAuth(need: Boolean) = Unit
        override fun getWantClientAuth(): Boolean = false
        override fun setWantClientAuth(want: Boolean) = Unit
        override fun getEnableSessionCreation(): Boolean = true
        override fun setEnableSessionCreation(flag: Boolean) = Unit
        override fun getSupportedProtocols(): Array<String> = arrayOf("TLSv1.3")
        override fun getEnabledProtocols(): Array<String> = arrayOf("TLSv1.3")
        override fun setEnabledProtocols(protocols: Array<String>) = Unit
        override fun getSupportedCipherSuites(): Array<String> = arrayOf("TLS_AES_128_GCM_SHA256")
        override fun getEnabledCipherSuites(): Array<String> = arrayOf("TLS_AES_128_GCM_SHA256")
        override fun setEnabledCipherSuites(suites: Array<String>) = Unit

        companion object {
            val KEY_UPDATE_RESPONSE: ByteArray = byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x01, 0x4B)
            val SESSION_TICKET: ByteArray = byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x4E, 0x54) +
                "new-session-ticket".encodeToByteArray()
        }
    }

    private class EngineOverlapProbe {
        val wrapStarted = CompletableDeferred<Unit>()
        val concurrentEntry = AtomicBoolean(false)
        private val activeCalls = AtomicInteger()
        private val wrapGate = CountDownLatch(1)

        fun enterWrap() {
            enter()
            wrapStarted.complete(Unit)
            check(wrapGate.await(5, TimeUnit.SECONDS))
        }

        fun releaseWrap() = wrapGate.countDown()

        fun enterUnwrap() = enter()

        fun leave() {
            check(activeCalls.decrementAndGet() >= 0)
        }

        private fun enter() {
            if (activeCalls.incrementAndGet() != 1) concurrentEntry.set(true)
        }
    }

    private object FakeSession : SSLSession {
        override fun getPacketBufferSize(): Int = 16_709
        override fun getApplicationBufferSize(): Int = 16_384
        override fun getProtocol(): String = "TLSv1.3"
        override fun getCipherSuite(): String = "TLS_AES_128_GCM_SHA256"
        override fun getPeerHost(): String = "localhost"
        override fun getPeerPort(): Int = 443
        override fun getId(): ByteArray = byteArrayOf(1)
        override fun getSessionContext() = null
        override fun getCreationTime(): Long = 0
        override fun getLastAccessedTime(): Long = 0
        override fun invalidate() = Unit
        override fun isValid(): Boolean = true
        override fun putValue(name: String, value: Any) = Unit
        override fun getValue(name: String): Any? = null
        override fun removeValue(name: String) = Unit
        override fun getValueNames(): Array<String> = emptyArray()
        override fun getPeerCertificates() = throw javax.net.ssl.SSLPeerUnverifiedException("scripted")
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getPeerCertificateChain(): Array<javax.security.cert.X509Certificate> =
            throw javax.net.ssl.SSLPeerUnverifiedException("scripted")
        override fun getLocalCertificates() = null
        override fun getPeerPrincipal() = throw javax.net.ssl.SSLPeerUnverifiedException("scripted")
        override fun getLocalPrincipal() = null
    }

    private class ScriptedEndpoint(
        private val blockWrites: Boolean = false,
    ) : DuplexByteChannel {
        private val toClient = Channel<ByteArray>(Channel.UNLIMITED)
        private val fromClient = Channel<ByteArray>(Channel.UNLIMITED)
        val readWaiting = CompletableDeferred<Unit>()
        val readStarted = CompletableDeferred<Unit>()
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        private var pending = ByteArray(0)
        private var pendingOffset = 0

        suspend fun offer(bytes: ByteArray) = toClient.send(bytes)

        suspend fun takeClientBytes(): ByteArray = fromClient.receive()

        override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
            while (pendingOffset >= pending.size) {
                readWaiting.complete(Unit)
                pending = toClient.receiveCatching().getOrNull() ?: return -1
                pendingOffset = 0
            }
            readStarted.complete(Unit)
            val count = minOf(length, pending.size - pendingOffset)
            pending.copyInto(destination, offset, pendingOffset, pendingOffset + count)
            pendingOffset += count
            return count
        }

        override suspend fun write(source: ByteArray, offset: Int, length: Int) {
            writeStarted.complete(Unit)
            if (blockWrites) releaseWrite.await()
            fromClient.send(source.copyOfRange(offset, offset + length))
        }

        override suspend fun close() {
            closed.complete(Unit)
            toClient.close()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 20_000L
        const val TICKET_FLIGHT_DELAY_MILLIS = 300L
        const val MAX_EXPECTED_WRAP_CALLS = 100
    }
}
