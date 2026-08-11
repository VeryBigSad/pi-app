package io.github.verybigsad.pimobile.security

import io.github.verybigsad.pimobile.protocol.canonicalizeJson
import java.net.URI
import java.security.PublicKey
import java.security.Signature
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

private const val InvitationMaxDecodedBytes = 2 * 1024
private const val InvitationMaxUriBytes = 4 * 1024
private const val InvitationValiditySeconds = 5L * 60
internal val opaqueIdPattern = Regex("^[A-Za-z0-9._-]{1,128}$")
private val hostPattern = Regex("^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(?:\\.(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?))*\\.?$")
private val ipv4Pattern = Regex("^[0-9]+(?:\\.[0-9]+){3}$")
private val ipv6Pattern = Regex("^[0-9A-Fa-f:.]+$")
private val uuidV4Pattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val sha256HexPattern = Regex("^[0-9a-f]{64}$")

/**
 * Canonical signed pairing invitation (`pimobile://pair?v=1&d=<base64url JCS envelope>`).
 * Field names and semantics mirror the Mac producer: `relayUrl`, `routeId`, `routeKeyId`,
 * `invitationId`, `macInstanceId` (uuid v4; SAN identity `urn:pimobile:mac:<macInstanceId>`
 * of the provisional server certificate), `expiresAt`, `nonce`, `serverCertificateSha256`
 * (lowercase hex of the provisional server certificate DER digest), and `directCandidates`.
 */
