package io.github.verybigsad.pimobile.network

import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.protocol.FrameKind
import io.github.verybigsad.pimobile.protocol.PimbCodec
import java.nio.ByteBuffer
import java.time.Clock
import java.time.ZoneOffset
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/**
 * Deterministic coverage for the handshake-to-channel carryover: a peer (such as the
 * mac daemon over the relay) may deliver its first application record coalesced in the
 * same transport flight as its TLS Finished. JSSE cannot generate such a flight from a
 * server-mode engine (it refuses application writes before the client Finished), so the
 * connected channel is exercised white-box with real engines and real records, while the
 * handshake capture is exercised against a scripted server flight with a trailing record.
 */
class TlsHandshakeCarryoverTest {
    private val clock = Clock.fixed(TestPki.server.notBefore.toInstant().plusSeconds(60), ZoneOffset.UTC)
    private val serverIdentity = CertificateIdentity(CertificateRole.MAC_SERVER, "test-mac")

    @Test
    fun channelDrainsCarriedCleartextThenCarriedCiphertextThenTransport() = runBlocking {
        withTimeout(TEST_TIMEOUT_MILLIS) {
            val pair = PumpedEngines(newClientEngine(), newServerEngine()).apply { completeHandshake() }
            val frame = PimbCodec.encode(FrameKind.Json, """{"type":"pairing.challenge"}""".encodeToByteArray())
            val carriedRecord = pair.wrapServerApplicationData(frame)
            val carriedCleartext = "produced-but-unconsumed".encodeToByteArray()
            val endpoint = ScriptedEndpoint()
            val tls = TlsByteChannel(endpoint, pair.client, TlsHandshakeCarryover(carriedRecord, carriedCleartext))

            // Carried cleartext is drained first, then the carried ciphertext record,
            // without any transport read (the endpoint queue is empty until the follow-up).
            assertThat(tls.readExact(carriedCleartext.size)).isEqualTo(carriedCleartext)
            assertThat(tls.readExact(frame.size)).isEqualTo(frame)

            // Records arriving over the transport afterwards are still delivered.
            val followup = PimbCodec.encode(FrameKind.Json, """{"type":"pairing.complete"}""".encodeToByteArray())
            endpoint.offer(pair.wrapServerApplicationData(followup))
            assertThat(tls.readExact(followup.size)).isEqualTo(followup)

            // Reverse direction: the client frame written right after connect must reach
            // the peer intact.
            val reply = PimbCodec.encode(FrameKind.Json, """{"type":"pairing.response"}""".encodeToByteArray())
            tls.write(reply)
            assertThat(pair.unwrapServerApplicationData(endpoint.takeClientBytes())).isEqualTo(reply)

            tls.close()
        }
    }

    @Test
    fun channelReassemblesFragmentedCarriedRecord() = runBlocking {
        withTimeout(TEST_TIMEOUT_MILLIS) {
            val pair = PumpedEngines(newClientEngine(), newServerEngine()).apply { completeHandshake() }
            val frame = PimbCodec.encode(FrameKind.Json, """{"type":"pairing.challenge","fragmented":true}""".encodeToByteArray())
            val carriedRecord = pair.wrapServerApplicationData(frame)
            val split = 9
            val endpoint = ScriptedEndpoint()
            val tls = TlsByteChannel(
                endpoint,
                pair.client,
                TlsHandshakeCarryover(carriedRecord.copyOfRange(0, split), ByteArray(0)),
            )

            // Only the record head was carried over; the tail arrives later.
            endpoint.offer(carriedRecord.copyOfRange(split, carriedRecord.size))
            assertThat(tls.readExact(frame.size)).isEqualTo(frame)

            val followup = "after-fragment".encodeToByteArray()
            endpoint.offer(pair.wrapServerApplicationData(followup))
            assertThat(tls.readExact(followup.size)).isEqualTo(followup)

            tls.close()
        }
    }

    @Test
    fun handshakeStopsAtCompletionAndCapturesLeftoverCiphertext() = runBlocking {
        withTimeout(TEST_TIMEOUT_MILLIS) {
            // Ciphertext trailing the server Finished in the same flight; its content is
            // irrelevant here because the handshake must stop consuming at completion.
            val leftover = ByteArray(113) { (it * 31 + 7).toByte() }
            val server = FlightScriptedServer(leftover)
            val driver = launch(Dispatchers.IO) { server.drive() }
            try {
                val context = TlsContexts.provisional(TestPki.serverPin(), serverIdentity, clock)
                val engine = context.newEngine("localhost", TEST_PORT)

                val carryover = TlsByteChannel.handshake(server.clientEndpoint, engine)

                assertThat(engine.handshakeStatus).isEqualTo(SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)
                assertThat(carryover.encrypted).isEqualTo(leftover)
                assertThat(carryover.decrypted).isEmpty()
                context.authenticate(engine.session)
            } finally {
                driver.cancelAndJoin()
            }
        }
    }

