package io.github.verybigsad.pimobile.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.security.PairingInvitation
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Loopback relay provisional pairing: real HttpURLConnection exchange against a loopback
 * HTTP server, then a real pinned provisional TLS 1.3 handshake over a loopback socket
 * standing in for the relay-spliced device-data tunnel.
 */
@RunWith(AndroidJUnit4::class)
class RelayPairingPathAndroidTest {
    private val invitationId: UUID = UUID.randomUUID()
    private val macInstanceId: UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun exchangeAndProvisionalHandshakeOverLoopback() = runBlocking {
        val recorded = ConcurrentLinkedQueue<String>()
        val reply = StrictJson.canonicalize(
            JsonObject(mapOf("accepted" to JsonPrimitive(true), "invitationId" to JsonPrimitive(invitationId.toString()))),
        )
        val http = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val httpPeer = async(Dispatchers.IO) {
            serve(http, recorded) { request ->
                check(request.startsWith("PUT /v1/routes/route-1/pairing/pair-1 "))
                "HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            }
            serve(http, recorded) { request ->
                check(request.startsWith("GET /v1/routes/route-1/pairing/pair-1/reply "))
                val body = StrictJson.canonicalize(JsonObject(mapOf("message" to JsonPrimitive(encodeBase64Url(reply)))))
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n" +
                    body.toString(Charsets.UTF_8)
            }
        }
        val tls = serverContext().serverSocketFactory.createServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val tlsPeer = async(Dispatchers.IO) {
            val socket = tls.accept() as SSLSocket
            socket.use {
                it.startHandshake()
                val buffer = ByteArray(64)
                val count = it.inputStream.read(buffer)
                it.outputStream.write(buffer.copyOf(count))
                it.outputStream.flush()
            }
        }
        val clock = Clock.systemUTC()
        val path = RelayPairingPath(
            clock = clock,
            pollIntervalMillis = 100,
            transportFactory = { HttpUrlConnectionRelayTransport() },
            tunnelOpener = RelayDataTunnelOpener { _, proof ->
                assertThat(proof).contains("\"audience\":\"device-data\"")
                StreamByteChannel.connect("127.0.0.1", tls.localPort)
            },
            exchangeBaseUrl = "http://127.0.0.1:${http.localPort}",
        )

        // The loopback HTTP endpoint replaces the relay API base; the invitation relayUrl
        // only feeds the WSS tunnel URI, which the injected opener intercepts.
        val connection = path.connect(
            invitation(clock),
            RelayPairingRendezvous("pair-1", "secret-1", clock.instant().plusSeconds(240)),
            "device-route-1",
            encodeBase64Url(ByteArray(91) { 5 }),
            RelayProofSigner { payload -> ByteArray(64) { 1 }.also { require(payload.isNotEmpty()) } },
            clock.instant().plusSeconds(240),
        )

        connection.channel.write("relay-tls".encodeToByteArray())
        val buffer = ByteArray(64)
        val count = connection.channel.read(buffer)
        assertThat(buffer.copyOf(count).toString(Charsets.UTF_8)).isEqualTo("relay-tls")
        assertThat(connection.macId).isEqualTo(macInstanceId.toString())
        val requests = recorded.toList()
        assertThat(requests).hasSize(2)
        assertThat(requests.all { it.contains("$RELAY_PAIRING_SECRET_HEADER: secret-1") }).isTrue()
        val putBody = requests.first().substringAfter("\r\n\r\n")
        val parsed = StrictJson.parseObject(putBody.encodeToByteArray(), JsonBounds(MAX_PAIRING_EXCHANGE_BODY_BYTES))
        val message = StrictJson.parseObject(
            decodeBase64Url(parsed.stringValue("message", NetworkError.MALFORMED_JSON), MAX_PAIRING_EXCHANGE_MESSAGE_BYTES),
            JsonBounds(MAX_PAIRING_EXCHANGE_BODY_BYTES),
        )
        assertThat(message.stringValue("invitationId", NetworkError.MALFORMED_JSON)).isEqualTo(invitationId.toString())
        assertThat(message.stringValue("deviceRouteKeyId", NetworkError.MALFORMED_JSON)).isEqualTo("device-route-1")
        connection.channel.close()
        httpPeer.await()
        tlsPeer.await()
        http.close()
        tls.close()
    }

    private fun invitation(clock: Clock): PairingInvitation {
        val signed = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "version" to JsonPrimitive(1),
            "relayUrl" to JsonPrimitive("wss://127.0.0.1:4443"),
            "routeId" to JsonPrimitive("route-1"),
            "routeKeyId" to JsonPrimitive("mac-route-key-1"),
            "invitationId" to JsonPrimitive(invitationId.toString()),
            "macInstanceId" to JsonPrimitive(macInstanceId.toString()),
            "expiresAt" to JsonPrimitive(clock.instant().plusSeconds(240).toString()),
            "nonce" to JsonPrimitive(encodeBase64Url(ByteArray(32) { 3 })),
            "serverCertificateSha256" to JsonPrimitive(pin().joinToString("") { "%02x".format(it) }),
            "directCandidates" to kotlinx.serialization.json.JsonArray(emptyList()),
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey("/pki/server-uuid-key.pk8"))
            update(StrictJson.canonicalize(JsonObject(signed)))
            sign()
        }
        val envelope = StrictJson.canonicalize(
            JsonObject(mapOf("signed" to JsonObject(signed), "signature" to JsonPrimitive(encodeBase64Url(signature)))),
        )
        return PairingInvitation.parse("pimobile://pair?v=1&d=${encodeBase64Url(envelope)}", clock.instant())
    }

    private fun serve(server: ServerSocket, recorded: ConcurrentLinkedQueue<String>, respond: (String) -> String) {
        server.accept().use { socket: Socket ->
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.ISO_8859_1))
            val builder = StringBuilder()
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                builder.append(line).append("\r\n")
                if (line.startsWith("Content-Length:")) contentLength = line.removePrefix("Content-Length:").trim().toInt()
            }
            if (contentLength > 0) {
                builder.append("\r\n")
                val body = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val count = reader.read(body, read, contentLength - read)
                    if (count == -1) break
                    read += count
                }
                builder.append(body.concatToString())
            }
            val request = builder.toString()
            recorded += request
            socket.outputStream.write(respond(request).encodeToByteArray())
            socket.outputStream.flush()
        }
    }

    private fun pin(): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(certificate("/pki/server-uuid-cert.crt").encoded)

    private fun certificate(path: String): X509Certificate = requireNotNull(javaClass.getResourceAsStream(path)).use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }

    private fun privateKey(path: String): PrivateKey {
        val text = requireNotNull(javaClass.getResource(path)).readText()
        val encoded = text.lineSequence().filterNot { it.startsWith("---") }.joinToString("")
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)))
    }

    private fun serverContext(): SSLContext {
        val password = "test-only".toCharArray()
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).run {
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).also {
                it.load(null)
                it.setKeyEntry("leaf", privateKey("/pki/server-uuid-key.pk8"), password, arrayOf(certificate("/pki/server-uuid-cert.crt")))
            }
            init(store, password)
            keyManagers
        }
        return SSLContext.getInstance("TLSv1.3").also { it.init(keyManagers, null, null) }
    }
}
