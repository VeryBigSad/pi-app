package io.github.verybigsad.pimobile.network

import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.security.PairingInvitation
import java.net.URI
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class RelayPairingPathTest {
    private val tlsClock = Clock.fixed(TestPki.serverUuid.notBefore.toInstant().plusSeconds(120), ZoneOffset.UTC)
    private val invitationId: UUID = UUID.randomUUID()
    private val macInstanceId: UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
    private val signer = RelayProofSigner { payload -> ByteArray(64) { 1 }.also { require(payload.isNotEmpty()) } }

    private class TickClock(start: Instant) : Clock() {
        private var current = start

        fun advance(millis: Long) {
            current = current.plusMillis(millis)
        }

        override fun instant(): Instant = current
        override fun withZone(zone: java.time.ZoneId): Clock = this
        override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
    }

    private class FakeTransport(vararg responses: RelayHttpResponse) {
        val requests = mutableListOf<Pair<String, String>>()
        private val queue = ArrayDeque(responses.toList())

        fun transport(onRequest: (String) -> Unit = {}): RelayHttpTransport = object : RelayHttpTransport {
            override suspend fun request(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: ByteArray?,
            ): RelayHttpResponse {
                requests += method to url
                onRequest(url)
                return queue.removeFirstOrNull() ?: RelayHttpResponse(500, ByteArray(0))
            }
        }
    }

    private fun invitation(expiresAt: Instant = tlsClock.instant().plusSeconds(240)): PairingInvitation {
        val signed = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "version" to JsonPrimitive(1),
            "relayUrl" to JsonPrimitive("wss://127.0.0.1:4443/base"),
            "routeId" to JsonPrimitive("route-1"),
            "routeKeyId" to JsonPrimitive("mac-route-key-1"),
            "invitationId" to JsonPrimitive(invitationId.toString()),
            "macInstanceId" to JsonPrimitive(macInstanceId.toString()),
            "expiresAt" to JsonPrimitive(expiresAt.toString()),
            "nonce" to JsonPrimitive(encodeBase64Url(ByteArray(32) { 3 })),
            "serverCertificateSha256" to JsonPrimitive(TestPki.serverUuidPin().joinToString("") { "%02x".format(it) }),
            "directCandidates" to kotlinx.serialization.json.JsonArray(emptyList()),
        )
        val canonical = StrictJson.canonicalize(JsonObject(signed))
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(TestPki.serverUuidKey)
            update(canonical)
            sign()
        }
        val envelope = StrictJson.canonicalize(
            JsonObject(
                mapOf(
                    "signed" to JsonObject(signed),
                    "signature" to JsonPrimitive(encodeBase64Url(signature)),
                ),
            ),
        )
        return PairingInvitation.parse("pimobile://pair?v=1&d=${encodeBase64Url(envelope)}", tlsClock.instant())
    }

    private val rendezvousFields = JsonObject(
        mapOf(
            "pairingId" to JsonPrimitive("pair-1"),
            "secret" to JsonPrimitive("secret-1"),
            "expiresAt" to JsonPrimitive(tlsClock.instant().plusSeconds(280).toString()),
        ),
    )

    @Test
    fun invitationWithoutRelayPairingYieldsNoRendezvous() {
        assertThat(RelayPairingRendezvous.fromInvitation(invitation(), tlsClock)).isNull()
    }

    @Test
    fun rendezvousExtractionIsStrictAndBounded() {
        val signed = JsonObject(mapOf("relayPairing" to rendezvousFields))
        val rendezvous = RelayPairingRendezvous.fromSignedPayload(signed, tlsClock)
        assertThat(rendezvous).isEqualTo(
            RelayPairingRendezvous("pair-1", "secret-1", tlsClock.instant().plusSeconds(280)),
        )
        assertThat(RelayPairingRendezvous.fromSignedPayload(JsonObject(emptyMap()), tlsClock)).isNull()

        val badSecret = JsonObject(mapOf("relayPairing" to JsonObject(rendezvousFields.toMap() + ("secret" to JsonPrimitive("has spaces")))))
        val error = runCatching {
            RelayPairingRendezvous.fromSignedPayload(badSecret, tlsClock)
        }.exceptionOrNull()
        assertThat((error as NetworkException).code).isEqualTo(NetworkError.RELAY_PAIRING_FAILED)

        val expired = JsonObject(
            mapOf("relayPairing" to JsonObject(rendezvousFields.toMap() + ("expiresAt" to JsonPrimitive(tlsClock.instant().minusSeconds(1).toString())))),
        )
        val expiredError = runCatching {
            RelayPairingRendezvous.fromSignedPayload(expired, tlsClock)
        }.exceptionOrNull()
        assertThat((expiredError as NetworkException).code).isEqualTo(NetworkError.RELAY_PAIRING_NOT_READY)
    }

    @Test
    fun connectRunsExchangeAndProvisionalTlsOverTheSplicedTunnel() = runBlocking {
        val clock = TickClock(tlsClock.instant())
        val reply = StrictJson.canonicalize(
            JsonObject(mapOf("accepted" to JsonPrimitive(true), "invitationId" to JsonPrimitive(invitationId.toString()))),
        )
        val fake = FakeTransport(
            RelayHttpResponse(202, ByteArray(0)),
            RelayHttpResponse(404, ByteArray(0)),
            RelayHttpResponse(200, JsonObject(mapOf("message" to JsonPrimitive(encodeBase64Url(reply)))).toString().encodeToByteArray()),
        )
        val serverSocket = TestPki.serverUuidContext().serverSocketFactory.createServerSocket(0)
        val peer = async(Dispatchers.IO) {
            val socket = serverSocket.accept() as SSLSocket
            socket.use {
                it.startHandshake()
                val buffer = ByteArray(64)
                val count = it.inputStream.read(buffer)
                it.outputStream.write(buffer.copyOf(count))
                it.outputStream.flush()
            }
        }
        val tunnelUri = java.util.concurrent.atomic.AtomicReference<URI?>(null)
        val proofHeader = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val path = RelayPairingPath(
            clock = clock,
            pollIntervalMillis = 100,
            transportFactory = { fake.transport { clock.advance(100) } },
            tunnelOpener = RelayDataTunnelOpener { uri, proof ->
                tunnelUri.set(uri)
                proofHeader.set(proof)
                StreamByteChannel.connect("127.0.0.1", serverSocket.localPort)
            },
        )

        val connection = path.connect(
            invitation(),
            RelayPairingRendezvous("pair-1", "secret-1", tlsClock.instant().plusSeconds(280)),
            "device-route-1",
            encodeBase64Url(ByteArray(91) { 5 }),
            signer,
            tlsClock.instant().plusSeconds(240),
        )

        connection.channel.write("hello-relay".encodeToByteArray())
        val buffer = ByteArray(64)
        val count = connection.channel.read(buffer)
        assertThat(buffer.copyOf(count).toString(Charsets.UTF_8)).isEqualTo("hello-relay")
        assertThat(connection.macId).isEqualTo(macInstanceId.toString())
        assertThat(fake.requests.map { it.first }).containsExactly("PUT", "GET", "GET").inOrder()
        assertThat(fake.requests.first().second)
            .isEqualTo("https://127.0.0.1:4443/base/v1/routes/route-1/pairing/pair-1")
        assertThat(tunnelUri.get().toString()).isEqualTo("wss://127.0.0.1:4443/base/v1/routes/route-1/data")
        val proof = StrictJson.parseObject(proofHeader.get()!!.encodeToByteArray(), JsonBounds(MAX_RELAY_CONTROL_BYTES))
        assertThat(proof.stringValue("type", NetworkError.MALFORMED_JSON)).isEqualTo("route.proof")
        val signedProof = proof.objectValue("signed", NetworkError.MALFORMED_JSON)
        assertThat(signedProof.stringValue("audience", NetworkError.MALFORMED_JSON)).isEqualTo("device-data")
        assertThat(signedProof.stringValue("keyId", NetworkError.MALFORMED_JSON)).isEqualTo("device-route-1")
        connection.channel.close()
        peer.await()
        serverSocket.close()
    }

    @Test
    fun connectFailsTypedWhenReplyIsNotAccepted() = runTest {
        val clock = TickClock(tlsClock.instant())
        val reply = StrictJson.canonicalize(
            JsonObject(mapOf("accepted" to JsonPrimitive(false), "invitationId" to JsonPrimitive(invitationId.toString()))),
        )
        val fake = FakeTransport(
            RelayHttpResponse(202, ByteArray(0)),
            RelayHttpResponse(200, JsonObject(mapOf("message" to JsonPrimitive(encodeBase64Url(reply)))).toString().encodeToByteArray()),
        )
        val path = RelayPairingPath(
            clock = clock,
            pollIntervalMillis = 100,
            transportFactory = { fake.transport() },
            tunnelOpener = RelayDataTunnelOpener { _, _ -> error("unreachable") },
        )

        val error = runCatching {
            path.connect(
                invitation(),
                RelayPairingRendezvous("pair-1", "secret-1", tlsClock.instant().plusSeconds(280)),
                "device-route-1",
                encodeBase64Url(ByteArray(91) { 5 }),
                signer,
                tlsClock.instant().plusSeconds(240),
            )
        }.exceptionOrNull()
        assertThat((error as NetworkException).code).isEqualTo(NetworkError.RELAY_PAIRING_FAILED)
    }

    @Test
    fun connectSurfacesRelayConflictAndRateLimitTyped() = runTest {
        for ((status, code) in listOf(
            409 to NetworkError.RELAY_PAIRING_CONFLICT,
            429 to NetworkError.RELAY_PAIRING_RATE_LIMITED,
            503 to NetworkError.RELAY_PAIRING_UNAVAILABLE,
        )) {
            val fake = FakeTransport(RelayHttpResponse(status, ByteArray(0)))
            val path = RelayPairingPath(
                clock = tlsClock,
                transportFactory = { fake.transport() },
                tunnelOpener = RelayDataTunnelOpener { _, _ -> error("unreachable") },
            )
            val error = runCatching {
                path.connect(
                    invitation(),
                    RelayPairingRendezvous("pair-1", "secret-1", tlsClock.instant().plusSeconds(280)),
                    "device-route-1",
                    encodeBase64Url(ByteArray(91) { 5 }),
                    signer,
                    tlsClock.instant().plusSeconds(240),
                )
            }.exceptionOrNull()
            assertThat((error as NetworkException).code).isEqualTo(code)
        }
    }

    @Test
    fun connectFailsWhenRendezvousExpiredBeforeReply() = runTest {
        val path = RelayPairingPath(
            clock = tlsClock,
            transportFactory = { FakeTransport(RelayHttpResponse(202, ByteArray(0))).transport() },
            tunnelOpener = RelayDataTunnelOpener { _, _ -> error("unreachable") },
        )
        val error = runCatching {
            path.connect(
                invitation(),
                RelayPairingRendezvous("pair-1", "secret-1", tlsClock.instant().minusSeconds(1)),
                "device-route-1",
                encodeBase64Url(ByteArray(91) { 5 }),
                signer,
                tlsClock.instant().plusSeconds(240),
            )
        }.exceptionOrNull()
        assertThat((error as NetworkException).code).isEqualTo(NetworkError.RELAY_PAIRING_NOT_READY)
    }

    @Test
    fun deviceDataProofVerifiesAgainstRoutePublicKey() = runTest {
        val keyPair = java.security.KeyPairGenerator.getInstance("EC").run {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val codec = RelayProofCodec(tlsClock)
        val proof = codec.encodeSelfIssuedProof(
            RelayAudience.DEVICE_DATA,
            "route-1",
            "device-route-1",
            RelayProofSigner { payload ->
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(keyPair.private)
                    update(payload)
                    sign()
                }
            },
        )
        val verified = RelayProofCodec(tlsClock).parseAndVerifyProof(
            proof,
            ExpectedRelayChallenge(RelayAudience.DEVICE_DATA, "route-1", "device-route-1"),
            keyPair.public,
        )
        assertThat(verified.audience).isEqualTo(RelayAudience.DEVICE_DATA)
    }
}
