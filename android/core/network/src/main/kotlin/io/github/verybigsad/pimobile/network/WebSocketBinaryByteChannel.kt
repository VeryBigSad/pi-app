package io.github.verybigsad.pimobile.network

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val MAX_HTTP_HEADER_BYTES = 16 * 1_024
private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
private const val OPCODE_CONTINUATION = 0x0
private const val OPCODE_BINARY = 0x2
private const val OPCODE_CLOSE = 0x8
private const val OPCODE_PING = 0x9
private const val OPCODE_PONG = 0xa

class WebSocketBinaryByteChannel private constructor(
    private val socket: SSLSocket,
    private val input: InputStream,
    private val output: OutputStream,
    private val random: SecureRandom,
) : DuplexByteChannel {
    private val readMutex = Mutex()
    private val writeMutex = Mutex()
    private val outputLock = Any()
    private val closed = AtomicBoolean(false)
    private var pending = ByteArray(0)
    private var pendingOffset = 0

    override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
        requireRange(destination.size, offset, length)
        if (length == 0) return 0
        return readMutex.withLock {
            withContext(Dispatchers.IO) {
                while (pendingOffset >= pending.size) {
                    pending = readMessage() ?: return@withContext -1
                    pendingOffset = 0
                    if (pending.isEmpty()) continue
                }
                val count = minOf(length, pending.size - pendingOffset)
                pending.copyInto(destination, offset, pendingOffset, pendingOffset + count)
                pendingOffset += count
                count
            }
        }
    }

    override suspend fun write(source: ByteArray, offset: Int, length: Int) {
        requireRange(source.size, offset, length)
        if (closed.get()) throw NetworkException(NetworkError.TRANSPORT_CLOSED, "WebSocket is closed")
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                if (length == 0) {
                    writeFrame(OPCODE_BINARY, ByteArray(0))
                    return@withContext
                }
                var position = offset
                var remaining = length
                while (remaining > 0) {
                    val count = minOf(remaining, MAX_CHANNEL_CHUNK_BYTES)
                    writeFrame(OPCODE_BINARY, source.copyOfRange(position, position + count))
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

    private fun readMessage(): ByteArray? {
        var outputBuffer: ByteArrayOutputStream? = null
        while (true) {
            val frame = readFrame() ?: return null
            when (frame.opcode) {
                OPCODE_PING -> writeControl(OPCODE_PONG, frame.payload)
                OPCODE_PONG -> Unit
                OPCODE_CLOSE -> {
                    closed.set(true)
                    socket.close()
                    return null
                }
                OPCODE_BINARY -> {
                    if (outputBuffer != null) protocolError()
                    if (frame.final) return frame.payload
                    outputBuffer = ByteArrayOutputStream(frame.payload.size.coerceAtLeast(32))
                    outputBuffer.write(frame.payload)
                }
                OPCODE_CONTINUATION -> {
                    val current = outputBuffer ?: protocolError()
                    if (current.size() + frame.payload.size > MAX_CHANNEL_CHUNK_BYTES) exhausted()
                    current.write(frame.payload)
                    if (frame.final) return current.toByteArray()
                }
                else -> protocolError()
            }
        }
    }

    private fun readFrame(): Frame? {
        val first = input.read()
        if (first == -1) return null
        val second = input.read()
        if (second == -1) protocolError()
        val final = first and 0x80 != 0
        if (first and 0x70 != 0 || second and 0x80 != 0) protocolError()
        val opcode = first and 0x0f
        val control = opcode >= 0x8
        if (control && !final) protocolError()
        val lengthMarker = second and 0x7f
        val length = when (lengthMarker) {
            in 0..125 -> lengthMarker.toLong()
            126 -> ByteBuffer.wrap(readExact(2)).order(ByteOrder.BIG_ENDIAN).short.toInt().and(0xffff).toLong().also {
                if (it < 126) protocolError()
            }
            else -> {
                val value = ByteBuffer.wrap(readExact(8)).order(ByteOrder.BIG_ENDIAN).long
                if (value <= 0xffff) protocolError()
                value
            }
        }
        if (control && length > 125 || length > MAX_CHANNEL_CHUNK_BYTES) exhausted()
        return Frame(final, opcode, readExact(length.toInt()))
    }

    private fun writeControl(opcode: Int, payload: ByteArray) {
        if (payload.size > 125) protocolError()
        writeFrame(opcode, payload)
    }

    private fun writeFrame(opcode: Int, payload: ByteArray) = synchronized(outputLock) {
        val mask = ByteArray(4).also(random::nextBytes)
        val header = ByteArrayOutputStream(14)
        header.write(0x80 or opcode)
        when {
            payload.size <= 125 -> header.write(0x80 or payload.size)
            payload.size <= 0xffff -> {
                header.write(0x80 or 126)
                header.write((payload.size ushr 8) and 0xff)
                header.write(payload.size and 0xff)
            }
            else -> {
                header.write(0x80 or 127)
                header.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(payload.size.toLong()).array())
            }
        }
        header.write(mask)
        val masked = ByteArray(payload.size) { index -> (payload[index].toInt() xor mask[index and 3].toInt()).toByte() }
        output.write(header.toByteArray())
        output.write(masked)
        output.flush()
    }

    private fun readExact(length: Int): ByteArray {
        val value = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(value, offset, length - offset)
            if (count == -1) protocolError()
            offset += count
        }
        return value
    }

    private fun protocolError(): Nothing = throw NetworkException(NetworkError.WEBSOCKET_PROTOCOL_ERROR, "WebSocket binary stream is invalid")
    private fun exhausted(): Nothing = throw NetworkException(NetworkError.TRANSPORT_EXHAUSTED, "WebSocket message exceeds its bound")

    private data class Frame(val final: Boolean, val opcode: Int, val payload: ByteArray)

    companion object {
        suspend fun connect(
            uri: URI,
            headers: Map<String, String> = emptyMap(),
            sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
            random: SecureRandom = SecureRandom(),
            connectTimeoutMillis: Int = 10_000,
        ): WebSocketBinaryByteChannel = withContext(Dispatchers.IO) {
            validateEndpoint(uri, headers, connectTimeoutMillis)
            val host = requireNotNull(uri.host).removeSurrounding("[", "]")
            val port = if (uri.port == -1) 443 else uri.port
            val plain = Socket()
            try {
                plain.connect(InetSocketAddress(host, port), connectTimeoutMillis)
                val tls = sslSocketFactory.createSocket(plain, host, port, true) as SSLSocket
                tls.soTimeout = connectTimeoutMillis
                tls.useClientMode = true
                tls.enabledProtocols = arrayOf("TLSv1.3")
                tls.sslParameters = tls.sslParameters.also { parameters ->
                    parameters.protocols = arrayOf("TLSv1.3")
                    parameters.endpointIdentificationAlgorithm = "HTTPS"
                }
                tls.startHandshake()
                val keyBytes = ByteArray(16).also(random::nextBytes)
                val key = Base64.getEncoder().encodeToString(keyBytes)
                val requestTarget = buildString {
                    append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
                    uri.rawQuery?.let { append('?').append(it) }
                }
                val authorityHost = if (':' in host) "[$host]" else host
                val hostHeader = if (uri.port == -1 || uri.port == 443) authorityHost else "$authorityHost:$port"
                val request = buildString {
                    append("GET ").append(requestTarget).append(" HTTP/1.1\r\n")
                    append("Host: ").append(hostHeader).append("\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                    append("\r\n")
                }.encodeToByteArray()
                if (request.size > MAX_HTTP_HEADER_BYTES) handshakeError()
                tls.outputStream.write(request)
                tls.outputStream.flush()
                verifyResponse(readHeaders(tls.inputStream), key)
                tls.soTimeout = 0
                WebSocketBinaryByteChannel(tls, tls.inputStream, tls.outputStream, random)
            } catch (error: NetworkException) {
                plain.close()
                throw error
            } catch (error: Exception) {
                plain.close()
                throw NetworkException(NetworkError.WEBSOCKET_HANDSHAKE_FAILED, "WSS connection failed", error)
            }
        }

        private fun validateEndpoint(uri: URI, headers: Map<String, String>, timeout: Int) {
            if (
                uri.scheme != "wss" || uri.host.isNullOrEmpty() || uri.userInfo != null || uri.fragment != null ||
                uri.port != -1 && uri.port !in 1..65_535 || timeout !in 1..60_000 || uri.toString().length > 4_096
            ) {
                handshakeError()
            }
            if (headers.size > 16 || headers.keys.any { !headerName.matches(it) || it.lowercase(Locale.US) in reservedHeaders } || headers.values.any { !headerValue.matches(it) }) {
                handshakeError()
            }
        }

        private fun readHeaders(input: InputStream): ByteArray {
            val bytes = ByteArrayOutputStream()
            var matched = 0
            val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
            while (bytes.size() < MAX_HTTP_HEADER_BYTES) {
                val next = input.read()
                if (next == -1) handshakeError()
                bytes.write(next)
                matched = if (next.toByte() == terminator[matched]) matched + 1 else if (next.toByte() == terminator[0]) 1 else 0
                if (matched == terminator.size) return bytes.toByteArray()
            }
            return handshakeError()
        }

        private fun verifyResponse(raw: ByteArray, key: String) {
            val text = raw.toString(Charsets.ISO_8859_1)
            val lines = text.removeSuffix("\r\n\r\n").split("\r\n")
            if (lines.firstOrNull()?.matches(Regex("^HTTP/1\\.1 101(?: .*)?$")) != true) handshakeError()
            val values = linkedMapOf<String, String>()
            lines.drop(1).forEach { line ->
                val separator = line.indexOf(':')
                if (separator <= 0 || line.firstOrNull()?.isWhitespace() == true) handshakeError()
                val name = line.substring(0, separator).lowercase(Locale.US)
                val value = line.substring(separator + 1).trim()
                if (values.put(name, value) != null) handshakeError()
            }
            val expectedAccept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).encodeToByteArray()),
            )
            if (
                values["upgrade"]?.lowercase(Locale.US) != "websocket" ||
                values["connection"]?.split(',')?.map { it.trim().lowercase(Locale.US) }?.contains("upgrade") != true ||
                values["sec-websocket-accept"] != expectedAccept ||
                "sec-websocket-extensions" in values ||
                "sec-websocket-protocol" in values
            ) {
                handshakeError()
            }
        }

        private fun handshakeError(): Nothing = throw NetworkException(NetworkError.WEBSOCKET_HANDSHAKE_FAILED, "WebSocket handshake is invalid")

        private val headerName = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}$")
        private val headerValue = Regex("^[\\x20-\\x7e]{0,8192}$")
        private val reservedHeaders = setOf(
            "host",
            "upgrade",
            "connection",
            "sec-websocket-key",
            "sec-websocket-version",
            "sec-websocket-extensions",
            "sec-websocket-protocol",
        )
    }
}
