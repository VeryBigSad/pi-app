package io.github.verybigsad.pimobile.wire

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class HostInboundRouterTest {
    private fun envelope(type: String, body: kotlinx.serialization.json.JsonObject = buildJsonObject { }): WireMessages.Envelope =
        WireMessages.parseEnvelope(WireMessages.encode(type, body))!!

    @Test
    fun `sync complete maps to SyncComplete event`() {
        val events = ArrayList<HostConnectionEvent>()
        val router = HostInboundRouter(events::add)
        router.handle(envelope("sync.complete"))
        assertThat(events).containsExactly(HostConnectionEvent.SyncComplete)
    }

    @Test
    fun `six-session replay only acknowledges each final fence and never synthesizes completion`() {
        val events = ArrayList<HostConnectionEvent>()
        val router = HostInboundRouter(events::add)
        repeat(6) { index ->
            val sessionId = "550e8400-e29b-41d4-a716-44665544004$index"
            val streamEpoch = "650e8400-e29b-41d4-a716-44665544004$index"
            router.handle(
                WireMessages.parseEnvelope(WireMessages.encode("sync.replay", buildJsonObject {
                    put("sessionId", sessionId)
                    put("streamEpoch", streamEpoch)
                    put("fromSequence", "0")
                    put("throughSequence", "2")
                }))!!,
            )
            router.handle(
                WireMessages.parseEnvelope(WireMessages.encode("event.batch", buildJsonObject {
                    put("events", buildJsonArray {
                        add(buildJsonObject {
                            put("sessionId", sessionId)
                            put("streamEpoch", streamEpoch)
                            put("sequence", "1")
                            put("piType", "agent_end")
                            put("rawJson", "{\"type\":\"agent_end\"}")
                            put("rawSize", "20")
                            put("rawSha256", io.github.verybigsad.pimobile.protocol.sha256Hex("{\"type\":\"agent_end\"}".encodeToByteArray()))
                            put("projection", buildJsonObject { put("type", "agent_end") })
                        })
                        add(buildJsonObject {
                            put("sessionId", sessionId)
                            put("streamEpoch", streamEpoch)
                            put("sequence", "2")
                            put("piType", "agent_end")
                            put("rawJson", "{\"type\":\"agent_end\"}")
                            put("rawSize", "20")
                            put("rawSha256", io.github.verybigsad.pimobile.protocol.sha256Hex("{\"type\":\"agent_end\"}".encodeToByteArray()))
                            put("projection", buildJsonObject { put("type", "agent_end") })
                        })
                    })
                }))!!,
            )
        }

        val canonical = events.filterIsInstance<HostConnectionEvent.CanonicalEvent>()
        assertThat(canonical).hasSize(12)
        assertThat(canonical.filter { it.acknowledgeSyncFence }.map { it.sessionId.value })
            .containsExactlyElementsIn((0 until 6).map { "550e8400-e29b-41d4-a716-44665544004$it" })
        assertThat(events.filterIsInstance<HostConnectionEvent.SyncComplete>()).isEmpty()
        router.handle(envelope("sync.complete"))
        assertThat(events.last()).isEqualTo(HostConnectionEvent.SyncComplete)
    }

    @Test
    fun `snapshot framing is validated and preserves last append id`() {
        val events = ArrayList<HostConnectionEvent>()
        val router = HostInboundRouter(events::add)
        val sessionId = "550e8400-e29b-41d4-a716-446655440002"
        val epoch = "650e8400-e29b-41d4-a716-446655440002"
        router.handle(envelope("snapshot.begin", buildJsonObject {
            put("sessionId", sessionId)
            put("streamEpoch", epoch)
            put("sequence", "7")
            put("messageCount", "1")
            put("lastAppendId", "1")
        }))
        router.handle(envelope("snapshot.page", buildJsonObject {
            put("sessionId", sessionId)
            put("streamEpoch", epoch)
            put("sequence", "7")
            put("page", 0)
            put("entries", buildJsonArray {
                add(buildJsonObject {
                    put("id", "m-1")
                    put("messageId", "m-1")
                    put("appendId", "1")
                    put("role", "user")
                    put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "hello") }) })
                    put("rawJson", "{}")
                    put("rawSize", "2")
                })
            })
        }))
        router.handle(envelope("snapshot.end", buildJsonObject {
            put("sessionId", sessionId)
            put("streamEpoch", epoch)
            put("sequence", "7")
            put("pages", 1)
            put("messageCount", "1")
            put("lastAppendId", "1")
            put("leafId", JsonNull)
            put("validated", true)
        }))

        val ready = events.filterIsInstance<HostConnectionEvent.SnapshotReady>().single()
        assertThat(ready.lastAppendId).isEqualTo("1")
        assertThat(ready.session.canonicalCursor?.lastAppendId).isEqualTo("1")
        assertThat(ready.messages.map { it.messageId }).containsExactly("m-1")
    }

    @Test
    fun `snapshot page identity mismatch rejects without a ready snapshot`() {
        val events = ArrayList<HostConnectionEvent>()
        val router = HostInboundRouter(events::add)
        val sessionId = "550e8400-e29b-41d4-a716-446655440002"
        val epoch = "650e8400-e29b-41d4-a716-446655440002"
        router.handle(envelope("snapshot.begin", buildJsonObject {
            put("sessionId", sessionId)
            put("streamEpoch", epoch)
            put("sequence", "7")
            put("messageCount", "0")
            put("lastAppendId", JsonNull)
        }))
        router.handle(envelope("snapshot.page", buildJsonObject {
            put("sessionId", sessionId)
            put("streamEpoch", epoch)
            put("sequence", "7")
            put("page", 1)
            put("entries", buildJsonArray { })
        }))

        assertThat(events.filterIsInstance<HostConnectionEvent.SnapshotReady>()).isEmpty()
        assertThat(events.filterIsInstance<HostConnectionEvent.SnapshotRejected>()).hasSize(1)
    }

    @Test
    fun `keyless push endpoint omits wake key`() {
        val body = WireMessages.pushEndpoint(
            endpointId = "550e8400-e29b-41d4-a716-446655440011",
            distributor = "ntfy",
            endpoint = "https://ntfy.example.com/upAbCdEf123",
            wakePublicKey = null,
        )

        assertThat(body.keys).containsExactly("endpointId", "distributor", "endpoint")
    }

    @Test
    fun `command result and state route only command identity and stable failure code`() {
        val events = ArrayList<HostConnectionEvent>()
        val router = HostInboundRouter(events::add)
        val commandId = "550e8400-e29b-41d4-a716-446655440018"
        router.handle(envelope("command.state", buildJsonObject {
            put("commandId", commandId)
            put("state", "ARMED")
            put("dormant", false)
        }))
        router.handle(envelope("command.result", buildJsonObject {
            put("commandId", commandId)
            put("state", "REJECTED")
            put("errorCode", "PI_RPC_REJECTED")
            put("result", buildJsonObject { put("message", "must not be retained") })
        }))

        assertThat(events).containsExactly(
            HostConnectionEvent.CommandStatus(commandId, "ARMED", null),
            HostConnectionEvent.CommandStatus(commandId, "REJECTED", "PI_RPC_REJECTED"),
        ).inOrder()
    }

    @Test
    fun `session settled is routed explicitly`() {
        val events = ArrayList<HostConnectionEvent>()
        val router = HostInboundRouter(events::add)
        router.handle(envelope("session.settled", buildJsonObject {
            put("sessionId", "550e8400-e29b-41d4-a716-446655440002")
            put("settlementId", "settlement-1")
        }))
        val settled = events.single() as HostConnectionEvent.SessionSettledReceived
        assertThat(settled.settled.sessionId.value).isEqualTo("550e8400-e29b-41d4-a716-446655440002")
        assertThat(settled.settled.settlementId.value).isEqualTo("settlement-1")
    }

    @Test
    fun `terminal history request serializes session generation and exact bounds`() {
        val body = WireMessages.terminalHistoryRequest(
            io.github.verybigsad.pimobile.model.SessionId("550e8400-e29b-41d4-a716-446655440002"),
            ULong.MAX_VALUE,
            5_000,
            1_048_576,
        )
        assertThat(body["sessionId"]?.toString()).isEqualTo("\"550e8400-e29b-41d4-a716-446655440002\"")
        assertThat(body["terminalGeneration"]?.toString()).isEqualTo("\"18446744073709551615\"")
        assertThat(body["maxLines"]?.toString()).isEqualTo("5000")
        assertThat(body["maxBytes"]?.toString()).isEqualTo("1048576")
    }

    @Test
    fun `terminal history response maps the active session capture`() {
        val events = ArrayList<HostConnectionEvent>()
        val router = HostInboundRouter(events::add)
        val response = WireMessages.parseEnvelope(
            WireMessages.encode("terminal.history.response", buildJsonObject {
                put("sessionId", "550e8400-e29b-41d4-a716-446655440002")
                put("terminalGeneration", "18446744073709551615")
                put("capturedAt", "2026-08-09T12:00:00Z")
                put("text", "line one\nline two")
                put("truncatedLines", true)
                put("truncatedBytes", false)
            }),
        )!!
        router.handle(response)
        val capture = events.single() as HostConnectionEvent.TerminalHistoryResult
        assertThat(capture.sessionId.value).isEqualTo("550e8400-e29b-41d4-a716-446655440002")
        assertThat(capture.terminalGeneration).isEqualTo(ULong.MAX_VALUE)
        assertThat(capture.text).isEqualTo("line one\nline two")
        assertThat(capture.truncatedLines).isTrue()
        assertThat(capture.truncatedBytes).isFalse()
    }
}
