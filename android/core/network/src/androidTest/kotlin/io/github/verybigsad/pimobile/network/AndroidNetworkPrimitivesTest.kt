package io.github.verybigsad.pimobile.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidNetworkPrimitivesTest {
    @Test
    fun platformBuildsTls13PinnedContextAndValidatesUriProfile() {
        val certificate = certificate("/pki/server-cert.crt")
        val identity = CertificateIdentity(CertificateRole.MAC_SERVER, "test-mac")
        val clock = Clock.fixed(certificate.notBefore.toInstant().plusSeconds(60), ZoneOffset.UTC)
        val pin = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)

        CertificateProfileValidator(identity, clock).validate(certificate)
        val engine = TlsContexts.provisional(pin, identity, clock).newEngine("localhost", 443)

        assertThat(engine.enabledProtocols.asList()).containsExactly("TLSv1.3")
        assertThat(engine.useClientMode).isTrue()
    }

    @Test
    fun androidP256ProviderSignsJcsRelayProof() {
        val now = Instant.parse("2026-08-09T12:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val keys = KeyPairGenerator.getInstance("EC").run {
            initialize(256)
            generateKeyPair()
        }
        val expected = ExpectedRelayChallenge(RelayAudience.DEVICE_DATA, "route-1", "device-1")
        val challenge = StrictJson.canonicalize(
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("route.challenge"),
                    "signed" to JsonObject(
                        mapOf(
                            "audience" to JsonPrimitive("device-data"),
                            "routeId" to JsonPrimitive("route-1"),
                            "keyId" to JsonPrimitive("device-1"),
                            "nonce" to JsonPrimitive(encodeBase64Url(ByteArray(32) { 7 })),
                            "expiresAt" to JsonPrimitive(now.plusSeconds(20).toString()),
                        ),
                    ),
                ),
            ),
        )
        val parsed = RelayProofCodec(clock).parseChallenge(challenge, expected)
        val proof = RelayProofCodec(clock).encodeProof(parsed, RelayProofSigner { payload ->
            java.security.Signature.getInstance("SHA256withECDSA").run {
                initSign(keys.private)
                update(payload)
                sign()
            }
        })

        RelayProofCodec(clock).parseAndVerifyProof(proof, expected, keys.public)
        assertThat(proof.size).isLessThan(MAX_RELAY_CONTROL_BYTES)
    }

    @Test
    fun streamByteChannelUsesAndroidLoopbackSocket() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val peer = async(Dispatchers.IO) {
            server.accept().use { socket ->
                val received = ByteArray(4)
                var offset = 0
                while (offset < received.size) {
                    val count = socket.inputStream.read(received, offset, received.size - offset)
                    check(count > 0)
                    offset += count
                }
                check(received.contentEquals(byteArrayOf(1, 2, 3, 4)))
                socket.outputStream.write(byteArrayOf(5, 6, 7, 8))
                socket.outputStream.flush()
            }
        }
        val channel = StreamByteChannel.connect("127.0.0.1", server.localPort)
        channel.write(byteArrayOf(1, 2, 3, 4))
        val response = ByteArray(4)
        var offset = 0
        while (offset < response.size) {
            val count = channel.read(response, offset, response.size - offset)
            check(count > 0)
            offset += count
        }

        assertThat(response.contentEquals(byteArrayOf(5, 6, 7, 8))).isTrue()
        channel.close()
        peer.await()
        server.close()
    }

    private fun certificate(path: String): X509Certificate = requireNotNull(javaClass.getResourceAsStream(path)).use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
}
