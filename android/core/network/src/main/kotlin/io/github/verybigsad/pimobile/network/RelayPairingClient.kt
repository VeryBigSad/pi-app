package io.github.verybigsad.pimobile.network

import java.net.HttpURLConnection
import java.net.URI
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val MAX_PAIRING_EXCHANGE_MESSAGE_BYTES = 16 * 1_024
const val MAX_PAIRING_EXCHANGE_BODY_BYTES = 24 * 1_024
const val RELAY_PAIRING_SECRET_HEADER = "X-Relay-Pairing-Secret"
const val RELAY_PAIRING_ACCEPTED = 202

/**
 * Client half of the relay provisional-pairing rendezvous exchange
 * (relay/internal/pairing): an unregistered device deposits exactly one bounded
 * message and collects exactly one reply. The exchange id and one-use secret arrive
 * out of band (QR); message content stays opaque to the relay.
 */
interface RelayHttpTransport {
    suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): RelayHttpResponse
}

data class RelayHttpResponse(
    val status: Int,
    val body: ByteArray,
)

/** HttpURLConnection transport; TLS comes from the `https://` relay base URL. */
class HttpUrlConnectionRelayTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 10_000,
) : RelayHttpTransport {
    override suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): RelayHttpResponse = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.requestMethod = method
            connection.useCaches = false
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use { input ->
                val buffer = ByteArray(MAX_PAIRING_EXCHANGE_BODY_BYTES + 1)
                var total = 0
                while (total < buffer.size) {
                    val count = input.read(buffer, total, buffer.size - total)
                    if (count == -1) break
                    total += count
                }
                if (total > MAX_PAIRING_EXCHANGE_BODY_BYTES) {
                    throw NetworkException(NetworkError.RELAY_PAIRING_FAILED, "Relay pairing response exceeds its bound")
                }
                buffer.copyOf(total)
            } ?: ByteArray(0)
            RelayHttpResponse(status, responseBody)
        } catch (error: NetworkException) {
            throw error
        } catch (error: Exception) {
            throw NetworkException(NetworkError.TRANSPORT_CLOSED, "Relay pairing request failed", error)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Drives one relay pairing exchange: `PUT /v1/routes/{route}/pairing/{pairing}` deposits
 * the request (202), `GET .../reply` is polled (404 while not ready) until the one reply
 * arrives (200) and the relay destroys the exchange. Mirrors relay/internal/pairing
 * semantics: one bounded message, one bounded reply, secret in a header, single use.
 */
class RelayPairingExchangeClient(
    private val baseUrl: String,
    private val routeId: String,
    private val pairingId: String,
    private val secret: String,
    private val transport: RelayHttpTransport = HttpUrlConnectionRelayTransport(),
    private val clock: Clock = Clock.systemUTC(),
    private val pollIntervalMillis: Long = 1_000,
) {
    init {
        require(opaqueIdPattern.matches(routeId) && opaqueIdPattern.matches(pairingId)) {
            "relay pairing identities are invalid"
        }
        require(secret.isNotEmpty() && secret.length <= 128) { "relay pairing secret is invalid" }
        require(pollIntervalMillis in 100..10_000) { "relay pairing poll interval is outside its bound" }
    }

    /** Deposits the one request message; fails typed on conflict/rate-limit/capacity. */
    suspend fun submitRequest(message: ByteArray) {
        if (message.isEmpty() || message.size > MAX_PAIRING_EXCHANGE_MESSAGE_BYTES) {
            throw NetworkException(NetworkError.RELAY_PAIRING_FAILED, "Relay pairing message is outside its bound")
        }
        val body = StrictJson.canonicalize(
            JsonObject(mapOf("message" to JsonPrimitive(encodeBase64Url(message)))),
        )
        val response = transport.request(
            "PUT",
            "$baseUrl/v1/routes/$routeId/pairing/$pairingId",
            mapOf(RELAY_PAIRING_SECRET_HEADER to secret, "Content-Type" to "application/json"),
            body,
        )
        if (response.status != RELAY_PAIRING_ACCEPTED) throw mapFailure(response.status)
    }

    /**
     * Polls for the one reply until [timeoutMillis] elapses. The relay answers 404 while
     * the reply is not ready (and also for expired/destroyed exchanges, which surface as
     * [NetworkError.RELAY_PAIRING_NOT_READY] at the deadline).
     */
    suspend fun awaitReply(timeoutMillis: Long): ByteArray {
        require(timeoutMillis in 1..10 * 60_000) { "relay pairing reply timeout is outside its bound" }
        val deadlineMillis = clock.millis() + timeoutMillis
        while (true) {
            val response = transport.request(
                "GET",
                "$baseUrl/v1/routes/$routeId/pairing/$pairingId/reply",
                mapOf(RELAY_PAIRING_SECRET_HEADER to secret),
                null,
            )
            when (response.status) {
                200 -> return parseReply(response.body)
                404 -> {
                    if (clock.millis() + pollIntervalMillis > deadlineMillis) {
                        throw NetworkException(NetworkError.RELAY_PAIRING_NOT_READY, "Relay pairing reply was not delivered")
                    }
                    delay(pollIntervalMillis)
                }

                else -> throw mapFailure(response.status)
            }
        }
    }

    private fun parseReply(body: ByteArray): ByteArray {
        val value = try {
            StrictJson.parseObject(
                body,
                JsonBounds(MAX_PAIRING_EXCHANGE_BODY_BYTES, maxStringChars = MAX_PAIRING_EXCHANGE_BODY_BYTES),
            )
        } catch (error: NetworkException) {
            throw NetworkException(NetworkError.RELAY_PAIRING_FAILED, "Relay pairing reply is malformed", error)
        }
        val message = value.stringValue("message", NetworkError.RELAY_PAIRING_FAILED)
        return decodeBase64Url(message, MAX_PAIRING_EXCHANGE_MESSAGE_BYTES)
    }

    private fun mapFailure(status: Int): NetworkException = when (status) {
        409 -> NetworkException(NetworkError.RELAY_PAIRING_CONFLICT, "Relay pairing exchange is busy or already used")
        429 -> NetworkException(NetworkError.RELAY_PAIRING_RATE_LIMITED, "Relay pairing creation is rate limited")
        503 -> NetworkException(NetworkError.RELAY_PAIRING_UNAVAILABLE, "Relay pairing capacity is reached")
        404 -> NetworkException(NetworkError.RELAY_PAIRING_NOT_READY, "Relay pairing exchange is unknown or expired")
        else -> NetworkException(NetworkError.RELAY_PAIRING_FAILED, "Relay pairing request failed with status $status")
    }

    companion object {
        /** Converts the invitation's `wss://` relay URL into the `https://` API base. */
        fun httpBase(relayUrl: URI): String {
            val scheme = when (relayUrl.scheme) {
                "wss" -> "https"
                "ws" -> "http"
                else -> throw NetworkException(NetworkError.MALFORMED_URI, "Relay URL scheme is unsupported")
            }
            val host = relayUrl.host
                ?: throw NetworkException(NetworkError.MALFORMED_URI, "Relay URL host is missing")
            val port = if (relayUrl.port == -1) "" else ":${relayUrl.port}"
            val path = (relayUrl.rawPath ?: "").trimEnd('/')
            if (path.length > 512 || relayUrl.rawQuery != null || relayUrl.rawFragment != null) {
                throw NetworkException(NetworkError.MALFORMED_URI, "Relay URL is invalid")
            }
            return "$scheme://$host$port$path"
        }
    }
}
