package io.github.verybigsad.pimobile.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelayPairingClientAndroidTest {
    @Test
    fun exchangeRoundTripsOneRequestAndOneReplyOverLoopbackHttp() = runBlocking {
        val recorded = ConcurrentLinkedQueue<String>()
        val replyMessage = byteArrayOf(9, 8, 7)
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val peer = async(Dispatchers.IO) {
            serveRequest(server, recorded) { request ->
                check(request.startsWith("PUT /v1/routes/route-1/pairing/pair-1 "))
                "HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            }
            serveRequest(server, recorded) { request ->
                check(request.startsWith("GET /v1/routes/route-1/pairing/pair-1/reply "))
                val body = StrictJson.canonicalize(
                    JsonObject(mapOf("message" to JsonPrimitive(encodeBase64Url(replyMessage)))),
                )
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n" +
                    body.toString(Charsets.UTF_8)
            }
        }
        val client = RelayPairingExchangeClient(
            baseUrl = "http://127.0.0.1:${server.localPort}",
            routeId = "route-1",
            pairingId = "pair-1",
            secret = "secret-1",
            transport = HttpUrlConnectionRelayTransport(),
        )

        client.submitRequest(byteArrayOf(1, 2, 3))
        assertThat(client.awaitReply(10_000)).isEqualTo(replyMessage)

        val requests = recorded.toList()
        assertThat(requests).hasSize(2)
        assertThat(requests.all { it.contains("$RELAY_PAIRING_SECRET_HEADER: secret-1") }).isTrue()
        val put = requests.first()
        val body = put.substringAfter("\r\n\r\n")
        val parsed = StrictJson.parseObject(body.encodeToByteArray(), JsonBounds(MAX_PAIRING_EXCHANGE_BODY_BYTES))
        assertThat(decodeBase64Url(parsed.stringValue("message", NetworkError.MALFORMED_JSON), MAX_PAIRING_EXCHANGE_MESSAGE_BYTES))
            .isEqualTo(byteArrayOf(1, 2, 3))
        peer.await()
        server.close()
    }

    private fun serveRequest(
        server: ServerSocket,
        recorded: ConcurrentLinkedQueue<String>,
        respond: (String) -> String,
    ) {
        server.accept().use { socket: Socket ->
            val request = readHttpRequest(socket)
            recorded += request
            val response = respond(request)
            socket.outputStream.write(response.encodeToByteArray())
            socket.outputStream.flush()
        }
    }

    private fun readHttpRequest(socket: Socket): String {
        val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.ISO_8859_1))
        val builder = StringBuilder()
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            builder.append(line).append("\r\n")
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toInt()
            }
        }
        repeat(contentLength) { builder.append(reader.read().toChar()) }
        return builder.toString()
    }
}