    private fun newClientEngine(): SSLEngine = TlsContexts.provisional(TestPki.serverPin(), serverIdentity, clock)
        .newEngine("localhost", TEST_PORT)

    private fun newServerEngine(): SSLEngine = TestPki.serverContext(false).createSSLEngine().apply {
        useClientMode = false
        enabledProtocols = arrayOf("TLSv1.3")
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

    /** In-memory engine-pair pump producing valid, fully handshaked client/server engines. */
    private class PumpedEngines(
        val client: SSLEngine,
        val server: SSLEngine,
    ) {
        private val clientToServer = ByteBuffer.allocate(PUMP_BUFFER_BYTES)
        private val serverToClient = ByteBuffer.allocate(PUMP_BUFFER_BYTES)

        fun completeHandshake() {
            client.beginHandshake()
            server.beginHandshake()
            while (true) {
                val drained = isWriteModeEmpty(clientToServer) && isWriteModeEmpty(serverToClient)
                if (drained && isIdle(client) && isIdle(server)) return
                val progressed = pump(client, clientToServer) or pump(server, serverToClient) or
                    drain(client, serverToClient) or drain(server, clientToServer)
                check(progressed) {
                    "Engine handshake pump stalled: client=${client.handshakeStatus} server=${server.handshakeStatus} " +
                        "c2s=${clientToServer.position()} s2c=${serverToClient.position()}"
                }
            }
        }

        fun wrapServerApplicationData(plaintext: ByteArray): ByteArray = wrap(server, plaintext)

        fun unwrapServerApplicationData(ciphertext: ByteArray): ByteArray {
            val source = ByteBuffer.wrap(ciphertext)
            val plaintext = ByteBuffer.allocate(PUMP_BUFFER_BYTES)
            while (source.hasRemaining()) {
                val result = server.unwrap(source, plaintext)
                check(result.status == SSLEngineResult.Status.OK) { "Server unwrap failed: $result" }
                if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) runDelegatedTasks(server)
                if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) break
            }
            plaintext.flip()
            return plaintext.toByteArray()
        }