class PairingInvitation private constructor(
    val version: Int,
    val relayUrl: URI,
    val routeId: String,
    val routeKeyId: String,
    val invitationId: UUID,
    val macInstanceId: UUID,
    val expiresAt: Instant,
    val directCandidates: List<DirectCandidate>,
    val serverCertificateFingerprint: CertificateFingerprint,
    private val nonceValue: ByteArray,
    private val signedValue: JsonObject,
    private val canonicalValue: ByteArray,
    private val signatureValue: ByteArray,
) {
    val serverCertificateSha256: String get() = serverCertificateFingerprint.hex()

    fun nonce(): ByteArray = nonceValue.copyOf()

    fun canonicalSignedPayload(): ByteArray = canonicalValue.copyOf()

    fun signatureDer(): ByteArray = signatureValue.copyOf()

    fun signedPayload(): JsonObject = JsonObject(signedValue.toMap())

    fun verifyRouteSignature(publicKey: PublicKey): Boolean {
        requireP256(publicKey)
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(canonicalValue)
            verify(signatureValue)
        }
    }

    companion object {
        fun parse(uriText: String, now: Instant = Instant.now()): PairingInvitation {
            require(uriText.length <= InvitationMaxUriBytes)
            require(uriText.encodeToByteArray().size <= InvitationMaxUriBytes)
            val uri = runCatching { URI(uriText) }.getOrElse { throw IllegalArgumentException("invalid pairing URI") }
            require(uri.scheme == "pimobile" && uri.host == "pair")
            require(uri.userInfo == null && uri.port == -1 && (uri.rawPath.isNullOrEmpty()))
            require(uri.rawFragment == null)
            val parameters = parseQuery(requireNotNull(uri.rawQuery))
            require(parameters.keys == setOf("v", "d") && parameters.getValue("v") == "1")
            val envelopeBytes = Base64Url.decode(parameters.getValue("d"), InvitationMaxDecodedBytes)
            val envelopeText = envelopeBytes.toString(Charsets.UTF_8)
            require(envelopeText.encodeToByteArray().contentEquals(envelopeBytes))
            val envelope = StrictJson.objectValue(
                envelopeText,
                InvitationMaxDecodedBytes,
                maxDepth = 6,
                maxObjectMembers = 16,
                maxArrayItems = 16,
            )
            require(canonicalizeJson(envelope) == envelopeText)
            envelope.requireExactKeys(setOf("signed", "signature"))
            val signed = envelope.requireObject("signed")
            signed.requireExactKeys(
                optional = setOf("relayPairing"),
                required =
                setOf(
                    "version",
                    "relayUrl",
                    "routeId",
                    "routeKeyId",
                    "invitationId",
                    "macInstanceId",
                    "expiresAt",
                    "nonce",
                    "serverCertificateSha256",
                    "directCandidates",
                ),
            )
            val version = signed.requireInt("version")
            require(version == 1)
            val relayUrl = parseRelayUrl(signed.requireString("relayUrl", 512))
            val routeId = signed.requireString("routeId", 128).also { require(opaqueIdPattern.matches(it)) }
            val keyId = signed.requireString("routeKeyId", 128).also { require(opaqueIdPattern.matches(it)) }
            val invitationText = signed.requireString("invitationId", 36).also { require(uuidV4Pattern.matches(it)) }
            val invitationId = UUID.fromString(invitationText)
            val macInstanceText = signed.requireString("macInstanceId", 36).also { require(uuidV4Pattern.matches(it)) }
            val macInstanceId = UUID.fromString(macInstanceText)
            val expiresAt = parseExpiry(signed.requireString("expiresAt", 35), now)
            val nonce = Base64Url.decode(signed.requireString("nonce", 43), maxBytes = 32, exactBytes = 32)
            val fingerprintHex = signed.requireString("serverCertificateSha256", 64)
            require(sha256HexPattern.matches(fingerprintHex))
            val fingerprint = CertificateFingerprint.fromHex(fingerprintHex)
            val candidates = parseCandidates(signed.requireArray("directCandidates"))
            val signature = EcdsaDer.requireP256Signature(
                Base64Url.decode(envelope.requireString("signature", 96), maxBytes = 72),
            )
            val canonical = canonicalizeJson(signed).encodeToByteArray()
            require(canonical.size <= InvitationMaxDecodedBytes)
            return PairingInvitation(
                version,
                relayUrl,
                routeId,
                keyId,
                invitationId,
                macInstanceId,
                expiresAt,
                candidates,
                fingerprint,
                nonce,
                signed,
                canonical,
                signature,
            )
        }

        private fun parseQuery(query: String): Map<String, String> {
            require(query.isNotEmpty() && '%' !in query && '+' !in query)
            val result = linkedMapOf<String, String>()
            query.split('&').forEach { part ->
                val separator = part.indexOf('=')
                require(separator > 0 && separator == part.lastIndexOf('='))
                val key = part.substring(0, separator)
                val value = part.substring(separator + 1)
                require(value.isNotEmpty() && result.put(key, value) == null)
            }
            return result
        }

        private fun parseRelayUrl(text: String): URI {
            val uri = runCatching { URI(text) }.getOrElse { throw IllegalArgumentException("invalid relay URL") }
            require(uri.scheme == "wss" && uri.host != null)
            require(uri.userInfo == null && uri.rawFragment == null && uri.rawQuery == null)
            require(uri.port == -1 || uri.port in 1..65535)
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath.length <= 512)
            return uri
        }

        private fun parseExpiry(text: String, now: Instant): Instant {
            val expiry = runCatching { Instant.parse(text) }.getOrElse { throw IllegalArgumentException("invalid invitation expiry") }
            val remaining = Duration.between(now, expiry)
            require(!remaining.isNegative && !remaining.isZero)
            require(remaining <= Duration.ofSeconds(InvitationValiditySeconds))
            return expiry
        }

        private fun parseCandidates(values: JsonArray): List<DirectCandidate> {
            require(values.size <= 16)
            return values.map { value ->
                val candidate = value as? JsonObject ?: throw IllegalArgumentException("direct candidate must be an object")
                candidate.requireExactKeys(setOf("host", "port"))
                val host = candidate.requireString("host", 253)
                require(validHost(host))
                val port = candidate.requireInt("port")
                require(port in 1..65535)
                DirectCandidate(host, port)
            }.also { candidates ->
                require(candidates.map { "${it.host.lowercase()}:${it.port}" }.distinct().size == candidates.size)
            }
        }

        private fun validHost(host: String): Boolean {
            if (':' in host) {
                if (!ipv6Pattern.matches(host)) return false
                return runCatching { URI("http://[$host]/").host != null }.getOrDefault(false)
            }
            if (!hostPattern.matches(host)) return false
            if (!ipv4Pattern.matches(host)) return true
            return host.split('.').all { part ->
                part.toIntOrNull()?.let { it in 0..255 && (part == "0" || !part.startsWith('0')) } == true
            }
        }
    }
}

data class DirectCandidate(val host: String, val port: Int)
