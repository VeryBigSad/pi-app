package io.github.verybigsad.pimobile.network

import com.google.common.truth.Truth.assertThat
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RelayPairingClientTest {
    private val now = Instant.parse("2026-08-09T12:00:00Z")
    private val clock = TickClock(now)

    private class TickClock(start: Instant) : Clock() {
        private var current = start

        fun advance(millis: Long) {
            current = current.plusMillis(millis)
        }

        override fun instant(): Instant = current
        override fun withZone(zone: java.time.ZoneId): Clock = this
        override fun getZone(): java.time.ZoneId = ZoneOffset.UTC
    }

    @Test
    fun httpBaseConvertsRelayWebsocketUrls() {
        assertThat(RelayPairingExchangeClient.httpBase(URI("wss://relay.example.test")))
            .isEqualTo("https://relay.example.test")
        assertThat(RelayPairingExchangeClient.httpBase(URI("wss://relay.example.test:8443/pair")))
            .isEqualTo("https://relay.example.test:8443/pair")
        assertThat(runCatching { RelayPairingExchangeClient.httpBase(URI("http://relay.example.test")) }.isFailure).isTrue()
    }

    @Test
    fun submitRequestPutsBoundedBase64MessageWithSecretHeader() = runTest {
        val transport = FakeTransport(RelayHttpResponse(202, ByteArray(0)))
        val client = client(transport)
        val message = ByteArray(1_024) { 7 }

        client.submitRequest(message)

        val request = transport.requests.single()
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.url).isEqualTo("https://relay.example.test/v1/routes/route-1/pairing/pair-1")
        assertThat(request.headers).containsEntry(RELAY_PAIRING_SECRET_HEADER, "secret-1")
        val body = StrictJson.parseObject(request.body!!, JsonBounds(MAX_PAIRING_EXCHANGE_BODY_BYTES))
        assertThat(body.keys).containsExactly("message")
        assertThat(decodeBase64Url(body.stringValue("message", NetworkError.MALFORMED_JSON), MAX_PAIRING_EXCHANGE_MESSAGE_BYTES))
            .isEqualTo(message)
    }

    @Test
    fun submitRequestRejectsEmptyAndOversizedMessages() = runTest {
        val client = client(FakeTransport(RelayHttpResponse(202, ByteArray(0))))
        assertThat(runCatching { client.submitRequest(ByteArray(0)) }.isFailure).isTrue()
        assertThat(runCatching { client.submitRequest(ByteArray(MAX_PAIRING_EXCHANGE_MESSAGE_BYTES + 1)) }.isFailure).isTrue()
        client.submitRequest(ByteArray(MAX_PAIRING_EXCHANGE_MESSAGE_BYTES))
    }

    @Test
    fun submitRequestMapsRelayFailureStatuses() = runTest {
        suspend fun code(status: Int): NetworkError {
            val client = client(FakeTransport(RelayHttpResponse(status, ByteArray(0))))
            return (runCatching { client.submitRequest(byteArrayOf(1)) }.exceptionOrNull() as NetworkException).code
        }
        assertThat(code(409)).isEqualTo(NetworkError.RELAY_PAIRING_CONFLICT)
        assertThat(code(429)).isEqualTo(NetworkError.RELAY_PAIRING_RATE_LIMITED)
        assertThat(code(503)).isEqualTo(NetworkError.RELAY_PAIRING_UNAVAILABLE)
        assertThat(code(404)).isEqualTo(NetworkError.RELAY_PAIRING_NOT_READY)
        assertThat(code(400)).isEqualTo(NetworkError.RELAY_PAIRING_FAILED)
    }

    @Test
    fun awaitReplyPollsPastNotReadyUntilReplyArrives() = runTest {
        val reply = StrictJson.canonicalize(
            kotlinx.serialization.json.JsonObject(
                mapOf("message" to kotlinx.serialization.json.JsonPrimitive(encodeBase64Url(byteArrayOf(1, 2, 3)))),
            ),
        )
        val transport = FakeTransport(
            RelayHttpResponse(404, ByteArray(0)),
            RelayHttpResponse(404, ByteArray(0)),
            RelayHttpResponse(200, reply),
        )
        val client = client(transport)

        assertThat(client.awaitReply(60_000)).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(transport.requests).hasSize(3)
        assertThat(transport.requests.first().method).isEqualTo("GET")
        assertThat(transport.requests.first().url)
            .isEqualTo("https://relay.example.test/v1/routes/route-1/pairing/pair-1/reply")
    }

    @Test
    fun awaitReplyTimesOutWhileNotReady() = runTest {
        val transport = FakeTransport(RelayHttpResponse(404, ByteArray(0)))
        val client = client(transport, pollIntervalMillis = 100)

        val error = runCatching { client.awaitReply(1_000) }.exceptionOrNull() as NetworkException
        assertThat(error.code).isEqualTo(NetworkError.RELAY_PAIRING_NOT_READY)
    }

    private fun client(transport: FakeTransport, pollIntervalMillis: Long = 100) = RelayPairingExchangeClient(
        baseUrl = "https://relay.example.test",
        routeId = "route-1",
        pairingId = "pair-1",
        secret = "secret-1",
        transport = transport,
        clock = clock,
        pollIntervalMillis = pollIntervalMillis,
    )

    private inner class FakeTransport(vararg responses: RelayHttpResponse) : RelayHttpTransport {
        private val queue = ArrayDeque(responses.toList())
        private val fallback = responses.last()
        val requests = mutableListOf<RecordedRequest>()

        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): RelayHttpResponse {
            requests += RecordedRequest(method, url, headers, body?.copyOf())
            clock.advance(100)
            return queue.removeFirstOrNull() ?: fallback
        }
    }

    private class RecordedRequest(val method: String, val url: String, val headers: Map<String, String>, val body: ByteArray?)
}
