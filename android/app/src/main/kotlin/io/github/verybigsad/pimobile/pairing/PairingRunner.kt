package io.github.verybigsad.pimobile.pairing

import io.github.verybigsad.pimobile.network.DuplexByteChannel
import io.github.verybigsad.pimobile.network.InMemoryInvitationConsumption
import io.github.verybigsad.pimobile.network.InvitationConsumption
import io.github.verybigsad.pimobile.network.NetworkError
import io.github.verybigsad.pimobile.network.NetworkException
import io.github.verybigsad.pimobile.network.PathAttempt
import io.github.verybigsad.pimobile.network.PathConnection
import io.github.verybigsad.pimobile.network.PathRaceCoordinator
import io.github.verybigsad.pimobile.network.PimbStreamTransport
import io.github.verybigsad.pimobile.network.RelayPairingPath
import io.github.verybigsad.pimobile.network.RelayPairingRendezvous
import io.github.verybigsad.pimobile.network.TransportPathKind
import io.github.verybigsad.pimobile.network.asRelayProofSigner
import io.github.verybigsad.pimobile.protocol.FrameKind
import io.github.verybigsad.pimobile.security.DeviceKeys
import io.github.verybigsad.pimobile.security.MacIdentityDeriver
import io.github.verybigsad.pimobile.security.PairedProfile
import io.github.verybigsad.pimobile.security.PairingInvitation
import io.github.verybigsad.pimobile.wire.WireMessages
import io.github.verybigsad.pimobile.wire.WireMessages.string
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

sealed interface PairingProgressEvent {
    data object Connecting : PairingProgressEvent

    data class PasskeyRequired(
        val registration: Boolean,
        val ceremonyId: String,
        val binding: JsonObject,
        val optionsJson: String,
    ) : PairingProgressEvent

    /** Short code rendered from host pair.confirm messages; confirmation happens on the Mac. */
    data class AwaitingConfirmation(val shortCode: String?) : PairingProgressEvent

    data object IssuingCertificate : PairingProgressEvent

    data class Completed(val profile: PairedProfile) : PairingProgressEvent

    data class Failed(val code: String) : PairingProgressEvent
}

/** Maps relay-path transport errors to typed PAIRING_RELAY_* failure codes. */
private fun relayFailureCode(error: NetworkException): String = when (error.code) {
    NetworkError.RELAY_PAIRING_CONFLICT -> "PAIRING_RELAY_CONFLICT"
    NetworkError.RELAY_PAIRING_RATE_LIMITED -> "PAIRING_RELAY_RATE_LIMITED"
    NetworkError.RELAY_PAIRING_UNAVAILABLE -> "PAIRING_RELAY_UNAVAILABLE"
    NetworkError.RELAY_PAIRING_NOT_READY -> "PAIRING_RELAY_REPLY_TIMEOUT"
    NetworkError.RELAY_PAIRING_FAILED -> "PAIRING_RELAY_FAILED"
    else -> "PAIRING_RELAY_${error.code.name}"
}

fun interface PairingPasskeyPort {
    /**
     * Runs a Credential Manager ceremony bound to the current foreground Activity.
     * Returns the validated response JSON, or null when the provider is locked/missing or
     * the ceremony failed; the failure code is surfaced through [onFailure].
     */
    suspend fun perform(registration: Boolean, optionsJson: String, onFailure: (String) -> Unit): String?
}

/**
 * Drives the provisional pairing ceremony over the pinned pairing channel:
 * pair.begin -> auth.*.options -> passkey -> auth.*.response -> auth.result -> pair.csr
 * -> pair.confirm (short code display) -> pair.result -> certificate install + profile.
 *
 * Paths raced: direct provisional-TLS candidates and the relay rendezvous
 * (relay/internal/pairing one-shot exchange + relay-spliced device-data tunnel carrying
 * the inner provisional TLS session). The ceremony, short-code confirmation surface,
 * invitation consumption, and expiry handling are identical on both paths.
 */
