package io.github.verybigsad.pimobile.network

import io.github.verybigsad.pimobile.protocol.sha256Hex
import io.github.verybigsad.pimobile.security.DeviceIdentityStore
import io.github.verybigsad.pimobile.security.InstalledClientIdentity
import io.github.verybigsad.pimobile.security.PairingInvitation
import io.github.verybigsad.pimobile.security.PasskeyCeremonyPerformer
import io.github.verybigsad.pimobile.security.PasskeyIdentity
import io.github.verybigsad.pimobile.security.PasskeyLockReason
import io.github.verybigsad.pimobile.security.PasskeyResult
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** TLS exporter label/length of the provisional session; mirrors the unified Mac exporter. */
const val TlsExporterLabel = "EXPORTER-Pi-Mobile-Pairing-v1"
const val TlsExporterBytes = 32

/** Exact byte length of the in-channel `pairingToken` carried by the pair.begin response. */
const val PairingTokenBytes = 32

private val MaxPairingMessage = JsonBounds(maxBytes = 64 * 1024, maxDepth = 10, maxNodes = 2_048, maxStringChars = 16 * 1024)
private val MaxPairingCeremonyLifetime: Duration = Duration.ofMinutes(5)
private val sha256HexPattern = Regex("^[0-9a-f]{64}$")
private val deviceIdPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val shortCodePattern = Regex("^[0-9]{6}$")
private val pemBase64Pattern = Regex("^[A-Za-z0-9+/=]+$")

enum class PairingCeremonyKind(val wireValue: String) {
    REGISTRATION("registration"),
    ASSERTION("assertion"),
    ;

