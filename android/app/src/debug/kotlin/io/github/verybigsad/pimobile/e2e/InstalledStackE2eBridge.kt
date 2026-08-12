package io.github.verybigsad.pimobile.e2e

import android.content.Context
import io.github.verybigsad.pimobile.PiMobileApplication
import io.github.verybigsad.pimobile.model.SessionId
import java.io.File
import io.github.verybigsad.pimobile.push.OpaqueWakePayload
import io.github.verybigsad.pimobile.push.WakePayloadParseResult
import io.github.verybigsad.pimobile.state.AppIntent
import java.util.UUID
import org.json.JSONObject

class InstalledStackE2eBridge private constructor(
    private val application: PiMobileApplication,
) {
    val state get() = application.container.coordinator.state
    val agentsState get() = application.container.agentsStore.state
    val terminalController get() = application.container.terminalController

    fun acceptOneUseInvitation(uri: String) {
        application.container.coordinator.submit(AppIntent.PairingUriScanned(uri))
    }

    fun requestAuthentication() {
        application.container.coordinator.submit(AppIntent.AuthenticateRequested)
    }

    fun openTerminal(sessionId: String) {
        application.container.openTerminal(SessionId(sessionId))
    }

    fun closeTerminal() {
        application.container.coordinator.submit(AppIntent.CloseTerminal)
    }

    fun injectSyntheticGroqTranscript(targetSessionId: String, text: String) {
        val streamId = UUID.randomUUID().toString()
        val gate = application.container.voiceTranscriptSink()
        gate.reset(0)
        check(gate.begin(streamId, targetSessionId, 0) == null)
        val partial = JSONObject()
            .put("sessionId", streamId)
            .put("chunkSequence", "0")
            .put("revision", "1")
            .put("text", text)
            .toString()
            .encodeToByteArray()
        val finish = JSONObject()
            .put("sessionId", streamId)
            .put("chunkSequence", "0")
            .put("text", text)
            .toString()
            .encodeToByteArray()
        check(gate.accept(0, streamId, "voice.partial", partial) == null)
        check(gate.accept(0, streamId, "voice.finish", finish) == null)
    }

    fun injectOpaqueWake(payload: String) {
        check(OpaqueWakePayload.parse(payload) is WakePayloadParseResult.Valid)
        application.container.coordinator.submit(AppIntent.WakeReceived)
    }

    fun beginFinalReplyWatch(sessionId: String): InstalledStackReplyWatch =
        InstalledStackReplyWatch(SessionId(sessionId), state.value.lastError)

    fun finalReplyFailureCode(watch: InstalledStackReplyWatch): String? = watch.failureCode(state.value)

    fun recordHookResult(hook: String, passed: Boolean, failureCode: String? = null) {
        InstalledStackHookEvidence.write(
            File(application.getExternalFilesDir(null), "e2e/hook-results.json"),
            hook,
            passed,
            failureCode,
        )
    }

    companion object {
        fun from(context: Context): InstalledStackE2eBridge =
            InstalledStackE2eBridge(context.applicationContext as PiMobileApplication)
    }
}
