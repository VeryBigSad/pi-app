package io.github.verybigsad.pimobile.network

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneOffset
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.concurrent.thread

class WebSocketBinaryByteChannelTest {
    @Test
    fun performsRealWssHandshakeWithoutCompressionAndStreamsMaskedBinary() = runBlocking {
        val server = server()
        val accepted = async(Dispatchers.IO) { serveBinary(server, includeCompression = false) }
        val client = WebSocketBinaryByteChannel.connect(
            URI("wss://localhost:${server.localPort}/v1/routes/route-1/data"),
            mapOf("X-Relay-Proof" to "bounded-proof"),
            clientContext().socketFactory,
        )

        client.write("inner-tls-record".encodeToByteArray())
        val response = ByteArray(64)
        val count = client.read(response)

        assertThat(response.copyOf(count).toString(Charsets.UTF_8)).isEqualTo("opaque-reply")
        val request = accepted.await()
        assertThat(request).contains("X-Relay-Proof: bounded-proof\r\n")
        assertThat(request.lowercase()).doesNotContain("sec-websocket-extensions")
        client.close()
        server.close()
    }

    @Test
    fun rejectsOversizedBinaryLengthBeforePayloadAllocation() = runBlocking {
        val server = server()
        val accepted = async(Dispatchers.IO) { serveBinary(server, includeCompression = false, oversized = true) }
        val client = WebSocketBinaryByteChannel.connect(
            URI("wss://localhost:${server.localPort}/data"),
            sslSocketFactory = clientContext().socketFactory,
        )

        val error = runCatching { client.read(ByteArray(1)) }.exceptionOrNull() as NetworkException

        assertThat(error.code).isEqualTo(NetworkError.TRANSPORT_EXHAUSTED)
        accepted.await()
        client.close()
        server.close()
    }

    @Test
    fun carriesRealNestedTlsThroughRealWssByteTunnel() = runBlocking {
        val innerServer = server()
        val innerPeer = async(Dispatchers.IO) { echoInnerTls(innerServer) }
        val outerServer = server()
        val tunnel = async(Dispatchers.IO) { serveTunnel(outerServer, innerServer.localPort) }
        val websocket = WebSocketBinaryByteChannel.connect(
            URI("wss://localhost:${outerServer.localPort}/data"),
            sslSocketFactory = clientContext().socketFactory,
        )
        val identity = CertificateIdentity(CertificateRole.MAC_SERVER, "test-mac")
        val clock = Clock.fixed(TestPki.server.notBefore.toInstant().plusSeconds(60), ZoneOffset.UTC)
        val tls = TlsByteChannel.connect(
            websocket,
            TlsContexts.provisional(TestPki.serverPin(), identity, clock),
            "localhost",
            innerServer.localPort,
        )

        tls.write("nested-request".encodeToByteArray())
        val response = ByteArray(32)
        val count = tls.read(response)

        assertThat(response.copyOf(count).toString(Charsets.UTF_8)).isEqualTo("nested-response")
        tls.close()
        innerPeer.await()
        tunnel.await()
        innerServer.close()
        outerServer.close()
    }

    @Test
    fun rejectsZeroPortBeforeOpeningSocket() = runBlocking {
        val error = runCatching {
            WebSocketBinaryByteChannel.connect(URI("wss://localhost:0/data"))
        }.exceptionOrNull() as NetworkException

        assertThat(error.code).isEqualTo(NetworkError.WEBSOCKET_HANDSHAKE_FAILED)
    }

    @Test
    fun rejectsAnyCompressionNegotiation() = runBlocking {
        val server = server()
        val accepted = async(Dispatchers.IO) { serveBinary(server, includeCompression = true) }

        val error = runCatching {
            WebSocketBinaryByteChannel.connect(
                URI("wss://localhost:${server.localPort}/data"),
                sslSocketFactory = clientContext().socketFactory,
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(NetworkException::class.java)
        assertThat((error as NetworkException).code).isEqualTo(NetworkError.WEBSOCKET_HANDSHAKE_FAILED)
        accepted.await()
        server.close()
    }

    private fun server(): SSLServerSocket = (TestPki.serverContext().serverSocketFactory
        .createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket).also {
        it.enabledProtocols = arrayOf("TLSv1.3")
    }

    private fun clientContext(): SSLContext = SSLContext.getInstance("TLSv1.3").also {
        it.init(null, TestPki.trustManagers(), null)
    }

    private fun serveBinary(server: SSLServerSocket, includeCompression: Boolean, oversized: Boolean = false): String {
        (server.accept() as SSLSocket).use { socket ->
            socket.enabledProtocols = arrayOf("TLSv1.3")
            socket.startHandshake()
            val request = readHeaders(socket)
            val key = request.lineSequence().first { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + GUID).encodeToByteArray()),
            )
            val extension = if (includeCompression) "Sec-WebSocket-Extensions: permessage-deflate\r\n" else ""
            socket.outputStream.write(
                ("HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n" + extension + "\r\n").encodeToByteArray(),
            )
            socket.outputStream.flush()
            if (oversized) {
                socket.outputStream.write(byteArrayOf(0x82.toByte(), 127, 0, 0, 0, 0, 0, 1, 0, 1))
                socket.outputStream.flush()
            } else if (!includeCompression) {
                val payload = readClientFrame(socket)
                check(payload.toString(Charsets.UTF_8) == "inner-tls-record")
                writeServerFrame(socket, "opaque-reply".encodeToByteArray())
            }
            return request
        }
    }