    companion object {
        fun fromWire(value: String): PairingCeremonyKind? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Challenge binding advertised by the Mac in `auth.*.options`. Mirrors the Mac
 * `pairingBinding` schema exactly: ceremonyKind/invitationId/sessionBinding/csrSha256/
 * rpId/origin/challenge/expiresAt. `sessionBinding` is the lowercase hex SHA-256 of the
 * 32-byte `pairingToken` from the pair.begin response; `csrSha256` is lowercase hex SHA-256.
 */
data class PairingBinding(
    val ceremonyKind: PairingCeremonyKind,
    val invitationId: String,
    val sessionBinding: String,
    val csrSha256: String,
    val rpId: String,
    val origin: String,
    val challenge: String,
    val expiresAt: Instant,
)

sealed interface PairingFailure {
    val code: String

    data class InvitationInvalid(val detail: String) : PairingFailure {
        override val code: String get() = "PAIRING_INVITATION_INVALID"
    }

    data object InvitationExpired : PairingFailure {
        override val code: String get() = "PAIRING_INVITATION_EXPIRED"
    }

    data object InvitationReplayed : PairingFailure {
        override val code: String get() = "PAIRING_INVITATION_REPLAYED"
    }

    data object InvitationSignatureInvalid : PairingFailure {
        override val code: String get() = "PAIRING_INVITATION_SIGNATURE_INVALID"
    }

    data class Transport(val detail: String) : PairingFailure {
        override val code: String get() = "PAIRING_TRANSPORT_FAILED"
    }

    data class ProtocolViolation(val detail: String) : PairingFailure {
        override val code: String get() = "PAIRING_PROTOCOL_VIOLATION"
    }

    data class BindingMismatch(val field: String) : PairingFailure {
        override val code: String get() = "PAIRING_BINDING_MISMATCH"
    }

    data class PasskeyLocked(val reason: PasskeyLockReason) : PairingFailure {
        override val code: String get() = "PAIRING_PASSKEY_LOCKED"
    }

    data class PasskeyFailed(val failure: PasskeyResult.Failure) : PairingFailure {
        override val code: String get() = "PAIRING_PASSKEY_FAILED"
    }

    data object LocalConfirmationRejected : PairingFailure {
        override val code: String get() = "PAIRING_LOCAL_CONFIRMATION_REJECTED"
    }

    data object CeremonyExpired : PairingFailure {
        override val code: String get() = "PAIRING_CEREMONY_EXPIRED"
    }

    data class IdentityStoreFailed(val detail: String) : PairingFailure {
        override val code: String get() = "PAIRING_IDENTITY_STORE_FAILED"
    }
}

/** Typed pairing ceremony states surfaced for app composition; there is no UI here. */
sealed interface PairingState {
    data object Idle : PairingState

    data class InvitationAccepted(val invitationId: String, val routeId: String, val expiresAt: Instant) : PairingState

    data class CeremonyOffered(val ceremonyId: String, val kind: PairingCeremonyKind, val binding: PairingBinding) : PairingState

    data class PasskeyPrompted(val kind: PairingCeremonyKind) : PairingState

    data class AwaitingLocalConfirmation(val ceremonyId: String, val shortCode: String) : PairingState

    data class InstallingIdentity(val deviceId: String) : PairingState

    data class Paired(val deviceId: String) : PairingState

    data class Failed(val failure: PairingFailure) : PairingState
}

sealed interface PairingOutcome {
    data class Paired(val connection: PairedConnection) : PairingOutcome

    data class Failed(val failure: PairingFailure) : PairingOutcome
}

sealed interface PrepareResult {
    data class Ready(val prepared: PreparedPairing) : PrepareResult

    data class Failed(val failure: PairingFailure) : PrepareResult
}

data class PairingChannelMessage(val type: String, val body: JsonObject)

/**
 * Provisional pairing transport (relay or direct LAN) after the inner TLS 1.3 session
 * is established. The ceremony is bound to the in-channel `pairingToken` from the
 * pair.begin response, not to the TLS exporter, so pairing works on platforms where
 * the exporter is unavailable (API 29). [TlsExporter] stays for optional use.
 */
interface PairingChannel {
    suspend fun send(type: String, body: JsonObject)

    suspend fun receive(): PairingChannelMessage

    suspend fun close()
}

/** Atomic one-shot invitation consumption guard. */
fun interface InvitationConsumption {
    fun tryConsume(invitationId: UUID, expiresAt: Instant): Boolean
}

class InMemoryInvitationConsumption : InvitationConsumption {
    private val consumed = HashSet<UUID>()

    @Synchronized
    override fun tryConsume(invitationId: UUID, expiresAt: Instant): Boolean = consumed.add(invitationId)
}

class PreparedPairing internal constructor(
    val invitation: PairingInvitation,
    val deviceId: String,
    val deviceRouteKeyId: String,
    val deviceRoutePublicKey: String,
    csrDer: ByteArray,
) {
    private val csrDerValue = csrDer.copyOf()

    val csrSha256: String = sha256Hex(csrDerValue)

    fun csrDer(): ByteArray = csrDerValue.copyOf()

    fun serverCertificatePin(): ByteArray = invitation.serverCertificateFingerprint.bytes()
}

/** Fresh post-pairing mTLS parameters: issued chain, key manager, and a CA-pinned trust manager. */
class PairedConnection internal constructor(
    val deviceId: String,
    val identity: InstalledClientIdentity,
    val certificateChain: List<X509Certificate>,
    private val keyManager: X509ExtendedKeyManager,
) {
    val caCertificates: List<X509Certificate> get() = certificateChain.drop(1)

    fun keyManager(): X509ExtendedKeyManager = keyManager

    fun trustManager(): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        caCertificates.forEachIndexed { index, ca -> store.setCertificateEntry("pimobile-mac-ca-$index", ca) }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
            init(store)
            trustManagers.filterIsInstance<X509TrustManager>().singleOrNull()
                ?: throw NetworkException(NetworkError.TLS_HANDSHAKE_FAILED, "No X.509 trust manager is available")
        }
    }

    fun mtlsContext(
        serverIdentity: CertificateIdentity,
        clock: Clock = Clock.systemUTC(),
        revocationChecker: CertificateRevocationChecker = CertificateRevocationChecker { false },
    ): TlsClientContext = TlsContexts.mutual(
        keyManagers = arrayOf(keyManager()),
        trustManagers = arrayOf(trustManager()),
        expectedServerIdentity = serverIdentity,
        clock = clock,
        revocationChecker = revocationChecker,
    )
}

