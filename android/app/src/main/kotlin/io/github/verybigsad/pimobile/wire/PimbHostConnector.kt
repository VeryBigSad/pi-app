package io.github.verybigsad.pimobile.wire

import io.github.verybigsad.pimobile.model.EventCursor
import io.github.verybigsad.pimobile.model.LeafId
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.StreamEpoch
import io.github.verybigsad.pimobile.model.TransportPath
import io.github.verybigsad.pimobile.model.Uint64Decimal
import io.github.verybigsad.pimobile.network.CertificateIdentity
import io.github.verybigsad.pimobile.network.CertificateRole
import io.github.verybigsad.pimobile.network.DuplexByteChannel
import io.github.verybigsad.pimobile.network.NetworkError
import io.github.verybigsad.pimobile.network.NetworkException
import io.github.verybigsad.pimobile.network.PathAttempt
import io.github.verybigsad.pimobile.network.PathConnection
import io.github.verybigsad.pimobile.network.PathRaceCoordinator
import io.github.verybigsad.pimobile.network.PimbStreamTransport
import io.github.verybigsad.pimobile.network.RelayAudience
import io.github.verybigsad.pimobile.network.RelayProofCodec
import io.github.verybigsad.pimobile.network.StreamByteChannel
import io.github.verybigsad.pimobile.network.WebSocketBinaryByteChannel
import io.github.verybigsad.pimobile.network.asRelayProofSigner
import io.github.verybigsad.pimobile.network.TlsContexts
import io.github.verybigsad.pimobile.network.TransportPathKind
import io.github.verybigsad.pimobile.protocol.FrameKind
import io.github.verybigsad.pimobile.protocol.PimbCodec
import io.github.verybigsad.pimobile.protocol.StreamPayload
import io.github.verybigsad.pimobile.protocol.TerminalPayload
import io.github.verybigsad.pimobile.security.DeviceKeys
import io.github.verybigsad.pimobile.security.PairedProfile
import io.github.verybigsad.pimobile.storage.AuthoritativeFinalMetadata
import io.github.verybigsad.pimobile.storage.CanonicalAppendCursor
import io.github.verybigsad.pimobile.storage.FinalMetadataSource
import io.github.verybigsad.pimobile.storage.FinalizedMessageState
import io.github.verybigsad.pimobile.storage.MessageEntity
import io.github.verybigsad.pimobile.storage.SessionEntity
import io.github.verybigsad.pimobile.storage.StoredMessageRole
import io.github.verybigsad.pimobile.voice.MacVoiceError
import io.github.verybigsad.pimobile.wire.WireMessages.string
import java.io.ByteArrayInputStream
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.KeyManager
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Direct-vs-relay racing host connector over PIMB/TLS 1.3 mTLS.
 *
 * The relay attempt mirrors the proven pairing path (`RelayPairingPath`): it opens a WSS
 * device-data tunnel to `wss://relay-host/v1/routes/{route}/data` with a self-issued
 * `device-data` proof (`X-Relay-Proof`) signed by the Keystore route key via
 * `DeviceKeys.routePayloadSigner`, the relay notifies the Mac which attaches `mac-data` and
 * splices, then the device runs inner mTLS 1.3 (paired CA trust, macId SAN) over the tunnel.
 *
 * Adapter gaps:
 * - Snapshot entries carry no append metadata on the wire; appendOrder falls back to a
 *   positional ordinal, so a later live event can invalidate canonical state and force a
 *   fresh snapshot (honest degradation, never fake content).
 */
