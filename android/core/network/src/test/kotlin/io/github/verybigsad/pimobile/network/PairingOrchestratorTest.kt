package io.github.verybigsad.pimobile.network

import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.protocol.canonicalizeJson
import io.github.verybigsad.pimobile.protocol.sha256Hex
import io.github.verybigsad.pimobile.security.CertificateFingerprint
import io.github.verybigsad.pimobile.security.DeviceIdentityStore
import io.github.verybigsad.pimobile.security.DevicePublicKeys
import io.github.verybigsad.pimobile.security.InstalledClientIdentity
import io.github.verybigsad.pimobile.security.PasskeyCeremonyPerformer
import io.github.verybigsad.pimobile.security.PasskeyIdentity
import io.github.verybigsad.pimobile.security.PasskeyResult
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.net.ssl.X509ExtendedKeyManager
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class PairingOrchestratorTest {
    private val now: Instant = Instant.parse("2030-01-01T00:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val deviceId = "550e8400-e29b-41d4-a716-446655440001"
    private val invitationId = "550e8400-e29b-41d4-a716-446655440000"
    private val macInstanceId = "550e8400-e29b-41d4-a716-446655440042"
    private val pairingToken = ByteArray(32) { 0x44 }
    private val pairingTokenB64 = encodeBase64Url(pairingToken)
    private val csrDer = byteArrayOf(0x30, 0x10, 1, 2, 3, 4)
    private val challenge = encodeBase64Url(ByteArray(32) { 0x22 })
    private val ceremonyId = "c-1"

    @Test
    fun registrationCeremonyInstallsIssuedChainAndYieldsMtlsParams() = runTest {
        val routeKey = newRouteKey()
        val channel = FakeChannel(
            registrationOptions(),
            authResult(),
            confirm(status = "waiting", shortCode = "123456"),
            confirm(status = "confirmed"),
            pairResult(),
        )
        val passkeys = FakePasskeys(PasskeyResult.Registration(credentialResponse("webauthn.create")))
        val identity = FakeDeviceIdentity()
        val orchestrator = newOrchestrator(identity, passkeys, routeKey = routeKey)
        channel.orchestrator = orchestrator

        val outcome = orchestrator.pair(invitationUri(routeKey), channel)

        val paired = outcome as PairingOutcome.Paired
        assertThat(paired.connection.deviceId).isEqualTo(deviceId)
        assertThat(paired.connection.certificateChain).hasSize(2)
        assertThat(paired.connection.caCertificates).hasSize(1)
        assertThat(paired.connection.trustManager().acceptedIssuers).hasLength(1)
        assertThat(orchestrator.state.value).isEqualTo(PairingState.Paired(deviceId))
        assertThat(channel.closed).isTrue()

        assertThat(channel.sent.map { it.type }).containsExactly(
            "pair.begin",
            "auth.registration.response",
            "pair.confirm",
            "pair.csr",
        ).inOrder()
        val begin = channel.sent[0].body
        assertThat(begin.getValue("invitationId")).isEqualTo(JsonPrimitive(invitationId))
        assertThat(begin.getValue("csrSha256")).isEqualTo(JsonPrimitive(sha256Hex(csrDer)))
        assertThat(begin.getValue("deviceRouteKeyId")).isEqualTo(JsonPrimitive("device-route-$deviceId"))
        val csr = channel.sent[3].body
        assertThat(decodeBase64Url((csr.getValue("csrDer") as JsonPrimitive).content, 1024)).isEqualTo(csrDer)
        assertThat(identity.installed?.first).isEqualTo(deviceId)
        assertThat(identity.installed?.second).hasSize(2)
        assertThat(passkeys.lastOptionsJson).isNotNull()
        assertThat(channel.statesAtReceipt).contains(PairingState.AwaitingLocalConfirmation(ceremonyId, "123456"))
    }

    @Test
    fun assertionCeremonyUsesAssertionMessages() = runTest {
        val routeKey = newRouteKey()
        val channel = FakeChannel(
            assertionOptions(),
            authResult(),
            confirm(status = "waiting", shortCode = "654321"),
            confirm(status = "confirmed"),
            pairResult(),
        )
        val passkeys = FakePasskeys(PasskeyResult.Assertion(credentialResponse("webauthn.get")))
        val orchestrator = newOrchestrator(FakeDeviceIdentity(), passkeys, routeKey = routeKey)

        val outcome = orchestrator.pair(invitationUri(routeKey), channel)

        assertThat(outcome).isInstanceOf(PairingOutcome.Paired::class.java)
        assertThat(channel.sent.map { it.type }).contains("auth.assertion.response")
    }

    @Test
    fun expiredInvitationFailsFast() = runTest {
        val routeKey = newRouteKey()
        val orchestrator = newOrchestrator(FakeDeviceIdentity(), FakePasskeys(null), routeKey = routeKey)
        val channel = FakeChannel()
        val outcome = orchestrator.pair(invitationUri(routeKey, expiresAt = "2029-12-31T23:59:59Z"), channel)
        val failed = outcome as PairingOutcome.Failed
        assertThat(failed.failure.code).isEqualTo("PAIRING_INVITATION_INVALID")
        assertThat(channel.closed).isTrue()
    }

    @Test
    fun wrongRouteKeySignatureIsRejected() = runTest {
        val signingKey = newRouteKey()
        val otherKey = newRouteKey()
        val orchestrator = newOrchestrator(FakeDeviceIdentity(), FakePasskeys(null), routeKey = otherKey)
        val outcome = orchestrator.pair(invitationUri(signingKey), FakeChannel())
        assertThat((outcome as PairingOutcome.Failed).failure).isEqualTo(PairingFailure.InvitationSignatureInvalid)
    }

    @Test
    fun sessionBindingMismatchIsRejected() = runTest {
        val routeKey = newRouteKey()
        val channel = FakeChannel(
            registrationOptions(sessionBinding = sha256Hex(ByteArray(32) { 0x33 })),
        )
        val orchestrator = newOrchestrator(FakeDeviceIdentity(), FakePasskeys(null), routeKey = routeKey)
        val outcome = orchestrator.pair(invitationUri(routeKey), channel)
        assertThat((outcome as PairingOutcome.Failed).failure).isEqualTo(PairingFailure.BindingMismatch("sessionBinding"))
    }

    @Test
    fun missingPairingTokenIsRejected() = runTest {
        val routeKey = newRouteKey()
        val options = registrationOptions()
        val withoutToken = PairingChannelMessage(
            options.type,
            JsonObject(options.body.toMutableMap().apply { remove("pairingToken") }),
        )
        val channel = FakeChannel(withoutToken)
        val orchestrator = newOrchestrator(FakeDeviceIdentity(), FakePasskeys(null), routeKey = routeKey)
        val outcome = orchestrator.pair(invitationUri(routeKey), channel)
        assertThat((outcome as PairingOutcome.Failed).failure)
            .isEqualTo(PairingFailure.ProtocolViolation("pairing message fields are invalid"))
    }

    @Test
    fun shortPairingTokenIsRejected() = runTest {
        val routeKey = newRouteKey()
        val channel = FakeChannel(registrationOptions(pairingToken = encodeBase64Url(ByteArray(31) { 0x55 })))
        val orchestrator = newOrchestrator(FakeDeviceIdentity(), FakePasskeys(null), routeKey = routeKey)
        val outcome = orchestrator.pair(invitationUri(routeKey), channel)
        assertThat((outcome as PairingOutcome.Failed).failure)
            .isEqualTo(PairingFailure.ProtocolViolation("pairing token is invalid"))
    }

    @Test
    fun longPairingTokenIsRejected() = runTest {
        val routeKey = newRouteKey()
        val channel = FakeChannel(registrationOptions(pairingToken = encodeBase64Url(ByteArray(33) { 0x55 })))
        val orchestrator = newOrchestrator(FakeDeviceIdentity(), FakePasskeys(null), routeKey = routeKey)
        val outcome = orchestrator.pair(invitationUri(routeKey), channel)
        assertThat((outcome as PairingOutcome.Failed).failure)
            .isEqualTo(PairingFailure.ProtocolViolation("pairing token is invalid"))
    }

    @Test
    fun nonBase64UrlPairingTokenIsRejected() = runTest {
        val routeKey = newRouteKey()
        val channel = FakeChannel(registrationOptions(pairingToken = "!!!!not-base64url!!!!"))
        val orchestrator = newOrchestrator(FakeDeviceIdentity(), FakePasskeys(null), routeKey = routeKey)
        val outcome = orchestrator.pair(invitationUri(routeKey), channel)
        assertThat((outcome as PairingOutcome.Failed).failure)
            .isEqualTo(PairingFailure.ProtocolViolation("pairing token is invalid"))
    }

    @Test
    fun nonCanonicalPairingTokenIsRejected() = runTest {
        val routeKey = newRouteKey()
        val padded = java.util.Base64.getUrlEncoder().encodeToString(pairingToken)
        val channel = FakeChannel(registrationOptions(pairingToken = padded))
        val orchestrator = newOrchestrator(FakeDeviceIdentity(), FakePasskeys(null), routeKey = routeKey)
        val outcome = orchestrator.pair(invitationUri(routeKey), channel)
        assertThat((outcome as PairingOutcome.Failed).failure)
            .isEqualTo(PairingFailure.ProtocolViolation("pairing token is invalid"))
    }

    @Test
    fun csrBindingMismatchIsRejected() = runTest {
        val routeKey = newRouteKey()
        val channel = FakeChannel(registrationOptions(csrSha256 = "00".repeat(32)))
        val orchestrator = newOrchestrator(FakeDeviceIdentity(), FakePasskeys(null), routeKey = routeKey)
        val outcome = orchestrator.pair(invitationUri(routeKey), channel)
        assertThat((outcome as PairingOutcome.Failed).failure).isEqualTo(PairingFailure.BindingMismatch("csrSha256"))
    }

    @Test
    fun passkeyCancellationSurfacesTypedFailure() = runTest {
        val routeKey = newRouteKey()
        val channel = FakeChannel(registrationOptions())
        val orchestrator = newOrchestrator(FakeDeviceIdentity(), FakePasskeys(PasskeyResult.Cancelled), routeKey = routeKey)
        val outcome = orchestrator.pair(invitationUri(routeKey), channel)
        val failure = (outcome as PairingOutcome.Failed).failure as PairingFailure.PasskeyFailed
        assertThat(failure.failure).isEqualTo(PasskeyResult.Cancelled)
    }

    @Test
    fun localRejectionFailsClosed() = runTest {
        val routeKey = newRouteKey()
        val channel = FakeChannel(
            registrationOptions(),
            authResult(),
            confirm(status = "rejected"),
        )
        val passkeys = FakePasskeys(PasskeyResult.Registration(credentialResponse("webauthn.create")))
        val orchestrator = newOrchestrator(FakeDeviceIdentity(), passkeys, routeKey = routeKey)
        val outcome = orchestrator.pair(invitationUri(routeKey), channel)
        assertThat((outcome as PairingOutcome.Failed).failure).isEqualTo(PairingFailure.LocalConfirmationRejected)
    }

    @Test
    fun consumedInvitationIsRejectedAtomically() = runTest {
        val routeKey = newRouteKey()
        val consumption = InMemoryInvitationConsumption()
        assertThat(consumption.tryConsume(UUID.fromString(invitationId), Instant.parse("2030-01-01T00:05:00Z"))).isTrue()
        val channel = FakeChannel(
            registrationOptions(),
            authResult(),
            confirm(status = "waiting", shortCode = "123456"),
        )
        val passkeys = FakePasskeys(PasskeyResult.Registration(credentialResponse("webauthn.create")))
        val orchestrator = newOrchestrator(FakeDeviceIdentity(), passkeys, routeKey = routeKey, consumption = consumption)
        val outcome = orchestrator.pair(invitationUri(routeKey), channel)
        assertThat((outcome as PairingOutcome.Failed).failure).isEqualTo(PairingFailure.InvitationReplayed)
    }

    @Test
    fun transcriptMismatchIsRejected() = runTest {
        val routeKey = newRouteKey()
        val channel = FakeChannel(
            registrationOptions(),
            authResult(transcriptHash = "00".repeat(32)),
        )
        val passkeys = FakePasskeys(PasskeyResult.Registration(credentialResponse("webauthn.create")))
        val orchestrator = newOrchestrator(FakeDeviceIdentity(), passkeys, routeKey = routeKey)
        val outcome = orchestrator.pair(invitationUri(routeKey), channel)
        assertThat((outcome as PairingOutcome.Failed).failure).isInstanceOf(PairingFailure.ProtocolViolation::class.java)
    }

    @Test
    fun provisionalContextDerivesSanIdentityFromInvitationMacInstanceId() = runTest {
        val routeKey = newRouteKey()
        val prepared = newOrchestrator(FakeDeviceIdentity(), FakePasskeys(null), routeKey = routeKey)
            .prepare(invitationUri(routeKey)) as PrepareResult.Ready
        val context = prepared.prepared.invitation.provisionalTlsContext(clock)
        assertThat(context.newEngine("localhost", 443).useClientMode).isTrue()
        assertThat(prepared.prepared.serverCertificatePin()).hasLength(32)
        assertThat(prepared.prepared.invitation.macInstanceId.toString()).isEqualTo(macInstanceId)
    }

    private fun newOrchestrator(
        identity: DeviceIdentityStore,
        passkeys: PasskeyCeremonyPerformer,
        routeKey: KeyPair? = null,
        consumption: InvitationConsumption = InMemoryInvitationConsumption(),
    ): PairingOrchestrator = PairingOrchestrator(
        deviceIdentity = identity,
        passkeys = passkeys,
        clock = clock,
        consumption = consumption,
        routePublicKey = routeKey?.public,
        deviceIdProvider = { deviceId },
    )

    private fun newRouteKey(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private fun invitationUri(routeKey: KeyPair, expiresAt: String = "2030-01-01T00:05:00Z"): String {
        val signed = JsonObject(
            mapOf(
                "version" to JsonPrimitive(1),
                "relayUrl" to JsonPrimitive("wss://relay.example.test/pair"),
                "routeId" to JsonPrimitive("route-1"),
                "routeKeyId" to JsonPrimitive("mac-key-1"),
                "invitationId" to JsonPrimitive(invitationId),
                "macInstanceId" to JsonPrimitive(macInstanceId),
                "expiresAt" to JsonPrimitive(expiresAt),
                "nonce" to JsonPrimitive(encodeBase64Url(ByteArray(32) { 1 })),
                "serverCertificateSha256" to JsonPrimitive("02".repeat(32)),
                "directCandidates" to JsonArray(
                    listOf(JsonObject(mapOf("host" to JsonPrimitive("mac.local"), "port" to JsonPrimitive(443)))),
                ),
            ),
        )
        val canonical = canonicalizeJson(signed).encodeToByteArray()
        val signature = sign(routeKey.private, canonical)
        val envelope = JsonObject(
            mapOf(
                "signed" to signed,
                "signature" to JsonPrimitive(encodeBase64Url(signature)),
            ),
        )
        return "pimobile://pair?v=1&d=${encodeBase64Url(canonicalizeJson(envelope).encodeToByteArray())}"
    }

    private fun sign(key: PrivateKey, payload: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(key)
        update(payload)
        sign()
    }

    private fun binding(kind: String, sessionBinding: String? = null, csrSha256: String? = null): JsonObject {
        return JsonObject(
            mapOf(
                "ceremonyKind" to JsonPrimitive(kind),
                "invitationId" to JsonPrimitive(invitationId),
                "sessionBinding" to JsonPrimitive(sessionBinding ?: sha256Hex(pairingToken)),
                "csrSha256" to JsonPrimitive(csrSha256 ?: sha256Hex(csrDer)),
                "rpId" to JsonPrimitive(PasskeyIdentity.RpId),
                "origin" to JsonPrimitive(PasskeyIdentity.AndroidOrigin),
                "challenge" to JsonPrimitive(challenge),
                "expiresAt" to JsonPrimitive("2030-01-01T00:04:00Z"),
            ),
        )
    }

    private fun registrationOptions(
        sessionBinding: String? = null,
        csrSha256: String? = null,
        pairingToken: String = pairingTokenB64,
    ) =
        PairingChannelMessage(
            "auth.registration.options",
            JsonObject(
                mapOf(
                    "ceremonyId" to JsonPrimitive(ceremonyId),
                    "binding" to binding("registration", sessionBinding, csrSha256),
                    "publicKey" to JsonObject(mapOf("challenge" to JsonPrimitive(challenge))),
                    "pairingToken" to JsonPrimitive(pairingToken),
                ),
            ),
        )

    private fun assertionOptions() = PairingChannelMessage(
        "auth.assertion.options",
        JsonObject(
            mapOf(
                "ceremonyId" to JsonPrimitive(ceremonyId),
                "binding" to binding("assertion"),
                "publicKey" to JsonObject(mapOf("challenge" to JsonPrimitive(challenge))),
                "pairingToken" to JsonPrimitive(pairingTokenB64),
            ),
        ),
    )

    private fun authResult(transcriptHash: String = sha256Hex("$ceremonyId:$challenge".encodeToByteArray())) =
        PairingChannelMessage(
            "auth.result",
            JsonObject(
                mapOf(
                    "ceremonyId" to JsonPrimitive(ceremonyId),
                    "success" to JsonPrimitive(true),
                    "transcriptHash" to JsonPrimitive(transcriptHash),
                ),
            ),
        )

    private fun confirm(status: String, shortCode: String? = null) = PairingChannelMessage(
        "pair.confirm",
        JsonObject(
            buildMap<String, JsonElement> {
                put("invitationId", JsonPrimitive(invitationId))
                put("status", JsonPrimitive(status))
                put("transcriptHash", JsonPrimitive(sha256Hex("$ceremonyId:$challenge".encodeToByteArray())))
                shortCode?.let { put("shortCode", JsonPrimitive(it)) }
            },
        ),
    )

    private fun pairResult(): PairingChannelMessage {
        val clientPem = resourceText("/pki/client-cert.crt")
        val caPem = resourceText("/pki/ca-cert.crt")
        return PairingChannelMessage(
            "pair.result",
            JsonObject(
                mapOf(
                    "invitationId" to JsonPrimitive(invitationId),
                    "deviceId" to JsonPrimitive(deviceId),
                    "deviceCertificateChain" to JsonArray(listOf(JsonPrimitive(clientPem), JsonPrimitive(caPem))),
                    "routeKeyId" to JsonPrimitive("device-route-$deviceId"),
                ),
            ),
        )
    }

    private fun resourceText(path: String): String =
        requireNotNull(javaClass.getResource(path)).readText()

    private fun credentialResponse(type: String): String {
        val id = encodeBase64Url(ByteArray(32) { 5 })
        val clientData = encodeBase64Url(
            """{"type":"$type","challenge":"$challenge","origin":"${PasskeyIdentity.AndroidOrigin}","crossOrigin":false}""".encodeToByteArray(),
        )
        return """{"id":"$id","rawId":"$id","type":"public-key","response":{"clientDataJSON":"$clientData"}}"""
    }

    private class FakeChannel(
        vararg incoming: PairingChannelMessage,
    ) : PairingChannel {
        private val queue = ArrayDeque(incoming.toList())
        val sent = mutableListOf<PairingChannelMessage>()
        val statesAtReceipt = mutableListOf<PairingState>()
        var closed = false

        var orchestrator: PairingOrchestrator? = null

        override suspend fun send(type: String, body: JsonObject) {
            sent += PairingChannelMessage(type, body)
        }

        override suspend fun receive(): PairingChannelMessage {
            orchestrator?.let { statesAtReceipt += it.state.value }
            return queue.removeFirstOrNull()
                ?: throw NetworkException(NetworkError.TRANSPORT_CLOSED, "no more messages")
        }

        override suspend fun close() {
            closed = true
        }
    }

    private class FakePasskeys(private val result: PasskeyResult?) : PasskeyCeremonyPerformer {
        var lastOptionsJson: String? = null

        override suspend fun register(optionsJson: String): PasskeyResult {
            lastOptionsJson = optionsJson
            return result ?: PasskeyResult.Failed("unexpected")
        }

        override suspend fun assert(optionsJson: String): PasskeyResult {
            lastOptionsJson = optionsJson
            return result ?: PasskeyResult.Failed("unexpected")
        }
    }

    private inner class FakeDeviceIdentity : DeviceIdentityStore {
        var installed: Pair<String, List<ByteArray>>? = null
        private val keyManager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?): String? = null
            override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?): String? = null
            override fun getCertificateChain(alias: String?): Array<java.security.cert.X509Certificate>? = null
            override fun getPrivateKey(alias: String?): PrivateKey? = null
        }

        override fun getOrCreate(deviceId: String): DevicePublicKeys = DevicePublicKeys(
            tlsPublicKeySpki = byteArrayOf(1),
            routePublicKeySpki = byteArrayOf(2),
            csrDer = csrDer.copyOf(),
        )

        override fun routePayloadSigner(): io.github.verybigsad.pimobile.security.RoutePayloadSigner =
            io.github.verybigsad.pimobile.security.RoutePayloadSigner { payload -> payload.copyOf() }

        override fun installClientCertificateChain(deviceId: String, certificateChainDer: List<ByteArray>): InstalledClientIdentity {
            installed = deviceId to certificateChainDer
            return InstalledClientIdentity(
                deviceId = deviceId,
                leafFingerprint = CertificateFingerprint.fromCertificate(certificateChainDer.first()),
                issuerFingerprint = CertificateFingerprint.fromCertificate(certificateChainDer.last()),
                notBefore = now,
                notAfter = now.plusSeconds(60),
            )
        }

        override fun tlsKeyManager(deviceId: String): X509ExtendedKeyManager = keyManager
    }
}