/**
 * Builds the provisional (server-auth, pinned-leaf) TLS context for a parsed invitation.
 * The expected SAN identity is `urn:pimobile:mac:<macInstanceId>` from the signed invitation;
 * there is no pin-only path.
 */
fun PairingInvitation.provisionalTlsContext(
    clock: Clock = Clock.systemUTC(),
): TlsClientContext = TlsContexts.provisional(
    serverCertificateFingerprint.bytes(),
    CertificateIdentity(CertificateRole.MAC_SERVER, macInstanceId.toString()),
    clock,
)

/**
 * Composable pairing ceremony: invitation parse -> CSR generation -> passkey ceremony
 * (challenge bound to the pair.begin response `pairingToken`) -> short-code/local confirmation
 * surface -> atomic invitation consumption -> issued chain install -> fresh mTLS params.
 * The channel is closed on every terminal outcome.
 */
class PairingOrchestrator(
    private val deviceIdentity: DeviceIdentityStore,
    private val passkeys: PasskeyCeremonyPerformer,
    private val clock: Clock = Clock.systemUTC(),
    private val consumption: InvitationConsumption = InMemoryInvitationConsumption(),
    private val routePublicKey: PublicKey? = null,
    private val deviceIdProvider: () -> String = { UUID.randomUUID().toString() },
    private val routeKeyIdProvider: (deviceId: String) -> String = { deviceId -> "device-route-$deviceId" },
) {
    private val mutableState = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = mutableState.asStateFlow()

    fun prepare(invitationUri: String): PrepareResult {
        val invitation = try {
            PairingInvitation.parse(invitationUri, clock.instant())
        } catch (error: Exception) {
            return failPrepare(PairingFailure.InvitationInvalid(error.message ?: "invitation is invalid"))
        }
        val routeKey = routePublicKey
        if (routeKey != null) {
            val verified = runCatching { invitation.verifyRouteSignature(routeKey) }.getOrDefault(false)
            if (!verified) return failPrepare(PairingFailure.InvitationSignatureInvalid)
        }
        val deviceId = deviceIdProvider()
        if (!deviceIdPattern.matches(deviceId)) {
            return failPrepare(PairingFailure.IdentityStoreFailed("device identity is invalid"))
        }
        val routeKeyId = routeKeyIdProvider(deviceId)
        if (!opaqueIdPattern.matches(routeKeyId)) {
            return failPrepare(PairingFailure.IdentityStoreFailed("device route key identity is invalid"))
        }
        val keys = try {
            deviceIdentity.getOrCreate(deviceId)
        } catch (error: Exception) {
            return failPrepare(PairingFailure.IdentityStoreFailed(error.message ?: "device keys are unavailable"))
        }
        val routePublicKey = encodeBase64Url(keys.routePublicKeySpki)
        mutableState.value = PairingState.InvitationAccepted(
            invitationId = invitation.invitationId.toString(),
            routeId = invitation.routeId,
            expiresAt = invitation.expiresAt,
        )
        return PrepareResult.Ready(
            PreparedPairing(invitation, deviceId, routeKeyId, routePublicKey, keys.csrDer),
        )
    }

    suspend fun pair(invitationUri: String, channel: PairingChannel): PairingOutcome = when (val result = prepare(invitationUri)) {
        is PrepareResult.Failed -> {
            runCatching { channel.close() }
            PairingOutcome.Failed(result.failure)
        }
        is PrepareResult.Ready -> pair(result.prepared, channel)
    }

    suspend fun pair(prepared: PreparedPairing, channel: PairingChannel): PairingOutcome {
        return try {
            val remaining = prepared.invitation.expiresAt.toEpochMilli() - clock.millis()
            if (remaining <= 0) return fail(PairingFailure.InvitationExpired)
            val connection = withTimeout(remaining) { runCeremony(prepared, channel) }
            mutableState.value = PairingState.Paired(connection.deviceId)
            PairingOutcome.Paired(connection)
        } catch (error: PairingFailureException) {
            fail(error.failure)
        } catch (_: TimeoutCancellationException) {
            fail(PairingFailure.CeremonyExpired)
        } catch (error: CancellationException) {
            throw error
        } catch (error: NetworkException) {
            fail(PairingFailure.Transport(error.message ?: "transport failure"))
        } catch (error: Exception) {
            fail(PairingFailure.ProtocolViolation(error.message ?: "unexpected pairing failure"))
        } finally {
            runCatching { channel.close() }
        }
    }

    fun reset() {
        mutableState.value = PairingState.Idle
    }

    private suspend fun runCeremony(prepared: PreparedPairing, channel: PairingChannel): PairedConnection {
        channel.send(
            "pair.begin",
            JsonObject(
                mapOf(
                    "invitationId" to JsonPrimitive(prepared.invitation.invitationId.toString()),
                    "csrSha256" to JsonPrimitive(prepared.csrSha256),
                    "deviceRouteKeyId" to JsonPrimitive(prepared.deviceRouteKeyId),
                    "deviceRoutePublicKey" to JsonPrimitive(prepared.deviceRoutePublicKey),
                ),
            ),
        )

        val optionsMessage = receive(channel)
        val kind = when (optionsMessage.type) {
            "auth.registration.options" -> PairingCeremonyKind.REGISTRATION
            "auth.assertion.options" -> PairingCeremonyKind.ASSERTION
            else -> raise(PairingFailure.ProtocolViolation("expected passkey options"))
        }
        val optionsBody = optionsMessage.body
        requireFields(optionsBody, setOf("ceremonyId", "binding", "publicKey", "pairingToken"))
        val ceremonyId = optionsBody.text("ceremonyId")
        if (!opaqueIdPattern.matches(ceremonyId)) raise(PairingFailure.ProtocolViolation("ceremony identity is invalid"))
        val pairingToken = try {
            decodeBase64Url(optionsBody.text("pairingToken"), PairingTokenBytes, exactBytes = PairingTokenBytes)
        } catch (error: NetworkException) {
            raise(PairingFailure.ProtocolViolation("pairing token is invalid"))
        }
        val sessionBinding = sha256Hex(pairingToken)
        val binding = parseBinding(optionsBody.obj("binding"), kind, prepared, sessionBinding)
        val publicKeyOptions = optionsBody.obj("publicKey")
        if (publicKeyOptions.text("challenge") != binding.challenge) {
            raise(PairingFailure.BindingMismatch("challenge"))
        }
        mutableState.value = PairingState.CeremonyOffered(ceremonyId, kind, binding)

        mutableState.value = PairingState.PasskeyPrompted(kind)
        val optionsJson = publicKeyOptions.toString()
        val credentialJson = when (
            val result = if (kind == PairingCeremonyKind.REGISTRATION) {
                passkeys.register(optionsJson)
            } else {
                passkeys.assert(optionsJson)
            }
        ) {
            is PasskeyResult.Registration -> {
                if (kind != PairingCeremonyKind.REGISTRATION) raise(PairingFailure.ProtocolViolation("unexpected passkey response"))
                result.responseJson
            }
            is PasskeyResult.Assertion -> {
                if (kind != PairingCeremonyKind.ASSERTION) raise(PairingFailure.ProtocolViolation("unexpected passkey response"))
                result.responseJson
            }
            is PasskeyResult.Locked -> raise(PairingFailure.PasskeyLocked(result.reason))
            is PasskeyResult.Failure -> raise(PairingFailure.PasskeyFailed(result))
        }
        val credential = StrictJson.parseObject(credentialJson.encodeToByteArray(), MaxPairingMessage)
        channel.send(
            if (kind == PairingCeremonyKind.REGISTRATION) "auth.registration.response" else "auth.assertion.response",
            JsonObject(mapOf("credential" to credential)),
        )

        val expectedTranscript = sha256Hex("$ceremonyId:${binding.challenge}".encodeToByteArray())
        val authResult = receive(channel)
        if (authResult.type != "auth.result") raise(PairingFailure.ProtocolViolation("expected passkey result"))
        validateAuthResult(authResult.body, ceremonyId, expectedTranscript)

        channel.send("pair.confirm", JsonObject(emptyMap()))
        val confirmation = receive(channel)
        if (confirmation.type != "pair.confirm") raise(PairingFailure.ProtocolViolation("expected pairing confirmation"))
        when (validateConfirmation(confirmation.body, prepared, expectedTranscript)) {
            Confirmation.REJECTED -> raise(PairingFailure.LocalConfirmationRejected)
            Confirmation.CONFIRMED -> Unit
            Confirmation.WAITING -> Unit
        }
        confirmation.body.optionalText("shortCode")?.let { shortCode ->
            if (!shortCodePattern.matches(shortCode)) raise(PairingFailure.ProtocolViolation("short code is invalid"))
            mutableState.value = PairingState.AwaitingLocalConfirmation(ceremonyId, shortCode)
        }

        if (!consumption.tryConsume(prepared.invitation.invitationId, prepared.invitation.expiresAt)) {
            raise(PairingFailure.InvitationReplayed)
        }
        channel.send(
            "pair.csr",
            JsonObject(
                mapOf(
                    "invitationId" to JsonPrimitive(prepared.invitation.invitationId.toString()),
                    "csrSha256" to JsonPrimitive(prepared.csrSha256),
                    "csrDer" to JsonPrimitive(encodeBase64Url(prepared.csrDer())),
                ),
            ),
        )

        val resultBody = awaitIssuance(channel, prepared, expectedTranscript)
        mutableState.value = PairingState.InstallingIdentity(prepared.deviceId)
        return installIdentity(prepared, resultBody)
    }

    private suspend fun awaitIssuance(
        channel: PairingChannel,
        prepared: PreparedPairing,
        expectedTranscript: String,
    ): JsonObject {
        var confirmed = false
        repeat(16) {
            val message = receive(channel)
            when (message.type) {
                "pair.confirm" -> {
                    when (validateConfirmation(message.body, prepared, expectedTranscript)) {
                        Confirmation.REJECTED -> raise(PairingFailure.LocalConfirmationRejected)
                        Confirmation.CONFIRMED -> confirmed = true
                        Confirmation.WAITING -> Unit
                    }
                }
                "pair.result" -> {
                    if (!confirmed) raise(PairingFailure.ProtocolViolation("issuance arrived before local confirmation"))
                    return message.body
                }
                else -> raise(PairingFailure.ProtocolViolation("unexpected pairing message"))
            }
        }
        raise(PairingFailure.ProtocolViolation("pairing issuance is missing"))
    }

    private fun installIdentity(prepared: PreparedPairing, resultBody: JsonObject): PairedConnection {
        requireFields(resultBody, setOf("invitationId", "deviceId", "deviceCertificateChain", "routeKeyId"))
        if (resultBody.text("invitationId") != prepared.invitation.invitationId.toString()) {
            raise(PairingFailure.ProtocolViolation("issued invitation mismatch"))
        }
        if (resultBody.text("deviceId") != prepared.deviceId) {
            raise(PairingFailure.ProtocolViolation("issued device identity mismatch"))
        }
        if (resultBody.text("routeKeyId") != prepared.deviceRouteKeyId) {
            raise(PairingFailure.ProtocolViolation("issued route key mismatch"))
        }
        val chainElement = resultBody["deviceCertificateChain"] as? JsonArray
            ?: raise(PairingFailure.ProtocolViolation("certificate chain is invalid"))
        if (chainElement.size !in 2..4) raise(PairingFailure.ProtocolViolation("certificate chain size is invalid"))
        val derChain = chainElement.map { element ->
            val pem = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: raise(PairingFailure.ProtocolViolation("certificate entry is invalid"))
            decodeCertificatePem(pem)
        }
        if (derChain.sumOf(ByteArray::size) > 32 * 1024) {
            raise(PairingFailure.ProtocolViolation("certificate chain exceeds its bound"))
        }
        val identity = try {
            deviceIdentity.installClientCertificateChain(prepared.deviceId, derChain.map(ByteArray::copyOf))
        } catch (error: Exception) {
            raise(PairingFailure.IdentityStoreFailed(error.message ?: "certificate install failed"))
        }
        if (identity.deviceId != prepared.deviceId) {
            raise(PairingFailure.IdentityStoreFailed("installed identity mismatch"))
        }
        val keyManager = try {
            deviceIdentity.tlsKeyManager(prepared.deviceId)
        } catch (error: Exception) {
            raise(PairingFailure.IdentityStoreFailed(error.message ?: "key manager is unavailable"))
        }
        val certificates = parseCertificates(derChain)
        return PairedConnection(prepared.deviceId, identity, certificates, keyManager)
    }

    private fun parseBinding(
        value: JsonObject,
        kind: PairingCeremonyKind,
        prepared: PreparedPairing,
        sessionBinding: String,
    ): PairingBinding {
        requireFields(
            value,
            setOf("ceremonyKind", "invitationId", "sessionBinding", "csrSha256", "rpId", "origin", "challenge", "expiresAt"),
        )
        if (value.text("ceremonyKind") != kind.wireValue) raise(PairingFailure.BindingMismatch("ceremonyKind"))
        if (value.text("invitationId") != prepared.invitation.invitationId.toString()) {
            raise(PairingFailure.BindingMismatch("invitationId"))
        }
        val sessionBindingValue = value.text("sessionBinding")
        if (!sha256HexPattern.matches(sessionBindingValue) || sessionBindingValue != sessionBinding) {
            raise(PairingFailure.BindingMismatch("sessionBinding"))
        }
        val csrSha256 = value.text("csrSha256")
        if (!sha256HexPattern.matches(csrSha256) || csrSha256 != prepared.csrSha256) {
            raise(PairingFailure.BindingMismatch("csrSha256"))
        }
        if (value.text("rpId") != PasskeyIdentity.RpId) raise(PairingFailure.BindingMismatch("rpId"))
        if (value.text("origin") != PasskeyIdentity.AndroidOrigin) raise(PairingFailure.BindingMismatch("origin"))
        val challenge = value.text("challenge")
        val challengeBytes = try {
            decodeBase64Url(challenge, 512)
        } catch (error: NetworkException) {
            raise(PairingFailure.BindingMismatch("challenge"))
        }
        if (challengeBytes.size < 16) raise(PairingFailure.BindingMismatch("challenge"))
        val expiresAt = try {
            parseInstant(value.text("expiresAt"), NetworkError.CHALLENGE_INVALID)
        } catch (error: NetworkException) {
            raise(PairingFailure.BindingMismatch("expiresAt"))
        }
        val now = clock.instant()
        if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(MaxPairingCeremonyLifetime))) {
            raise(PairingFailure.CeremonyExpired)
        }
        return PairingBinding(
            ceremonyKind = kind,
            invitationId = value.text("invitationId"),
            sessionBinding = sessionBindingValue,
            csrSha256 = csrSha256,
            rpId = value.text("rpId"),
            origin = value.text("origin"),
            challenge = challenge,
            expiresAt = expiresAt,
        )
    }

    private fun validateAuthResult(body: JsonObject, ceremonyId: String, expectedTranscript: String) {
        requireFields(body, setOf("ceremonyId", "success", "transcriptHash"))
        if (body.text("ceremonyId") != ceremonyId) raise(PairingFailure.ProtocolViolation("ceremony identity mismatch"))
        if (body["success"] != JsonPrimitive(true)) raise(PairingFailure.ProtocolViolation("passkey ceremony was rejected"))
        if (body.text("transcriptHash") != expectedTranscript) {
            raise(PairingFailure.ProtocolViolation("ceremony transcript mismatch"))
        }
    }

    private enum class Confirmation { WAITING, CONFIRMED, REJECTED }

    private fun validateConfirmation(body: JsonObject, prepared: PreparedPairing, expectedTranscript: String): Confirmation {
        requireFields(body, setOf("invitationId", "status", "transcriptHash"), optional = setOf("shortCode"))
        if (body.text("invitationId") != prepared.invitation.invitationId.toString()) {
            raise(PairingFailure.ProtocolViolation("confirmation invitation mismatch"))
        }
        if (body.text("transcriptHash") != expectedTranscript) {
            raise(PairingFailure.ProtocolViolation("confirmation transcript mismatch"))
        }
        return when (body.text("status")) {
            "waiting" -> Confirmation.WAITING
            "confirmed" -> Confirmation.CONFIRMED
            "rejected" -> Confirmation.REJECTED
            else -> raise(PairingFailure.ProtocolViolation("confirmation status is invalid"))
        }
    }

    private suspend fun receive(channel: PairingChannel): PairingChannelMessage {
        val message = channel.receive()
        if (message.type.isEmpty() || message.type.length > 64) {
            raise(PairingFailure.ProtocolViolation("pairing message type is invalid"))
        }
        StrictJson.canonicalize(message.body).also {
            if (it.size > MaxPairingMessage.maxBytes) raise(PairingFailure.ProtocolViolation("pairing message exceeds its bound"))
        }
        return message
    }

    private fun requireFields(value: JsonObject, required: Set<String>, optional: Set<String> = emptySet()) {
        if (!value.keys.containsAll(required) || !value.keys.all { it in required || it in optional }) {
            raise(PairingFailure.ProtocolViolation("pairing message fields are invalid"))
        }
    }

    private fun JsonObject.text(name: String): String = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: raise(PairingFailure.ProtocolViolation("$name is invalid"))

    private fun JsonObject.optionalText(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.obj(name: String): JsonObject = this[name] as? JsonObject
        ?: raise(PairingFailure.ProtocolViolation("$name is invalid"))

    private fun decodeCertificatePem(pem: String): ByteArray {
        if (pem.length > 16 * 1024) raise(PairingFailure.ProtocolViolation("certificate entry exceeds its bound"))
        val lines = pem.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.size < 3 || lines.first() != "-----BEGIN CERTIFICATE-----" || lines.last() != "-----END CERTIFICATE-----") {
            raise(PairingFailure.ProtocolViolation("certificate entry is not PEM"))
        }
        val body = lines.subList(1, lines.size - 1).joinToString("")
        if (!pemBase64Pattern.matches(body)) raise(PairingFailure.ProtocolViolation("certificate entry is not PEM"))
        val der = try {
            Base64.getDecoder().decode(body)
        } catch (error: IllegalArgumentException) {
            raise(PairingFailure.ProtocolViolation("certificate entry is not PEM"))
        }
        if (der.size !in 1..8 * 1024) raise(PairingFailure.ProtocolViolation("certificate entry exceeds its bound"))
        return der
    }

    private fun parseCertificates(derChain: List<ByteArray>): List<X509Certificate> {
        val factory = try {
            CertificateFactory.getInstance("X.509")
        } catch (error: Exception) {
            raise(PairingFailure.IdentityStoreFailed("X.509 factory is unavailable"))
        }
        return derChain.map { der ->
            try {
                val input = ByteArrayInputStream(der)
                val certificate = factory.generateCertificate(input) as X509Certificate
                if (input.available() != 0) throw IllegalArgumentException("trailing bytes")
                certificate
            } catch (error: Exception) {
                raise(PairingFailure.ProtocolViolation("certificate entry is not X.509"))
            }
        }
    }

    private fun fail(failure: PairingFailure): PairingOutcome.Failed {
        mutableState.value = PairingState.Failed(failure)
        return PairingOutcome.Failed(failure)
    }

    private fun failPrepare(failure: PairingFailure): PrepareResult.Failed {
        mutableState.value = PairingState.Failed(failure)
        return PrepareResult.Failed(failure)
    }

    private fun raise(failure: PairingFailure): Nothing = throw PairingFailureException(failure)

    private class PairingFailureException(val failure: PairingFailure) : Exception(failure.code)
}