    private fun serveTunnel(server: SSLServerSocket, innerPort: Int) {
        val outer = server.accept() as SSLSocket
        outer.enabledProtocols = arrayOf("TLSv1.3")
        outer.startHandshake()
        val request = readHeaders(outer)
        val key = request.lineSequence().first { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + GUID).encodeToByteArray()),
        )
        outer.outputStream.write(
            ("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").encodeToByteArray(),
        )
        outer.outputStream.flush()
        val inner = Socket("127.0.0.1", innerPort)
        val incoming = thread(isDaemon = true, name = "test-wss-to-tls") {
            runCatching {
                while (true) {
                    val payload = readClientFrame(outer)
                    inner.outputStream.write(payload)
                    inner.outputStream.flush()
                }
            }
            runCatching { inner.shutdownOutput() }
        }
        val outgoing = thread(isDaemon = true, name = "test-tls-to-wss") {
            runCatching {
                val bytes = ByteArray(MAX_CHANNEL_CHUNK_BYTES)
                while (true) {
                    val count = inner.inputStream.read(bytes)
                    if (count == -1) break
                    writeServerFrame(outer, bytes.copyOf(count))
                }
            }
            runCatching { outer.close() }
            runCatching { inner.close() }
        }
        outgoing.join(30_000)
        outer.close()
        inner.close()
        incoming.join(5_000)
        check(!outgoing.isAlive && !incoming.isAlive)
    }

    private fun echoInnerTls(server: SSLServerSocket) {
        (server.accept() as SSLSocket).use { socket ->
            socket.enabledProtocols = arrayOf("TLSv1.3")
            socket.startHandshake()
            val expected = "nested-request".encodeToByteArray()
            val request = readExact(socket, expected.size)
            check(request.contentEquals(expected))
            socket.outputStream.write("nested-response".encodeToByteArray())
            socket.outputStream.flush()
        }
    }

    private fun readHeaders(socket: SSLSocket): String {
        val output = ByteArrayOutputStream()
        var matched = 0
        val marker = "\r\n\r\n".encodeToByteArray()
        while (matched < marker.size) {
            val next = socket.inputStream.read()
            check(next >= 0)
            output.write(next)
            matched = if (next.toByte() == marker[matched]) matched + 1 else 0
        }
        return output.toString(Charsets.ISO_8859_1)
    }

    private fun readClientFrame(socket: SSLSocket): ByteArray {
        val first = socket.inputStream.read()
        val second = socket.inputStream.read()
        check(first == 0x82 && second and 0x80 != 0)
        val marker = second and 0x7f
        val size = when (marker) {
            in 0..125 -> marker
            126 -> ByteBuffer.wrap(readExact(socket, 2)).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
            else -> error("unexpected frame")
        }
        val mask = readExact(socket, 4)
        val body = readExact(socket, size)
        return ByteArray(size) { index -> (body[index].toInt() xor mask[index and 3].toInt()).toByte() }
    }

    private fun writeServerFrame(socket: SSLSocket, payload: ByteArray) = synchronized(socket.outputStream) {
        check(payload.size <= MAX_CHANNEL_CHUNK_BYTES)
        socket.outputStream.write(0x82)
        if (payload.size <= 125) {
            socket.outputStream.write(payload.size)
        } else {
            socket.outputStream.write(126)
            socket.outputStream.write((payload.size ushr 8) and 0xff)
            socket.outputStream.write(payload.size and 0xff)
        }
        socket.outputStream.write(payload)
        socket.outputStream.flush()
    }

    private fun readExact(socket: SSLSocket, size: Int): ByteArray = ByteArray(size).also { bytes ->
        var offset = 0
        while (offset < size) {
            val count = socket.inputStream.read(bytes, offset, size - offset)
            check(count > 0)
            offset += count
        }
    }

    private companion object {
        const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