class PairingRunner(
    private val invitation: PairingInvitation,
    private val deviceKeys: DeviceKeys,
    private val deviceId: String,
    private val passkey: PairingPasskeyPort,
    private val onEvent: (PairingProgressEvent) -> Unit,
    private val relayPairingPath: RelayPairingPath = RelayPairingPath(),
    private val consumption: InvitationConsumption = sharedInvitationConsumption,
) {
    private val relayFailure = AtomicReference<NetworkException?>(null)

    suspend fun run() {
        try {
            onEvent(PairingProgressEvent.Connecting)
            // Field names only; diagnostics for path selection.
            android.util.Log.w("PairingRunner", "invitation fields: ${invitation.signedPayload().keys.sorted()}")
            val keys = deviceKeys.getOrCreate(deviceId)
            val csrSha256 = MacIdentityDeriver.sha256Hex(keys.csrDer)
            val routeKeyId = "device-route-$deviceId"
            val routePublicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(keys.routePublicKeySpki)

            val rendezvous = try {
                RelayPairingRendezvous.fromInvitation(invitation).also {
                    android.util.Log.w("PairingRunner", "rendezvous extracted: ${it != null}")
                }
            } catch (error: NetworkException) {
                android.util.Log.w("PairingRunner", "rendezvous error: ${error.code.name}")
                relayFailure.compareAndSet(null, error)
                null
            }
            if (invitation.directCandidates.isEmpty() && rendezvous == null) {
                onEvent(PairingProgressEvent.Failed("PAIRING_NO_DIRECT_PATH"))
                return
            }

            val learnedMacId = AtomicReference<String?>(null)
            val race = PathRaceCoordinator()
            val attempts = invitation.directCandidates.map { candidate ->
                PathAttempt { generation ->
                    val (channel, peer) = ProvisionalTls.connect(
                        candidate.host,
                        candidate.port,
                        invitation.serverCertificateFingerprint.bytes(),
                    )
                    object : PathConnection {
                        override val kind: TransportPathKind = TransportPathKind.DIRECT
                        override val generation: Long = generation
                        override val channel: DuplexByteChannel = channel
                        override suspend fun authenticate() {
                            learnedMacId.compareAndSet(null, peer.macId)
                        }

                        override suspend fun close() = channel.close()
                    }
                }
            } + listOfNotNull(
                rendezvous?.let { active ->
                    PathAttempt { generation ->
                        val established = try {
                            relayPairingPath.connect(
                                invitation,
                                active,
                                routeKeyId,
                                routePublicKey,
                                deviceKeys.routePayloadSigner().asRelayProofSigner(),
                                invitation.expiresAt,
                            )
                        } catch (error: NetworkException) {
                            relayFailure.compareAndSet(null, error)
                            throw error
                        } catch (error: Throwable) {
                            android.util.Log.w("PairingRunner", "relay path threw: ${error.javaClass.simpleName}: ${error.message?.take(160)}")
                            throw error
                        }
                        object : PathConnection {
                            override val kind: TransportPathKind = TransportPathKind.RELAY
                            override val generation: Long = generation
                            override val channel: DuplexByteChannel = established.channel
                            override suspend fun authenticate() {
                                learnedMacId.compareAndSet(null, established.macId)
                            }

                            override suspend fun close() = established.channel.close()
                        }
                    }
                },
            )
            val connection = try {
                race.connect(attempts)
            } catch (error: NetworkException) {
                onEvent(PairingProgressEvent.Failed(connectFailureCode(error)))
                return
            }

            val transport = PimbStreamTransport(connection.channel)
            try {
                exchange(transport, learnedMacId.get(), routeKeyId, routePublicKey, csrSha256, keys.csrDer)
            } finally {
                runCatching { transport.close() }
            }
        } catch (failed: PairingFailed) {
            // Already reported through onEvent.
        } catch (error: NetworkException) {
            onEvent(PairingProgressEvent.Failed("PAIRING_${error.code.name}"))
        } catch (_: Exception) {
            onEvent(PairingProgressEvent.Failed("PAIRING_FAILED"))
        }
    }

    private suspend fun exchange(
        transport: PimbStreamTransport,
        learnedMacId: String?,
        routeKeyId: String,
        routePublicKey: String,
        csrSha256: String,
        csrDer: ByteArray,
    ) {
        val invitationId = invitation.invitationId.toString()
        android.util.Log.w("PairingRunner", "sending pair.begin")
        transport.send(
            FrameKind.Json,
            WireMessages.encode("pair.begin", WireMessages.pairBegin(invitationId, routeKeyId, routePublicKey, csrSha256)),
        )
        android.util.Log.w("PairingRunner", "pair.begin sent")

        val options = awaitMessage(transport, setOf("auth.registration.options", "auth.assertion.options"))
            ?: fail("PAIRING_NO_OPTIONS")
        val ceremonyId = options.body.string("ceremonyId") ?: fail("PAIRING_OPTIONS_INVALID")
        val binding = options.body["binding"] as? JsonObject ?: fail("PAIRING_OPTIONS_INVALID")
        val publicKey = options.body["publicKey"] as? JsonObject ?: fail("PAIRING_OPTIONS_INVALID")
        val registration = options.type == "auth.registration.options"

        onEvent(PairingProgressEvent.PasskeyRequired(registration, ceremonyId, binding, publicKey.toString()))
        var failureCode: String? = null
        val credentialJson = passkey.perform(registration, publicKey.toString()) { code -> failureCode = code }
            ?: fail(failureCode ?: "PASSKEY_FAILED")
        transport.send(
            FrameKind.Json,
            WireMessages.encode(
                if (registration) "auth.registration.response" else "auth.assertion.response",
                WireMessages.assertionResponse(ceremonyId, binding, credentialJson),
            ),
        )

        val authResult = awaitMessage(transport, setOf("auth.result")) ?: fail("PAIRING_NO_AUTH_RESULT")
        val success = (authResult.body["success"] as? JsonPrimitive)?.contentOrNull
            ?.let { runCatching { it.toBooleanStrict() }.getOrNull() }
        when (success) {
            true -> Unit
            false -> fail("PAIRING_AUTH_REJECTED")
            null -> fail("PAIRING_AUTH_RESULT_INVALID")
        }

        onEvent(PairingProgressEvent.AwaitingConfirmation(null))
        onEvent(PairingProgressEvent.IssuingCertificate)
        if (!consumption.tryConsume(invitation.invitationId, invitation.expiresAt)) {
            fail("PAIRING_INVITATION_REPLAYED")
        }
        transport.send(
            FrameKind.Json,
            WireMessages.encode(
                "pair.csr",
                WireMessages.pairCsr(
                    invitationId,
                    csrSha256,
                    Base64.getUrlEncoder().withoutPadding().encodeToString(csrDer),
                ),
            ),
        )

        while (true) {
            val remaining = invitation.expiresAt.toEpochMilli() - System.currentTimeMillis()
            if (remaining <= 0) fail("PAIRING_CONFIRMATION_EXPIRED")
            val message = awaitMessage(transport, setOf("pair.confirm", "pair.result"), remaining)
                ?: fail("PAIRING_CONFIRMATION_EXPIRED")
            when (message.type) {
                "pair.confirm" -> when (message.body.string("status")) {
                    "waiting" -> onEvent(PairingProgressEvent.AwaitingConfirmation(message.body.string("shortCode")))
                    "confirmed" -> Unit
                    "rejected" -> fail("PAIRING_REJECTED_ON_MAC")
                    else -> fail("PAIRING_CONFIRM_INVALID")
                }

                "pair.result" -> {
                    if (message.body.string("invitationId") != invitationId) fail("PAIRING_RESULT_MISMATCH")
                    complete(message.body, learnedMacId)
                    return
                }
            }
        }
    }

    private fun complete(result: JsonObject, learnedMacId: String?) {
        val deviceId = result.string("deviceId") ?: fail("PAIRING_RESULT_INVALID")
        if (deviceId != this.deviceId) fail("PAIRING_DEVICE_ID_MISMATCH")
        val chain = (result["deviceCertificateChain"] as? JsonArray) ?: fail("PAIRING_RESULT_INVALID")
        val derChain = chain.map { element ->
            val text = (element as? JsonPrimitive)?.contentOrNull ?: fail("PAIRING_RESULT_INVALID")
            decodeCertificate(text) ?: fail("PAIRING_CERTIFICATE_ENCODING_INVALID")
        }
        if (derChain.size !in 2..4) fail("PAIRING_CERTIFICATE_CHAIN_INVALID")
        val installed = try {
            deviceKeys.installClientCertificateChain(deviceId, derChain)
        } catch (_: Exception) {
            fail("PAIRING_CERTIFICATE_REJECTED")
        }
        val factory = CertificateFactory.getInstance("X.509")
        val caCertificate = factory.generateCertificate(ByteArrayInputStream(derChain.last())) as X509Certificate
        val caMacId = runCatching { MacIdentityDeriver.deriveMacId(caCertificate) }.getOrNull()
        val macId = learnedMacId ?: caMacId ?: fail("PAIRING_MAC_IDENTITY_UNKNOWN")
        if (learnedMacId != null && caMacId != null && learnedMacId != caMacId) {
            fail("PAIRING_MAC_IDENTITY_MISMATCH")
        }
        val leaf = factory.generateCertificate(ByteArrayInputStream(derChain.first())) as X509Certificate
        val caPem = derChain.drop(1).joinToString("") { der ->
            "-----BEGIN CERTIFICATE-----\n" +
                Base64.getMimeEncoder(64, "\n".encodeToByteArray()).encodeToString(der) +
                "\n-----END CERTIFICATE-----\n"
        }
        onEvent(
            PairingProgressEvent.Completed(
                PairedProfile(
                    deviceId = deviceId,
                    macId = macId,
                    macDisplayName = "Pi Mac ${macId.take(8)}",
                    relayWssUrl = invitation.relayUrl.toString(),
                    routeId = invitation.routeId,
                    deviceRouteKeyId = "device-route-$deviceId",
                    directCandidates = invitation.directCandidates,
                    caCertificatePem = caPem,
                    certificateSerial = MacIdentityDeriver.sha256Hex(leaf.encoded),
                    certificateNotAfterEpochMillis = installed.notAfter.toEpochMilli(),
                    endpointId = null,
                ),
            ),
        )
    }

    private fun decodeCertificate(text: String): ByteArray? {
        if (text.startsWith("-----BEGIN")) {
            val base64 = text.lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("-----") }
                .joinToString("")
            return runCatching { Base64.getMimeDecoder().decode(base64) }.getOrNull()
        }
        return runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()
    }

    /** Returns null on timeout or on a host "error" envelope while awaiting [types]. */
    private suspend fun awaitMessage(
        transport: PimbStreamTransport,
        types: Set<String>,
        timeoutMillis: Long = 120_000,
    ): WireMessages.Envelope? = withTimeoutOrNull(timeoutMillis) {
        while (true) {
            val frame = transport.receive()
            if (frame.kind != FrameKind.Json) continue
            val envelope = WireMessages.parseEnvelope(frame.payload) ?: continue
            when (envelope.type) {
                in types -> return@withTimeoutOrNull envelope
                "error" -> return@withTimeoutOrNull null
            }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    private fun fail(code: String): Nothing {
        onEvent(PairingProgressEvent.Failed(code))
        throw PairingFailed(code)
    }

    /** Typed relay failure codes (PAIRING_RELAY_*) win when every raced path failed. */
    private fun connectFailureCode(error: NetworkException): String {
        val relay = relayFailure.get()
        // Codes only; never payloads or endpoints.
        android.util.Log.w("PairingRunner", "connect failed: direct=${error.code.name} relay=${relay?.code?.name ?: "none"}")
        if (relay != null) return relayFailureCode(relay)
        return "PAIRING_CONNECT_${error.code.name}"
    }

    private class PairingFailed(val code: String) : Exception(code)

    companion object {
        /** Process-wide one-shot invitation consumption guard shared by both pairing paths. */
        val sharedInvitationConsumption: InvitationConsumption = InMemoryInvitationConsumption()
    }
}