        private fun pump(engine: SSLEngine, outbound: ByteBuffer): Boolean {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> runDelegatedTasks(engine)
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val record = ByteBuffer.allocate(PUMP_BUFFER_BYTES)
                    val result = engine.wrap(EMPTY, record)
                    check(result.status == SSLEngineResult.Status.OK) { "Handshake wrap failed: $result" }
                    record.flip()
                    check(outbound.remaining() >= record.remaining()) { "Pump buffer overflow" }
                    outbound.put(record)
                    if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) runDelegatedTasks(engine)
                }
                else -> return false
            }
            return true
        }

        private fun drain(engine: SSLEngine, inbound: ByteBuffer): Boolean {
            if (isWriteModeEmpty(inbound)) return false
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP,
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING,
                SSLEngineResult.HandshakeStatus.FINISHED,
                -> Unit
                else -> return false
            }
            inbound.flip()
            val result = engine.unwrap(inbound, ByteBuffer.allocate(PUMP_BUFFER_BYTES))
            inbound.compact()
            check(result.status == SSLEngineResult.Status.OK) { "Handshake unwrap failed: $result" }
            if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) runDelegatedTasks(engine)
            return true
        }

        private fun wrap(engine: SSLEngine, plaintext: ByteArray): ByteArray {
            val destination = ByteBuffer.allocate(PUMP_BUFFER_BYTES)
            val result = engine.wrap(ByteBuffer.wrap(plaintext), destination)
            check(result.status == SSLEngineResult.Status.OK && result.bytesConsumed() == plaintext.size) {
                "Application wrap failed: $result"
            }
            destination.flip()
            return destination.toByteArray()
        }

        private fun isIdle(engine: SSLEngine): Boolean = when (engine.handshakeStatus) {
            SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, SSLEngineResult.HandshakeStatus.FINISHED -> true
            else -> false
        }

        private fun isWriteModeEmpty(buffer: ByteBuffer): Boolean = buffer.position() == 0
    }

    /**
     * Replays a real server flight from a live server engine, appending [leftover] bytes
     * to the final flight chunk (the one carrying the server Finished).
     */
    private class FlightScriptedServer(
        private val leftover: ByteArray,
    ) {
        val clientEndpoint: DuplexByteChannel = Endpoint()

        private val toClient = Channel<ByteArray>(Channel.UNLIMITED)
        private val fromClient = Channel<ByteArray>(Channel.UNLIMITED)
        private val engine = TestPki.serverContext(false).createSSLEngine().apply {
            useClientMode = false
            enabledProtocols = arrayOf("TLSv1.3")
        }

        suspend fun drive() = coroutineScope {
            engine.beginHandshake()
            var netIn = ByteBuffer.allocate(PUMP_BUFFER_BYTES)
            var flightComplete = false
            while (isActive) {
                when (engine.handshakeStatus) {
                    SSLEngineResult.HandshakeStatus.NEED_TASK -> runDelegatedTasks(engine)
                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                        val netOut = ByteBuffer.allocate(PUMP_BUFFER_BYTES)
                        val result = engine.wrap(EMPTY, netOut)
                        check(result.status == SSLEngineResult.Status.OK) { "Server handshake wrap failed: $result" }
                        netOut.flip()
                        var chunk = netOut.toByteArray()
                        if (!flightComplete && engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                            chunk += leftover
                            flightComplete = true
                        }
                        toClient.send(chunk)
                    }
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP,
                    SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING,
                    SSLEngineResult.HandshakeStatus.FINISHED,
                    -> {
                        netIn.flip()
                        val result = engine.unwrap(netIn, ByteBuffer.allocate(PUMP_BUFFER_BYTES))
                        netIn.compact()
                        when (result.status) {
                            SSLEngineResult.Status.OK -> {
                                if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) runDelegatedTasks(engine)
                                if (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) return@coroutineScope
                            }
                            SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                                val chunk = fromClient.receiveCatching().getOrNull() ?: return@coroutineScope
                                if (netIn.remaining() < chunk.size) {
                                    val grown = ByteBuffer.allocate(netIn.capacity() * 2)
                                    netIn.flip()
                                    grown.put(netIn)
                                    netIn = grown
                                }
                                netIn.put(chunk)
                            }
                            else -> return@coroutineScope
                        }
                    }
                }
            }
        }

        private inner class Endpoint : DuplexByteChannel {
            private var pending = ByteArray(0)
            private var pendingOffset = 0

            override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
                while (pendingOffset >= pending.size) {
                    pending = toClient.receiveCatching().getOrNull() ?: return -1
                    pendingOffset = 0
                }
                val count = minOf(length, pending.size - pendingOffset)
                pending.copyInto(destination, offset, pendingOffset, pendingOffset + count)
                pendingOffset += count
                return count
            }

            override suspend fun write(source: ByteArray, offset: Int, length: Int) {
                fromClient.send(source.copyOfRange(offset, offset + length))
            }

            override suspend fun close() {
                toClient.close()
            }
        }
    }

    private class ScriptedEndpoint : DuplexByteChannel {
        private val toClient = Channel<ByteArray>(Channel.UNLIMITED)
        private val fromClient = Channel<ByteArray>(Channel.UNLIMITED)
        private var pending = ByteArray(0)
        private var pendingOffset = 0

        suspend fun offer(bytes: ByteArray) = toClient.send(bytes)

        suspend fun takeClientBytes(): ByteArray = fromClient.receive()

        override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
            while (pendingOffset >= pending.size) {
                pending = toClient.receiveCatching().getOrNull() ?: return -1
                pendingOffset = 0
            }
            val count = minOf(length, pending.size - pendingOffset)
            pending.copyInto(destination, offset, pendingOffset, pendingOffset + count)
            pendingOffset += count
            return count
        }

        override suspend fun write(source: ByteArray, offset: Int, length: Int) {
            fromClient.send(source.copyOfRange(offset, offset + length))
        }

        override suspend fun close() {
            toClient.close()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 20_000L
        const val TEST_PORT = 44_443
        const val PUMP_BUFFER_BYTES = 128 * 1_024
        val EMPTY: ByteBuffer = ByteBuffer.allocate(0)

        fun ByteBuffer.toByteArray(): ByteArray = ByteArray(remaining()).also(::get)

        fun runDelegatedTasks(engine: SSLEngine) {
            while (true) (engine.delegatedTask ?: break).run()
        }
    }
}
