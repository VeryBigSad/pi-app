package io.github.verybigsad.pimobile.network

import io.github.verybigsad.pimobile.security.PairingInvitation
import java.net.URI
import java.time.Clock
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val relayPairingSecretPattern = Regex("^[A-Za-z0-9_-]{1,128}$")

/**
 * Relay rendezvous parameters carried by the signed pairing invitation (`relayPairing`):
 * the Mac opened one single-use exchange on the relay and published its id/secret out of
 * band. Mirrors the Mac producer in mac/host/src/daemon/daemon.ts `adminPairBegin`.
 *
 * Cross-module gap: core/security `PairingInvitation.parse` currently rejects invitations
 * carrying the optional `relayPairing` key (`requireExactKeys` without it), so this value
 * can only be observed once core/security admits the key. Extraction here is strict and
 * ready for that contract fix.
 */
data class RelayPairingRendezvous(
    val pairingId: String,
    val secret: String,
    val expiresAt: Instant,
) {
    companion object {
        /** Returns null when the invitation carries no relay rendezvous; throws typed when malformed. */
        fun fromInvitation(invitation: PairingInvitation, clock: Clock = Clock.systemUTC()): RelayPairingRendezvous? =
            fromSignedPayload(invitation.signedPayload(), clock)

        internal fun fromSignedPayload(signed: JsonObject, clock: Clock): RelayPairingRendezvous? {
            val value = signed["relayPairing"] ?: return null
            val record = value as? JsonObject
                ?: throw NetworkException(NetworkError.RELAY_PAIRING_FAILED, "Relay pairing rendezvous is malformed")
            if (record.keys != setOf("pairingId", "secret", "expiresAt")) {
                throw NetworkException(NetworkError.RELAY_PAIRING_FAILED, "Relay pairing rendezvous fields are invalid")
            }
            fun text(name: String): String = (record[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw NetworkException(NetworkError.RELAY_PAIRING_FAILED, "Relay pairing rendezvous fields are invalid")
            val pairingId = text("pairingId")
            val secret = text("secret")
            if (!opaqueIdPattern.matches(pairingId) || !relayPairingSecretPattern.matches(secret)) {
                throw NetworkException(NetworkError.RELAY_PAIRING_FAILED, "Relay pairing rendezvous identities are invalid")
            }
            val expiresAt = parseInstant(text("expiresAt"), NetworkError.RELAY_PAIRING_FAILED)
            if (!expiresAt.isAfter(clock.instant())) {
                throw NetworkException(NetworkError.RELAY_PAIRING_NOT_READY, "Relay pairing rendezvous is expired")
            }
            return RelayPairingRendezvous(pairingId, secret, expiresAt)
        }
    }
}

/** Established relay provisional-pairing channel: inner pinned TLS over the spliced device-data tunnel. */
class RelayPairingConnection(
    val channel: TlsByteChannel,
    val macId: String,
)

/** Opens the device-data WebSocket tunnel to the relay with a self-issued route proof. */
fun interface RelayDataTunnelOpener {
    suspend fun open(tunnelUri: URI, proofHeader: String): DuplexByteChannel
}

/** Production tunnel opener: WSS to `GET /v1/routes/{route}/data` with `X-Relay-Proof`. */
class WebSocketRelayDataTunnelOpener : RelayDataTunnelOpener {
    override suspend fun open(tunnelUri: URI, proofHeader: String): DuplexByteChannel =
        WebSocketBinaryByteChannel.connect(tunnelUri, mapOf("X-Relay-Proof" to proofHeader))
}

/**
 * Remote provisional pairing over the relay rendezvous (relay/internal/pairing):
 * the device PUTs one bounded JSON message (`invitationId`, `deviceRouteKeyId`,
 * `deviceRoutePublicKey`), polls for the Mac's one reply (`{"accepted":true,...}`,
 * meaning the Mac registered the device route key), then attaches a device-data
 * WebSocket with a self-issued `device-data` proof. The relay splices that tunnel to
 * the Mac (`pairing_provisional` mode), the Mac terminates the inner provisional TLS
 * 1.3 session, and the pairing ceremony (pair.begin/passkey/pair.csr/pair.result) runs
 * over the resulting channel exactly as on the direct path.
 */
class RelayPairingPath(
    private val clock: Clock = Clock.systemUTC(),
    private val pollIntervalMillis: Long = 1_000,
    private val transportFactory: () -> RelayHttpTransport = { HttpUrlConnectionRelayTransport() },
    private val tunnelOpener: RelayDataTunnelOpener = WebSocketRelayDataTunnelOpener(),
    private val exchangeBaseUrl: String? = null,
) {
    /**
     * Runs the rendezvous exchange and establishes the pinned provisional TLS channel.
     * [deadline] caps the whole path (the race coordinator applies its own bound too).
     */
    suspend fun connect(
        invitation: PairingInvitation,
        rendezvous: RelayPairingRendezvous,
        routeKeyId: String,
        routePublicKey: String,
        signer: RelayProofSigner,
        deadline: Instant,
    ): RelayPairingConnection {
        if (!opaqueIdPattern.matches(routeKeyId)) {
            throw NetworkException(NetworkError.RELAY_PAIRING_FAILED, "Device route key identity is invalid")
        }
        val requestMessage = StrictJson.canonicalize(
            JsonObject(
                mapOf(
                    "invitationId" to JsonPrimitive(invitation.invitationId.toString()),
                    "deviceRouteKeyId" to JsonPrimitive(routeKeyId),
                    "deviceRoutePublicKey" to JsonPrimitive(routePublicKey),
                ),
            ),
        )
        val exchange = RelayPairingExchangeClient(
            baseUrl = exchangeBaseUrl ?: RelayPairingExchangeClient.httpBase(invitation.relayUrl),
            routeId = invitation.routeId,
            pairingId = rendezvous.pairingId,
            secret = rendezvous.secret,
            transport = transportFactory(),
            clock = clock,
            pollIntervalMillis = pollIntervalMillis,
        )
        exchange.submitRequest(requestMessage)
        val reply = exchange.awaitReply(replyTimeoutMillis(invitation, rendezvous, deadline))
        validateReply(reply, invitation.invitationId.toString())

        val proof = RelayProofCodec(clock).encodeSelfIssuedProof(
            RelayAudience.DEVICE_DATA,
            invitation.routeId,
            routeKeyId,
            signer,
        )
        val tunnelUri = dataTunnelUri(invitation)
        val tunnel = tunnelOpener.open(tunnelUri, proof.toString(Charsets.UTF_8))
        val channel = try {
            TlsByteChannel.connect(
                tunnel,
                invitation.provisionalTlsContext(clock),
                requireNotNull(tunnelUri.host),
                if (tunnelUri.port == -1) 443 else tunnelUri.port,
            )
        } catch (error: Exception) {
            runCatching { tunnel.close() }
            throw error
        }
        return RelayPairingConnection(channel, invitation.macInstanceId.toString())
    }

    private fun replyTimeoutMillis(
        invitation: PairingInvitation,
        rendezvous: RelayPairingRendezvous,
        deadline: Instant,
    ): Long {
        val now = clock.instant()
        val remaining = minOf(
            java.time.Duration.between(now, invitation.expiresAt).toMillis(),
            java.time.Duration.between(now, rendezvous.expiresAt).toMillis(),
            java.time.Duration.between(now, deadline).toMillis(),
        )
        if (remaining <= 0) {
            throw NetworkException(NetworkError.RELAY_PAIRING_NOT_READY, "Relay pairing rendezvous is expired")
        }
        return remaining.coerceIn(1, 10 * 60_000)
    }

    private fun validateReply(reply: ByteArray, invitationId: String) {
        val value = try {
            StrictJson.parseObject(reply, JsonBounds(MAX_PAIRING_EXCHANGE_MESSAGE_BYTES, maxStringChars = MAX_PAIRING_EXCHANGE_MESSAGE_BYTES))
        } catch (error: NetworkException) {
            throw NetworkException(NetworkError.RELAY_PAIRING_FAILED, "Relay pairing reply is malformed", error)
        }
        val accepted = value["accepted"]
        if (accepted != JsonPrimitive(true) || value["invitationId"] != JsonPrimitive(invitationId)) {
            throw NetworkException(NetworkError.RELAY_PAIRING_FAILED, "Relay pairing reply was not accepted")
        }
    }

    private fun dataTunnelUri(invitation: PairingInvitation): URI {
        val relay = invitation.relayUrl
        val basePath = (relay.rawPath ?: "").trimEnd('/')
        return URI("wss", null, relay.host, relay.port, "$basePath/v1/routes/${invitation.routeId}/data", null, null)
    }
}
