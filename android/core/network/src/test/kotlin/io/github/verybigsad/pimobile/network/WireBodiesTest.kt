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
    fun sessionCatalogParsesSixNormalizedHostEntriesWithoutChangingIds() {
        val ids = (0 until 6).map { index -> "550e8400-e29b-41d4-a716-44665544004$index" }
        val body = JsonObject(
            mapOf(
                "sessions" to kotlinx.serialization.json.JsonArray(
                    ids.map { id ->
                        catalogEntry(
                            mapOf(
                                "sessionId" to JsonPrimitive(id),
                                "provider" to JsonPrimitive("unavailable"),
                                "model" to JsonPrimitive("unavailable"),
                                "thinkingLevel" to JsonPrimitive("unavailable"),
                                "repo" to JsonPrimitive("/host/sessions/$id"),
                                "cwd" to JsonPrimitive("/host/sessions/$id"),
                            ),
                        )
                    },
                ),
            ),
        )

        val parsed = WireBodies.parseSessionCatalog(body)
        assertThat(parsed.sessions.map { it.id.value }).containsExactlyElementsIn(ids).inOrder()
        assertThat(parsed.sessions).hasSize(6)
        assertThat(parsed.sessions.all { it.provider == "unavailable" && it.parentSessionId == null }).isTrue()
    }

    @Test
    fun sessionCatalogRejectsEveryMissingOrMalformedWireField() {
        val required = listOf(
            "sessionId",
            "provider",
            "model",
            "thinkingLevel",
            "repo",
            "cwd",
            "createdAt",
            "updatedAt",
        )
        for (field in required) {
            val missing = JsonObject(catalogEntry().filterKeys { it != field })
            val result = runCatching { WireBodies.parseSessionCatalog(catalogBody(missing)) }
            assertWithMessage("missing $field").that(result.isFailure).isTrue()
        }

        val malformed = listOf(
            "sessionId" to JsonPrimitive("not-a-uuid"),
            "provider" to JsonPrimitive(""),
            "provider" to JsonPrimitive("p".repeat(65)),
            "model" to JsonPrimitive(""),
            "model" to JsonPrimitive("m".repeat(129)),
            "thinkingLevel" to JsonPrimitive(""),
            "thinkingLevel" to JsonPrimitive("t".repeat(33)),
            "repo" to JsonPrimitive(""),
            "repo" to JsonPrimitive("é".repeat(2_049)),
            "worktree" to JsonPrimitive(""),
            "worktree" to JsonPrimitive("w".repeat(4_097)),
            "cwd" to JsonPrimitive(""),
            "cwd" to JsonPrimitive("c".repeat(4_097)),
            "parentId" to JsonPrimitive("not-a-uuid"),
            "parentId" to JsonPrimitive("550e8400-e29b-41d4-a716-446655440002"),
            "createdAt" to JsonPrimitive("not-a-date"),
            "createdAt" to JsonPrimitive("d".repeat(65)),
            "updatedAt" to JsonPrimitive("not-a-date"),
            "updatedAt" to JsonPrimitive("d".repeat(65)),
        )
        for ((field, value) in malformed) {
            val result = runCatching { WireBodies.parseSessionCatalog(catalogBody(catalogEntry(mapOf(field to value)))) }
            assertWithMessage("malformed $field").that(result.isFailure).isTrue()
        }
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
            terminalGeneration = Uint64Decimal("18446744073709551615"),
            maxLines = 500,
            maxBytes = 1_024,
        )
        val encoded = WireBodies.encodeTerminalHistoryRequest(request)
        assertThat(WireBodies.parseTerminalHistoryRequest(encoded)).isEqualTo(request)

        val response = WireBodies.parseTerminalHistoryResponse(
            JsonObject(
                mapOf(
                    "sessionId" to JsonPrimitive("550e8400-e29b-41d4-a716-446655440002"),
                    "terminalGeneration" to JsonPrimitive("18446744073709551615"),
                    "capturedAt" to JsonPrimitive("2026-08-09T12:00:00Z"),
                    "text" to JsonPrimitive("line one\nline two"),
                    "truncatedLines" to JsonPrimitive(false),
                    "truncatedBytes" to JsonPrimitive(true),
                ),
            ),
        )
        assertThat(response.terminalGeneration).isEqualTo(Uint64Decimal("18446744073709551615"))
        assertThat(response.text).isEqualTo("line one\nline two")
        assertThat(response.truncatedLines).isFalse()
        assertThat(response.truncatedBytes).isTrue()
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

    private fun catalogBody(entry: JsonObject): JsonObject = JsonObject(
        mapOf("sessions" to kotlinx.serialization.json.JsonArray(listOf(entry))),
    )

    private fun catalogEntry(overrides: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()): JsonObject = JsonObject(
        mapOf(
            "sessionId" to JsonPrimitive("550e8400-e29b-41d4-a716-446655440002"),
            "provider" to JsonPrimitive("openai"),
            "model" to JsonPrimitive("gpt-5"),
            "thinkingLevel" to JsonPrimitive("high"),
            "repo" to JsonPrimitive("/work/pi-app"),
            "worktree" to kotlinx.serialization.json.JsonNull,
            "cwd" to JsonPrimitive("/work/pi-app"),
            "parentId" to kotlinx.serialization.json.JsonNull,
            "createdAt" to JsonPrimitive("2026-08-09T10:00:00Z"),
            "updatedAt" to JsonPrimitive("2026-08-09T11:00:00Z"),
        ) + overrides,
    )

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
