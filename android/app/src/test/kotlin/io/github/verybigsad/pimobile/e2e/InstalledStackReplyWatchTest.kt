package io.github.verybigsad.pimobile.e2e

import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.state.PiAppState
import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledStackReplyWatchTest {
    @Test
    fun prefersSanitizedCommandFailureOverUnrelatedBaselineState() {
        val watch = InstalledStackReplyWatch(SessionId("00000000-0000-4000-8000-000000000001"), null)

        assertEquals(
            "E2E_FINAL_REPLY_COMMAND_INDETERMINATE",
            watch.failureCode(PiAppState(lastError = "COMMAND_INDETERMINATE")),
        )
    }
}