class PimbHostConnector(
    private val profile: PairedProfile,
    private val deviceKeys: DeviceKeys,
    private val appVersion: String,
    private val scope: CoroutineScope,
    private val onEvent: (HostConnectionEvent) -> Unit,
) : HostConnectionRunner {
    override suspend fun run(): HostConnector {
        onEvent(HostConnectionEvent.Connecting(TransportPath.DIRECT, 1))
        val trustManagers = pairedTrustManagers(profile)
        val identity = CertificateIdentity(CertificateRole.MAC_SERVER, profile.macId)
        val keyManagers = arrayOf<KeyManager>(deviceKeys.tlsKeyManager(profile.deviceId))

        val race = PathRaceCoordinator()
        val attempts = buildList<PathAttempt> {
            profile.directCandidates.forEach { candidate ->
                add(
                    PathAttempt { generation ->
                        val tcp = StreamByteChannel.connect(candidate.host, candidate.port)
                        val context = TlsContexts.mutual(keyManagers, trustManagers, identity)
                        val tls = io.github.verybigsad.pimobile.network.TlsByteChannel.connect(
                            tcp,
                            context,
                            candidate.host,
                            candidate.port,
                        )
                        object : PathConnection {
                            override val kind: TransportPathKind = TransportPathKind.DIRECT
                            override val generation: Long = generation
                            override val channel: DuplexByteChannel = tls
                            override suspend fun authenticate() = Unit
                            override suspend fun close() = tls.close()
                        }
                    },
                )
            }
            add(
                PathAttempt { generation ->
                    connectRelay(generation, keyManagers, trustManagers, identity)
                },
            )
        }
        val connection = race.connect(attempts)
        val path = when (connection.kind) {
            TransportPathKind.DIRECT -> TransportPath.DIRECT
            TransportPathKind.RELAY -> TransportPath.RELAY
        }
        val transport = PimbStreamTransport(connection.channel)
        val connector = Connector(transport, path)
        transport.send(
            FrameKind.Json,
            WireMessages.encode(
                "client.hello",
                WireMessages.clientHello(profile.deviceId, appVersion, listOf("terminal", "voice", "push", "agents")),
            ),
        )
        onEvent(
            HostConnectionEvent.DeviceAuthenticated(
                path,
                certificateSerialFromPeer(connection) ?: profile.certificateSerial,
            ),
        )
        scope.launch(Dispatchers.IO) { readerLoop(transport) }
        return connector
    }

    private suspend fun connectRelay(
        generation: Long,
        keyManagers: Array<KeyManager>,
        trustManagers: Array<TrustManager>,
        identity: CertificateIdentity,
    ): PathConnection {
        val relay = try {
            URI(profile.relayWssUrl)
        } catch (error: Exception) {
            throw NetworkException(NetworkError.MALFORMED_URI, "Paired relay URL is malformed", error)
        }
        if (relay.scheme != "wss" || relay.host == null) {
            throw NetworkException(NetworkError.MALFORMED_URI, "Paired relay URL is not a wss:// endpoint")
        }
        val proof = RelayProofCodec().encodeSelfIssuedProof(
            RelayAudience.DEVICE_DATA,
            profile.routeId,
            profile.deviceRouteKeyId,
            deviceKeys.routePayloadSigner().asRelayProofSigner(),
        )
        val basePath = (relay.rawPath ?: "").trimEnd('/')
        val tunnelUri = URI("wss", null, relay.host, relay.port, "$basePath/v1/routes/${profile.routeId}/data", null, null)
        val tunnel = try {
            WebSocketBinaryByteChannel.connect(tunnelUri, mapOf("X-Relay-Proof" to proof.toString(Charsets.UTF_8)))
        } catch (error: NetworkException) {
            throw error
        } catch (error: Exception) {
            throw NetworkException(NetworkError.RELAY_PAIRING_UNAVAILABLE, "Relay data tunnel is unreachable", error)
        }
        val context = TlsContexts.mutual(keyManagers, trustManagers, identity)
        val tls = io.github.verybigsad.pimobile.network.TlsByteChannel.connect(
            tunnel,
            context,
            requireNotNull(tunnelUri.host),
            if (tunnelUri.port == -1) 443 else tunnelUri.port,
        )
        return object : PathConnection {
            override val kind: TransportPathKind = TransportPathKind.RELAY
            override val generation: Long = generation
            override val channel: DuplexByteChannel = tls
            override suspend fun authenticate() = Unit
            override suspend fun close() = tls.close()
        }
    }

    private fun certificateSerialFromPeer(connection: PathConnection): String? = null

    private suspend fun readerLoop(transport: PimbStreamTransport) {
        val router = HostInboundRouter(onEvent)
        try {
            while (true) {
                val frame = transport.receive()
                when (frame.kind) {
                    FrameKind.TerminalBytes -> {
                        val payload = runCatching { PimbCodec.decodeTerminalPayload(frame.payload) }.getOrNull() ?: continue
                        onEvent(HostConnectionEvent.TerminalOutput(payload.terminalGeneration, payload.sequence, payload.data))
                    }

                    FrameKind.Json -> {
                        val envelope = WireMessages.parseEnvelope(frame.payload) ?: continue
                        router.handle(envelope)
                    }

                    else -> Unit
                }
            }
        } catch (_: Exception) {
            onEvent(HostConnectionEvent.Disconnected("TRANSPORT_CLOSED"))
        }
    }

    private inner class Connector(
        private val transport: PimbStreamTransport,
        override val path: TransportPath,
    ) : HostConnector {
        override suspend fun send(type: String, body: JsonObject, replyTo: String?) {
            transport.send(FrameKind.Json, WireMessages.encode(type, body, replyTo))
        }

        override suspend fun sendTerminalInput(terminalGeneration: ULong, sequence: ULong, bytes: ByteArray) {
            var offset = 0
            var chunkSequence = sequence
            while (offset < bytes.size) {
                val end = minOf(bytes.size, offset + io.github.verybigsad.pimobile.protocol.ProtocolConstants.maxBinaryDataBytes)
                transport.send(
                    FrameKind.TerminalBytes,
                    PimbCodec.encodeTerminalPayload(TerminalPayload(terminalGeneration, chunkSequence, bytes.copyOfRange(offset, end))),
                )
                chunkSequence += 1u
                offset = end
            }
        }

        override suspend fun sendVoicePcm(streamId: String, sequence: Long, offset: ULong, bytes: ByteArray) {
            transport.send(
                FrameKind.AudioPcm,
                PimbCodec.encodeStreamPayload(StreamPayload(streamId, sequence, offset, bytes)),
            )
        }

        override suspend fun close() = transport.close()
    }

    companion object {
        fun pairedTrustManagers(profile: PairedProfile): Array<TrustManager> {
            val factory = CertificateFactory.getInstance("X.509")
            val certificates = PEM_CERTIFICATE.findAll(profile.caCertificatePem).map { match ->
                val der = java.util.Base64.getMimeDecoder().decode(match.groupValues[1].filterNot(Char::isWhitespace))
                factory.generateCertificate(ByteArrayInputStream(der)) as java.security.cert.X509Certificate
            }.toList()
            require(certificates.isNotEmpty()) { "paired profile has no CA certificates" }
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
            certificates.forEachIndexed { index, certificate ->
                store.setCertificateEntry("paired-ca-$index", certificate)
            }
            val trustFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trustFactory.init(store)
            return trustFactory.trustManagers
        }

        private val PEM_CERTIFICATE = Regex("-----BEGIN CERTIFICATE-----([^-]+)-----END CERTIFICATE-----")
    }
}


