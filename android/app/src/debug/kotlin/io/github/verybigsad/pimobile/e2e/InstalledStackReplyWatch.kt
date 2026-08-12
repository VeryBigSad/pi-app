package io.github.verybigsad.pimobile.e2e

import io.github.verybigsad.pimobile.model.CanonicalAvailability
import io.github.verybigsad.pimobile.model.CanonicalResetReason
import io.github.verybigsad.pimobile.model.ConnectionState
import io.github.verybigsad.pimobile.state.PiAppState
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.SessionRunState
import io.github.verybigsad.pimobile.model.TrustState
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class InstalledStackReplyWatch(
    private val sessionId: SessionId,
    private val initialLastError: String?,
) {
    fun failureCode(state: PiAppState): String? {
        val conversation = state.sessions[sessionId]?.conversation
        val availability = conversation?.availability
        if (availability is CanonicalAvailability.Unavailable &&
            availability.reason == CanonicalResetReason.SEQUENCE_GAP
        ) {
            return "E2E_FINAL_REPLY_SEQUENCE_GAP"
        }
        when (val error = state.lastError?.takeUnless { it == initialLastError }) {
            "COMMAND_SEND_FAILED" -> return "E2E_FINAL_REPLY_COMMAND_SEND_FAILED"
            "COMMAND_INDETERMINATE" -> return "E2E_FINAL_REPLY_COMMAND_INDETERMINATE"
            "AUTH_REQUIRED", "PASSKEY_ASSERTION_REJECTED", "PASSKEY_RESPONSE_SEND_FAILED" -> {
                return "E2E_FINAL_REPLY_AUTH_LOST"
            }
            else -> if (error != null && (error.startsWith("COMMAND_") || error.startsWith("JOURNAL_"))) {
                return "E2E_FINAL_REPLY_COMMAND_REJECTED"
            }
        }
        if (conversation?.runState == SessionRunState.FAULTED) return "E2E_FINAL_REPLY_COMMAND_REJECTED"
        if (state.authentication == null || state.trust !is TrustState.Trusted) return "E2E_FINAL_REPLY_AUTH_LOST"
        return when (state.connection) {
            is ConnectionState.Ready -> null
            is ConnectionState.DeviceAuthenticated -> "E2E_FINAL_REPLY_AUTH_LOST"
            else -> "E2E_FINAL_REPLY_CONNECTION_LOST"
        }
    }
}

internal object InstalledStackHookEvidence {
    private val hook = Regex("^(voice|push|external-push)$")
    private val code = Regex("^E2E_[A-Z0-9_]{1,96}$")

    @Synchronized
    fun write(destination: File, name: String, passed: Boolean, failureCode: String?) {
        check(hook.matches(name))
        val normalizedCode = failureCode?.takeIf(code::matches)
        check(passed || normalizedCode != null)
        destination.parentFile?.mkdirs()
        val current = read(destination)
        val results = JSONObject()
        current?.optJSONArray("hooks")?.let { entries ->
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val existingName = entry.optString("hook")
                if (hook.matches(existingName) && existingName != name) results.put(existingName, entry)
            }
        }
        results.put(name, JSONObject().put("hook", name).put("outcome", if (passed) "passed" else "failed").apply {
            if (normalizedCode != null) put("failureCode", normalizedCode)
        })
        val hooks = JSONArray()
        listOf("voice", "push", "external-push").forEach { candidate ->
            results.optJSONObject(candidate)?.let(hooks::put)
        }
        val temporary = File(destination.parentFile, ".${destination.name}.tmp")
        temporary.outputStream().bufferedWriter().use { it.write(JSONObject().put("version", 1).put("hooks", hooks).toString()) }
        check(temporary.renameTo(destination))
    }

    private fun read(source: File): JSONObject? = runCatching {
        if (!source.isFile || source.length() !in 1..4_096) return null
        JSONObject(source.readText(Charsets.UTF_8))
    }.getOrNull()
}
