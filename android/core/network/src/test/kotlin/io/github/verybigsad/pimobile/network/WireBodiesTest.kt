package io.github.verybigsad.pimobile.network

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.verybigsad.pimobile.model.AppendId
import io.github.verybigsad.pimobile.model.EventCursor
import io.github.verybigsad.pimobile.model.LeafId
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.StreamEpoch
import io.github.verybigsad.pimobile.model.Uint64Decimal
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/**
 * Consumes the cross-language frozen fixtures (protocol/fixtures/pimb-v1.json
 * `wireMessageCases`) against the typed core codecs.
 */
class WireBodiesTest {
    private val cases: List<FixtureCase> = run {
        val text = requireNotNull(javaClass.getResource("/pimb-v1.json")).readText()
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text) as JsonObject
        root.getValue("wireMessageCases").let { it as kotlinx.serialization.json.JsonArray }.map { element ->
            val value = element as JsonObject
            FixtureCase(
                name = (value.getValue("name") as JsonPrimitive).content,
                type = (value.getValue("type") as JsonPrimitive).content,
                body = value.getValue("body") as JsonObject,
                valid = (value.getValue("valid") as JsonPrimitive).content == "true",
            )
        }
    }

    @Test
    fun allFrozenWireMessageCasesMatchTypedParsers() {
        assertThat(cases).isNotEmpty()
        for (case in cases) {
            if (case.type !in CODEC_TYPES) continue
            val parsed = runCatching { parse(case.type, case.body) }
            assertWithMessage("${case.name} (${case.type})").that(parsed.isSuccess).isEqualTo(case.valid)
        }
    }

    @Test
    fun authResultExposesTypedFields() {
        val success = WireBodies.parseAuthResult(
            JsonObject(
                mapOf(
                    "success" to JsonPrimitive(true),
                    "expiresAt" to JsonPrimitive("2026-08-09T12:30:00Z"),
                ),
            ),
        )
        assertThat(success.success).isTrue()
        assertThat(success.error).isNull()
        assertThat(success.expiresAt).isEqualTo(Instant.parse("2026-08-09T12:30:00Z"))

        val failure = WireBodies.parseAuthResult(
            JsonObject(mapOf("success" to JsonPrimitive(false), "error" to JsonPrimitive("AUTH_FAILED"))),
        )
        assertThat(failure.success).isFalse()
        assertThat(failure.error).isEqualTo("AUTH_FAILED")
    }

    @Test
    fun syncResumeRoundTripsThroughCodec() {
        val cursors = listOf(
            WireBodies.SessionCursor(
                SessionId("550e8400-e29b-41d4-a716-446655440002"),
                EventCursor(StreamEpoch("550e8400-e29b-41d4-a716-446655440003"), Uint64Decimal("42"), LeafId("deadbeef")),
            ),
            WireBodies.SessionCursor(
                SessionId("550e8400-e29b-41d4-a716-44665544000b"),
                EventCursor(StreamEpoch("550e8400-e29b-41d4-a716-446655440003"), Uint64Decimal("7"), null),
            ),
        )
        val encoded = WireBodies.encodeSyncResume(cursors)
        assertThat(WireBodies.parseSyncResume(encoded).cursors).isEqualTo(cursors)
    }

    @Test
    fun messageAppendAndSettlementExposeCanonicalIds() {
        val append = WireBodies.parseMessageAppend(
            JsonObject(
                mapOf(
                    "sessionId" to JsonPrimitive("550e8400-e29b-41d4-a716-446655440002"),
                    "streamEpoch" to JsonPrimitive("550e8400-e29b-41d4-a716-446655440003"),
                    "appendId" to JsonPrimitive("18446744073709551615"),
                    "leafId" to JsonPrimitive("deadbeef"),
                ),
            ),
        )
        assertThat(append.appendId).isEqualTo(AppendId("18446744073709551615"))
        assertThat(append.leafId).isEqualTo(LeafId("deadbeef"))

        val settled = WireBodies.parseSessionSettled(
            JsonObject(
                mapOf(
                    "sessionId" to JsonPrimitive("550e8400-e29b-41d4-a716-446655440002"),
                    "settlementId" to JsonPrimitive("settlement-1"),
                ),
            ),
        )
        assertThat(settled.settlementId).isEqualTo(io.github.verybigsad.pimobile.model.SettlementId("settlement-1"))
    }

    @Test
    fun sessionCatalogExposesTypedEntries() {
        val catalog = WireBodies.parseSessionCatalog(
            JsonObject(
                mapOf(
                    "sessions" to kotlinx.serialization.json.JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "sessionId" to JsonPrimitive("550e8400-e29b-41d4-a716-446655440002"),
                                    "provider" to JsonPrimitive("openai"),
                                    "model" to JsonPrimitive("gpt-5"),
                                    "thinkingLevel" to JsonPrimitive("high"),
                                    "repo" to JsonPrimitive("/work/pi-app"),
                                    "worktree" to JsonPrimitive(null as String?),
                                    "cwd" to JsonPrimitive("/work/pi-app"),
                                    "parentId" to JsonPrimitive(null as String?),
                                    "createdAt" to JsonPrimitive("2026-08-09T10:00:00Z"),
                                    "updatedAt" to JsonPrimitive("2026-08-09T11:00:00Z"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val entry = catalog.sessions.single()
        assertThat(entry.id).isEqualTo(SessionId("550e8400-e29b-41d4-a716-446655440002"))
        assertThat(entry.model).isEqualTo("gpt-5")
        assertThat(entry.worktreePath).isNull()
        assertThat(entry.updatedAtEpochMillis).isEqualTo(Instant.parse("2026-08-09T11:00:00Z").toEpochMilli())
    }

    @Test
    fun snapshotBoundsExposeAppendIdsAndLeaf() {
        val begin = WireBodies.parseSnapshotBegin(
            JsonObject(
                mapOf(
                    "sessionId" to JsonPrimitive("550e8400-e29b-41d4-a716-446655440002"),
                    "streamEpoch" to JsonPrimitive("550e8400-e29b-41d4-a716-446655440003"),
                    "messageCount" to JsonPrimitive("4096"),
                    "lastAppendId" to JsonPrimitive("4096"),
                ),
            ),
        )
        assertThat(begin.lastAppendId).isEqualTo(AppendId("4096"))

        val end = WireBodies.parseSnapshotEnd(
            JsonObject(
                mapOf(
                    "sessionId" to JsonPrimitive("550e8400-e29b-41d4-a716-446655440002"),
                    "streamEpoch" to JsonPrimitive("550e8400-e29b-41d4-a716-446655440003"),
                    "messageCount" to JsonPrimitive("4096"),
                    "lastAppendId" to JsonPrimitive(null as String?),
                    "leafId" to JsonPrimitive("deadbeef"),
                    "validated" to JsonPrimitive(true),
                ),
            ),
        )
        assertThat(end.lastAppendId).isNull()
        assertThat(end.leafId).isEqualTo(LeafId("deadbeef"))
    }

    @Test
    fun terminalHistoryRequestRoundTripsThroughCodec() {
        val request = WireBodies.TerminalHistoryRequest(
            sessionId = SessionId("550e8400-e29b-41d4-a716-446655440002"),
            beforeSequence = null,
            limit = 500,
        )
        val encoded = WireBodies.encodeTerminalHistoryRequest(request)
        assertThat(WireBodies.parseTerminalHistoryRequest(encoded)).isEqualTo(request)

        val response = WireBodies.parseTerminalHistoryResponse(
            JsonObject(
                mapOf(
                    "sessionId" to JsonPrimitive("550e8400-e29b-41d4-a716-446655440002"),
                    "entries" to kotlinx.serialization.json.JsonArray(
                        listOf(JsonPrimitive("line one"), JsonPrimitive("line two")),
                    ),
                    "truncated" to JsonPrimitive(false),
                ),
            ),
        )
        assertThat(response.entries).containsExactly("line one", "line two").inOrder()
        assertThat(response.truncated).isFalse()
    }

    @Test
    fun agentsCatalogAndUpdateExposeTypedFields() {
        val catalog = WireBodies.parseAgentsCatalog(
            JsonObject(
                mapOf(
                    "sessions" to kotlinx.serialization.json.JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "sessionId" to JsonPrimitive("550e8400-e29b-41d4-a716-446655440002"),
                                    "agents" to kotlinx.serialization.json.JsonArray(
                                        listOf(
                                            JsonObject(
                                                mapOf(
                                                    "agentId" to JsonPrimitive("agent-1"),
                                                    "parentAgentId" to JsonPrimitive("agent-root"),
                                                    "description" to JsonPrimitive("Inspect protocol parity"),
                                                    "agentType" to JsonPrimitive("explore"),
                                                    "status" to JsonPrimitive("running"),
                                                    "startedAt" to JsonPrimitive("2026-08-11T05:00:00Z"),
                                                    "toolUses" to JsonPrimitive(3),
                                                    "model" to JsonPrimitive("k3-large"),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val agent = catalog.sessions.single().agents.single()
        assertThat(agent.agentId).isEqualTo("agent-1")
        assertThat(agent.parentAgentId).isEqualTo("agent-root")
        assertThat(agent.status).isEqualTo(WireBodies.AgentStatus.RUNNING)
        assertThat(agent.startedAt).isEqualTo(Instant.parse("2026-08-11T05:00:00Z"))
        assertThat(agent.endedAt).isNull()
        assertThat(agent.toolUses).isEqualTo(3)
        assertThat(agent.model).isEqualTo("k3-large")

        val update = WireBodies.parseAgentsUpdate(
            JsonObject(
                mapOf(
                    "sessionId" to JsonPrimitive("550e8400-e29b-41d4-a716-446655440002"),
                    "agent" to JsonObject(
                        mapOf(
                            "agentId" to JsonPrimitive("agent-1"),
                            "description" to JsonPrimitive("Inspect protocol parity"),
                            "agentType" to JsonPrimitive("explore"),
                            "status" to JsonPrimitive("completed"),
                            "startedAt" to JsonPrimitive("2026-08-11T05:00:00Z"),
                            "endedAt" to JsonPrimitive("2026-08-11T05:01:00Z"),
                        ),
                    ),
                ),
            ),
        )
        assertThat(update.sessionId).isEqualTo(SessionId("550e8400-e29b-41d4-a716-446655440002"))
        assertThat(update.agent.status).isEqualTo(WireBodies.AgentStatus.COMPLETED)
        assertThat(update.agent.endedAt).isEqualTo(Instant.parse("2026-08-11T05:01:00Z"))
        assertThat(update.agent.parentAgentId).isNull()
        assertThat(update.agent.toolUses).isNull()
        assertThat(update.agent.model).isNull()
    }

    @Test
    fun agentDescriptionAllowsMultibyteWithinByteBound() {
        val description = "\u043e".repeat(256) // 256 chars, 512 bytes: over 256 bytes, within 1024
        val parsed = WireBodies.parseAgentsUpdate(
            JsonObject(
                mapOf(
                    "sessionId" to JsonPrimitive("550e8400-e29b-41d4-a716-446655440002"),
                    "agent" to JsonObject(
                        mapOf(
                            "agentId" to JsonPrimitive("agent-1"),
                            "description" to JsonPrimitive(description),
                            "agentType" to JsonPrimitive("explore"),
                            "status" to JsonPrimitive("stopped"),
                            "startedAt" to JsonPrimitive("2026-08-11T05:00:00Z"),
                        ),
                    ),
                ),
            ),
        )
        assertThat(parsed.agent.description).hasLength(256)

        val overChars = runCatching {
            WireBodies.parseAgentsUpdate(
                JsonObject(
                    mapOf(
                        "sessionId" to JsonPrimitive("550e8400-e29b-41d4-a716-446655440002"),
                        "agent" to JsonObject(
                            mapOf(
                                "agentId" to JsonPrimitive("agent-1"),
                                "description" to JsonPrimitive("a".repeat(257)),
                                "agentType" to JsonPrimitive("explore"),
                                "status" to JsonPrimitive("stopped"),
                                "startedAt" to JsonPrimitive("2026-08-11T05:00:00Z"),
                            ),
                        ),
                    ),
                ),
            )
        }
        assertThat(overChars.isFailure).isTrue()
    }

    private fun parse(type: String, body: JsonObject): Any = when (type) {
        "auth.result" -> WireBodies.parseAuthResult(body)
        "sync.resume" -> WireBodies.parseSyncResume(body)
        "message.append" -> WireBodies.parseMessageAppend(body)
        "session.settled" -> WireBodies.parseSessionSettled(body)
        "session.catalog" -> WireBodies.parseSessionCatalog(body)
        "agents.catalog" -> WireBodies.parseAgentsCatalog(body)
        "agents.update" -> WireBodies.parseAgentsUpdate(body)
        "snapshot.begin" -> WireBodies.parseSnapshotBegin(body)
        "snapshot.end" -> WireBodies.parseSnapshotEnd(body)
        "voice.audio" -> WireBodies.parseVoiceAudio(body)
        "voice.partial" -> WireBodies.parseVoicePartial(body)
        "voice.finish" -> WireBodies.parseVoiceFinish(body)
        "terminal.history.request" -> WireBodies.parseTerminalHistoryRequest(body)
        "terminal.history.response" -> WireBodies.parseTerminalHistoryResponse(body)
        else -> throw IllegalArgumentException("unmapped fixture type $type")
    }

    private data class FixtureCase(
        val name: String,
        val type: String,
        val body: JsonObject,
        val valid: Boolean,
    )

    private companion object {
        val CODEC_TYPES = setOf(
            "auth.result",
            "sync.resume",
            "message.append",
            "session.settled",
            "session.catalog",
            "agents.catalog",
            "agents.update",
            "snapshot.begin",
            "snapshot.end",
            "voice.audio",
            "voice.partial",
            "voice.finish",
            "terminal.history.request",
            "terminal.history.response",
        )
    }
}
